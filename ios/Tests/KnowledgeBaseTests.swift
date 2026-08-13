import XCTest
import SQLite3
import OrynodeApplication
import OrynodeDomain
@testable import OrynodeInfrastructure
@testable import OrynodeMobileAI

final class KnowledgeBaseTests: XCTestCase {
    func testCitationLocatorRefinerNarrowsChunkSpanToAnswerFact() {
        let lead = """
        新单位网上开通公积金账户操作手册

        1、新单位如需在我中心开设公积金单位账户的，可直接通过互联网申请。

        2、选择网上开户后，所有带*的为必填项。

        3、第二个录入页面为"单位缴存信息"，根据您单位所属位置可就近选择对应的缴存管理部：
        """
        let target = "岳麓区管理部地址：长沙市岳麓区金星大道20号长沙市政务中心一楼A厅；"
        let tail = "湘江新区网点地址：长沙市岳麓区文轩路27号麓谷企业广场。"
        let indexed = [lead, target, tail].joined(separator: "\n")

        let chunkLocator = SourceLocator.markdown(headingPath: nil, startLine: 1, endLine: 19)
        let refined = CitationLocatorRefiner.refine(
            indexedText: indexed,
            chunkLocator: chunkLocator,
            question: "岳麓区地址",
            answerText: """
            岳麓区管理部地址是：

            * **长沙市岳麓区金星大道20号长沙市政务中心一楼A厅** [2]。
            """
        )

        guard case let .markdown(_, startLine, endLine) = refined else {
            return XCTFail("expected refined markdown locator, got \(String(describing: refined))")
        }
        XCTAssertGreaterThan(startLine, 1, "should not stay at chunk start")
        XCTAssertEqual(startLine, endLine, "fact should collapse to one line")
        let lines = indexed.split(separator: "\n", omittingEmptySubsequences: false).map(String.init)
        XCTAssertLessThanOrEqual(startLine, lines.count)
        XCTAssertTrue(lines[startLine - 1].contains("岳麓区管理部地址"))
    }

    func testCitationLocatorRefinerAcceptsShortDistrictQuery() {
        let lead = """
        新单位网上开通公积金账户操作手册

        3、第二个录入页面为"单位缴存信息"：
        望城区管理部地址：望城区望府路198号区政务中心二楼；
        芙蓉区管理部地址：长沙市芙蓉区蔡锷中路伍家井一号；
        """
        let target = "雨花区管理部地址：长沙市雨花区湘府中路12号；"
        let indexed = [lead, target].joined(separator: "\n")
        let chunkLocator = SourceLocator.markdown(headingPath: nil, startLine: 1, endLine: 19)

        // Paraphrased answer (加入「是」) so long answer needles miss; short question must win.
        let refined = CitationLocatorRefiner.refine(
            indexedText: indexed,
            chunkLocator: chunkLocator,
            question: "雨花区",
            answerText: "雨花区管理部地址是：长沙市雨花区湘府中路12号 [1]。"
        )

        guard case let .markdown(_, startLine, endLine) = refined else {
            return XCTFail("expected refined markdown locator, got \(String(describing: refined))")
        }
        XCTAssertEqual(startLine, endLine)
        let lines = indexed.split(separator: "\n", omittingEmptySubsequences: false).map(String.init)
        XCTAssertEqual(lines[startLine - 1], target)
        XCTAssertNotEqual(startLine, 1, "must not keep the whole chunk span")
    }

    func testCitationLocatorRefinerNarrowsPlainTextChunkUsingEvidenceExcerpt() {
        let lead = String(repeating: "前言说明与无关背景。", count: 20)
        let target = "吉卜赛人又来了：这是本页的关键事实句。"
        let tail = String(repeating: "后文补充材料。", count: 20)
        let indexed = [lead, target, tail].joined(separator: "\n")
        let targetStart = lead.utf16.count + 1
        let chunkLocator = SourceLocator.plainText(
            startOffset: 0,
            endOffset: min(511, indexed.utf16.count)
        )

        let refined = CitationLocatorRefiner.refine(
            indexedText: indexed,
            chunkLocator: chunkLocator,
            question: "吉卜赛人又来了",
            answerText: "资料提到吉卜赛人又来了这一事实。[1]",
            evidenceExcerpt: target
        )

        guard case let .plainText(start, end) = refined else {
            return XCTFail("expected refined plainText locator, got \(String(describing: refined))")
        }
        XCTAssertGreaterThan(start, 0, "must not keep chunk start at 0 when fact is later")
        XCTAssertLessThan(end - start, 120, "must not keep the whole ~511 chunk window")
        XCTAssertEqual(
            CitationLocatorEnricher.displayLabel(
                locator: refined,
                indexedText: indexed
            ),
            "第 2 行"
        )
        let excerpt = CitationLocatorRefiner.excerpt(at: refined, in: indexed)
        XCTAssertEqual(excerpt, target)
        XCTAssertEqual(start, targetStart)
    }

    func testCitationLocatorRefinerPrefersQuestionTermOverEarlyAnswerOverlap() {
        // Mirrors the Changsha essay failure: query is late in the doc, but the model
        // answer leans on early "烟火气/美食" wording so old scoring pinned line 7.
        let early = """
        如果要问我，哪一座城市最能把“烟火气”和“年轻感”融合在一起，我想，答案一定是长沙。

        长沙是一座很特别的城市。它没有北上广深那样的喧嚣。

        长沙的美，首先美在它的烟火气。

        来到长沙，最不能错过的就是这里的美食。长沙人的生活，好像总是和“吃”紧紧联系在一起。
        """
        let target = "走在五一商圈、黄兴路步行街附近，你总能看到很多年轻人的身影。"
        let indexed = early + "\n\n" + target + "\n\n长沙还有一种特别吸引人的地方，那就是它的年轻。"
        let chunkLocator = SourceLocator.plainText(startOffset: 0, endOffset: 511)
        let paraphrasedAnswer = """
        长沙很有烟火气，也有很多美食。年轻人喜欢在这里生活。[1]
        """

        let refined = CitationLocatorRefiner.refine(
            indexedText: indexed,
            chunkLocator: chunkLocator,
            question: "黄兴路步行街",
            answerText: paraphrasedAnswer,
            evidenceExcerpt: early
        )

        guard case let .plainText(start, end) = refined else {
            return XCTFail("expected plainText locator, got \(String(describing: refined))")
        }
        let label = CitationLocatorEnricher.displayLabel(locator: refined, indexedText: indexed)
        let excerpt = CitationLocatorRefiner.excerpt(at: refined, in: indexed) ?? ""
        XCTAssertTrue(excerpt.contains("黄兴路步行街"), "excerpt=\(excerpt) label=\(label ?? "")")
        XCTAssertEqual(label, "第 \(UTF16TextIndex.lineNumber(utf16Offset: start, in: indexed)) 行")
        let targetLine = UTF16TextIndex.lineNumber(
            utf16Offset: (indexed as NSString).range(of: "黄兴路步行街").location,
            in: indexed
        )
        XCTAssertEqual(
            UTF16TextIndex.lineNumber(utf16Offset: start, in: indexed),
            targetLine
        )
        XCTAssertEqual(
            UTF16TextIndex.lineNumber(utf16Offset: max(start, end - 1), in: indexed),
            targetLine
        )
    }

    func testCitationLocatorRefinerKeepsPDFPageNotPlainTextOffsets() {
        let page1 = String(repeating: "前言内容。", count: 30)
        let page2 = "反向代理服务器利用 Nginx 等工具，将不同域名的请求转发给内网不同机器的特定端口。"
        let spans = [
            KnowledgePageSpan(page: 1, start: 0, end: page1.utf16.count),
            KnowledgePageSpan(
                page: 2,
                start: page1.utf16.count + 2,
                end: page1.utf16.count + 2 + page2.utf16.count
            ),
        ]
        let indexed = page1 + "\n\n" + page2

        let refined = CitationLocatorRefiner.refine(
            indexedText: indexed,
            chunkLocator: .pdf(page: 2, startOffset: 0, endOffset: page2.utf16.count),
            question: "什么是反向代理",
            answerText: "反向代理服务器利用 Nginx 等工具转发请求 [2]。",
            pageSpans: spans
        )

        guard case let .pdf(page, start, end) = refined else {
            return XCTFail("expected pdf locator, got \(String(describing: refined))")
        }
        XCTAssertEqual(page, 2)
        XCTAssertEqual(refined?.shortLabel, "第 2 页")
        XCTAssertNotNil(start)
        XCTAssertNotNil(end)
        if let start, let end {
            XCTAssertGreaterThan(end, start)
            XCTAssertLessThan(end, 10_000, "offsets must be page-local, not global indexedText")
        }
    }

