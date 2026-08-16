package ai.orynode.mobile.application

import ai.orynode.mobile.domain.EmbeddedKnowledgeChunk
import ai.orynode.mobile.domain.KnowledgeBaseError
import ai.orynode.mobile.domain.KnowledgeBaseLimits
import ai.orynode.mobile.domain.KnowledgeChunker
import ai.orynode.mobile.domain.KnowledgeDocument
import ai.orynode.mobile.domain.KnowledgeRepository
import ai.orynode.mobile.domain.KnowledgeTextExtractor
import ai.orynode.mobile.domain.TextEmbedding
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

class ImportKnowledgeDocument(
    private val extractor: KnowledgeTextExtractor,
    private val chunker: KnowledgeChunker,
    private val embedding: TextEmbedding,
    private val repository: KnowledgeRepository,
    private val maxChunks: Int = KnowledgeBaseLimits.MAX_CHUNKS,
    private val indexBatchSize: Int = KnowledgeBaseLimits.INDEX_BATCH_SIZE,
) {
    /** Visible in the document list before extract/embed. Not searchable until Ready. */
    suspend fun enqueue(
        path: Path,
        documentId: UUID,
        title: String? = null,
    ): KnowledgeDocument {
        repository.document(documentId)?.let { return it }
        val document = KnowledgeDocument(
            id = documentId,
            sourcePath = path.toString(),
            title = DocumentDisplayName.title(preferred = title, path = path, documentId = documentId),
            contentHash = "",
            state = KnowledgeDocument.State.Importing,
        )
        repository.save(document)
        return document
    }

    /**
     * Same document ID resumes from the last committed embed batch.
     * A ready document keeps serving until publish.
     */
    suspend operator fun invoke(path: Path, resuming: UUID? = null): KnowledgeDocument {
        val id = resuming ?: UUID.randomUUID()
        var queued = enqueue(path, id)
        if (queued.state == KnowledgeDocument.State.Failed) {
            queued = queued.copy(
                state = KnowledgeDocument.State.Importing,
                errorMessage = null,
                updatedAt = Instant.now(),
            )
            repository.save(queued)
        }
        val keepServing = queued.state == KnowledgeDocument.State.Ready
        return try {
            val extraction = extractor.extract(path)
            val text = extraction.indexedText.trim()
            if (text.isEmpty()) throw KnowledgeBaseError.EmptyDocument
            val hash = contentHash(extraction.indexedText)
            val existing = repository.document(contentHash = hash)
            if (existing != null && existing.id != id) {
                throw KnowledgeBaseError.DuplicateDocument(existing.title)
            }
            if (!keepServing) {
                repository.save(
                    KnowledgeDocument(
                        id = id,
                        sourcePath = path.toString(),
                        title = queued.title,
                        contentHash = hash,
                        state = KnowledgeDocument.State.Importing,
                        indexedText = extraction.indexedText,
                        pageSpans = extraction.pageSpans,
                        createdAt = queued.createdAt,
                    ),
                )
            }
            var committed = repository.prepareIndexJob(
                documentId = id,
                contentHash = hash,
                indexedText = extraction.indexedText,
                pageSpans = extraction.pageSpans,
            )
            val chunks = chunker.chunks(id, extraction)
            if (committed > chunks.size) {
                repository.discardUnpublishedChunks(id)
                committed = repository.prepareIndexJob(
                    documentId = id,
                    contentHash = hash,
                    indexedText = extraction.indexedText,
                    pageSpans = extraction.pageSpans,
                )
            }
            val existingLive = repository.chunkCount(id)
            val current = repository.chunkCount()
            val projected = current - existingLive + chunks.size
            if (projected > maxChunks) {
                throw KnowledgeBaseError.ChunkCapacityExceeded(
                    current = current - existingLive,
                    incoming = chunks.size,
                    limit = maxChunks,
                )
            }
            val remaining = chunks.drop(minOf(committed, chunks.size))
            val batchSize = maxOf(1, indexBatchSize)
            var offset = 0
            while (offset < remaining.size) {
                val end = minOf(offset + batchSize, remaining.size)
                val batch = remaining.subList(offset, end)
                val vectors = embedding.embedDocuments(batch.map { it.text })
                if (vectors.size != batch.size) {
                    throw KnowledgeBaseError.Storage("embedding count mismatch")
                }
                val embedded = batch.zip(vectors).map { (chunk, vector) ->
                    if (vector.size != embedding.dimensions) {
                        throw KnowledgeBaseError.InvalidEmbeddingDimensions(
                            expected = embedding.dimensions,
                            actual = vector.size,
                        )
                    }
                    EmbeddedKnowledgeChunk(chunk = chunk, embedding = vector)
                }
                repository.appendUnpublishedChunks(id, embedded)
                offset = end
            }
            val published = KnowledgeDocument(
                id = id,
                sourcePath = path.toString(),
                title = queued.title,
                contentHash = hash,
                state = KnowledgeDocument.State.Ready,
                importedChunkCount = chunks.size,
                indexedText = extraction.indexedText,
                pageSpans = extraction.pageSpans,
                createdAt = queued.createdAt,
                updatedAt = Instant.now(),
            )
            repository.publishUnpublishedChunks(published)
            published
        } catch (error: Exception) {
            if (error is KnowledgeBaseError.DuplicateDocument) {
                if (queued.contentHash.isEmpty()) {
                    runCatching { repository.deleteDocument(id) }
                }
                throw error
            }
            val latest = repository.document(id) ?: queued
            if (latest.state == KnowledgeDocument.State.Ready) throw error
            runCatching {
                repository.save(
                    latest.copy(
                        state = KnowledgeDocument.State.Failed,
                        errorMessage = error.message,
                        updatedAt = Instant.now(),
                    ),
                )
            }
            throw error
        }
    }

    companion object {
        fun contentHash(text: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }
        }
    }
}

