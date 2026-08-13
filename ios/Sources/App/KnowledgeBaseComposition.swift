import Foundation
import OrynodeApplication
import OrynodeDomain
import OrynodeInfrastructure

/// App-level composition root. Features talk to `KnowledgeBaseServing` only.
@MainActor
enum KnowledgeBaseComposition {
    static func makeService(engine: any LocalModelEngine) -> any KnowledgeBaseServing {
        do {
            return try makeLive(engine: engine)
        } catch {
            return UnavailableKnowledgeBaseService(error: error)
        }
    }

    private static func makeLive(engine: any LocalModelEngine) throws -> LocalKnowledgeBaseService {
        IndexFaultInjection.installFromProcessArguments()
        let fileManager = FileManager.default
        let embedding = try OnDeviceEmbeddingFactory.make()
        let chunker = StructuredKnowledgeChunker()
        let root = fileManager.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appending(path: "KnowledgeBase", directoryHint: .isDirectory)
        let documentsRoot = root.appending(path: "Documents", directoryHint: .isDirectory)
        try fileManager.createDirectory(at: documentsRoot, withIntermediateDirectories: true)
        try LocalKnowledgeBaseService.protect(root, fileManager: fileManager)
        try LocalKnowledgeBaseService.protect(documentsRoot, fileManager: fileManager)
        let repository = try SQLiteKnowledgeRepository(
            url: root.appending(path: "knowledge.sqlite"),
            embeddingDimensions: embedding.dimensions,
            embeddingIndexVersion: embedding.descriptor.indexVersion,
            retrievalVersion: KnowledgeIndexContract.retrievalVersion,
            chunkerVersion: chunker.contractVersion,
            contentHashVersion: KnowledgeIndexContract.contentHashVersion
        )
        return LocalKnowledgeBaseService(
            repository: repository,
            importer: ImportKnowledgeDocument(
                extractor: LocalKnowledgeTextExtractor(
                    textRecognizer: VisionTextRecognizer()
                ),
                chunker: chunker,
                embedding: embedding,
                repository: repository
            ),
            asker: AskKnowledgeBase(
                repository: repository,
                embedding: embedding,
                generator: LocalModelKnowledgeAnswerGenerator(
                    engine: engine,
                    budget: .gemmaE2B
                ),
                budget: .gemmaE2B
            ),
            documentsRoot: documentsRoot,
            fileManager: fileManager
        )
    }
}