    func testCitationLocatorRefinerPDFWithoutSpansDoesNotBecomePlainText() {
        let refined = CitationLocatorRefiner.refine(
            indexedText: "反向代理服务器利用 Nginx。",
            chunkLocator: .pdf(page: 5, startOffset: 0, endOffset: 20),
            question: "反向代理",
            answerText: "反向代理服务器利用 Nginx。[1]",
            pageSpans: []
        )
        guard case let .pdf(page, _, _) = refined else {
            return XCTFail("must keep pdf locator, got \(String(describing: refined))")
        }
        XCTAssertEqual(page, 5)
        XCTAssertEqual(refined?.shortLabel, "第 5 页")
    }

    func testPDFRefinerKeepsIngestPageWhenAnswerMatchesElsewhere() {
        let early = "反向代理服务器利用 Nginx 进行代理，将不同域名流量转发。作用：将请求代理到后端服务器。"
        let late = "反向代理还可以与不允许代理端主动关闭连接的配置一起使用。"
        let filler = String(repeating: "其他内容。", count: 40)
        let page1 = early
        let page2 = filler + late
        let spans = [
            KnowledgePageSpan(page: 36, start: 0, end: page1.utf16.count),
            KnowledgePageSpan(
                page: 43,
                start: page1.utf16.count + 2,
                end: page1.utf16.count + 2 + page2.utf16.count
            ),
        ]
        let indexed = page1 + "\n\n" + page2

        // Chunk cited as [n] was ingested on page 43. Even if the answer prose
        // overlaps page 36 more, the locator page must stay 43 (closed-set binding).
        let refined = CitationLocatorRefiner.refine(
            indexedText: indexed,
            chunkLocator: .pdf(page: 43, startOffset: 0, endOffset: 30),
            question: "反向代理",
            answerText: "反向代理服务器利用 Nginx 进行代理，将不同域名流量转发。作用：将请求代理到后端服务器。[1]",
            pageSpans: spans
        )

        guard case let .pdf(page, _, _) = refined else {
            return XCTFail("expected pdf locator, got \(String(describing: refined))")
        }
        XCTAssertEqual(page, 43, "must not rebind [n] away from ingest chunk page")

        let overlap36 = CitationEnrichDebugTrace.answerOverlapScore(answer: "反向代理服务器利用 Nginx 进行代理", pageText: early)
        let overlap43 = CitationEnrichDebugTrace.answerOverlapScore(answer: "反向代理服务器利用 Nginx 进行代理", pageText: late)
        XCTAssertGreaterThan(overlap36, overlap43)
        let diagnosis = CitationEnrichDebugTrace.diagnosePDF(
            ingestPage: 43,
            finalPage: 43,
            overlapIngest: overlap43,
            bestPage: 36,
            bestScore: overlap36
        )
        // Either weak grounds on cited page, or explicit retrieval/model mismatch — both mean
        // "do not remap page in enrich"; fix pack / cite upstream.
        XCTAssertFalse(diagnosis.contains("CONTRACT_VIOLATION"), diagnosis)
        XCTAssertFalse(diagnosis.hasPrefix("OK:"), diagnosis)
    }

    func testCitationLocatorEnricherLocksPDFPageAndUsesPersistedSpansForExcerpt() {
        let page1 = "封面与目录。"
        let page2 = "岳麓区管理部地址：长沙市岳麓区金星大道20号。"
        let spans = [
            KnowledgePageSpan(page: 1, start: 0, end: page1.utf16.count),
            KnowledgePageSpan(
                page: 2,
                start: page1.utf16.count + 2,
                end: page1.utf16.count + 2 + page2.utf16.count
            ),
        ]
        let indexed = page1 + "\n\n" + page2
        let citation = KnowledgeCitation(
            index: 1,
            documentID: UUID(),
            documentTitle: "手册",
            chunkID: UUID(),
            excerpt: "岳麓区管理部",
            locator: .pdf(page: 2, startOffset: 0, endOffset: 8)
        )

        let drifted = CitationLocatorEnricher.lockedPDFPage(
            refined: .pdf(page: 1, startOffset: 0, endOffset: 4),
            ingest: citation.locator
        )
        guard case let .pdf(lockedPage, start, end) = drifted else {
            return XCTFail("expected locked pdf locator")
        }
        XCTAssertEqual(lockedPage, 2)
        XCTAssertNil(start)
        XCTAssertNil(end)

        let enriched = CitationLocatorEnricher.enrich(
            citation: citation,
            indexedText: indexed,
            pageSpans: spans,
            question: "岳麓区地址",
            answerText: "岳麓区管理部地址：长沙市岳麓区金星大道20号。[1]"
        )
        guard case let .pdf(page, offsetStart, offsetEnd) = enriched.locator else {
            return XCTFail("expected pdf locator, got \(String(describing: enriched.locator))")
        }
        XCTAssertEqual(page, 2)
        XCTAssertNotNil(offsetStart)
        XCTAssertNotNil(offsetEnd)
        XCTAssertTrue(
            enriched.excerpt.contains("岳麓区"),
            "excerpt should come from pageSpans, got \(enriched.excerpt)"
        )
        XCTAssertFalse(enriched.excerpt.contains("封面"))
        XCTAssertNotEqual(enriched.excerpt, citation.excerpt)
    }

    func testChunkLocatorPointsToMiddleOfDocxLikeText() {
        let lead = String(repeating: "前言内容。", count: 80)
        let target = "定位目标：离线知识库不会上传文档。"
        let tail = String(repeating: "附录内容。", count: 80)
        let text = "\(lead)\n\n\(target)\n\n\(tail)"
        let chunks = StructuredKnowledgeChunker(targetCharacters: 120, overlapCharacters: 16)
            .chunks(
                documentID: UUID(),
                extraction: KnowledgeExtraction(kind: .markdown, indexedText: text)
            )
        let targetChunk = chunks.first { $0.text.contains("定位目标") }
        XCTAssertNotNil(targetChunk)
        guard case let .markdown(_, startLine, _) = targetChunk?.locator else {
            return XCTFail("expected markdown locator")
        }
        XCTAssertGreaterThan(startLine, 1, "locator should not stick to document start")
    }

    func testStructuredChunkerPreservesHeadingsAndBudget() {
        let text = """
        # 安装
        第一段说明。

        第二段包含更多安装细节。

        # 使用
        使用方式说明。
        """
        let chunks = StructuredKnowledgeChunker(targetCharacters: 200, overlapCharacters: 20)
            .chunks(
                documentID: UUID(),
                extraction: KnowledgeExtraction(kind: .markdown, indexedText: text)
            )
        XCTAssertEqual(chunks.map(\.heading), ["安装", "使用"])
        XCTAssertTrue(chunks.allSatisfy { $0.tokenEstimate > 0 })
        XCTAssertTrue(chunks.allSatisfy {
            if case .markdown = $0.locator { return true }
            return false
        })
    }

    func testPlainTextAndPDFLocators() async throws {
        let directory = FileManager.default.temporaryDirectory
            .appending(path: "knowledge-loc-\(UUID().uuidString)", directoryHint: .isDirectory)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }

