package ai.orynode.mobile.domain

import java.nio.file.Path
import java.util.UUID

interface KnowledgeTextExtractor {
    suspend fun extract(from: Path): KnowledgeExtraction
}

interface KnowledgeChunker {
    fun chunks(documentId: UUID, extraction: KnowledgeExtraction): List<KnowledgeChunk>

    fun chunks(documentId: UUID, text: String): List<KnowledgeChunk> =
        chunks(
            documentId,
            KnowledgeExtraction(kind = KnowledgeExtraction.Kind.PlainText, indexedText = text),
        )
}

interface TextEmbedding {
    val name: String
    val dimensions: Int
    val descriptor: EmbeddingDescriptor
        get() = EmbeddingDescriptor(
            id = name,
            version = "1",
            dimensions = dimensions,
            tokenizerVersion = "unspecified",
        )

    suspend fun embed(texts: List<String>): List<FloatArray>

    suspend fun embedDocuments(texts: List<String>): List<FloatArray> = embed(texts)

    suspend fun embedQuery(text: String): FloatArray =
        embed(listOf(text)).firstOrNull()
            ?: throw KnowledgeBaseError.Storage("query embedding missing")
}

interface KnowledgeRepository {
    suspend fun document(id: UUID): KnowledgeDocument?
    suspend fun document(contentHash: String): KnowledgeDocument?
    suspend fun documents(): List<KnowledgeDocument>
    suspend fun save(document: KnowledgeDocument)
    suspend fun replaceChunks(documentId: UUID, chunks: List<EmbeddedKnowledgeChunk>)

    /** Same-hash resume returns already-committed unpublished count; hash change discards staging. */
    suspend fun prepareIndexJob(
        documentId: UUID,
        contentHash: String,
        indexedText: String,
        pageSpans: List<KnowledgePageSpan>,
    ): Int

    suspend fun unpublishedChunkCount(documentId: UUID): Int
    suspend fun discardUnpublishedChunks(documentId: UUID)

    /** Persists one embedded batch. Checkpoint advances only after this call returns. */
    suspend fun appendUnpublishedChunks(documentId: UUID, chunks: List<EmbeddedKnowledgeChunk>)

    /** Atomically swaps staging into live chunks and writes the ready document row. */
    suspend fun publishUnpublishedChunks(document: KnowledgeDocument)
    suspend fun deleteDocument(id: UUID)
    suspend fun search(
        query: String,
        embedding: FloatArray,
        limit: Int,
        scope: KnowledgeSearchScope,
    ): List<KnowledgeSearchHit>

    suspend fun chunkCount(): Int
    suspend fun chunkCount(documentId: UUID): Int
}

interface KnowledgeAnswerGenerator {
    suspend fun answer(question: String, context: String): String

    fun answerStream(question: String, context: String): kotlinx.coroutines.flow.Flow<String>

    fun finalize(rawAnswer: String): String = rawAnswer
}
