import Foundation

public struct KnowledgeDocument: Codable, Equatable, Sendable, Identifiable {
    public enum State: String, Codable, Sendable {
        case importing
        case ready
        case failed
    }

    public let id: UUID
    public let sourceURL: URL
    public var title: String
    public var contentHash: String
    public var state: State
    public var importedChunkCount: Int
    public var errorMessage: String?
    /// Canonical text used for chunking / line offsets (TXT/MD). PDF may store joined page text.
    public var indexedText: String?
    /// PDF page ranges within `indexedText`. Empty for non-PDF. Loaded with the detail row, not the list.
    public var pageSpans: [KnowledgePageSpan]
    public let createdAt: Date
    public var updatedAt: Date

    public init(
        id: UUID = UUID(),
        sourceURL: URL,
        title: String,
        contentHash: String,
        state: State = .importing,
        importedChunkCount: Int = 0,
        errorMessage: String? = nil,
        indexedText: String? = nil,
        pageSpans: [KnowledgePageSpan] = [],
        createdAt: Date = Date(),
        updatedAt: Date = Date()
    ) {
        self.id = id
        self.sourceURL = sourceURL
        self.title = title
        self.contentHash = contentHash
        self.state = state
        self.importedChunkCount = importedChunkCount
        self.errorMessage = errorMessage
        self.indexedText = indexedText
        self.pageSpans = pageSpans
        self.createdAt = createdAt
        self.updatedAt = updatedAt
    }
}

/// Stable jump target for preview. Written at ingest; citations copy it as a snapshot.
public enum SourceLocator: Codable, Equatable, Hashable, Sendable {
    /// PDF page is 1-based. Offsets are UTF-16 units within that page's extracted string.
    case pdf(page: Int, startOffset: Int?, endOffset: Int?)
    /// Markdown lines are 1-based within `indexedText`.
    case markdown(headingPath: [String]?, startLine: Int, endLine: Int)
    /// Plain text offsets are UTF-16 units within `indexedText`.
    case plainText(startOffset: Int, endOffset: Int)

    public var shortLabel: String {
        switch self {
        case let .pdf(page, _, _):
            return "第 \(page) 页"
        case let .markdown(headingPath, startLine, endLine):
            if let heading = headingPath?.last, !heading.isEmpty {
                return heading
            }
            return startLine == endLine ? "第 \(startLine) 行" : "第 \(startLine)–\(endLine) 行"
        case let .plainText(start, end):
            return "字符 \(start)–\(end)"
        }
    }
}

public enum UTF16TextIndex {
    public static func lineNumber(utf16Offset: Int, in text: String) -> Int {
        let utf16 = text.utf16
        guard utf16Offset > 0 else { return 1 }
        let clamped = min(max(0, utf16Offset), utf16.count)
        var line = 1
        var index = utf16.startIndex
        var offset = 0
        while offset < clamped, index < utf16.endIndex {
            if utf16[index] == 10 { line += 1 }
            index = utf16.index(after: index)
            offset += 1
        }
        return line
    }
}

public struct KnowledgePageSpan: Codable, Equatable, Sendable {
    public let page: Int
    public let start: Int
    public let end: Int

    public init(page: Int, start: Int, end: Int) {
        self.page = page
        self.start = start
        self.end = end
    }
}

public struct KnowledgeExtraction: Equatable, Sendable {
    public enum Kind: String, Codable, Sendable {
        case plainText
        case markdown
        case pdf
    }

    public let kind: Kind
    public let indexedText: String
    public let pageSpans: [KnowledgePageSpan]

    public init(kind: Kind, indexedText: String, pageSpans: [KnowledgePageSpan] = []) {
        self.kind = kind
        self.indexedText = indexedText
        self.pageSpans = pageSpans
    }
}

public struct KnowledgeChunk: Codable, Equatable, Sendable, Identifiable {
    public let id: UUID
    public let documentID: UUID
    public let ordinal: Int
    public let heading: String?
    public let text: String
    public let tokenEstimate: Int
    public let locator: SourceLocator?

    public init(
        id: UUID = UUID(),
        documentID: UUID,
        ordinal: Int,
        heading: String? = nil,
        text: String,
        tokenEstimate: Int,
        locator: SourceLocator? = nil
    ) {
        self.id = id
        self.documentID = documentID
        self.ordinal = ordinal
        self.heading = heading
        self.text = text
        self.tokenEstimate = tokenEstimate
        self.locator = locator
    }
}

public struct EmbeddedKnowledgeChunk: Equatable, Sendable {
    public let chunk: KnowledgeChunk
    public let embedding: [Float]

    public init(chunk: KnowledgeChunk, embedding: [Float]) {
        self.chunk = chunk
        self.embedding = embedding
    }
}

public struct KnowledgeSearchHit: Equatable, Sendable {
    public let chunk: KnowledgeChunk
    public let documentTitle: String
    public let score: Float