        let txt = directory.appending(path: "note.txt")
        try "第一行\n第二行定位目标\n第三行".write(to: txt, atomically: true, encoding: .utf8)
        let extraction = try await LocalKnowledgeTextExtractor().extract(from: txt)
        XCTAssertEqual(extraction.kind, .plainText)
        let chunks = StructuredKnowledgeChunker().chunks(documentID: UUID(), extraction: extraction)
        XCTAssertFalse(chunks.isEmpty)
        guard case let .plainText(start, end) = chunks[0].locator else {
            return XCTFail("expected plainText locator")
        }
        XCTAssertGreaterThan(end, start)
    }

    func testDeterministicEmbeddingAndExactCosine() async throws {
        let adapter = DeterministicHashEmbedding(dimensions: 64)
        let vectors = try await adapter.embed(["本地知识库", "本地知识库", "完全不同"])
        XCTAssertEqual(vectors[0], vectors[1])
        XCTAssertEqual(try ExactCosineSimilarity.score(vectors[0], vectors[1]), 1, accuracy: 0.0001)
        XCTAssertLessThan(try ExactCosineSimilarity.score(vectors[0], vectors[2]), 1)
    }

    func testImportResumeAndHybridSearch() async throws {
        let directory = FileManager.default.temporaryDirectory
            .appending(path: "knowledge-\(UUID().uuidString)", directoryHint: .isDirectory)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let source = directory.appending(path: "guide.md")
        try "# 离线模式\n应用支持完全离线分析，不会上传文档。".write(
            to: source,
            atomically: true,
            encoding: .utf8
        )

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
        let first = try await importer(url: source)
        let resumed = try await importer(url: source, resuming: first.id)
        XCTAssertEqual(resumed.state, .ready)
        XCTAssertEqual(resumed.importedChunkCount, 1)

        let duplicatePath = directory.appending(path: "guide-copy.md")
        try FileManager.default.copyItem(at: source, to: duplicatePath)
        do {
            _ = try await importer(url: duplicatePath)
            XCTFail("expected duplicate import to fail")
        } catch let error as KnowledgeBaseError {
            XCTAssertEqual(error, .duplicateDocument(existingTitle: "guide"))
        }

        let query = try await embedding.embed(["离线分析"]).first!
        let hits = try await repository.search(
            query: "离线分析",
            embedding: query,
            limit: 3,
            scope: .all
        )
        XCTAssertEqual(hits.first?.chunk.documentID, first.id)
        XCTAssertTrue(hits.first?.chunk.text.contains("不会上传") == true)

        let listed = try await repository.documents()
        XCTAssertEqual(listed.count, 1)
        XCTAssertNil(listed.first?.indexedText, "list projection must not load full text")
        let detail = try await repository.document(id: first.id)
        XCTAssertTrue(detail?.indexedText?.contains("不会上传文档") == true)

        var wiped = listed[0]
        wiped.indexedText = nil
        try await repository.save(document: wiped)
        let afterListSave = try await repository.document(id: first.id)
        XCTAssertTrue(
            afterListSave?.indexedText?.contains("不会上传文档") == true,
            "saving a list row must not erase indexed_text"
        )
    }

    func testPageSpansPersistOnDetailRowAndSurviveListSave() async throws {
        let directory = FileManager.default.temporaryDirectory
            .appending(path: "knowledge-spans-\(UUID().uuidString)", directoryHint: .isDirectory)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }

        let repository = try SQLiteKnowledgeRepository(
            url: directory.appending(path: "knowledge.sqlite"),
            embeddingDimensions: 32
        )
        let spans = [
            KnowledgePageSpan(page: 1, start: 0, end: 12),
            KnowledgePageSpan(page: 2, start: 14, end: 40),
        ]
        let document = KnowledgeDocument(
            id: UUID(),
            sourceURL: directory.appending(path: "book.pdf"),
            title: "手册",
            contentHash: "abc",
            state: .ready,
            indexedText: "第一页文字\n\n第二页文字",
            pageSpans: spans
        )
        try await repository.save(document: document)

        let listed = try await repository.documents()
        XCTAssertEqual(listed.first?.pageSpans, [])
        var row = listed[0]
        row.indexedText = nil
        try await repository.save(document: row)

        let detail = try await repository.document(id: document.id)
        XCTAssertEqual(detail?.pageSpans, spans)
        XCTAssertEqual(detail?.indexedText, "第一页文字\n\n第二页文字")

        let refined = CitationLocatorRefiner.refine(
            indexedText: detail?.indexedText,
            chunkLocator: .pdf(page: 2, startOffset: 0, endOffset: 10),
            question: "第二页",
            answerText: "第二页文字 [1]",
            pageSpans: detail?.pageSpans ?? []
        )
        guard case let .pdf(page, _, _) = refined else {
            return XCTFail("expected pdf locator")
        }
        XCTAssertEqual(page, 2)
    }

    func testRepositoryRejectsRetrievalVersionMismatchWhenChunksExist() async throws {
        let directory = FileManager.default.temporaryDirectory
            .appending(path: "knowledge-retver-\(UUID().uuidString)", directoryHint: .isDirectory)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }

        let dbURL = directory.appending(path: "knowledge.sqlite")
        let first = try SQLiteKnowledgeRepository(
            url: dbURL,
            embeddingDimensions: 32,
            retrievalVersion: "hybrid-cosine0.7-fts0.3-v1"
        )
        let source = directory.appending(path: "note.md")
        try "# 离线\n版本闸门测试。".write(to: source, atomically: true, encoding: .utf8)
        _ = try await ImportKnowledgeDocument(
            extractor: LocalKnowledgeTextExtractor(),
            chunker: StructuredKnowledgeChunker(),
            embedding: DeterministicHashEmbedding(dimensions: 32),
            repository: first
        )(url: source)

        do {
            _ = try SQLiteKnowledgeRepository(
                url: dbURL,
                embeddingDimensions: 32,
                retrievalVersion: "hybrid-rrf-v2"
            )
            XCTFail("expected retrieval version mismatch")
        } catch let error as KnowledgeBaseError {
            guard case let .storage(message) = error else {
                return XCTFail("expected storage error, got \(error)")
            }
            XCTAssertTrue(message.contains("检索融合策略"))
            XCTAssertTrue(message.contains("必须重建索引"))
        }
    }

    func testRepositoryRejectsChunkerVersionMismatchWhenChunksExist() async throws {
        let directory = FileManager.default.temporaryDirectory
            .appending(path: "knowledge-chunkver-\(UUID().uuidString)", directoryHint: .isDirectory)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }

        let dbURL = directory.appending(path: "knowledge.sqlite")
        let chunker = StructuredKnowledgeChunker()
        let first = try SQLiteKnowledgeRepository(
            url: dbURL,
            embeddingDimensions: 32,
            chunkerVersion: chunker.contractVersion
        )
        let source = directory.appending(path: "note.md")
        try "# 离线\n切分版本闸门。".write(to: source, atomically: true, encoding: .utf8)
        _ = try await ImportKnowledgeDocument(
            extractor: LocalKnowledgeTextExtractor(),
            chunker: chunker,
            embedding: DeterministicHashEmbedding(dimensions: 32),
            repository: first
        )(url: source)

        do {
            _ = try SQLiteKnowledgeRepository(
                url: dbURL,
                embeddingDimensions: 32,
                chunkerVersion: "structured-800-100-v2"
            )
            XCTFail("expected chunker version mismatch")
        } catch let error as KnowledgeBaseError {
            guard case let .storage(message) = error else {
                return XCTFail("expected storage error, got \(error)")
            }
            XCTAssertTrue(message.contains("切分策略"))
            XCTAssertTrue(message.contains("必须重建索引"))
        }
    }

    func testRepositoryGrandfathersMissingRetrievalAndChunkerVersions() async throws {
        let directory = FileManager.default.temporaryDirectory
            .appending(path: "knowledge-meta-gf-\(UUID().uuidString)", directoryHint: .isDirectory)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }

        let dbURL = directory.appending(path: "knowledge.sqlite")
        let seeded = try SQLiteKnowledgeRepository(
            url: dbURL,
            embeddingDimensions: 32
        )
        let source = directory.appending(path: "note.md")
        try "# 离线\n旧库补盖版本键。".write(to: source, atomically: true, encoding: .utf8)
        _ = try await ImportKnowledgeDocument(
            extractor: LocalKnowledgeTextExtractor(),
            chunker: StructuredKnowledgeChunker(),
            embedding: DeterministicHashEmbedding(dimensions: 32),
            repository: seeded
        )(url: source)

        // Simulate a pre-versioned library: drop the new keys but keep chunks.
        var handle: OpaquePointer?
        XCTAssertEqual(sqlite3_open(dbURL.path, &handle), SQLITE_OK)
        defer { sqlite3_close(handle) }
        XCTAssertEqual(
            sqlite3_exec(
                handle,
                "DELETE FROM knowledge_metadata WHERE key IN ('retrieval_version','chunker_version')",
                nil,
                nil,
                nil
            ),
            SQLITE_OK
        )

        let reopened = try SQLiteKnowledgeRepository(
            url: dbURL,
            embeddingDimensions: 32
        )
        let docs = try await reopened.documents()
        XCTAssertEqual(docs.count, 1)
    }

    func testRepositoryRejectsLegacyFNVContentHashWhenChunksExist() async throws {
        let directory = FileManager.default.temporaryDirectory
            .appending(path: "knowledge-fnv-\(UUID().uuidString)", directoryHint: .isDirectory)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }

        let dbURL = directory.appending(path: "knowledge.sqlite")
        let seeded = try SQLiteKnowledgeRepository(
            url: dbURL,
            embeddingDimensions: 32
        )
        let source = directory.appending(path: "note.md")
        try "# 离线\n旧 FNV 哈希必须重建。".write(to: source, atomically: true, encoding: .utf8)
        _ = try await ImportKnowledgeDocument(
            extractor: LocalKnowledgeTextExtractor(),
            chunker: StructuredKnowledgeChunker(),
            embedding: DeterministicHashEmbedding(dimensions: 32),
            repository: seeded
        )(url: source)

        var handle: OpaquePointer?
        XCTAssertEqual(sqlite3_open(dbURL.path, &handle), SQLITE_OK)
        defer { sqlite3_close(handle) }
        XCTAssertEqual(
            sqlite3_exec(
                handle,
                "DELETE FROM knowledge_metadata WHERE key='content_hash_version'",
                nil,
                nil,
                nil
            ),
            SQLITE_OK
        )

        do {
            _ = try SQLiteKnowledgeRepository(
                url: dbURL,
                embeddingDimensions: 32
            )
            XCTFail("expected content hash version mismatch for legacy FNV libraries")
        } catch let error as KnowledgeBaseError {
            guard case let .storage(message) = error else {
                return XCTFail("expected storage error, got \(error)")
            }
            XCTAssertTrue(message.contains("内容去重哈希"))
            XCTAssertTrue(message.contains(KnowledgeIndexContract.legacyContentHashVersion))
            XCTAssertTrue(message.contains("必须重建索引"))
        }
    }

    func testContentHashLookupFindsDuplicateWithoutScanningAllDocuments() async throws {
        let directory = FileManager.default.temporaryDirectory
            .appending(path: "knowledge-hash-lookup-\(UUID().uuidString)", directoryHint: .isDirectory)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }

        let repository = try SQLiteKnowledgeRepository(
            url: directory.appending(path: "knowledge.sqlite"),
            embeddingDimensions: 32
        )
        let source = directory.appending(path: "guide.md")
        try "# 离线\n不会上传文档。".write(to: source, atomically: true, encoding: .utf8)
        let first = try await ImportKnowledgeDocument(
            extractor: LocalKnowledgeTextExtractor(),
            chunker: StructuredKnowledgeChunker(),
            embedding: DeterministicHashEmbedding(dimensions: 32),
            repository: repository
        )(url: source)

        let found = try await repository.document(contentHash: first.contentHash)
        XCTAssertEqual(found?.id, first.id)
        XCTAssertEqual(found?.title, "guide")
        XCTAssertNil(found?.indexedText)
        let empty = try await repository.document(contentHash: "")
        let missing = try await repository.document(contentHash: "missing")
        XCTAssertNil(empty)
        XCTAssertNil(missing)
    }

    func testStaleImportingDocumentsAreDetectedForReconciliation() {
        let live = UUID()
        let orphan = UUID()
        let docs = [
            KnowledgeDocument(
                id: live,
                sourceURL: URL(fileURLWithPath: "/tmp/a.md"),
                title: "live",
                contentHash: "a",
                state: .importing
            ),
            KnowledgeDocument(
                id: orphan,
                sourceURL: URL(fileURLWithPath: "/tmp/b.md"),
                title: "orphan",
                contentHash: "b",
                state: .importing
            ),
            KnowledgeDocument(
                id: UUID(),
                sourceURL: URL(fileURLWithPath: "/tmp/c.md"),
                title: "ready",
                contentHash: "c",
                state: .ready
            ),
        ]
        let stale = StaleImportReconciliation.staleImportingIDs(
            in: docs,
            activeImportIDs: [live]
        )
        XCTAssertEqual(stale, [orphan])
    }

    func testLoadDocumentsMarksOrphanImportingAsFailed() async throws {
        let directory = FileManager.default.temporaryDirectory
            .appending(path: "knowledge-stale-\(UUID().uuidString)", directoryHint: .isDirectory)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }

        let docsRoot = directory.appending(path: "Documents", directoryHint: .isDirectory)
        try FileManager.default.createDirectory(at: docsRoot, withIntermediateDirectories: true)
        let source = docsRoot.appending(path: "note.md")
        try "# 离线\n索引中断后应可重试。".write(to: source, atomically: true, encoding: .utf8)

        let embedding = DeterministicHashEmbedding(dimensions: 32)
        let repository = try SQLiteKnowledgeRepository(
            url: directory.appending(path: "knowledge.sqlite"),
            embeddingDimensions: 32
        )
        let orphanID = UUID()
        try await repository.save(
            document: KnowledgeDocument(
                id: orphanID,
                sourceURL: source,
                title: "note",
                contentHash: "",
                state: .importing
            )
        )

        let service = await MainActor.run {
            LocalKnowledgeBaseService(
                repository: repository,
                importer: ImportKnowledgeDocument(
                    extractor: LocalKnowledgeTextExtractor(),
                    chunker: StructuredKnowledgeChunker(),
                    embedding: embedding,
                    repository: repository
                ),
                asker: AskKnowledgeBase(
                    repository: repository,
                    embedding: embedding,
                    generator: GeneratorStub()
                ),
                documentsRoot: docsRoot
            )
        }
        let items = try await service.loadDocuments()
        let orphan = items.first { $0.id == orphanID }
        guard case let .failed(message) = orphan?.status else {
            return XCTFail("expected failed status, got \(String(describing: orphan?.status))")
        }
        XCTAssertTrue(message.contains("索引中断"))
        let stored = try await repository.document(id: orphanID)
        XCTAssertEqual(stored?.state, .failed)
        XCTAssertEqual(stored?.errorMessage, StaleImportReconciliation.interruptedMessage)
    }

    func testImportRejectsWhenChunkCapacityWouldBeExceeded() async throws {
        let directory = FileManager.default.temporaryDirectory
            .appending(path: "knowledge-cap-\(UUID().uuidString)", directoryHint: .isDirectory)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }

        let embedding = DeterministicHashEmbedding(dimensions: 32)
        let repository = try SQLiteKnowledgeRepository(
            url: directory.appending(path: "knowledge.sqlite"),
            embeddingDimensions: 32
        )
        let importer = ImportKnowledgeDocument(
            extractor: LocalKnowledgeTextExtractor(),
            chunker: StructuredKnowledgeChunker(),
            embedding: embedding,
            repository: repository,
            maxChunks: 1
        )

        let first = directory.appending(path: "one.md")
        try "# 一\n离线知识库不会上传文档。".write(to: first, atomically: true, encoding: .utf8)
        let ready = try await importer(url: first)
        XCTAssertEqual(ready.state, .ready)
        let afterFirst = try await repository.chunkCount()
        XCTAssertEqual(afterFirst, 1)

        let second = directory.appending(path: "two.md")
        try "# 二\n另一份完全不同的资料内容。".write(to: second, atomically: true, encoding: .utf8)
        do {
            _ = try await importer(url: second)
            XCTFail("expected capacity error")
        } catch let error as KnowledgeBaseError {
            XCTAssertEqual(
                error,
                .chunkCapacityExceeded(current: 1, incoming: 1, limit: 1)
            )
        }
        let afterReject = try await repository.chunkCount()
        let kept = try await repository.document(id: ready.id)
        XCTAssertEqual(afterReject, 1)
        XCTAssertEqual(kept?.state, .ready)

        let resumed = try await importer(url: first, resuming: ready.id)
        XCTAssertEqual(resumed.state, .ready)
        let afterResume = try await repository.chunkCount()
        XCTAssertEqual(afterResume, 1)
    }

    func testImportPersistsImportingRowBeforeExtraction() async throws {
        let directory = FileManager.default.temporaryDirectory
            .appending(path: "knowledge-queue-\(UUID().uuidString)", directoryHint: .isDirectory)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }

        let source = directory.appending(path: "note.md")
        try "# 离线\n复制后应立即出现在列表中。".write(to: source, atomically: true, encoding: .utf8)

        let gate = ExtractGate()
        let repository = try SQLiteKnowledgeRepository(
            url: directory.appending(path: "knowledge.sqlite"),
            embeddingDimensions: 32
        )
        let importer = ImportKnowledgeDocument(
            extractor: GatedExtractor(gate: gate),
            chunker: StructuredKnowledgeChunker(),
            embedding: DeterministicHashEmbedding(dimensions: 32),
            repository: repository
        )

        let task = Task { try await importer(url: source) }
        await gate.waitUntilStarted()
        let listed = try await repository.documents()
        XCTAssertEqual(listed.count, 1)
        XCTAssertEqual(listed.first?.state, .importing)
        let query = try await DeterministicHashEmbedding(dimensions: 32).embed(["离线"]).first!
        let hits = try await repository.search(
            query: "离线",
            embedding: query,
            limit: 3,
            scope: .all
        )
        XCTAssertTrue(hits.isEmpty, "importing documents must not be searchable")
        await gate.open()
        let ready = try await task.value
        XCTAssertEqual(ready.state, .ready)
        let after = try await repository.documents()
        XCTAssertEqual(after.first?.state, .ready)
    }

    func testIndexCheckpointResumesAfterInjectedEmbedFailures() async throws {
        for fraction in [0.1, 0.5, 0.9] {
            try await assertIndexCheckpointResume(failAtFraction: fraction)
        }
    }

    func testCancelledEmbedKeepsStagingForRetry() async throws {
        let directory = FileManager.default.temporaryDirectory
            .appending(path: "knowledge-cancel-\(UUID().uuidString)", directoryHint: .isDirectory)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }

        let chunker = StructuredKnowledgeChunker(targetCharacters: 80, overlapCharacters: 0)
        let source = try Self.writeChunkyMarkdown(to: directory, paragraphs: 12)
        let extraction = try await LocalKnowledgeTextExtractor().extract(from: source)
        let planned = chunker.chunks(documentID: UUID(), extraction: extraction)
        XCTAssertGreaterThanOrEqual(planned.count, 6)

        let gate = ExtractGate()
        let embedding = GatedAfterFirstBatchEmbedding(
            dimensions: 32,
            gate: gate
        )
        let repository = try SQLiteKnowledgeRepository(
            url: directory.appending(path: "knowledge.sqlite"),
            embeddingDimensions: 32
        )
        let importer = ImportKnowledgeDocument(
            extractor: LocalKnowledgeTextExtractor(),
            chunker: chunker,
            embedding: embedding,
            repository: repository,
            indexBatchSize: 2
        )

        let task = Task { try await importer(url: source) }
        await gate.waitUntilStarted()
        task.cancel()
        await gate.open()
        do {
            _ = try await task.value
            XCTFail("expected cancellation")
        } catch is CancellationError {
            // expected
        } catch {
            XCTFail("expected CancellationError, got \(error)")
        }

        let listed = try await repository.documents()
        XCTAssertEqual(listed.first?.state, .failed)
        let unpublished = try await repository.unpublishedChunkCount(documentID: listed[0].id)
        XCTAssertEqual(unpublished, 2)
        let liveCountAfterCancel = try await repository.chunkCount()
        XCTAssertEqual(liveCountAfterCancel, 0)

        let query = try await DeterministicHashEmbedding(dimensions: 32).embed(["离线内容"]).first!
        let hits = try await repository.search(
            query: "离线内容",
            embedding: query,
            limit: 3,
            scope: .all
        )
        XCTAssertTrue(hits.isEmpty, "cancelled index must not be searchable")

        let resumed = try await ImportKnowledgeDocument(
            extractor: LocalKnowledgeTextExtractor(),
            chunker: chunker,
            embedding: DeterministicHashEmbedding(dimensions: 32),
            repository: repository,
            indexBatchSize: 2
        )(url: source, resuming: listed[0].id)
        XCTAssertEqual(resumed.state, .ready)
        XCTAssertEqual(resumed.importedChunkCount, planned.count)
        let unpublishedAfterResume = try await repository.unpublishedChunkCount(documentID: resumed.id)
        let liveAfterResume = try await repository.chunkCount(documentID: resumed.id)
        XCTAssertEqual(unpublishedAfterResume, 0)
        XCTAssertEqual(liveAfterResume, planned.count)
    }

    func testReadyDocumentKeepsServingUntilPublish() async throws {
        let directory = FileManager.default.temporaryDirectory
            .appending(path: "knowledge-serve-\(UUID().uuidString)", directoryHint: .isDirectory)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }

        let source = directory.appending(path: "guide.md")
        try "# 旧版\n旧版关键词ALPHA不会上传文档。".write(to: source, atomically: true, encoding: .utf8)
        let embedding = DeterministicHashEmbedding(dimensions: 32)
        let repository = try SQLiteKnowledgeRepository(
            url: directory.appending(path: "knowledge.sqlite"),
            embeddingDimensions: 32
        )
        let importer = ImportKnowledgeDocument(
            extractor: LocalKnowledgeTextExtractor(),
            chunker: StructuredKnowledgeChunker(),
            embedding: embedding,
            repository: repository
        )
        let first = try await importer(url: source)
        XCTAssertEqual(first.state, .ready)

        try "# 新版\n新版关键词BETA是完全不同的资料。".write(to: source, atomically: true, encoding: .utf8)
        let gate = ExtractGate()
        let gated = ImportKnowledgeDocument(
            extractor: LocalKnowledgeTextExtractor(),
            chunker: StructuredKnowledgeChunker(),
            embedding: GatedDocumentEmbedding(inner: embedding, gate: gate),
            repository: repository,
            indexBatchSize: 1
        )
        let task = Task { try await gated(url: source, resuming: first.id) }
        await gate.waitUntilStarted()

        let live = try await repository.document(id: first.id)
        XCTAssertEqual(live?.state, .ready)
        let oldQuery = try await embedding.embed(["ALPHA"]).first!
        let oldHits = try await repository.search(
            query: "ALPHA",
            embedding: oldQuery,
            limit: 3,
            scope: .all
        )
        XCTAssertTrue(oldHits.contains { $0.chunk.text.contains("ALPHA") })
        let newQuery = try await embedding.embed(["BETA"]).first!
        let newHits = try await repository.search(
            query: "BETA",
            embedding: newQuery,
            limit: 3,
            scope: .all
        )
        XCTAssertFalse(newHits.contains { $0.chunk.text.contains("BETA") })

        await gate.open()
        let published = try await task.value
        XCTAssertEqual(published.state, .ready)
        XCTAssertTrue(published.indexedText?.contains("BETA") == true)
        let afterOld = try await repository.search(
            query: "ALPHA",
            embedding: oldQuery,
            limit: 3,
            scope: .all
        )
        XCTAssertFalse(afterOld.contains { $0.chunk.text.contains("ALPHA") })
        let afterNew = try await repository.search(
            query: "BETA",
            embedding: newQuery,
            limit: 3,
            scope: .all
        )
        XCTAssertTrue(afterNew.contains { $0.chunk.text.contains("BETA") })
    }

    private func assertIndexCheckpointResume(failAtFraction: Double) async throws {
        let directory = FileManager.default.temporaryDirectory
            .appending(path: "knowledge-ckpt-\(failAtFraction)-\(UUID().uuidString)", directoryHint: .isDirectory)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }

        let chunker = StructuredKnowledgeChunker(targetCharacters: 80, overlapCharacters: 0)
        let source = try Self.writeChunkyMarkdown(to: directory, paragraphs: 12)
        let extraction = try await LocalKnowledgeTextExtractor().extract(from: source)
        let planned = chunker.chunks(documentID: UUID(), extraction: extraction)
        let batchSize = 2
        let totalBatches = (planned.count + batchSize - 1) / batchSize
        let succeed = max(1, min(totalBatches - 1, Int((Double(totalBatches) * failAtFraction).rounded(.down))))
        XCTAssertGreaterThan(planned.count, succeed * batchSize)

        let failing = BatchFailingEmbedding(
            dimensions: 32,
            failAfterSuccessfulBatches: succeed
        )
        let repository = try SQLiteKnowledgeRepository(
            url: directory.appending(path: "knowledge.sqlite"),
            embeddingDimensions: 32
        )
        let importer = ImportKnowledgeDocument(
            extractor: LocalKnowledgeTextExtractor(),
            chunker: chunker,
            embedding: failing,
            repository: repository,
            indexBatchSize: batchSize
        )
        do {
            _ = try await importer(url: source)
            XCTFail("expected injected failure at \(failAtFraction)")
        } catch let error as KnowledgeBaseError {
            guard case .storage(let message) = error else {
                return XCTFail("expected storage failure, got \(error)")
            }
            XCTAssertTrue(message.contains("injected embed failure"))
        }

        let listed = try await repository.documents()
        XCTAssertEqual(listed.first?.state, .failed)
        let unpublished = try await repository.unpublishedChunkCount(documentID: listed[0].id)
        XCTAssertEqual(unpublished, succeed * batchSize)
        let liveDuringFailure = try await repository.chunkCount()
        XCTAssertEqual(liveDuringFailure, 0)
        let query = try await DeterministicHashEmbedding(dimensions: 32).embed(["离线内容"]).first!
        let hits = try await repository.search(
            query: "离线内容",
            embedding: query,
            limit: 3,
            scope: .all
        )
        XCTAssertTrue(hits.isEmpty, "half-indexed document must not be searchable")

        await failing.allowCompletion()
        await failing.resetCounts()
        let resumed = try await ImportKnowledgeDocument(
            extractor: LocalKnowledgeTextExtractor(),
            chunker: chunker,
            embedding: failing,
            repository: repository,
            indexBatchSize: batchSize
        )(url: source, resuming: listed[0].id)
        XCTAssertEqual(resumed.state, .ready)
        XCTAssertEqual(resumed.importedChunkCount, planned.count)
        let liveAfterResume = try await repository.chunkCount(documentID: resumed.id)
        let unpublishedAfterResume = try await repository.unpublishedChunkCount(documentID: resumed.id)
        let embeddedOnResume = await failing.embeddedCount
        XCTAssertEqual(liveAfterResume, planned.count)
        XCTAssertEqual(unpublishedAfterResume, 0)
        let remaining = planned.count - unpublished
        XCTAssertEqual(embeddedOnResume, remaining)
        let after = try await repository.search(
            query: "离线内容",
            embedding: query,
            limit: 3,
            scope: .all
        )
        XCTAssertFalse(after.isEmpty)
        XCTAssertEqual(after.first?.chunk.documentID, resumed.id)
    }

    func testTenThousandChunkHardCapAndOverflowReject() async throws {
        let directory = FileManager.default.temporaryDirectory
            .appending(path: "knowledge-10k-\(UUID().uuidString)", directoryHint: .isDirectory)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }

        let target = KnowledgeBaseLimits.maxChunks
        let source = try Self.writeExactChunkMarkdown(to: directory, chunks: target, fileName: "capacity.md")
        let chunker = StructuredKnowledgeChunker()
        let extraction = try await LocalKnowledgeTextExtractor().extract(from: source)
        let planned = chunker.chunks(documentID: UUID(), extraction: extraction)
        XCTAssertEqual(planned.count, target, "generator must map 1 section → 1 chunk")

        let embedding = DeterministicHashEmbedding(dimensions: 32)
        let repository = try SQLiteKnowledgeRepository(
            url: directory.appending(path: "knowledge.sqlite"),
            embeddingDimensions: 32
        )
        let importer = ImportKnowledgeDocument(
            extractor: LocalKnowledgeTextExtractor(),
            chunker: chunker,
            embedding: embedding,
            repository: repository,
            maxChunks: target,
            indexBatchSize: 64
        )
        let ready = try await importer(url: source)
        XCTAssertEqual(ready.state, .ready)
        XCTAssertEqual(ready.importedChunkCount, target)
        let liveCount = try await repository.chunkCount()
        let unpublished = try await repository.unpublishedChunkCount(documentID: ready.id)
        XCTAssertEqual(liveCount, target)
        XCTAssertEqual(unpublished, 0)

        let firstQuery = try await embedding.embedQuery("CHUNK-00001")
        let firstHits = try await repository.search(
            query: "CHUNK-00001",
            embedding: firstQuery,
            limit: 5,
            scope: .all
        )
        XCTAssertTrue(firstHits.contains { $0.chunk.text.contains("CHUNK-00001") })

        let lastTag = String(format: "CHUNK-%05d", target)
        let lastQuery = try await embedding.embedQuery(lastTag)
        let lastHits = try await repository.search(
            query: lastTag,
            embedding: lastQuery,
            limit: 5,
            scope: .all
        )
        XCTAssertTrue(lastHits.contains { $0.chunk.text.contains(lastTag) })

        let overflow = try Self.writeExactChunkMarkdown(
            to: directory,
            chunks: 1,
            fileName: "overflow.md"
        )
        do {
            _ = try await importer(url: overflow)
            XCTFail("expected capacity exceeded")
        } catch let error as KnowledgeBaseError {
            XCTAssertEqual(
                error,
                .chunkCapacityExceeded(current: target, incoming: 1, limit: target)
            )
        }
        let afterOverflow = try await repository.chunkCount()
        XCTAssertEqual(afterOverflow, target)
        let kept = try await repository.document(id: ready.id)
        XCTAssertEqual(kept?.state, .ready)
        XCTAssertEqual(kept?.importedChunkCount, target)
    }

    func testDebugFaultInjectionTripsOnceThenAllowsRetry() async throws {
        IndexFaultInjection.reset()
        IndexFaultInjection.installFromProcessArguments(["-KBFailAtFraction", "0.5"])
        defer { IndexFaultInjection.reset() }

        let directory = FileManager.default.temporaryDirectory
            .appending(path: "knowledge-fault-\(UUID().uuidString)", directoryHint: .isDirectory)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }

        let chunker = StructuredKnowledgeChunker(targetCharacters: 80, overlapCharacters: 0)
        let source = try Self.writeChunkyMarkdown(to: directory, paragraphs: 20)
        let extraction = try await LocalKnowledgeTextExtractor().extract(from: source)
        let planned = chunker.chunks(documentID: UUID(), extraction: extraction)
        XCTAssertGreaterThanOrEqual(planned.count, 8)

        let repository = try SQLiteKnowledgeRepository(
            url: directory.appending(path: "knowledge.sqlite"),
            embeddingDimensions: 32
        )
        let failing = ImportKnowledgeDocument(
            extractor: LocalKnowledgeTextExtractor(),
            chunker: chunker,
            embedding: DeterministicHashEmbedding(dimensions: 32),
            repository: repository,
            indexBatchSize: 2
        )
        do {
            _ = try await failing(url: source)
            XCTFail("expected DEBUG fault injection")
        } catch let error as KnowledgeBaseError {
            guard case .storage(let message) = error else {
                return XCTFail("expected storage fault, got \(error)")
            }
            XCTAssertTrue(message.contains("DEBUG fault injection"))
        }
        let listed = try await repository.documents()
        XCTAssertEqual(listed.first?.state, .failed)
        let unpublishedDuringFault = try await repository.unpublishedChunkCount(documentID: listed[0].id)
        let liveDuringFault = try await repository.chunkCount()
        XCTAssertGreaterThan(unpublishedDuringFault, 0)
        XCTAssertEqual(liveDuringFault, 0)

        let resumed = try await ImportKnowledgeDocument(
            extractor: LocalKnowledgeTextExtractor(),
            chunker: chunker,
            embedding: DeterministicHashEmbedding(dimensions: 32),
            repository: repository,
            indexBatchSize: 2
        )(url: source, resuming: listed[0].id)
        XCTAssertEqual(resumed.state, .ready)
        XCTAssertEqual(resumed.importedChunkCount, planned.count)
        let unpublishedAfter = try await repository.unpublishedChunkCount(documentID: resumed.id)
        XCTAssertEqual(unpublishedAfter, 0)
    }

    private static func writeExactChunkMarkdown(
        to directory: URL,
        chunks: Int,
        fileName: String
    ) throws -> URL {
        var body = ""
        for index in 1...chunks {
            body += """
            # C\(String(format: "%05d", index))
            CHUNK-\(String(format: "%05d", index)) 离线容量闸门填充段。本段用于构造恰好一万个可检索 chunks，不得上传文档，也不得调用云端搜索。

            """
        }
        let url = directory.appending(path: fileName)
        try body.write(to: url, atomically: true, encoding: .utf8)
        return url
    }

    private static func writeChunkyMarkdown(to directory: URL, paragraphs: Int) throws -> URL {
        var body = "# 手册\n"
        for index in 1...paragraphs {
            body += "段落\(index)：" + String(repeating: "离线内容。", count: 18) + "\n\n"
        }
        let url = directory.appending(path: "chunky.md")
        try body.write(to: url, atomically: true, encoding: .utf8)
        return url
    }

    func testDuplicateImportIsRejectedForANewDocumentID() async throws {
        let directory = FileManager.default.temporaryDirectory
            .appending(path: "knowledge-dup-id-\(UUID().uuidString)", directoryHint: .isDirectory)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }

        let source = directory.appending(path: "guide.md")
        try "# 离线模式\n不会上传文档。".write(to: source, atomically: true, encoding: .utf8)
        let repository = try SQLiteKnowledgeRepository(
            url: directory.appending(path: "knowledge.sqlite"),
            embeddingDimensions: 32
        )
        let importer = ImportKnowledgeDocument(
            extractor: LocalKnowledgeTextExtractor(),
            chunker: StructuredKnowledgeChunker(),
            embedding: DeterministicHashEmbedding(dimensions: 32),
            repository: repository
        )
        let first = try await importer(url: source)
        let copyID = UUID()
        let copy = directory.appending(path: "guide-copy.md")
        try FileManager.default.copyItem(at: source, to: copy)
        do {
            _ = try await importer(url: copy, resuming: copyID)
            XCTFail("expected duplicate import to fail")
        } catch let error as KnowledgeBaseError {
            XCTAssertEqual(error, .duplicateDocument(existingTitle: "guide"))
        }
        let leftover = try await repository.document(id: copyID)
        let remaining = try await repository.documents()
        XCTAssertNil(leftover)
        XCTAssertEqual(remaining.map(\.id), [first.id])
    }

    func testHybridSearchKeepsTopKWithoutLoadingLosingBestHit() async throws {
        let directory = FileManager.default.temporaryDirectory
            .appending(path: "knowledge-topk-\(UUID().uuidString)", directoryHint: .isDirectory)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }

        let embedding = DeterministicHashEmbedding(dimensions: 64)
        let repository = try SQLiteKnowledgeRepository(
            url: directory.appending(path: "knowledge.sqlite"),
            embeddingDimensions: 64
        )
        let importer = ImportKnowledgeDocument(
            extractor: LocalKnowledgeTextExtractor(),
            chunker: StructuredKnowledgeChunker(targetCharacters: 60, overlapCharacters: 0),
            embedding: embedding,
            repository: repository
        )

        let source = directory.appending(path: "many.md")
        var body = "# 手册\n"
        for index in 1...20 {
            body += "无关段落 \(index)：" + String(repeating: "填充内容。", count: 12) + "\n\n"
        }
        body += "离线知识库不会上传文档，检索必须命中这一段。\n"
        try body.write(to: source, atomically: true, encoding: .utf8)
        let document = try await importer(url: source)
        XCTAssertGreaterThan(document.importedChunkCount, 4)

        let query = try await embedding.embedQuery("不会上传文档")
        let hits = try await repository.search(
            query: "不会上传文档",
            embedding: query,
            limit: 2,
            scope: .all
        )
        XCTAssertEqual(hits.count, 2)
        XCTAssertTrue(
            hits.contains { $0.chunk.text.contains("不会上传文档") },
            "top-k scan must still return the lexical/vector best chunk"
        )
    }

    func testAskKnowledgeBaseEnforcesContextBudgetAndCitations() async throws {
        let documentID = UUID()
        // Long chunk text so focused excerpts still consume budget (query has no literal overlap).
        let longA = String(repeating: "证据一内容。", count: 40)
        let longB = String(repeating: "证据二内容。", count: 40)
        let repository = RepositoryStub(hits: [
            Self.hit(documentID: documentID, ordinal: 0, text: longA, tokens: 100),
            Self.hit(documentID: documentID, ordinal: 1, text: longB, tokens: 100),
        ])
        let useCase = AskKnowledgeBase(
            repository: repository,
            embedding: DeterministicHashEmbedding(dimensions: 32),
            generator: GeneratorStub(),
            budget: OnDeviceRAGBudget(
                evidenceTokenBudget: 80,
                retrievalLimit: 5,
                maxCitations: 3,
                maxChunksPerDocument: 2,
                minimumScore: 0.0,
                evidenceExcerptCharacters: 420
            )
        )
        let answer = try await useCase(question: "问题")
        XCTAssertEqual(answer.citations.count, 1)
        XCTAssertEqual(answer.citations.first?.index, 1)
        XCTAssertTrue(answer.text.contains("[1]"), answer.text)
        XCTAssertFalse(answer.text.contains("[9]"), answer.text)
    }

    func testAskKnowledgeBaseOnlyExposesModelReferencedCitations() async throws {
        let documentID = UUID()
        let repository = RepositoryStub(hits: [
            Self.hit(documentID: documentID, ordinal: 0, text: "证据一内容足够长", tokens: 40),
            Self.hit(documentID: documentID, ordinal: 1, text: "证据二内容足够长", tokens: 40),
        ])
        let useCase = AskKnowledgeBase(
            repository: repository,
            embedding: DeterministicHashEmbedding(dimensions: 32),
            generator: CiteSecondOnlyStub(),
            budget: OnDeviceRAGBudget(
                evidenceTokenBudget: 200,
                retrievalLimit: 5,
                maxCitations: 3,
                maxChunksPerDocument: 2,
                minimumScore: 0.0,
                evidenceExcerptCharacters: 420
            )
        )
        let answer = try await useCase(question: "问题")
        XCTAssertEqual(answer.citations.map(\.index), [2], answer.text)
        XCTAssertTrue(answer.text.contains("[2]"), answer.text)
        XCTAssertFalse(answer.text.contains("[1]"), answer.text)
    }

    func testAskKnowledgeBaseRestrictsEvidenceToSelectedDocuments() async throws {
        let firstID = UUID()
        let secondID = UUID()
        let repository = RepositoryStub(hits: [
            Self.hit(documentID: firstID, ordinal: 0, text: "第一份资料", tokens: 40),
            Self.hit(documentID: secondID, ordinal: 0, text: "第二份资料", tokens: 40),
        ])
        let useCase = AskKnowledgeBase(
            repository: repository,
            embedding: DeterministicHashEmbedding(dimensions: 32),
            generator: ScopedGeneratorStub(),
            budget: OnDeviceRAGBudget(minimumScore: 0)
        )

        let answer = try await useCase(
            question: "问题",
            scope: .documents([secondID])
        )

        XCTAssertEqual(answer.citations.map(\.documentID), [secondID])
        XCTAssertEqual(answer.citations.map(\.index), [1])
    }

    func testCitationJSONIgnoresLegacyIndexedText() throws {
        let id = UUID()
        let documentID = UUID()
        let json = """
        {
          "id": "\(id.uuidString)",
          "index": 1,
          "documentID": "\(documentID.uuidString)",
          "documentTitle": "手册",
          "excerpt": "片段",
          "indexedText": "整篇正文不应该进入会话模型"
        }
        """
        let citation = try JSONDecoder().decode(
            OrynodeMobileAI.CitedSource.self,
            from: Data(json.utf8)
        )
        XCTAssertEqual(citation.id, id)
        XCTAssertEqual(citation.excerpt, "片段")
        let encoded = try JSONEncoder().encode(citation)
        let object = try JSONSerialization.jsonObject(with: encoded) as? [String: Any]
        XCTAssertNil(object?["indexedText"])
    }

    func testUTF16TextIndexCountsNewlinesLikeTheChunker() {
        let text = "第一行\n第二行\n第三行"
        XCTAssertEqual(UTF16TextIndex.lineNumber(utf16Offset: 0, in: text), 1)
        XCTAssertEqual(
            UTF16TextIndex.lineNumber(utf16Offset: 0, in: text),
            StructuredKnowledgeChunker.lineNumber(utf16Offset: 0, in: text)
        )
        let second = StructuredKnowledgeChunker.lineNumber(utf16Offset: 4, in: text)
        XCTAssertEqual(UTF16TextIndex.lineNumber(utf16Offset: 4, in: text), second)
        XCTAssertEqual(second, 2)
    }

    func testOnDeviceRAGBudgetLeavesRoomForAnswer() {
        let budget = OnDeviceRAGBudget.gemmaE2B
        XCTAssertLessThanOrEqual(
            budget.systemPromptTokens
                + budget.questionTokens
                + budget.evidenceTokenBudget
                + budget.answerReserveTokens,
            budget.engineMaxTokens
        )
        XCTAssertEqual(budget.retrievalLimit, 5)
        XCTAssertEqual(budget.maxCitations, 3)
    }

    func testCitationCanonicalizerDropsIllegalAndKeepsAllowed() {
        let result = CitationCanonicalizer().canonicalize(
            "反向代理把请求转到内网端口[9]。也可以按域名分流[2]。\n\n保留空行与位置[2]。",
            allowedIndices: [1, 2]
        )
        XCTAssertEqual(result.referencedIndices, [2])
        XCTAssertEqual(
            result.text,
            "反向代理把请求转到内网端口。也可以按域名分流[2]。\n\n保留空行与位置[2]。"
        )
    }

    func testKnowledgeAnswerSanitizerStripsLeakedControlTokens() {
        let raw = "雨花区管理部地址是：长沙市雨花区蔡<bos>锷中路1号 [1]"
        let cleaned = KnowledgeAnswerSanitizer.stripControlTokens(raw)
        XCTAssertEqual(cleaned, "雨花区管理部地址是：长沙市雨花区蔡锷中路1号 [1]")
        XCTAssertFalse(cleaned.contains("<bos>"))
        XCTAssertEqual(
            KnowledgeAnswerSanitizer.stripControlTokens("a</s><eos><PAD>b"),
            "ab"
        )
    }

    func testEvidencePackFocusWindowsExcerptOntoQueryOverlap() {
        let text = """
        望城区管理部地址：望城区望府路198号区政务中心二楼；
        芙蓉区管理部地址：长沙市芙蓉区蔡锷中路伍家井一号；
        雨花区管理部地址：长沙市雨花区湘府中路12号；
        天心区管理部地址：长沙市天心区新开铺路80号。
        """
        let excerpt = EvidencePackFocus.excerpt(from: text, query: "雨花区", maxCharacters: 80)
        XCTAssertTrue(excerpt.contains("雨花区管理部地址"))
        XCTAssertTrue(excerpt.contains("湘府中路12号"))
        XCTAssertFalse(excerpt.hasPrefix("望城区"), "should not lead with a neighboring district")
    }

    func testEvidencePackFocusExpandsPastShortHeadingHit() {
        let heading = "编辑反向代理服务器配置文件："
        let body = """
        反向代理服务器，利用 nginx反向代理将不同域名的请求转发给内网不同机器的特定端口。
        例如输入 xxx123.tk 访问 192.168.10.38:3000。
        """
        let text = "前文无关说明。\n\(heading)\n\(body)\n后文无关说明。"
        let excerpt = EvidencePackFocus.excerpt(from: text, query: "反向代理", maxCharacters: 200)
        XCTAssertTrue(excerpt.contains(heading))
        XCTAssertTrue(
            excerpt.contains("不同域名"),
            "must not starve pack to the heading line alone (AskKB tokens=5 failure)"
        )
        XCTAssertGreaterThan(excerpt.count, heading.count + 20)
    }

    func testEvidencePackFocusPrioritizesRicherOverlapWindow() {
        let documentID = UUID()
        let headingOnly = KnowledgeSearchHit(
            chunk: KnowledgeChunk(
                documentID: documentID,
                ordinal: 0,
                text: "编辑反向代理服务器配置文件：\nproxy_pass http://127.0.0.1;",
                tokenEstimate: 40
            ),
            documentTitle: "手册",
            score: 0.90
        )
        let definition = KnowledgeSearchHit(
            chunk: KnowledgeChunk(
                documentID: documentID,
                ordinal: 1,
                text: "反向代理服务器，利用 nginx反向代理将不同域名的请求转发给内网不同机器的特定端口。",
                tokenEstimate: 40
            ),
            documentTitle: "手册",
            score: 0.61
        )
        let ranked = EvidencePackFocus.prioritize([headingOnly, definition], query: "反向代理")
        XCTAssertEqual(
            ranked.first?.chunk.ordinal,
            1,
            "definition window should outrank a thin heading hit"
        )
    }

    func testEvidencePackFocusPrioritizesOverlappingHits() {
        let documentID = UUID()
        let wangcheng = KnowledgeSearchHit(
            chunk: KnowledgeChunk(
                documentID: documentID,
                ordinal: 0,
                text: "望城区管理部地址：望城区望府路198号区政务中心二楼；",
                tokenEstimate: 40
            ),
            documentTitle: "手册",
            score: 0.90
        )
        let yuhua = KnowledgeSearchHit(
            chunk: KnowledgeChunk(
                documentID: documentID,
                ordinal: 1,
                text: "雨花区管理部地址：长沙市雨花区湘府中路12号；",
                tokenEstimate: 40
            ),
            documentTitle: "手册",
            score: 0.55
        )
        let ranked = EvidencePackFocus.prioritize([wangcheng, yuhua], query: "雨花区")
        XCTAssertEqual(ranked.first?.chunk.ordinal, 1)
        XCTAssertTrue(ranked.first?.chunk.text.contains("雨花区") == true)
    }

    private static func hit(
        documentID: UUID,
        ordinal: Int,
        text: String,
        tokens: Int
    ) -> KnowledgeSearchHit {
        KnowledgeSearchHit(
            chunk: KnowledgeChunk(
                documentID: documentID,
                ordinal: ordinal,
                text: text,
                tokenEstimate: tokens
            ),
            documentTitle: "文档",
            score: Float(1 - ordinal) / 2
        )
    }
}

