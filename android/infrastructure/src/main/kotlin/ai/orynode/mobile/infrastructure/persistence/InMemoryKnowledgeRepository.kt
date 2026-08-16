package ai.orynode.mobile.infrastructure.persistence

import ai.orynode.mobile.domain.EmbeddedKnowledgeChunk
import ai.orynode.mobile.domain.KnowledgeDocument
import ai.orynode.mobile.domain.KnowledgeIndexContract
import ai.orynode.mobile.domain.KnowledgePageSpan
import ai.orynode.mobile.domain.KnowledgeRepository
import ai.orynode.mobile.domain.KnowledgeSearchHit
import ai.orynode.mobile.domain.KnowledgeSearchScope
import ai.orynode.mobile.infrastructure.embedding.ExactCosineSimilarity
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import kotlin.math.ln
import kotlin.math.max

/**
 * In-memory hybrid retrieval for lightweight repository contract tests.
 * Production uses [SqliteKnowledgeRepository]; this fixture skips index-version gates
 * and FTS focus-term extraction. Same fusion formula as the iOS index contract.
 */
class InMemoryKnowledgeRepository(
    private val embeddingDimensions: Int,
    private val embeddingIndexVersion: String,
    private val retrievalVersion: String = KnowledgeIndexContract.RETRIEVAL_VERSION,
    private val chunkerVersion: String = KnowledgeIndexContract.DEFAULT_CHUNKER_VERSION,
    private val contentHashVersion: String = KnowledgeIndexContract.CONTENT_HASH_VERSION,
) : KnowledgeRepository {
    private val mutex = Mutex()
    private val documents = LinkedHashMap<UUID, KnowledgeDocument>()
    private val liveChunks = LinkedHashMap<UUID, MutableList<EmbeddedKnowledgeChunk>>()
    private val unpublished = LinkedHashMap<UUID, MutableList<EmbeddedKnowledgeChunk>>()
    private val indexJobs = HashMap<UUID, IndexJob>()

    init {
        require(embeddingDimensions > 0)
        require(embeddingIndexVersion.isNotEmpty())
        require(retrievalVersion.isNotEmpty())
        require(chunkerVersion.isNotEmpty())
        require(contentHashVersion.isNotEmpty())
    }

    data class IndexJob(
        val contentHash: String,
        val indexedText: String,
        val pageSpans: List<KnowledgePageSpan>,
        val committed: Int,
    )

    override suspend fun document(id: UUID): KnowledgeDocument? = mutex.withLock { documents[id] }

    override suspend fun document(contentHash: String): KnowledgeDocument? = mutex.withLock {
        documents.values.firstOrNull { it.contentHash == contentHash && it.contentHash.isNotEmpty() }
    }

    override suspend fun documents(): List<KnowledgeDocument> = mutex.withLock {
        documents.values.sortedByDescending { it.updatedAt }
    }

    override suspend fun save(document: KnowledgeDocument) {
        mutex.withLock { documents[document.id] = document }
    }

    override suspend fun replaceChunks(
        documentId: UUID,
        chunks: List<EmbeddedKnowledgeChunk>,
    ) {
        mutex.withLock {
            liveChunks[documentId] = chunks.toMutableList()
            unpublished.remove(documentId)
            indexJobs.remove(documentId)
        }
    }

    override suspend fun prepareIndexJob(
        documentId: UUID,
        contentHash: String,
        indexedText: String,
        pageSpans: List<KnowledgePageSpan>,
    ): Int = mutex.withLock {
        val existing = indexJobs[documentId]
        if (existing != null && existing.contentHash == contentHash) {
            return@withLock unpublished[documentId]?.size ?: existing.committed
        }
        unpublished[documentId] = mutableListOf()
        indexJobs[documentId] = IndexJob(contentHash, indexedText, pageSpans, committed = 0)
        0
    }

    override suspend fun unpublishedChunkCount(documentId: UUID): Int = mutex.withLock {
        unpublished[documentId]?.size ?: 0
    }

    override suspend fun discardUnpublishedChunks(documentId: UUID) {
        mutex.withLock {
            unpublished.remove(documentId)
            indexJobs.remove(documentId)
        }
    }

    override suspend fun appendUnpublishedChunks(
        documentId: UUID,
        chunks: List<EmbeddedKnowledgeChunk>,
    ) {
        mutex.withLock {
            val staging = unpublished.getOrPut(documentId) { mutableListOf() }
            staging += chunks
            val job = indexJobs[documentId]
            if (job != null) {
                indexJobs[documentId] = job.copy(committed = staging.size)
            }
        }
    }

    override suspend fun publishUnpublishedChunks(document: KnowledgeDocument) {
        mutex.withLock {
            val staging = unpublished.remove(document.id).orEmpty()
            liveChunks[document.id] = staging.toMutableList()
            indexJobs.remove(document.id)
            documents[document.id] = document
        }
    }

    override suspend fun deleteDocument(id: UUID) {
        mutex.withLock {
            documents.remove(id)
            liveChunks.remove(id)
            unpublished.remove(id)
            indexJobs.remove(id)
        }
    }

    override suspend fun chunkCount(): Int = mutex.withLock {
        liveChunks.values.sumOf { it.size }
    }

    override suspend fun chunkCount(documentId: UUID): Int = mutex.withLock {
        liveChunks[documentId]?.size ?: 0
    }

    override suspend fun search(
        query: String,
        embedding: FloatArray,
        limit: Int,
        scope: KnowledgeSearchScope,
    ): List<KnowledgeSearchHit> = mutex.withLock {
        val allowed = scope.documentIds
        val candidates = liveChunks.flatMap { (documentId, chunks) ->
            val document = documents[documentId] ?: return@flatMap emptyList()
            if (document.state != KnowledgeDocument.State.Ready) return@flatMap emptyList()
            if (allowed != null && documentId !in allowed) return@flatMap emptyList()
            chunks.map { embedded -> document to embedded }
        }
        if (candidates.isEmpty()) return@withLock emptyList()

        val lexicalRaw = candidates.map { (document, embedded) ->
            lexicalScore(query, embedded.chunk.text) to (document to embedded)
        }
        val maxLexical = lexicalRaw.maxOf { it.first }.coerceAtLeast(1e-6f)
        val fused = lexicalRaw.map { (lex, pair) ->
            val (document, embedded) = pair
            val cosine = ExactCosineSimilarity.score(embedding, embedded.embedding)
            val normalizedLex = lex / maxLexical
            val score = 0.7f * cosine + 0.3f * normalizedLex
            KnowledgeSearchHit(
                chunk = embedded.chunk,
                documentTitle = document.title,
                score = score,
            )
        }
        fused.sortedByDescending { it.score }.take(max(1, limit))
    }

    private fun lexicalScore(query: String, text: String): Float {
        val terms = query.lowercase().split(Regex("\\s+")).filter { it.length >= 2 }
        if (terms.isEmpty()) {
            val needle = query.trim().lowercase()
            if (needle.isEmpty()) return 0f
            val count = text.lowercase().windowed(needle.length, 1, partialWindows = false)
                .count { it == needle }
            return if (count == 0) 0f else (1f + ln(1f + count))
        }
        val haystack = text.lowercase()
        var score = 0f
        for (term in terms) {
            var from = 0
            var count = 0
            while (true) {
                val index = haystack.indexOf(term, from)
                if (index < 0) break
                count += 1
                from = index + term.length
            }
            if (count > 0) score += 1f + ln(1f + count)
        }
        return score
    }
}
