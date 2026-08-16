package ai.orynode.mobile.app.serving

import ai.orynode.mobile.app.serving.DocumentPreviewIntent
import ai.orynode.mobile.domain.KnowledgeAnswer
import ai.orynode.mobile.domain.KnowledgeAnswerStreamEvent
import ai.orynode.mobile.domain.KnowledgeCitation
import ai.orynode.mobile.domain.KnowledgeDocument
import ai.orynode.mobile.domain.KnowledgeSearchScope
import android.net.Uri
import kotlinx.coroutines.flow.Flow
import java.util.UUID

/**
 * The only façade Features/UI may call. Must not leak SQLite / LiteRT / embedding types.
 */
interface KnowledgeBaseServing {
    suspend fun loadDocuments(): List<KnowledgeDocument>
    fun importDocument(from: Uri, displayName: String?): Flow<KnowledgeDocument>
    suspend fun retryIndexing(documentId: UUID): KnowledgeDocument
    suspend fun deleteDocument(documentId: UUID)
    suspend fun ask(question: String, scope: KnowledgeSearchScope = KnowledgeSearchScope.All): KnowledgeAnswer
    fun askStream(
        question: String,
        scope: KnowledgeSearchScope = KnowledgeSearchScope.All,
    ): Flow<KnowledgeAnswerStreamEvent>

    suspend fun indexedChunkCount(): Int
    suspend fun previewDocument(documentId: UUID): DocumentPreviewIntent
    suspend fun previewCitation(citation: KnowledgeCitation): DocumentPreviewIntent

    suspend fun generatorRuntimeState(): GeneratorRuntimeState
    suspend fun installedGenerator(): InstalledGeneratorInfo?
    suspend fun importGeneratorModel(from: Uri)

    /**
     * Resume-capable download into app-private staging. Progress is network bytes only.
     * Call [installDownloadedGenerator] after success, then [loadGenerator].
     */
    suspend fun downloadGeneratorModel(onProgress: suspend (bytesReceived: Long, totalBytes: Long?) -> Unit)

    /** Promote the completed staging download into the installed model slot (no second full copy). */
    suspend fun installDownloadedGenerator()

    fun cancelGeneratorDownload()

    suspend fun loadGenerator()
    suspend fun unloadGenerator()
    suspend fun deleteGenerator()

    /** Human-readable embedding backend id (e.g. e5 LiteRT or Debug hash fallback). */
    fun embeddingBackendLabel(): String
}

class UnavailableKnowledgeBaseService(
    private val error: Throwable,
) : KnowledgeBaseServing {
    override suspend fun loadDocuments(): List<KnowledgeDocument> = throw error
    override fun importDocument(from: Uri, displayName: String?) = kotlinx.coroutines.flow.flow<KnowledgeDocument> {
        throw error
    }
    override suspend fun retryIndexing(documentId: UUID): KnowledgeDocument = throw error
    override suspend fun deleteDocument(documentId: UUID) = throw error
    override suspend fun ask(question: String, scope: KnowledgeSearchScope): KnowledgeAnswer = throw error
    override fun askStream(question: String, scope: KnowledgeSearchScope) =
        kotlinx.coroutines.flow.flow<KnowledgeAnswerStreamEvent> { throw error }

    override suspend fun indexedChunkCount(): Int = 0
    override suspend fun previewDocument(documentId: UUID): DocumentPreviewIntent = throw error
    override suspend fun previewCitation(citation: KnowledgeCitation): DocumentPreviewIntent = throw error
    override suspend fun generatorRuntimeState(): GeneratorRuntimeState =
        GeneratorRuntimeState.Failed(error.message ?: "知识库不可用")
    override suspend fun installedGenerator(): InstalledGeneratorInfo? = null
    override suspend fun importGeneratorModel(from: Uri) = throw error
    override suspend fun downloadGeneratorModel(
        onProgress: suspend (bytesReceived: Long, totalBytes: Long?) -> Unit,
    ) = throw error
    override suspend fun installDownloadedGenerator() = throw error
    override fun cancelGeneratorDownload() = Unit
    override suspend fun loadGenerator() = throw error
    override suspend fun unloadGenerator() = Unit
    override suspend fun deleteGenerator() = throw error
    override fun embeddingBackendLabel(): String = "不可用"
}