    public init(chunk: KnowledgeChunk, documentTitle: String, score: Float) {
        self.chunk = chunk
        self.documentTitle = documentTitle
        self.score = score
    }
}

public struct KnowledgeCitation: Codable, Equatable, Sendable {
    public let index: Int
    public let documentID: UUID
    public let documentTitle: String
    public let chunkID: UUID
    public let excerpt: String
    public let locator: SourceLocator?

    public init(
        index: Int,
        documentID: UUID,
        documentTitle: String,
        chunkID: UUID,
        excerpt: String,
        locator: SourceLocator? = nil
    ) {
        self.index = index
        self.documentID = documentID
        self.documentTitle = documentTitle
        self.chunkID = chunkID
        self.excerpt = excerpt
        self.locator = locator
    }
}

public struct KnowledgeAnswer: Equatable, Sendable {
    public let text: String
    public let citations: [KnowledgeCitation]

    public init(text: String, citations: [KnowledgeCitation]) {
        self.text = text
        self.citations = citations
    }
}

public struct EmbeddingDescriptor: Codable, Equatable, Sendable {
    public let id: String
    public let version: String
    public let dimensions: Int
    public let tokenizerVersion: String

    public init(id: String, version: String, dimensions: Int, tokenizerVersion: String) {
        self.id = id
        self.version = version
        self.dimensions = dimensions
        self.tokenizerVersion = tokenizerVersion
    }

    public var indexVersion: String {
        "\(id)@\(version):\(dimensions):\(tokenizerVersion)"
    }
}

/// Token budget for on-device RAG with a small local generator (e.g. Gemma 4 E2B).
/// Designed for ~2048 total model tokens: evidence must leave room for rules, question, and answer.
public struct OnDeviceRAGBudget: Sendable, Equatable {
    public var engineMaxTokens: Int
    public var systemPromptTokens: Int
    public var questionTokens: Int
    public var answerReserveTokens: Int
    public var evidenceTokenBudget: Int
    public var retrievalLimit: Int
    public var maxCitations: Int
    public var maxChunksPerDocument: Int
    public var minimumScore: Float
    public var preferredAnswerCharacters: Int
    public var evidenceExcerptCharacters: Int

    public init(
        engineMaxTokens: Int = 2_048,
        systemPromptTokens: Int = 220,
        questionTokens: Int = 120,
        answerReserveTokens: Int = 280,
        evidenceTokenBudget: Int = 900,
        retrievalLimit: Int = 5,
        maxCitations: Int = 3,
        maxChunksPerDocument: Int = 2,
        minimumScore: Float = 0.15,
        preferredAnswerCharacters: Int = 220,
        evidenceExcerptCharacters: Int = 420
    ) {
        self.engineMaxTokens = engineMaxTokens
        self.systemPromptTokens = systemPromptTokens
        self.questionTokens = questionTokens
        self.answerReserveTokens = answerReserveTokens
        self.evidenceTokenBudget = min(
            evidenceTokenBudget,
            max(128, engineMaxTokens - systemPromptTokens - questionTokens - answerReserveTokens)
        )
        self.retrievalLimit = max(1, retrievalLimit)
        self.maxCitations = max(1, maxCitations)
        self.maxChunksPerDocument = max(1, maxChunksPerDocument)
        self.minimumScore = minimumScore
        self.preferredAnswerCharacters = max(80, preferredAnswerCharacters)
        self.evidenceExcerptCharacters = max(120, evidenceExcerptCharacters)
    }

    /// Default for Gemma 4 E2B + LiteRT-LM on iPhone (8 GB class).
    public static let gemmaE2B = OnDeviceRAGBudget()
}

public protocol KnowledgeTextExtractor: Sendable {
    func extract(from url: URL) async throws -> KnowledgeExtraction
}

public protocol KnowledgeChunker: Sendable {
    func chunks(documentID: UUID, extraction: KnowledgeExtraction) -> [KnowledgeChunk]
}

public extension KnowledgeChunker {
    func chunks(documentID: UUID, text: String) -> [KnowledgeChunk] {
        chunks(
            documentID: documentID,
            extraction: KnowledgeExtraction(kind: .plainText, indexedText: text)
        )
    }
}

public protocol TextEmbedding: Sendable {
    var name: String { get }
    var dimensions: Int { get }
    var descriptor: EmbeddingDescriptor { get }
    func embed(_ texts: [String]) async throws -> [[Float]]
    func embedDocuments(_ texts: [String]) async throws -> [[Float]]
    func embedQuery(_ text: String) async throws -> [Float]
}

public extension TextEmbedding {
    var descriptor: EmbeddingDescriptor {
        EmbeddingDescriptor(
            id: name,
            version: "1",
            dimensions: dimensions,
            tokenizerVersion: "unspecified"
        )
    }

    func embedDocuments(_ texts: [String]) async throws -> [[Float]] {
        try await embed(texts)
    }

