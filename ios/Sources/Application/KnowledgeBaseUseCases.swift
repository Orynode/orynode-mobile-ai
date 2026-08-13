import Foundation
import CryptoKit
import OrynodeDomain

public struct StructuredKnowledgeChunker: KnowledgeChunker {
    public let targetCharacters: Int
    public let overlapCharacters: Int

    /// Smaller chunks improve retrieval precision on-device and fit the E2B evidence budget.
    public init(targetCharacters: Int = 520, overlapCharacters: Int = 64) {
        self.targetCharacters = max(200, targetCharacters)
        self.overlapCharacters = max(0, min(overlapCharacters, targetCharacters / 3))
    }

    /// Written to `knowledge_metadata.chunker_version` at repository open.
    public var contractVersion: String {
        KnowledgeIndexContract.chunkerVersion(
            targetCharacters: targetCharacters,
            overlapCharacters: overlapCharacters
        )
    }

    public func chunks(documentID: UUID, extraction: KnowledgeExtraction) -> [KnowledgeChunk] {
        let normalized = extraction.indexedText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !normalized.isEmpty else { return [] }

        // Re-normalize against the same canonical string used for locators.
        let text = extraction.indexedText
            .replacingOccurrences(of: "\r\n", with: "\n")
            .replacingOccurrences(of: "\r", with: "\n")

        let sections = sections(in: text)
        var output: [KnowledgeChunk] = []
        var ordinal = 0
        for section in sections {
            for piece in split(section.body, baseOffset: section.bodyStart) {
                let range = piece.startUTF16..<(piece.startUTF16 + piece.text.utf16.count)
                let locator = makeLocator(
                    kind: extraction.kind,
                    text: text,
                    range: range,
                    heading: section.heading,
                    pageSpans: extraction.pageSpans
                )
                output.append(KnowledgeChunk(
                    documentID: documentID,
                    ordinal: ordinal,
                    heading: section.heading,
                    text: piece.text,
                    tokenEstimate: Self.estimateTokens(piece.text),
                    locator: locator
                ))
                ordinal += 1
            }
        }
        return output
    }

    public static func estimateTokens(_ text: String) -> Int {
        max(1, Int(ceil(Double(text.unicodeScalars.count) / 3.2)))
    }

    public static func lineNumber(utf16Offset: Int, in text: String) -> Int {
        UTF16TextIndex.lineNumber(utf16Offset: utf16Offset, in: text)
    }

    private func sections(in text: String) -> [(heading: String?, body: String, bodyStart: Int)] {
        var result: [(String?, String, Int)] = []
        var heading: String?
        var body: [String] = []
        var bodyStart = 0
        var cursor = 0
        var collectingStart: Int?

        func flush() {
            let raw = body.joined(separator: "\n")
            let value = raw.trimmingCharacters(in: .whitespacesAndNewlines)
            if !value.isEmpty {
                let hint = collectingStart ?? bodyStart
                let start = Self.utf16Offset(of: value, in: text, preferredStart: hint) ?? hint
                result.append((heading, value, start))
            }
            body.removeAll(keepingCapacity: true)
            collectingStart = nil
        }

        let lines = text.split(separator: "\n", omittingEmptySubsequences: false)
        for (index, lineSub) in lines.enumerated() {
            let line = String(lineSub)
            let lineStart = cursor
            let trimmed = line.trimmingCharacters(in: .whitespaces)
            if trimmed.hasPrefix("#") {
                flush()
                heading = trimmed.drop(while: { $0 == "#" || $0 == " " }).description
                bodyStart = lineStart + line.utf16.count + (index < lines.count - 1 ? 1 : 0)
            } else {
                if collectingStart == nil { collectingStart = lineStart }
                body.append(line)
            }
            cursor += line.utf16.count
            if index < lines.count - 1 { cursor += 1 }
        }
        flush()
        return result.isEmpty ? [(nil, text.trimmingCharacters(in: .whitespacesAndNewlines), 0)] : result
    }

    private struct Piece {
        var text: String
        var startUTF16: Int
    }

    private func split(_ sectionBody: String, baseOffset: Int) -> [Piece] {
        let trimmed = sectionBody.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return [] }

