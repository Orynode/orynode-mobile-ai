import XCTest
import OrynodeApplication
import OrynodeDomain
@testable import OrynodeInfrastructure

/// Seed dry-run: import evalset TXT/MD/PDF and check retrieval Hit@5 for answer-like questions.
/// Uses DeterministicHashEmbedding — process check only, not the release E5 gate.
final class EvalsetSeedRetrievalTests: XCTestCase {
    func testSeedAnswerQuestionsHitMustContainInTop5() async throws {
        let evalset = try Self.evalsetRoot()
        let directory = FileManager.default.temporaryDirectory
            .appending(path: "evalset-seed-\(UUID().uuidString)", directoryHint: .isDirectory)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }

        let embedding = DeterministicHashEmbedding(dimensions: 64)
        let repository = try SQLiteKnowledgeRepository(
            url: directory.appending(path: "knowledge.sqlite"),
            embeddingDimensions: 64
        )
        let importer = ImportKnowledgeDocument(
            extractor: LocalKnowledgeTextExtractor(),
            chunker: StructuredKnowledgeChunker(),
            embedding: embedding,
            repository: repository
        )

        var titleToURL: [String: URL] = [:]
        let corpus = [
            "corpus/txt/01_short_offline.txt",
            "corpus/txt/02_long_capacity.txt",
            "corpus/txt/03_mixed_en_zh.txt",
            "corpus/markdown/01_install_guide.md",
            "corpus/markdown/02_faq_headings.md",
            "corpus/markdown/03_duplicate_terms.md",
            "corpus/pdf/01_short.pdf",
            "corpus/pdf/02_long.pdf",
            "corpus/pdf/03_multipage.pdf",
            "corpus/pdf/04_complex_layout.pdf",
        ]
        for relative in corpus {
            let source = evalset.appending(path: relative)
            XCTAssertTrue(FileManager.default.fileExists(atPath: source.path), relative)
            let local = directory.appending(path: source.lastPathComponent)
            try FileManager.default.copyItem(at: source, to: local)
            let document = try await importer(url: local)
            XCTAssertEqual(document.state, .ready, relative)
            titleToURL[document.title] = local
        }

        let questions = try Self.loadQuestions(from: evalset)
        let answerLike = questions.filter {
            ["answered", "paraphrase", "sparse"].contains($0.kind)
        }
        XCTAssertFalse(answerLike.isEmpty)

        var failures: [String] = []
        for item in answerLike {
            let query = try await embedding.embedQuery(item.question)
            let hits = try await repository.search(
                query: item.question,
                embedding: query,
                limit: 5,
                scope: .all
            )
            let needles = item.expect.evidence.flatMap(\.mustContain)
            let hitText = hits.map(\.chunk.text).joined(separator: "\n")
            let matched = needles.contains { hitText.contains($0) }
            if !matched {
                failures.append(
                    "\(item.id) kind=\(item.kind) q=\(item.question) needles=\(needles) hits=\(hits.map { String($0.chunk.text.prefix(40)) })"
                )
            }
            if item.expect.behavior == "answer", let score = hits.first?.score {
                XCTAssertGreaterThan(
                    score,
                    0,
                    "\(item.id) expected a positive top hit score"
                )
            }
        }

        XCTAssertTrue(
            failures.isEmpty,
            "Seed Hit@5 failures (hash embedding; investigate anchors):\n" + failures.joined(separator: "\n")
        )
        XCTAssertEqual(titleToURL.count, 10)
    }

    func testSeedConflictDocumentContainsBothPasswords() throws {
        let evalset = try Self.evalsetRoot()
        let faq = try String(
            contentsOf: evalset.appending(path: "corpus/markdown/02_faq_headings.md"),
            encoding: .utf8
        )
        XCTAssertTrue(faq.contains("2468"))
        XCTAssertTrue(faq.contains("1357"))
    }

    // MARK: - Helpers

    private struct EvalQuestion: Decodable {
        struct Expect: Decodable {
            struct Evidence: Decodable {
                let document: String
                let mustContain: [String]

                enum CodingKeys: String, CodingKey {
                    case document
                    case mustContain = "must_contain"
                }
            }

            let behavior: String
            let evidence: [Evidence]

            enum CodingKeys: String, CodingKey {
                case behavior, evidence
            }

            init(from decoder: Decoder) throws {
                let container = try decoder.container(keyedBy: CodingKeys.self)
                behavior = try container.decode(String.self, forKey: .behavior)
                evidence = try container.decodeIfPresent([Evidence].self, forKey: .evidence) ?? []
            }
        }

        let id: String
        let kind: String
        let question: String
        let expect: Expect
    }

    private static func evalsetRoot() throws -> URL {
        let testsDir = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        let candidate = testsDir
            .deletingLastPathComponent()
            .appending(path: "docs/verification/evalset", directoryHint: .isDirectory)
        guard FileManager.default.fileExists(atPath: candidate.path) else {
            throw XCTSkip("evalset not found at \(candidate.path)")
        }
        return candidate
    }

    private static func loadQuestions(from evalset: URL) throws -> [EvalQuestion] {
        var rows: [EvalQuestion] = []
        let files = [
            "questions/answered.jsonl",
            "questions/paraphrase_sparse.jsonl",
            "questions/no_answer_conflict.jsonl",
        ]
        let decoder = JSONDecoder()
        for relative in files {
            let text = try String(contentsOf: evalset.appending(path: relative), encoding: .utf8)
            for line in text.split(whereSeparator: \.isNewline) {
                let trimmed = line.trimmingCharacters(in: .whitespacesAndNewlines)
                guard !trimmed.isEmpty else { continue }
                rows.append(try decoder.decode(EvalQuestion.self, from: Data(trimmed.utf8)))
            }
        }
        return rows
    }
}
