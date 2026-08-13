import Foundation
import PDFKit
import OrynodeApplication
import OrynodeDomain

enum KnowledgeIndexStatus: Equatable, Codable, Sendable {
    case pending
    case indexing
    case ready
    case failed(String)
}

struct KnowledgeDocumentItem: Identifiable, Equatable, Codable, Sendable {
    let id: UUID
    var title: String
    var fileName: String
    var fileURL: URL
    var importedAt: Date
    var byteCount: Int64
    var importedChunkCount: Int
    var status: KnowledgeIndexStatus
}

struct CitedSource: Identifiable, Equatable, Codable, Sendable {
    let id: UUID
    let index: Int
    let documentID: UUID
    let documentTitle: String
    let excerpt: String
    let locator: SourceLocator?
    /// User-facing locator caption. For PDFs this prefers the printed page label
    /// (`PDFPage.label`) when it differs from the 1-based document index used for jumps.
    let locatorLabel: String?
    let fileURL: URL?

    enum CodingKeys: String, CodingKey {
        case id, index, documentID, documentTitle, excerpt, locator, locatorLabel, fileURL
    }

    init(
        id: UUID,
        index: Int,
        documentID: UUID,
        documentTitle: String,
        excerpt: String,
        locator: SourceLocator? = nil,
        locatorLabel: String? = nil,
        fileURL: URL? = nil
    ) {
        self.id = id
        self.index = index
        self.documentID = documentID
        self.documentTitle = documentTitle
        self.excerpt = excerpt
        self.locator = locator
        self.locatorLabel = locatorLabel
        self.fileURL = fileURL
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        id = try container.decode(UUID.self, forKey: .id)
        index = try container.decodeIfPresent(Int.self, forKey: .index) ?? 0
        documentID = try container.decode(UUID.self, forKey: .documentID)
        documentTitle = try container.decode(String.self, forKey: .documentTitle)
        excerpt = try container.decode(String.self, forKey: .excerpt)
        locator = try container.decodeIfPresent(SourceLocator.self, forKey: .locator)
        locatorLabel = try container.decodeIfPresent(String.self, forKey: .locatorLabel)
        fileURL = try container.decodeIfPresent(URL.self, forKey: .fileURL)
    }

    /// Prefer printed PDF page label; fall back to locator shortLabel.
    var displayLocatorLabel: String? {
        if let locatorLabel, !locatorLabel.isEmpty { return locatorLabel }
        return locator?.shortLabel
    }
}

struct KnowledgeAnswer: Equatable, Sendable {
    let text: String
    let citations: [CitedSource]
}

enum KnowledgeAnswerUpdate: Equatable, Sendable {
    case phase(String)
    case delta(String)
    case finished(KnowledgeAnswer)
}

/// App/Features 层的稳定接缝。核心 KnowledgeStore / use case 就绪后只需提供此协议的适配器。
@MainActor
protocol KnowledgeBaseServing: AnyObject {
    func loadDocuments() async throws -> [KnowledgeDocumentItem]
    func importDocument(from url: URL) -> AsyncThrowingStream<KnowledgeDocumentItem, any Error>
    func retryIndexing(documentID: UUID) async throws -> KnowledgeDocumentItem
    func deleteDocument(documentID: UUID) async throws
    func ask(_ question: String, scope: KnowledgeSearchScope) async throws -> KnowledgeAnswer
    func askStream(
        _ question: String,
        scope: KnowledgeSearchScope
    ) -> AsyncThrowingStream<KnowledgeAnswerUpdate, any Error>
    func previewIntent(forDocumentID id: UUID) async throws -> DocumentPreviewIntent
    func previewIntent(for citation: CitedSource) async throws -> DocumentPreviewIntent
}

enum KnowledgeBaseServiceError: LocalizedError {
    case unreadableDocument
    case emptyDocument
    case noIndexedDocuments

    var errorDescription: String? {
        switch self {
        case .unreadableDocument: "无法读取这个文档。"
        case .emptyDocument: "文档中没有可索引的文字。"
        case .noIndexedDocuments: "请先导入并完成至少一个文档的索引。"
        }
    }
}

@MainActor
final class UnavailableKnowledgeBaseService: KnowledgeBaseServing {
    private let error: Error

    init(error: Error) {
        self.error = error
    }