        let trimRelative = Self.utf16Offset(of: trimmed, in: sectionBody, preferredStart: 0) ?? 0
        let trimmedBase = baseOffset + trimRelative

        guard trimmed.utf16.count > targetCharacters else {
            return [Piece(text: trimmed, startUTF16: trimmedBase)]
        }

        var pieces: [Piece] = []
        var current = ""
        var currentStartRel: Int?
        var paraUTF16Cursor = 0
        let paragraphs = trimmed.components(separatedBy: "\n\n")

        func emitCurrent() {
            let pieceText = current.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !pieceText.isEmpty else {
                current = ""
                currentStartRel = nil
                return
            }
            let hint = currentStartRel ?? 0
            if let rel = Self.utf16Offset(of: pieceText, in: trimmed, preferredStart: hint) {
                pieces.append(Piece(text: pieceText, startUTF16: trimmedBase + rel))
            }
            current = ""
            currentStartRel = nil
        }

        for (index, paragraph) in paragraphs.enumerated() {
            let paraStart = paraUTF16Cursor
            let paraUTF16Len = paragraph.utf16.count

            if current.isEmpty { currentStartRel = paraStart }

            let combinedLen = current.utf16.count + (current.isEmpty ? 0 : 2) + paraUTF16Len
            if combinedLen <= targetCharacters {
                current += (current.isEmpty ? "" : "\n\n") + paragraph
            } else {
                emitCurrent()
                if paraUTF16Len <= targetCharacters {
                    current = paragraph
                    currentStartRel = paraStart
                } else {
                    var local = 0
                    while local < paraUTF16Len {
                        let end = min(local + targetCharacters, paraUTF16Len)
                        guard let startIdx = Self.utf16Index(at: paraStart + local, in: trimmed),
                              let endIdx = Self.utf16Index(at: paraStart + end, in: trimmed) else {
                            break
                        }
                        let slice = String(trimmed[startIdx..<endIdx])
                            .trimmingCharacters(in: .whitespacesAndNewlines)
                        if !slice.isEmpty,
                           let rel = Self.utf16Offset(of: slice, in: trimmed, preferredStart: paraStart + local) {
                            pieces.append(Piece(text: slice, startUTF16: trimmedBase + rel))
                        }
                        if end >= paraUTF16Len { break }
                        local = max(end - overlapCharacters, local + 1)
                    }
                }
            }

            paraUTF16Cursor += paraUTF16Len
            if index < paragraphs.count - 1 { paraUTF16Cursor += 2 }
        }
        emitCurrent()
        return pieces
    }

    static func utf16Offset(of needle: String, in haystack: String, preferredStart: Int) -> Int? {
        let hay = Array(haystack.utf16)
        let needleUTF16 = Array(needle.utf16)
        guard !needleUTF16.isEmpty, hay.count >= needleUTF16.count else { return nil }
        let from = min(max(0, preferredStart), max(0, hay.count - needleUTF16.count))
        for start in from...(hay.count - needleUTF16.count) {
            var matched = true
            for index in 0..<needleUTF16.count where hay[start + index] != needleUTF16[index] {
                matched = false
                break
            }
            if matched { return start }
        }
        return nil
    }

    private static func utf16Index(at offset: Int, in string: String) -> String.Index? {
        let utf16 = string.utf16
        guard offset >= 0, offset <= utf16.count else { return nil }
        return String.Index(utf16.index(utf16.startIndex, offsetBy: offset), within: string)
    }

    private func makeLocator(
        kind: KnowledgeExtraction.Kind,
        text: String,
        range: Range<Int>,
        heading: String?,
        pageSpans: [KnowledgePageSpan]
    ) -> SourceLocator {
        switch kind {
        case .pdf:
            let page = pageSpans.first { span in
                range.lowerBound < span.end && range.upperBound > span.start
            } ?? pageSpans.first
            if let page {
                let localStart = max(0, range.lowerBound - page.start)
                let localEnd = max(localStart, min(range.upperBound, page.end) - page.start)
                return .pdf(page: page.page, startOffset: localStart, endOffset: localEnd)
            }
            return .pdf(page: 1, startOffset: range.lowerBound, endOffset: range.upperBound)
        case .markdown:
            let startLine = Self.lineNumber(utf16Offset: range.lowerBound, in: text)
            let endLine = Self.lineNumber(utf16Offset: max(range.lowerBound, range.upperBound - 1), in: text)
            let path = heading.map { [$0] }
            return .markdown(headingPath: path, startLine: startLine, endLine: max(startLine, endLine))
        case .plainText:
            return .plainText(startOffset: range.lowerBound, endOffset: range.upperBound)
        }
    }
}

