package ai.orynode.mobile.infrastructure.persistence

import ai.orynode.mobile.domain.EmbeddedKnowledgeChunk
import ai.orynode.mobile.domain.KnowledgeChunk
import ai.orynode.mobile.domain.KnowledgeDocument
import ai.orynode.mobile.domain.KnowledgeSearchScope
import ai.orynode.mobile.infrastructure.embedding.DeterministicHashEmbedding
import kotlinx.coroutines.test.runTest
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InMemoryKnowledgeRepositoryTest {
    @Test
    fun unpublishedChunksAreNotSearchableUntilPublish() = runTest {
        val embedding = DeterministicHashEmbedding(dimensions = 32)
        val repository = InMemoryKnowledgeRepository(
            embeddingDimensions = embedding.dimensions,
            embeddingIndexVersion = embedding.descriptor.indexVersion,
        )
        val id = UUID.randomUUID()
        val document = KnowledgeDocument(
            id = id,
            sourcePath = "/tmp/a.txt",
            title = "隐私说明",
            contentHash = "abc",
            state = KnowledgeDocument.State.Importing,
        )
        repository.save(document)
        repository.prepareIndexJob(id, "abc", "不会上传文档", emptyList())
        val chunk = KnowledgeChunk(
            documentId = id,
            ordinal = 0,
            text = "不会上传文档",
            tokenEstimate = 8,
        )
        val vector = embedding.embedDocuments(listOf(chunk.text)).first()
        repository.appendUnpublishedChunks(id, listOf(EmbeddedKnowledgeChunk(chunk, vector)))
        val before = repository.search("上传", vector, limit = 5, scope = KnowledgeSearchScope.All)
        assertEquals(emptyList(), before)

        repository.publishUnpublishedChunks(
            document.copy(state = KnowledgeDocument.State.Ready, importedChunkCount = 1),
        )
        val after = repository.search("上传", vector, limit = 5, scope = KnowledgeSearchScope.All)
        assertEquals(1, after.size)
        assertTrue(after.first().chunk.text.contains("不会上传"))
    }
}