    func loadDocuments() async throws -> [KnowledgeDocumentItem] { throw error }
    func importDocument(from url: URL) -> AsyncThrowingStream<KnowledgeDocumentItem, any Error> {
        AsyncThrowingStream { $0.finish(throwing: error) }
    }
    func retryIndexing(documentID: UUID) async throws -> KnowledgeDocumentItem { throw error }
    func deleteDocument(documentID: UUID) async throws { throw error }
    func ask(_ question: String, scope: KnowledgeSearchScope) async throws -> KnowledgeAnswer {
        throw error
    }
    func askStream(
        _ question: String,
        scope: KnowledgeSearchScope
    ) -> AsyncThrowingStream<KnowledgeAnswerUpdate, any Error> {
        AsyncThrowingStream { continuation in
            continuation.finish(throwing: error)
        }
    }
    func previewIntent(forDocumentID id: UUID) async throws -> DocumentPreviewIntent { throw error }
    func previewIntent(for citation: CitedSource) async throws -> DocumentPreviewIntent { throw error }
}

@MainActor
final class LocalKnowledgeBaseService: KnowledgeBaseServing {
    private let fileManager: FileManager
    private let repository: any KnowledgeRepository
    private let importer: ImportKnowledgeDocument
    private let asker: AskKnowledgeBase
    private let documentsRoot: URL
    /// In-flight import/retry IDs. Force-quit leaves `importing` rows with no live Task.
    private var activeImportIDs = Set<UUID>()

    init(
        repository: any KnowledgeRepository,
        importer: ImportKnowledgeDocument,
        asker: AskKnowledgeBase,
        documentsRoot: URL,
        fileManager: FileManager = .default
    ) {
        self.fileManager = fileManager
        self.repository = repository
        self.importer = importer
        self.asker = asker
        self.documentsRoot = documentsRoot
    }

    func loadDocuments() async throws -> [KnowledgeDocumentItem] {
        try await reconcileStaleImportingDocuments()
        var items: [KnowledgeDocumentItem] = []
        for stored in try await repository.documents() {
            let resolved = resolvedDocument(stored)
            if resolved.sourceURL != stored.sourceURL,
               fileManager.fileExists(atPath: resolved.sourceURL.path) {
                // Reload full row before save so a list projection cannot wipe indexed_text.
                let full = try await repository.document(id: stored.id) ?? stored
                let migrated = resolvedDocument(full)
                try await repository.save(document: migrated)
                items.append(Self.item(migrated))
            } else {
                items.append(Self.item(resolved))
            }
        }
        return items
    }

    private func reconcileStaleImportingDocuments() async throws {
        let listed = try await repository.documents()
        let staleIDs = StaleImportReconciliation.staleImportingIDs(
            in: listed,
            activeImportIDs: activeImportIDs
        )
        for id in staleIDs {
            guard let full = try await repository.document(id: id) else { continue }
            var failed = full
            failed.state = .failed
            failed.errorMessage = StaleImportReconciliation.interruptedMessage
            failed.updatedAt = Date()
            try await repository.save(document: failed)
        }
    }

    func importDocument(from url: URL) -> AsyncThrowingStream<KnowledgeDocumentItem, any Error> {
        AsyncThrowingStream { continuation in
            let task = Task { @MainActor in
                let hasAccess = url.startAccessingSecurityScopedResource()
                defer { if hasAccess { url.stopAccessingSecurityScopedResource() } }
                let id = UUID()
                activeImportIDs.insert(id)
                defer { activeImportIDs.remove(id) }
                let localURL = documentsRoot
                    .appending(path: id.uuidString, directoryHint: .isDirectory)
                    .appending(path: url.lastPathComponent)
                do {
                    try fileManager.createDirectory(
                        at: localURL.deletingLastPathComponent(),
                        withIntermediateDirectories: true
                    )
                    try fileManager.copyItem(at: url, to: localURL)
                    try Self.protect(localURL, fileManager: fileManager)
                    let queued = try await importer.enqueue(url: localURL, documentID: id)
                    continuation.yield(Self.item(queued))
                    await Task.yield()
                    let finished = try await importer(url: localURL, resuming: id)
                    continuation.yield(Self.item(finished))
                    continuation.finish()
                } catch {
                    if let failed = try await repository.document(id: id) {
                        continuation.yield(Self.item(failed))
                        continuation.finish()
                        return
                    }
                    try? fileManager.removeItem(at: localURL.deletingLastPathComponent())
                    continuation.finish(throwing: error)
                }
            }
            continuation.onTermination = { @Sendable _ in
                task.cancel()
            }
        }
    }