public struct ImportKnowledgeDocument: Sendable {
    private let extractor: any KnowledgeTextExtractor
    private let chunker: any KnowledgeChunker
    private let embedding: any TextEmbedding
    private let repository: any KnowledgeRepository
    private let maxChunks: Int
    private let indexBatchSize: Int

    public init(
        extractor: any KnowledgeTextExtractor,
        chunker: any KnowledgeChunker,
        embedding: any TextEmbedding,
        repository: any KnowledgeRepository,
        maxChunks: Int = KnowledgeBaseLimits.maxChunks,
        indexBatchSize: Int = KnowledgeBaseLimits.indexBatchSize
    ) {
        self.extractor = extractor
        self.chunker = chunker
        self.embedding = embedding
        self.repository = repository
        self.maxChunks = max(1, maxChunks)
        self.indexBatchSize = max(1, indexBatchSize)
    }

    /// Visible in the document list before extract/embed. Not searchable until `ready`.
    public func enqueue(url: URL, documentID: UUID) async throws -> KnowledgeDocument {
        if let existing = try await repository.document(id: documentID) {
            return existing
        }
        let document = KnowledgeDocument(
            id: documentID,
            sourceURL: url,
            title: url.deletingPathExtension().lastPathComponent,
            contentHash: "",
            state: .importing
        )
        try await repository.save(document: document)
        return document
    }