    func embedQuery(_ text: String) async throws -> [Float] {
        guard let vector = try await embed([text]).first else {
            throw KnowledgeBaseError.storage("query embedding missing")
        }
        return vector
    }
}

public enum KnowledgeSearchScope: Codable, Equatable, Sendable {
    case all
    case documents(Set<UUID>)

    public var documentIDs: Set<UUID>? {
        switch self {
        case .all: nil
        case let .documents(ids): ids
        }
    }
}

public protocol KnowledgeRepository: Sendable {
    func document(id: UUID) async throws -> KnowledgeDocument?
    func document(contentHash: String) async throws -> KnowledgeDocument?
    func documents() async throws -> [KnowledgeDocument]
    func save(document: KnowledgeDocument) async throws
    func replaceChunks(documentID: UUID, chunks: [EmbeddedKnowledgeChunk]) async throws
    /// Same-hash resume returns already-committed unpublished count; hash change discards staging.
    func prepareIndexJob(
        documentID: UUID,
        contentHash: String,
        indexedText: String,
        pageSpans: [KnowledgePageSpan]
    ) async throws -> Int
    func unpublishedChunkCount(documentID: UUID) async throws -> Int
    func discardUnpublishedChunks(documentID: UUID) async throws
    /// Persists one embedded batch. Checkpoint advances only after this transaction commits.
    func appendUnpublishedChunks(documentID: UUID, chunks: [EmbeddedKnowledgeChunk]) async throws
    /// Atomically swaps staging into live chunks+FTS and writes the ready document row.
    func publishUnpublishedChunks(document: KnowledgeDocument) async throws
    func deleteDocument(id: UUID) async throws
    func search(
        query: String,
        embedding: [Float],
        limit: Int,
        scope: KnowledgeSearchScope
    ) async throws -> [KnowledgeSearchHit]
    func chunkCount() async throws -> Int
    func chunkCount(documentID: UUID) async throws -> Int
}

public protocol KnowledgeAnswerGenerator: Sendable {
    func answer(question: String, context: String) async throws -> String
    func answerStream(
        question: String,
        context: String
    ) async throws -> AsyncThrowingStream<String, any Error>
    func finalize(_ rawAnswer: String) -> String
}

public extension KnowledgeAnswerGenerator {
    func answerStream(
        question: String,
        context: String
    ) async throws -> AsyncThrowingStream<String, any Error> {
        let answer = try await answer(question: question, context: context)
        return AsyncThrowingStream { continuation in
            continuation.yield(answer)
            continuation.finish()
        }
    }

    func finalize(_ rawAnswer: String) -> String {
        rawAnswer
    }
}

public enum KnowledgeAnswerStreamEvent: Equatable, Sendable {
    case phase(String)
    case delta(String)
    case finished(KnowledgeAnswer)
}

public enum KnowledgeBaseLimits {
    public static let maxChunks = 10_000
    /// Embed/write granularity. Smaller values checkpoint more often; keep ≥1.
    public static let indexBatchSize = 16
}

/// Versions stored in `knowledge_metadata`. Bump when changing the contract; never silently mix with old indexes.
public enum KnowledgeIndexContract {
    /// Exact cosine 0.7 + normalized FTS 0.3. Change weights/formula → new version → rebuild.
    public static let retrievalVersion = "hybrid-cosine0.7-fts0.3-v1"

    /// Default structured chunker: 520 chars / 64 overlap. Instance params are appended by the chunker.
    public static let defaultChunkerVersion = "structured-520-64-v1"

    /// Content de-duplication hash. Pre-SHA-256 libraries are treated as `fnv-64-v0`.
    public static let contentHashVersion = "sha256-v1"
    public static let legacyContentHashVersion = "fnv-64-v0"

    public static func chunkerVersion(targetCharacters: Int, overlapCharacters: Int) -> String {
        "structured-\(targetCharacters)-\(overlapCharacters)-v1"
    }
}

public enum KnowledgeBaseError: Error, Equatable, Sendable {
    case unsupportedFileType(String)
    case emptyDocument
    case duplicateDocument(existingTitle: String)
    case invalidEmbeddingDimensions(expected: Int, actual: Int)
    case chunkCapacityExceeded(current: Int, incoming: Int, limit: Int)
    case storage(String)
}

extension KnowledgeBaseError: LocalizedError {
    public var errorDescription: String? {
        switch self {
        case let .unsupportedFileType(ext):
            return "暂不支持 .\(ext) 格式。"
        case .emptyDocument:
            return "文档中没有可索引的文字。"
        case let .duplicateDocument(existingTitle):
            return "该资料已导入：\(existingTitle)"
        case let .invalidEmbeddingDimensions(expected, actual):
            return "向量维度不匹配（期望 \(expected)，实际 \(actual)）。"
        case let .chunkCapacityExceeded(current, incoming, limit):
            return "知识库现有 \(current) 个片段，本次还需 \(incoming) 个，超过上限 \(limit)。请删除部分资料后再导入。"
        case let .storage(message):
            return message
        }
    }
}