/** Human-readable document titles — strips storage UUID prefixes like `{uuid}-foo.pdf`. */
object DocumentDisplayName {
    private val leadingUuid = Regex(
        "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}-",
    )

    fun title(preferred: String?, path: Path, documentId: UUID? = null): String {
        val fromPreferred = preferred?.trim()?.takeIf { it.isNotEmpty() }?.let { stripExtension(it) }
        if (!fromPreferred.isNullOrBlank() && !looksLikeStorageName(fromPreferred, documentId)) {
            return fromPreferred
        }
        return fromFileName(path.fileName.toString(), documentId)
    }

    fun fromFileName(fileName: String, documentId: UUID? = null): String {
        val base = stripExtension(fileName)
        return stripLeadingDocumentId(base, documentId).ifBlank { base.ifBlank { "未命名文档" } }
    }

    fun normalizeStoredTitle(title: String, sourcePath: String, documentId: UUID): String {
        val cleaned = stripLeadingDocumentId(title, documentId)
        if (cleaned.isNotBlank() && cleaned != title) return cleaned
        if (!looksLikeStorageName(title, documentId)) return title
        return fromFileName(sourcePath.substringAfterLast('/'), documentId)
    }

    private fun stripExtension(name: String): String {
        val trimmed = name.trim()
        val dot = trimmed.lastIndexOf('.')
        if (dot <= 0) return trimmed
        return trimmed.substring(0, dot)
    }

    private fun stripLeadingDocumentId(name: String, documentId: UUID?): String {
        val trimmed = name.trim()
        if (documentId != null && trimmed.startsWith("$documentId-")) {
            return trimmed.removePrefix("$documentId-")
        }
        return leadingUuid.replaceFirst(trimmed, "")
    }

    private fun looksLikeStorageName(name: String, documentId: UUID?): Boolean {
        if (documentId != null && name.startsWith("$documentId")) return true
        return leadingUuid.containsMatchIn(name)
    }
}