    /// Same document ID resumes from the last committed embed batch. A ready document keeps serving until publish.
    public func callAsFunction(url: URL, resuming documentID: UUID? = nil) async throws -> KnowledgeDocument {
        let id = documentID ?? UUID()
        var queued = try await enqueue(url: url, documentID: id)
        // Failed → importing immediately so retry UI is not stuck on the error label for minutes.
        if queued.state == .failed {
            queued.state = .importing
            queued.errorMessage = nil
            queued.updatedAt = Date()
            try await repository.save(document: queued)
        }
        let keepServing = queued.state == .ready
        do {
            let extraction = try await extractor.extract(from: url)
            try Task.checkCancellation()
            let text = extraction.indexedText.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !text.isEmpty else { throw KnowledgeBaseError.emptyDocument }
            let hash = Self.contentHash(extraction.indexedText)
            if let existing = try await repository.document(contentHash: hash), existing.id != id {
                throw KnowledgeBaseError.duplicateDocument(existingTitle: existing.title)
            }
            if !keepServing {
                let document = KnowledgeDocument(
                    id: id,
                    sourceURL: url,
                    title: queued.title,
                    contentHash: hash,
                    state: .importing,
                    indexedText: extraction.indexedText,
                    pageSpans: extraction.pageSpans,
                    createdAt: queued.createdAt
                )
                try await repository.save(document: document)
            }
            var committed = try await repository.prepareIndexJob(
                documentID: id,
                contentHash: hash,
                indexedText: extraction.indexedText,
                pageSpans: extraction.pageSpans
            )
            let chunks = chunker.chunks(documentID: id, extraction: extraction)
            if committed > chunks.count {
                try await repository.discardUnpublishedChunks(documentID: id)
                committed = try await repository.prepareIndexJob(
                    documentID: id,
                    contentHash: hash,
                    indexedText: extraction.indexedText,
                    pageSpans: extraction.pageSpans
                )
            }
            let existingLive = try await repository.chunkCount(documentID: id)
            let current = try await repository.chunkCount()
            let projected = current - existingLive + chunks.count
            if projected > maxChunks {
                throw KnowledgeBaseError.chunkCapacityExceeded(
                    current: current - existingLive,
                    incoming: chunks.count,
                    limit: maxChunks
                )
            }
            let remaining = Array(chunks.dropFirst(min(committed, chunks.count)))
            let batchSize = indexBatchSize
            var offset = 0
            while offset < remaining.count {
                try Task.checkCancellation()
                let end = min(offset + batchSize, remaining.count)
                let batch = Array(remaining[offset..<end])
                let vectors = try await embedding.embedDocuments(batch.map(\.text))
                guard vectors.count == batch.count else {
                    throw KnowledgeBaseError.storage("embedding count mismatch")
                }
                let embedded = try zip(batch, vectors).map { chunk, vector in
                    guard vector.count == embedding.dimensions else {
                        throw KnowledgeBaseError.invalidEmbeddingDimensions(
                            expected: embedding.dimensions,
                            actual: vector.count
                        )
                    }
                    return EmbeddedKnowledgeChunk(chunk: chunk, embedding: vector)
                }
                try await repository.appendUnpublishedChunks(documentID: id, chunks: embedded)
                offset = end
                let committedNow = committed + offset
                if IndexFaultInjection.shouldFail(committed: committedNow, total: chunks.count) {
                    throw KnowledgeBaseError.storage(
                        "DEBUG fault injection at committed=\(committedNow)/\(chunks.count)"
                    )
                }
            }
            try Task.checkCancellation()
            var published = KnowledgeDocument(
                id: id,
                sourceURL: url,
                title: queued.title,
                contentHash: hash,
                state: .ready,
                importedChunkCount: chunks.count,
                indexedText: extraction.indexedText,
                pageSpans: extraction.pageSpans,
                createdAt: queued.createdAt
            )
            published.updatedAt = Date()
            try await repository.publishUnpublishedChunks(document: published)
            return published
        } catch {
            if case KnowledgeBaseError.duplicateDocument = error {
                if queued.contentHash.isEmpty {
                    try? await repository.deleteDocument(id: id)
                }
                throw error
            }
            let latest = (try? await repository.document(id: id)) ?? queued
            if latest.state == .ready {
                throw error
            }
            var failed = latest
            failed.state = .failed
            failed.errorMessage = (error as? LocalizedError)?.errorDescription
                ?? error.localizedDescription
            failed.updatedAt = Date()
            try? await repository.save(document: failed)
            throw error
        }
    }

    private static func contentHash(_ text: String) -> String {
        SHA256.hash(data: Data(text.utf8)).map { String(format: "%02x", $0) }.joined()
    }
}

public struct AskKnowledgeBase: Sendable {
    private let repository: any KnowledgeRepository
    private let embedding: any TextEmbedding
    private let generator: any KnowledgeAnswerGenerator
    private let budget: OnDeviceRAGBudget

    public init(
        repository: any KnowledgeRepository,
        embedding: any TextEmbedding,
        generator: any KnowledgeAnswerGenerator,
        budget: OnDeviceRAGBudget = .gemmaE2B,
        contextTokenBudget: Int? = nil,
        retrievalLimit: Int? = nil,
        minimumScore: Float? = nil
    ) {
        self.repository = repository
        self.embedding = embedding
        self.generator = generator
        var resolved = budget
        if let contextTokenBudget {
            resolved.evidenceTokenBudget = max(128, contextTokenBudget)
        }
        if let retrievalLimit {
            resolved.retrievalLimit = max(1, retrievalLimit)
        }
        if let minimumScore {
            resolved.minimumScore = minimumScore
        }
        self.budget = resolved
    }

    public func callAsFunction(
        question: String,
        scope: KnowledgeSearchScope = .all
    ) async throws -> KnowledgeAnswer {
        for try await event in stream(question: question, scope: scope) {
            if case let .finished(answer) = event {
                return answer
            }
        }
        throw KnowledgeBaseError.storage("问答流未返回完成事件")
    }