private actor ExtractGate {
    private var started = false
    private var proceed = false
    private var startedWaiters: [CheckedContinuation<Void, Never>] = []
    private var proceedWaiters: [CheckedContinuation<Void, Never>] = []

    func markStarted() {
        started = true
        let waiters = startedWaiters
        startedWaiters.removeAll()
        waiters.forEach { $0.resume() }
    }

    func waitUntilStarted() async {
        if started { return }
        await withCheckedContinuation { startedWaiters.append($0) }
    }

    func waitToProceed() async {
        if proceed { return }
        await withCheckedContinuation { proceedWaiters.append($0) }
    }

    func open() {
        proceed = true
        let waiters = proceedWaiters
        proceedWaiters.removeAll()
        waiters.forEach { $0.resume() }
    }
}

private struct GatedExtractor: KnowledgeTextExtractor {
    let gate: ExtractGate

    func extract(from url: URL) async throws -> KnowledgeExtraction {
        await gate.markStarted()
        await gate.waitToProceed()
        return try await LocalKnowledgeTextExtractor().extract(from: url)
    }
}

private struct GatedDocumentEmbedding: TextEmbedding {
    let inner: DeterministicHashEmbedding
    let gate: ExtractGate

    var name: String { inner.name }
    var dimensions: Int { inner.dimensions }

