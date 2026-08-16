package ai.orynode.mobile.app.serving

import ai.orynode.mobile.application.AskKnowledgeBase
import ai.orynode.mobile.application.CitationLocatorEnricher
import ai.orynode.mobile.application.DocumentDisplayName
import ai.orynode.mobile.application.ImportKnowledgeDocument
import ai.orynode.mobile.domain.KnowledgeAnswer
import ai.orynode.mobile.domain.KnowledgeAnswerStreamEvent
import ai.orynode.mobile.domain.KnowledgeBaseError
import ai.orynode.mobile.domain.KnowledgeCitation
import ai.orynode.mobile.domain.KnowledgeDocument
import ai.orynode.mobile.domain.KnowledgeRepository
import ai.orynode.mobile.domain.KnowledgeSearchScope
import ai.orynode.mobile.domain.LocalModelEngine
import ai.orynode.mobile.domain.ModelDescriptor
import ai.orynode.mobile.domain.ModelStore
import ai.orynode.mobile.domain.ResumableModelDownloader
import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.UUID

class LocalKnowledgeBaseService(
    private val contentResolver: ContentResolver,
    private val documentsRoot: Path,
    private val repository: KnowledgeRepository,
    private val importer: ImportKnowledgeDocument,
    private val asker: AskKnowledgeBase,
    private val engine: LocalModelEngine,
    private val modelStore: ModelStore,
    private val modelDownloader: ResumableModelDownloader,
    private val modelDescriptor: ModelDescriptor = ModelDescriptor.Gemma4E2B,
    private val embeddingLabel: String,
) : KnowledgeBaseServing {
    /** Serializes import / retry / ask so embed+OCR and LLM load never overlap. */
    private val knowledgeOpsMutex = Mutex()
    private val generatorMutex = Mutex()
    @Volatile private var runtimeState: GeneratorRuntimeState = GeneratorRuntimeState.NotInstalled

    override suspend fun loadDocuments(): List<KnowledgeDocument> {
        val documents = repository.documents()
        return documents.map { document ->
            val title = DocumentDisplayName.normalizeStoredTitle(
                title = document.title,
                sourcePath = document.sourcePath,
                documentId = document.id,
            )
            if (title == document.title) document
            else {
                val fixed = document.copy(title = title)
                runCatching { repository.save(fixed) }
                fixed
            }
        }
    }

    override fun importDocument(from: Uri, displayName: String?): Flow<KnowledgeDocument> = flow {
        knowledgeOpsMutex.withLock {
            // Free Gemma native memory before PdfBox / OCR — otherwise the process is LMK'd.
            runCatching { unloadGenerator() }
            Files.createDirectories(documentsRoot)
            val documentId = UUID.randomUUID()
            val fileName = displayName ?: queryDisplayName(from) ?: "document-$documentId.txt"
            val title = DocumentDisplayName.title(
                preferred = fileName,
                path = documentsRoot.resolve(fileName),
                documentId = documentId,
            )
            val target = documentsRoot.resolve("$documentId-${sanitize(fileName)}")
            copyUri(from, target)
            val queued = importer.enqueue(target, documentId, title = title)
            emit(queued)
            emit(importer(target, resuming = documentId))
        }
    }

    override suspend fun retryIndexing(documentId: UUID): KnowledgeDocument =
        knowledgeOpsMutex.withLock {
            runCatching { unloadGenerator() }
            val document = repository.document(documentId)
                ?: throw KnowledgeBaseError.Storage("文档不存在")
            val path = Paths.get(document.sourcePath)
            if (!Files.isRegularFile(path)) {
                throw KnowledgeBaseError.Storage("找不到已复制的原件，请重新导入。")
            }
            importer(path, resuming = documentId)
        }

    override suspend fun deleteDocument(documentId: UUID) {
        val document = repository.document(documentId)
        repository.deleteDocument(documentId)
        document?.sourcePath?.let { runCatching { Files.deleteIfExists(Paths.get(it)) } }
    }

    override suspend fun ask(question: String, scope: KnowledgeSearchScope): KnowledgeAnswer {
        // Route through askStream so refuse-without-generation never warms the LLM.
        var answer: KnowledgeAnswer? = null
        askStream(question, scope).collect { event ->
            if (event is KnowledgeAnswerStreamEvent.Finished) {
                answer = event.answer
            }
        }
        return answer ?: throw KnowledgeBaseError.Storage("问答流未返回完成事件")
    }

    override fun askStream(
        question: String,
        scope: KnowledgeSearchScope,
    ): Flow<KnowledgeAnswerStreamEvent> = flow {
        knowledgeOpsMutex.withLock {
            ensureIndexed()
            asker.stream(question, scope).collect { event ->
                when (event) {
                    is KnowledgeAnswerStreamEvent.Phase -> {
                        // Warm LLM only for typed Generating — refuse path never emits it.
                        if (event.kind == KnowledgeAnswerStreamEvent.PhaseKind.Generating) {
                            ensureGeneratorReady()
                        }
                        emit(event)
                    }
                    is KnowledgeAnswerStreamEvent.Delta -> emit(event)
                    is KnowledgeAnswerStreamEvent.Finished -> {
                        // Show sources immediately with ingest locators — never block UI on enrich.
                        emit(event)
                        val polished = enrichCitations(event.answer, question)
                        if (polished.citations != event.answer.citations) {
                            emit(KnowledgeAnswerStreamEvent.Finished(polished))
                        }
                    }
                }
            }
        }
    }

    override suspend fun indexedChunkCount(): Int = repository.chunkCount()

    override suspend fun previewDocument(documentId: UUID): DocumentPreviewIntent {
        val document = repository.document(documentId)
            ?: throw KnowledgeBaseError.Storage("文档不存在")
        if (!Files.isRegularFile(Paths.get(document.sourcePath))) {
            throw KnowledgeBaseError.Storage("找不到已复制的原件。")
        }
        return DocumentPreviewIntent(
            documentId = document.id,
            title = document.title,
            filePath = document.sourcePath,
            indexedText = document.indexedText,
        )
    }

    override suspend fun previewCitation(citation: KnowledgeCitation): DocumentPreviewIntent {
        val document = repository.document(citation.documentId)
            ?: throw KnowledgeBaseError.Storage("文档不存在")
        if (!Files.isRegularFile(Paths.get(document.sourcePath))) {
            throw KnowledgeBaseError.Storage("找不到已复制的原件。")
        }
        return DocumentPreviewIntent(
            documentId = document.id,
            title = document.title,
            filePath = document.sourcePath,
            indexedText = document.indexedText,
            locator = citation.locator,
            excerpt = citation.excerpt,
            locatorLabel = citation.locatorLabel ?: citation.locator?.shortLabel,
        )
    }

    private suspend fun enrichCitations(
        answer: KnowledgeAnswer,
        question: String,
    ): KnowledgeAnswer {
        val enriched = answer.citations.map { citation ->
            val document = repository.document(citation.documentId)
            val result = CitationLocatorEnricher.enrich(
                citation = citation,
                indexedText = document?.indexedText,
                pageSpans = document?.pageSpans.orEmpty(),
                question = question,
                answerText = answer.text,
            )
            citation.copy(
                excerpt = result.excerpt,
                locator = result.locator,
                locatorLabel = result.locatorLabel,
            )
        }
        return answer.copy(citations = enriched)
    }

    override suspend fun generatorRuntimeState(): GeneratorRuntimeState {
        refreshInstalledStateIfNeeded()
        return runtimeState
    }

    override suspend fun installedGenerator(): InstalledGeneratorInfo? {
        val installed = modelStore.installedModel(forDescriptor = modelDescriptor) ?: return null
        return InstalledGeneratorInfo(
            displayName = modelDescriptor.displayName,
            byteCount = installed.byteCount,
        )
    }

    override suspend fun importGeneratorModel(from: Uri) {
        generatorMutex.withLock {
            runtimeState = GeneratorRuntimeState.Loading
            val displayName = queryDisplayName(from) ?: "gemma-4-E2B-it.litertlm"
            try {
                // Multi-GB copy must not run on Main — that freezes Compose after SAF resume (black gap).
                withContext(Dispatchers.IO) {
                    val stream = contentResolver.openInputStream(from)
                        ?: throw ai.orynode.mobile.domain.ModelRuntimeError.InvalidModelFile
                    stream.use { input ->
                        modelStore.importModel(input, displayName, modelDescriptor)
                    }
                }
                // Copied into sandbox; caller loads via loadGenerator() with its own status.
                runtimeState = GeneratorRuntimeState.Installed
            } catch (error: Exception) {
                runtimeState = if (modelStore.installedModel(forDescriptor = modelDescriptor) != null) {
                    GeneratorRuntimeState.Installed
                } else {
                    GeneratorRuntimeState.NotInstalled
                }
                throw error
            }
        }
    }

    override suspend fun downloadGeneratorModel(
        onProgress: suspend (bytesReceived: Long, totalBytes: Long?) -> Unit,
    ) {
        modelDownloader.download { progress ->
            onProgress(progress.bytesReceived, progress.totalBytes)
        }
    }

    override suspend fun installDownloadedGenerator() {
        generatorMutex.withLock {
            runtimeState = GeneratorRuntimeState.Loading
            try {
                withContext(Dispatchers.IO) {
                    modelStore.promoteDownloadedModel(
                        modelDownloader.partialFile(),
                        modelDescriptor,
                    )
                }
                runtimeState = GeneratorRuntimeState.Installed
            } catch (error: Exception) {
                runtimeState = if (modelStore.installedModel(forDescriptor = modelDescriptor) != null) {
                    GeneratorRuntimeState.Installed
                } else {
                    GeneratorRuntimeState.NotInstalled
                }
                throw error
            }
        }
    }

    override fun cancelGeneratorDownload() {
        modelDownloader.cancel()
    }

    override suspend fun loadGenerator() {
        generatorMutex.withLock {
            val installed = modelStore.installedModel(forDescriptor = modelDescriptor)
                ?: run {
                    runtimeState = GeneratorRuntimeState.NotInstalled
                    throw ai.orynode.mobile.domain.ModelRuntimeError.ModelNotInstalled
                }
            runtimeState = GeneratorRuntimeState.Loading
            try {
                withContext(Dispatchers.IO) {
                    engine.load(installed.filePath)
                }
                runtimeState = GeneratorRuntimeState.Ready
            } catch (error: Exception) {
                runtimeState = GeneratorRuntimeState.Failed(error.message ?: "加载失败")
                throw error
            }
        }
    }

    override suspend fun unloadGenerator() {
        generatorMutex.withLock {
            engine.unload()
            runtimeState = if (modelStore.installedModel(forDescriptor = modelDescriptor) != null) {
                GeneratorRuntimeState.Installed
            } else {
                GeneratorRuntimeState.NotInstalled
            }
        }
    }

    override suspend fun deleteGenerator() {
        generatorMutex.withLock {
            engine.unload()
            modelStore.deleteModel(modelDescriptor)
            modelDownloader.clearPartial()
            runtimeState = GeneratorRuntimeState.NotInstalled
        }
    }

    private suspend fun refreshInstalledStateIfNeeded() {
        if (runtimeState is GeneratorRuntimeState.Ready || runtimeState is GeneratorRuntimeState.Loading) {
            return
        }
        runtimeState = if (modelStore.installedModel(forDescriptor = modelDescriptor) != null) {
            GeneratorRuntimeState.Installed
        } else {
            GeneratorRuntimeState.NotInstalled
        }
    }

    private suspend fun ensureIndexed() {
        val ready = repository.documents().any { it.state == KnowledgeDocument.State.Ready }
        if (!ready) throw KnowledgeBaseError.NoIndexedDocuments
    }

    private suspend fun ensureGeneratorReady() {
        if (runtimeState is GeneratorRuntimeState.Ready) return
        loadGenerator()
    }

    private fun copyUri(from: Uri, target: Path) {
        runCatching {
            contentResolver.takePersistableUriPermission(
                from,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        val stream = contentResolver.openInputStream(from)
            ?: throw KnowledgeBaseError.Storage("无法读取这个文档。")
        stream.use { input ->
            Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        val cursor = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?: return null
        cursor.use {
            if (!it.moveToFirst()) return null
            val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index < 0) return null
            return it.getString(index)
        }
    }

    private fun sanitize(name: String): String =
        name.replace(Regex("[^A-Za-z0-9._\\-\\u4e00-\\u9fff]"), "_")

    override fun embeddingBackendLabel(): String = embeddingLabel
}