    public func stream(
        question: String,
        scope: KnowledgeSearchScope = .all
    ) -> AsyncThrowingStream<KnowledgeAnswerStreamEvent, any Error> {
        AsyncThrowingStream { continuation in
            let task = Task {
                do {
                    continuation.yield(.phase("正在检索本机资料…"))
                    let vector = try await embedding.embedQuery(question)
                    let retrieved = try await repository.search(
                        query: question,
                        embedding: vector,
                        limit: budget.retrievalLimit,
                        scope: scope
                    )
                    // Lexical focus only: put query-overlapping chunks first and
                    // window excerpts onto the overlap. Does not rewrite answers.
                    let hits = EvidencePackFocus.prioritize(retrieved, query: question)
                    var used = 0
                    var perDocument: [UUID: Int] = [:]
                    var blocks: [String] = []
                    var citations: [KnowledgeCitation] = []
                    #if DEBUG
                    var hitSummaries: [String] = []
                    #endif

                    for hit in hits where hit.score >= budget.minimumScore {
                        if citations.count >= budget.maxCitations { break }
                        let documentCount = perDocument[hit.chunk.documentID, default: 0]
                        if documentCount >= budget.maxChunksPerDocument { continue }

                        let excerpt = EvidencePackFocus.excerpt(
                            from: hit.chunk.text,
                            query: question,
                            maxCharacters: budget.evidenceExcerptCharacters
                        )
                        let estimate = StructuredKnowledgeChunker.estimateTokens(excerpt)
                        guard used + estimate <= budget.evidenceTokenBudget || blocks.isEmpty else {
                            continue
                        }

                        used += estimate
                        perDocument[hit.chunk.documentID, default: 0] += 1
                        let index = citations.count + 1
                        let heading = hit.chunk.heading.map { " / \($0)" } ?? ""
                        let place = hit.chunk.locator.map { " · \($0.shortLabel)" } ?? ""
                        blocks.append("[\(index)] \(hit.documentTitle)\(heading)\(place)\n\(excerpt)")
                        citations.append(KnowledgeCitation(
                            index: index,
                            documentID: hit.chunk.documentID,
                            documentTitle: hit.documentTitle,
                            chunkID: hit.chunk.id,
                            // Keep the same excerpt the model saw in the evidence pack.
                            excerpt: excerpt,
                            locator: hit.chunk.locator
                        ))
                        #if DEBUG
                        hitSummaries.append(
                            String(
                                format: "  [%d] score=%.3f tokens=%d title=%@%@%@",
                                index,
                                hit.score,
                                estimate,
                                hit.documentTitle,
                                heading,
                                place
                            )
                        )
                        #endif
                    }

                    guard !blocks.isEmpty else {
                        continuation.yield(.finished(KnowledgeAnswer(
                            text: "现有资料不足以回答这个问题。请补充资料，或换一个更具体的问题。",
                            citations: []
                        )))
                        continuation.finish()
                        return
                    }

                    continuation.yield(.phase("正在生成本机回答…"))
                    let context = blocks.joined(separator: "\n\n")
                    let allowed = Set(citations.map(\.index))
                    let answerStream = try await generator.answerStream(
                        question: question,
                        context: context
                    )
                    var raw = ""
                    for try await delta in answerStream {
                        try Task.checkCancellation()
                        raw += delta
                        continuation.yield(.delta(delta))
                    }
                    let finalized = generator.finalize(raw)
                    // Model decides whether/where to cite; system only drops illegal markers.
                    let canonical = CitationCanonicalizer().canonicalize(
                        finalized,
                        allowedIndices: allowed
                    )
                    #if DEBUG
                    AskKnowledgeBaseDebugTrace.log(
                        question: question,
                        pack: context,
                        citationIndices: citations.map(\.index),
                        hitSummaries: hitSummaries,
                        raw: raw,
                        final: canonical.text,
                        referencedIndices: canonical.referencedIndices
                    )
                    #endif
                    // Retrieval evidence is internal input. Public citations are only the
                    // closed-set sources explicitly referenced by the model's final body.
                    let referenced = Set(canonical.referencedIndices)
                    let answerCitations = citations.filter { referenced.contains($0.index) }
                    continuation.yield(.finished(KnowledgeAnswer(
                        text: canonical.text,
                        citations: answerCitations
                    )))
                    continuation.finish()
                } catch {
                    continuation.finish(throwing: error)
                }
            }
            continuation.onTermination = { @Sendable _ in
                task.cancel()
            }
        }
    }
}