    func embed(_ texts: [String]) async throws -> [[Float]] {
        try await embedDocuments(texts)
    }

    func embedDocuments(_ texts: [String]) async throws -> [[Float]] {
        await gate.markStarted()
        await gate.waitToProceed()
        return try await inner.embedDocuments(texts)
    }
}

private actor GatedAfterFirstBatchEmbedding: TextEmbedding {
    let inner: DeterministicHashEmbedding
    let gate: ExtractGate
    private var batches = 0
    nonisolated let dimensions: Int

    init(dimensions: Int, gate: ExtractGate) {
        inner = DeterministicHashEmbedding(dimensions: dimensions)
        self.gate = gate
        self.dimensions = dimensions
    }

    nonisolated var name: String { "gated-after-first-batch" }

    func embed(_ texts: [String]) async throws -> [[Float]] {
        try await embedDocuments(texts)
    }

    func embedDocuments(_ texts: [String]) async throws -> [[Float]] {
        batches += 1
        if batches == 2 {
            await gate.markStarted()
            await gate.waitToProceed()
            try Task.checkCancellation()
        }
        return try await inner.embedDocuments(texts)
    }
}

private actor BatchFailingEmbedding: TextEmbedding {
    let inner: DeterministicHashEmbedding
    private let failAfterSuccessfulBatches: Int
    private var shouldFail = true
    private(set) var embeddedCount = 0
    private var batchCount = 0
    nonisolated let dimensions: Int

    init(dimensions: Int, failAfterSuccessfulBatches: Int) {
        inner = DeterministicHashEmbedding(dimensions: dimensions)
        self.failAfterSuccessfulBatches = failAfterSuccessfulBatches
        self.dimensions = dimensions
    }

    nonisolated var name: String { "batch-failing" }

    func allowCompletion() {
        shouldFail = false
    }

    func resetCounts() {
        embeddedCount = 0
        batchCount = 0
    }

    func embed(_ texts: [String]) async throws -> [[Float]] {
        try await embedDocuments(texts)
    }

    func embedDocuments(_ texts: [String]) async throws -> [[Float]] {
        batchCount += 1
        if shouldFail, batchCount > failAfterSuccessfulBatches {
            throw KnowledgeBaseError.storage("injected embed failure at batch \(batchCount)")
        }
        embeddedCount += texts.count
        return try await inner.embedDocuments(texts)
    }
}

