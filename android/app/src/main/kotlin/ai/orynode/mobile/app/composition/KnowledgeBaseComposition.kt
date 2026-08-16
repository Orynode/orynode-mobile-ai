package ai.orynode.mobile.app.composition

import ai.orynode.mobile.BuildConfig
import ai.orynode.mobile.app.serving.KnowledgeBaseServing
import ai.orynode.mobile.app.serving.LocalKnowledgeBaseService
import ai.orynode.mobile.app.serving.UnavailableKnowledgeBaseService
import ai.orynode.mobile.application.AskKnowledgeBase
import ai.orynode.mobile.application.ImportKnowledgeDocument
import ai.orynode.mobile.application.LocalModelKnowledgeAnswerGenerator
import ai.orynode.mobile.application.StructuredKnowledgeChunker
import ai.orynode.mobile.domain.KnowledgeIndexContract
import ai.orynode.mobile.domain.ModelDescriptor
import ai.orynode.mobile.domain.OnDeviceRagBudget
import ai.orynode.mobile.infrastructure.embedding.OnDeviceEmbeddingFactory
import ai.orynode.mobile.infrastructure.extraction.LocalKnowledgeTextExtractor
import ai.orynode.mobile.infrastructure.model.FileModelStore
import ai.orynode.mobile.infrastructure.model.HfMirrorModelDownloader
import ai.orynode.mobile.infrastructure.model.LiteRtLmModelEngine
import ai.orynode.mobile.infrastructure.ocr.MlKitTextRecognizer
import ai.orynode.mobile.infrastructure.persistence.SqliteKnowledgeRepository
import android.content.Context
import java.nio.file.Files

/**
 * App-level composition root. Features talk to [KnowledgeBaseServing] only.
 * This is the only package allowed to import Infrastructure.
 */
object KnowledgeBaseComposition {
    fun makeService(context: Context): KnowledgeBaseServing {
        return try {
            makeLive(context)
        } catch (error: Exception) {
            UnavailableKnowledgeBaseService(error)
        }
    }

    private fun makeLive(context: Context): LocalKnowledgeBaseService {
        val embedding = OnDeviceEmbeddingFactory.make(
            assets = context.assets,
            allowDebugFallback = BuildConfig.DEBUG,
        )
        val chunker = StructuredKnowledgeChunker()
        val root = context.filesDir.toPath().resolve("KnowledgeBase")
        val documentsRoot = root.resolve("Documents")
        val modelsRoot = root.resolve("Models")
        val litertCache = context.cacheDir.toPath().resolve("LiteRTLM")
        Files.createDirectories(documentsRoot)
        Files.createDirectories(modelsRoot)
        Files.createDirectories(litertCache)
        val repository = SqliteKnowledgeRepository.open(
            path = root.resolve("knowledge.sqlite"),
            embeddingDimensions = embedding.dimensions,
            embeddingIndexVersion = embedding.descriptor.indexVersion,
            retrievalVersion = KnowledgeIndexContract.RETRIEVAL_VERSION,
            chunkerVersion = chunker.contractVersion,
            contentHashVersion = KnowledgeIndexContract.CONTENT_HASH_VERSION,
        )
        val budget = OnDeviceRagBudget.GemmaE2B
        val engine = LiteRtLmModelEngine(
            cacheDir = litertCache,
            maxNumTokens = budget.engineMaxTokens,
            preferGpu = true,
        )
        val modelStore = FileModelStore(modelsRoot)
        val modelDownloader = HfMirrorModelDownloader(modelsRoot = modelsRoot)
        val descriptor = ModelDescriptor.Gemma4E2B
        return LocalKnowledgeBaseService(
            contentResolver = context.contentResolver,
            documentsRoot = documentsRoot,
            repository = repository,
            importer = ImportKnowledgeDocument(
                extractor = LocalKnowledgeTextExtractor(
                    textRecognizer = MlKitTextRecognizer(),
                    context = context.applicationContext,
                ),
                chunker = chunker,
                embedding = embedding,
                repository = repository,
            ),
            asker = AskKnowledgeBase(
                repository = repository,
                embedding = embedding,
                generator = LocalModelKnowledgeAnswerGenerator(engine, budget),
                budget = budget,
            ),
            engine = engine,
            modelStore = modelStore,
            modelDownloader = modelDownloader,
            modelDescriptor = descriptor,
            embeddingLabel = embedding.name,
        )
    }
}
