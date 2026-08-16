package ai.orynode.mobile.application

import ai.orynode.mobile.domain.EmbeddedKnowledgeChunk
import ai.orynode.mobile.domain.KnowledgeDocument
import ai.orynode.mobile.domain.KnowledgePageSpan
import ai.orynode.mobile.domain.KnowledgeRepository
import ai.orynode.mobile.domain.KnowledgeSearchHit
import ai.orynode.mobile.domain.KnowledgeSearchScope
import java.util.UUID

open class FakeKnowledgeRepository : KnowledgeRepository {
    private val documents = LinkedHashMap<UUID, KnowledgeDocument>()
    private val live = LinkedHashMap<UUID, MutableList<EmbeddedKnowledgeChunk>>()
    private val unpublished = LinkedHashMap<UUID, MutableList<EmbeddedKnowledgeChunk>>()
    private val jobs = HashMap<UUID, String>()

    override suspend fun document(id: UUID) = documents[id]
    override suspend fun document(contentHash: String) =
        documents.values.firstOrNull { it.contentHash == contentHash && it.contentHash.isNotEmpty() }
    override suspend fun documents() = documents.values.toList()
    override suspend fun save(document: KnowledgeDocument) {
        documents[document.id] = document
    }
    override suspend fun replaceChunks(documentId: UUID, chunks: List<EmbeddedKnowledgeChunk>) {
        live[documentId] = chunks.toMutableList()
    }
    override suspend fun prepareIndexJob(
        documentId: UUID,
        contentHash: String,
        indexedText: String,
        pageSpans: List<KnowledgePageSpan>,
    ): Int {
        if (jobs[documentId] == contentHash) return unpublished[documentId]?.size ?: 0
        unpublished[documentId] = mutableListOf()
        jobs[documentId] = contentHash
        return 0
    }
    override suspend fun unpublishedChunkCount(documentId: UUID) = unpublished[documentId]?.size ?: 0
    override suspend fun discardUnpublishedChunks(documentId: UUID) {
        unpublished.remove(documentId)
        jobs.remove(documentId)
    }
    override suspend fun appendUnpublishedChunks(documentId: UUID, chunks: List<EmbeddedKnowledgeChunk>) {
        unpublished.getOrPut(documentId) { mutableListOf() } += chunks
    }
    override suspend fun publishUnpublishedChunks(document: KnowledgeDocument) {
        live[document.id] = unpublished.remove(document.id).orEmpty().toMutableList()
        jobs.remove(document.id)
        documents[document.id] = document
    }
    override suspend fun deleteDocument(id: UUID) {
        documents.remove(id)
        live.remove(id)
        unpublished.remove(id)
        jobs.remove(id)
    }
    override suspend fun search(
        query: String,
        embedding: FloatArray,
        limit: Int,
        scope: KnowledgeSearchScope,
    ): List<KnowledgeSearchHit> = emptyList()
    override suspend fun chunkCount() = live.values.sumOf { it.size }
    override suspend fun chunkCount(documentId: UUID) = live[documentId]?.size ?: 0
}