    func retryIndexing(documentID: UUID) async throws -> KnowledgeDocumentItem {
        guard let stored = try await repository.document(id: documentID) else {
            throw KnowledgeBaseServiceError.unreadableDocument
        }
        activeImportIDs.insert(documentID)
        defer { activeImportIDs.remove(documentID) }
        let document = resolvedDocument(stored)
        // Persist importing before the long extract/embed so list refresh cannot resurrect "failed".
        if document.state == .failed {
            var restarting = document
            restarting.state = .importing
            restarting.errorMessage = nil
            restarting.updatedAt = Date()
            try await repository.save(document: restarting)
        }
        return Self.item(try await importer(url: document.sourceURL, resuming: documentID))
    }

    func deleteDocument(documentID: UUID) async throws {
        try await repository.deleteDocument(id: documentID)
        try? fileManager.removeItem(at: documentsRoot.appending(path: documentID.uuidString))
    }

    func ask(_ question: String, scope: KnowledgeSearchScope) async throws -> KnowledgeAnswer {
        for try await update in askStream(question, scope: scope) {
            if case let .finished(answer) = update {
                return answer
            }
        }
        throw KnowledgeBaseServiceError.emptyDocument
    }

    func askStream(
        _ question: String,
        scope: KnowledgeSearchScope
    ) -> AsyncThrowingStream<KnowledgeAnswerUpdate, any Error> {
        // Keep retrieval/generation off the main actor. Pinning this Task to
        // @MainActor previously deadlocked first-token streaming with LiteRT.
        AsyncThrowingStream { continuation in
            let task = Task {
                do {
                    guard try await repository.documents().contains(where: { $0.state == .ready }) else {
                        throw KnowledgeBaseServiceError.noIndexedDocuments
                    }
                    for try await event in asker.stream(question: question, scope: scope) {
                        switch event {
                        case let .phase(message):
                            continuation.yield(.phase(message))
                        case let .delta(delta):
                            continuation.yield(.delta(delta))
                        case let .finished(answer):
                            // Show sources immediately with ingest locators — never block UI on
                            // PDF re-extraction / DEBUG page scans (multi-second stalls).
                            let immediate = try await mapCitationsQuick(answer)
                            continuation.yield(.finished(immediate))
                            // Same-actor yield does not paint until this task suspends.
                            await Task.yield()
                            let repository = self.repository
                            let documentsRoot = self.documentsRoot
                            let polished = try await Task.detached(priority: .userInitiated) {
                                try await Self.enrich(
                                    answer,
                                    question: question,
                                    repository: repository,
                                    documentsRoot: documentsRoot
                                )
                            }.value
                            if polished.citations != immediate.citations {
                                continuation.yield(.finished(polished))
                            }
                        }
                    }
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

    /// Fast path: bind file URLs + ingest locators so the source list can render now.
    private func mapCitationsQuick(
        _ answer: OrynodeDomain.KnowledgeAnswer
    ) async throws -> KnowledgeAnswer {
        let docs = Dictionary(uniqueKeysWithValues: try await repository.documents().map { ($0.id, $0) })
        let citations = answer.citations.map { citation -> CitedSource in
            let document = docs[citation.documentID].map(resolvedDocument)
            return CitedSource(
                id: citation.chunkID,
                index: citation.index,
                documentID: citation.documentID,
                documentTitle: citation.documentTitle,
                excerpt: citation.excerpt,
                locator: citation.locator,
                locatorLabel: citation.locator?.shortLabel,
                fileURL: document?.sourceURL
            )
        }
        return KnowledgeAnswer(text: answer.text, citations: citations)
    }

    nonisolated private static func enrich(
        _ answer: OrynodeDomain.KnowledgeAnswer,
        question: String,
        repository: any KnowledgeRepository,
        documentsRoot: URL
    ) async throws -> KnowledgeAnswer {
        var fullDocuments: [UUID: OrynodeDomain.KnowledgeDocument] = [:]
        var pdfDocuments: [UUID: PDFDocument] = [:]
        var enriched: [CitedSource] = []

        for citation in answer.citations {
            try Task.checkCancellation()
            let document: OrynodeDomain.KnowledgeDocument?
            if let cached = fullDocuments[citation.documentID] {
                document = cached
            } else if let stored = try await repository.document(id: citation.documentID) {
                let resolved = resolvedDocument(stored, documentsRoot: documentsRoot)
                fullDocuments[citation.documentID] = resolved
                document = resolved
            } else {
                document = nil
            }
            // Locator narrowing + PDF page lock live in Application; Features only adds printed labels.
            let enrichedLocator = CitationLocatorEnricher.enrich(
                citation: citation,
                indexedText: document?.indexedText,
                pageSpans: document?.pageSpans ?? [],
                question: question,
                answerText: answer.text
            )
            let locator = enrichedLocator.locator
            let excerpt = enrichedLocator.excerpt

            var pdfDoc: PDFDocument?
            if case .pdf = locator, let url = document?.sourceURL {
                if let cached = pdfDocuments[citation.documentID] {
                    pdfDoc = cached
                } else if let opened = PDFDocument(url: url) {
                    pdfDocuments[citation.documentID] = opened
                    pdfDoc = opened
                }
            }
            let locatorLabel = Self.pdfPrintedPageLabel(
                locator: locator,
                document: pdfDoc,
                fallbackLabel: enrichedLocator.locatorLabel
            )

            #if DEBUG
            if case let .pdf(ingestPage, _, _) = citation.locator,
               CitationEnrichDebugTrace.isEnabled || AskKnowledgeBaseDebugTrace.isEnabled {
                Self.logPDFEnrichDecision(
                    citationIndex: citation.index,
                    ingestPage: ingestPage,
                    printedLabel: locatorLabel,
                    finalLocator: locator,
                    answer: answer.text,
                    pdfDocument: pdfDoc
                )
            }
            #endif

            enriched.append(CitedSource(
                id: citation.chunkID,
                index: citation.index,
                documentID: citation.documentID,
                documentTitle: citation.documentTitle,
                excerpt: excerpt,
                locator: locator,
                locatorLabel: locatorLabel,
                fileURL: document?.sourceURL
            ))
        }

        return KnowledgeAnswer(text: answer.text, citations: enriched)
    }

    /// Prefer the PDF catalog page label (what readers see) over 1-based document index.
    nonisolated private static func pdfPrintedPageLabel(
        locator: SourceLocator?,
        document: PDFDocument?,
        fallbackLabel: String? = nil
    ) -> String? {
        guard case let .pdf(page, _, _) = locator else {
            return fallbackLabel ?? locator?.shortLabel
        }
        if let document,
           let pdfPage = document.page(at: max(0, page - 1)),
           let printed = pdfPage.label?.trimmingCharacters(in: .whitespacesAndNewlines),
           !printed.isEmpty {
            return "第 \(printed) 页"
        }
        return "第 \(page) 页"
    }

    nonisolated private static func pdfPrintedPageLabel(
        locator: SourceLocator?,
        fileURL: URL?
    ) -> String? {
        guard let fileURL else { return locator?.shortLabel }
        return pdfPrintedPageLabel(locator: locator, document: PDFDocument(url: fileURL))
    }

    #if DEBUG
    nonisolated private static func logPDFEnrichDecision(
        citationIndex: Int,
        ingestPage: Int,
        printedLabel: String?,
        finalLocator: SourceLocator?,
        answer: String,
        pdfDocument: PDFDocument?
    ) {
        let finalPage: Int
        if case let .pdf(page, _, _) = finalLocator {
            finalPage = page
        } else {
            finalPage = ingestPage
        }

        // Cheap DEBUG probe: score only the cited page (+ optional neighbors), not the whole book.
        var overlapIngest = 0
        if let pdfDocument, let page = pdfDocument.page(at: max(0, ingestPage - 1)) {
            overlapIngest = CitationEnrichDebugTrace.answerOverlapScore(
                answer: answer,
                pageText: page.string ?? ""
            )
        }

        var bestPage: Int? = ingestPage
        var bestScore = overlapIngest
        if let pdfDocument {
            for delta in [-1, 1] {
                let pageNumber = ingestPage + delta
                let index = pageNumber - 1
                guard index >= 0, index < pdfDocument.pageCount,
                      let page = pdfDocument.page(at: index) else { continue }
                let score = CitationEnrichDebugTrace.answerOverlapScore(
                    answer: answer,
                    pageText: page.string ?? ""
                )
                if score > bestScore {
                    bestScore = score
                    bestPage = pageNumber
                }
            }
        }
        let diagnosis = CitationEnrichDebugTrace.diagnosePDF(
            ingestPage: ingestPage,
            finalPage: finalPage,
            overlapIngest: overlapIngest,
            bestPage: bestPage,
            bestScore: bestScore
        )
        let printed = printedLabel ?? "∅"
        CitationEnrichDebugTrace.logPDF(
            .init(
                citationIndex: citationIndex,
                ingestPage: ingestPage,
                finalPage: finalPage,
                refinedChangedPage: finalPage != ingestPage,
                answerOverlapOnIngestPage: overlapIngest,
                bestOverlapPage: bestPage,
                bestOverlapScore: bestScore,
                diagnosis: "\(diagnosis) | printedLabel=\(printed) (index=\(ingestPage))"
            )
        )
    }
    #endif

    func previewIntent(forDocumentID id: UUID) async throws -> DocumentPreviewIntent {
        guard let stored = try await repository.document(id: id) else {
            throw KnowledgeBaseServiceError.unreadableDocument
        }
        let document = resolvedDocument(stored)
        guard fileManager.fileExists(atPath: document.sourceURL.path) else {
            throw KnowledgeBaseServiceError.unreadableDocument
        }
        return DocumentPreviewIntent(
            documentID: document.id,
            title: document.title,
            fileURL: document.sourceURL,
            indexedText: document.indexedText
        )
    }

    func previewIntent(for citation: CitedSource) async throws -> DocumentPreviewIntent {
        guard let stored = try await repository.document(id: citation.documentID) else {
            throw KnowledgeBaseServiceError.unreadableDocument
        }
        let document = resolvedDocument(stored)
        let fileURL: URL
        if let citedURL = citation.fileURL, fileManager.fileExists(atPath: citedURL.path) {
            fileURL = citedURL
        } else if fileManager.fileExists(atPath: document.sourceURL.path) {
            fileURL = document.sourceURL
        } else {
            throw KnowledgeBaseServiceError.unreadableDocument
        }
        let label = citation.displayLocatorLabel
            ?? Self.pdfPrintedPageLabel(locator: citation.locator, fileURL: fileURL)
        return DocumentPreviewIntent(
            documentID: document.id,
            title: document.title,
            fileURL: fileURL,
            indexedText: document.indexedText,
            locator: citation.locator,
            excerpt: citation.excerpt,
            locatorLabel: label
        )
    }

    /// Resolve persisted documents against the current app container.
    /// iOS may change the absolute data-container prefix between installations/builds.
    private func resolvedDocument(_ document: OrynodeDomain.KnowledgeDocument)
        -> OrynodeDomain.KnowledgeDocument {
        Self.resolvedDocument(document, documentsRoot: documentsRoot, fileManager: fileManager)
    }

    nonisolated private static func resolvedDocument(
        _ document: OrynodeDomain.KnowledgeDocument,
        documentsRoot: URL,
        fileManager: FileManager = .default
    ) -> OrynodeDomain.KnowledgeDocument {
        guard !fileManager.fileExists(atPath: document.sourceURL.path) else {
            return document
        }

        let directory = documentsRoot.appending(
            path: document.id.uuidString,
            directoryHint: .isDirectory
        )
        let preferred = directory.appending(path: document.sourceURL.lastPathComponent)
        let resolvedURL: URL
        if fileManager.fileExists(atPath: preferred.path) {
            resolvedURL = preferred
        } else if let candidate = try? fileManager.contentsOfDirectory(
            at: directory,
            includingPropertiesForKeys: [.isRegularFileKey],
            options: [.skipsHiddenFiles]
        ).first(where: {
            (try? $0.resourceValues(forKeys: [.isRegularFileKey]).isRegularFile) == true
        }) {
            resolvedURL = candidate
        } else {
            resolvedURL = preferred
        }

        return OrynodeDomain.KnowledgeDocument(
            id: document.id,
            sourceURL: resolvedURL,
            title: document.title,
            contentHash: document.contentHash,
            state: document.state,
            importedChunkCount: document.importedChunkCount,
            errorMessage: document.errorMessage,
            indexedText: document.indexedText,
            pageSpans: document.pageSpans,
            createdAt: document.createdAt,
            updatedAt: document.updatedAt
        )
    }

    private static func item(_ document: OrynodeDomain.KnowledgeDocument) -> KnowledgeDocumentItem {
        let byteCount = (try? document.sourceURL.resourceValues(forKeys: [.fileSizeKey]).fileSize)
            .map(Int64.init) ?? 0
        let status: KnowledgeIndexStatus = switch document.state {
        case .importing: .indexing
        case .ready: .ready
        case .failed: .failed(document.errorMessage ?? "索引失败")
        }
        return KnowledgeDocumentItem(
            id: document.id,
            title: document.title,
            fileName: document.sourceURL.lastPathComponent,
            fileURL: document.sourceURL,
            importedAt: document.createdAt,
            byteCount: byteCount,
            importedChunkCount: document.importedChunkCount,
            status: status
        )
    }

    static func protect(_ url: URL, fileManager: FileManager) throws {
        try fileManager.setAttributes(
            [.protectionKey: FileProtectionType.complete],
            ofItemAtPath: url.path
        )
    }
}