private actor RepositoryStub: KnowledgeRepository {
    let hits: [KnowledgeSearchHit]
    init(hits: [KnowledgeSearchHit]) { self.hits = hits }
    func document(id: UUID) -> KnowledgeDocument? { nil }
    func document(contentHash: String) -> KnowledgeDocument? { nil }
    func documents() -> [KnowledgeDocument] { [] }
    func save(document: KnowledgeDocument) {}
    func replaceChunks(documentID: UUID, chunks: [EmbeddedKnowledgeChunk]) {}
    func prepareIndexJob(
        documentID: UUID,
        contentHash: String,
        indexedText: String,
        pageSpans: [KnowledgePageSpan]
    ) -> Int { 0 }
    func unpublishedChunkCount(documentID: UUID) -> Int { 0 }
    func discardUnpublishedChunks(documentID: UUID) {}
    func appendUnpublishedChunks(documentID: UUID, chunks: [EmbeddedKnowledgeChunk]) {}
    func publishUnpublishedChunks(document: KnowledgeDocument) {}
    func deleteDocument(id: UUID) {}
    func search(
        query: String,
        embedding: [Float],
        limit: Int,
        scope: KnowledgeSearchScope
    ) -> [KnowledgeSearchHit] {
        let scoped: [KnowledgeSearchHit]
        switch scope {
        case .all:
            scoped = hits
        case let .documents(ids):
            scoped = hits.filter { ids.contains($0.chunk.documentID) }
        }
        return Array(scoped.prefix(limit))
    }
    func chunkCount() -> Int { hits.count }
    func chunkCount(documentID: UUID) -> Int {
        hits.filter { $0.chunk.documentID == documentID }.count
    }
}

private struct GeneratorStub: KnowledgeAnswerGenerator {
    func answer(question: String, context: String) async throws -> String {
        XCTAssertTrue(context.contains("证据一"))
        XCTAssertFalse(context.contains("证据二"))
        return "证据一支持离线分析能力，不会上传文档。[1]"
    }
}

private struct CiteSecondOnlyStub: KnowledgeAnswerGenerator {
    func answer(question: String, context: String) async throws -> String {
        XCTAssertTrue(context.contains("[1]"))
        XCTAssertTrue(context.contains("[2]"))
        return "仅依据第二条证据作答。[2]"
    }
}

private struct ScopedGeneratorStub: KnowledgeAnswerGenerator {
    func answer(question: String, context: String) async throws -> String {
        XCTAssertFalse(context.contains("第一份资料"))
        XCTAssertTrue(context.contains("第二份资料"))
        return "仅使用选中的资料回答。[1]"
    }
}
