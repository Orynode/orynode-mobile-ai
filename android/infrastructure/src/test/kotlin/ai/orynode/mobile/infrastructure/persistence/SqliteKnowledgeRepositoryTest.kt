package ai.orynode.mobile.infrastructure.persistence

import ai.orynode.mobile.domain.EmbeddedKnowledgeChunk
import ai.orynode.mobile.domain.KnowledgeChunk
import ai.orynode.mobile.domain.KnowledgeDocument
import ai.orynode.mobile.domain.KnowledgeIndexContract
import ai.orynode.mobile.domain.KnowledgeSearchScope
import ai.orynode.mobile.domain.SourceLocator
import ai.orynode.mobile.infrastructure.embedding.DeterministicHashEmbedding
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.nio.file.Files
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import ai.orynode.mobile.domain.KnowledgeBaseError

@RunWith(RobolectricTestRunner::class)
class SqliteKnowledgeRepositoryTest {
    @Test
    fun unpublishedChunksAreNotSearchableUntilPublishAndSurviveReopen() = runTest {
        val dir = Files.createTempDirectory("orynode-sqlite")
        val dbPath = dir.resolve("knowledge.sqlite")
        val embedding = DeterministicHashEmbedding(dimensions = 32)
        val id = UUID.randomUUID()
        val document = KnowledgeDocument(
            id = id,
            sourcePath = "/tmp/a.txt",
            title = "隐私说明",
            contentHash = "abc",
            state = KnowledgeDocument.State.Importing,
            indexedText = "不会上传文档",
        )
        val chunk = KnowledgeChunk(
            documentId = id,
            ordinal = 0,
            text = "不会上传文档",
            tokenEstimate = 8,
            locator = SourceLocator.PlainText(0, 6),
        )
        val vector = embedding.embedDocuments(listOf(chunk.text)).first()

        SqliteKnowledgeRepository.open(
            path = dbPath,
            embeddingDimensions = embedding.dimensions,
            embeddingIndexVersion = embedding.descriptor.indexVersion,
            chunkerVersion = KnowledgeIndexContract.DEFAULT_CHUNKER_VERSION,
        ).use { repository ->
            repository.save(document)
            repository.prepareIndexJob(id, "abc", "不会上传文档", emptyList())
            repository.appendUnpublishedChunks(id, listOf(EmbeddedKnowledgeChunk(chunk, vector)))
            val before = repository.search("上传", vector, limit = 5, scope = KnowledgeSearchScope.All)
            assertEquals(emptyList(), before)

            repository.publishUnpublishedChunks(
                document.copy(state = KnowledgeDocument.State.Ready, importedChunkCount = 1),
            )
            val after = repository.search("上传", vector, limit = 5, scope = KnowledgeSearchScope.All)
            assertEquals(1, after.size)
            assertTrue(after.first().chunk.text.contains("不会上传"))
            assertEquals(SourceLocator.PlainText(0, 6), after.first().chunk.locator)
        }

        SqliteKnowledgeRepository.open(
            path = dbPath,
            embeddingDimensions = embedding.dimensions,
            embeddingIndexVersion = embedding.descriptor.indexVersion,
            chunkerVersion = KnowledgeIndexContract.DEFAULT_CHUNKER_VERSION,
        ).use { reopened ->
            val docs = reopened.documents()
            assertEquals(1, docs.size)
            assertEquals(KnowledgeDocument.State.Ready, docs.first().state)
            val hits = reopened.search("上传", vector, limit = 5, scope = KnowledgeSearchScope.All)
            assertEquals(1, hits.size)
        }
    }

    @Test
    fun rejectsEmbeddingVersionMismatchWhenIndexExists() = runTest {
        val dir = Files.createTempDirectory("orynode-sqlite-version")
        val dbPath = dir.resolve("knowledge.sqlite")
        val embedding = DeterministicHashEmbedding(dimensions = 32)
        val id = UUID.randomUUID()
        SqliteKnowledgeRepository.open(
            path = dbPath,
            embeddingDimensions = embedding.dimensions,
            embeddingIndexVersion = "old@1:32:v1",
        ).use { repository ->
            val document = KnowledgeDocument(
                id = id,
                sourcePath = "/tmp/b.txt",
                title = "版本",
                contentHash = "hash",
                state = KnowledgeDocument.State.Ready,
                importedChunkCount = 1,
            )
            repository.save(document)
            val chunk = KnowledgeChunk(
                documentId = id,
                ordinal = 0,
                text = "版本闸门",
                tokenEstimate = 4,
            )
            val vector = embedding.embedDocuments(listOf(chunk.text)).first()
            repository.replaceChunks(id, listOf(EmbeddedKnowledgeChunk(chunk, vector)))
        }

        val error = assertFailsWith<KnowledgeBaseError.IndexVersionMismatch> {
            SqliteKnowledgeRepository.open(
                path = dbPath,
                embeddingDimensions = embedding.dimensions,
                embeddingIndexVersion = "new@1:32:v1",
            )
        }
        assertTrue(error.message!!.contains("重建索引"))
    }

    @Test
    fun definitionQuestionMatchesFocusTermInChunk() = runTest {
        val dir = Files.createTempDirectory("orynode-sqlite-rag")
        val dbPath = dir.resolve("knowledge.sqlite")
        val embedding = DeterministicHashEmbedding(dimensions = 32)
        val id = UUID.randomUUID()
        SqliteKnowledgeRepository.open(
            path = dbPath,
            embeddingDimensions = embedding.dimensions,
            embeddingIndexVersion = embedding.descriptor.indexVersion,
        ).use { repository ->
            val definition =
                "RAG（Retrieval-Augmented Generation）是一种先检索资料再生成回答的方法。"
            val filler = "本手册还介绍了其它部署与运维细节，与检索增强无关。"
            repository.save(
                KnowledgeDocument(
                    id = id,
                    sourcePath = "/tmp/rag.txt",
                    title = "RAG说明",
                    contentHash = "rag",
                    state = KnowledgeDocument.State.Ready,
                    importedChunkCount = 2,
                ),
            )
            val defChunk = KnowledgeChunk(
                documentId = id,
                ordinal = 0,
                text = definition,
                tokenEstimate = 40,
            )
            val otherChunk = KnowledgeChunk(
                documentId = id,
                ordinal = 1,
                text = filler,
                tokenEstimate = 40,
            )
            val vectors = embedding.embedDocuments(listOf(definition, filler))
            repository.replaceChunks(
                id,
                listOf(
                    EmbeddedKnowledgeChunk(defChunk, vectors[0]),
                    EmbeddedKnowledgeChunk(otherChunk, vectors[1]),
                ),
            )
            val queryVector = embedding.embedQuery("什么是RAG")
            val hits = repository.search(
                query = "什么是RAG",
                embedding = queryVector,
                limit = 5,
                scope = KnowledgeSearchScope.All,
            )
            assertTrue(hits.isNotEmpty(), "expected lexical focus term RAG to surface the definition")
            assertTrue(hits.first().chunk.text.contains("Retrieval-Augmented"))
        }
    }

    @Test
    fun sameHashResumeKeepsUnpublishedCheckpoint() = runTest {
        val dir = Files.createTempDirectory("orynode-sqlite-resume")
        val dbPath = dir.resolve("knowledge.sqlite")
        val embedding = DeterministicHashEmbedding(dimensions = 32)
        val id = UUID.randomUUID()
        SqliteKnowledgeRepository.open(
            path = dbPath,
            embeddingDimensions = embedding.dimensions,
            embeddingIndexVersion = embedding.descriptor.indexVersion,
        ).use { repository ->
            repository.save(
                KnowledgeDocument(
                    id = id,
                    sourcePath = "/tmp/c.txt",
                    title = "续跑",
                    contentHash = "same",
                    state = KnowledgeDocument.State.Importing,
                ),
            )
            assertEquals(0, repository.prepareIndexJob(id, "same", "第一批 第二批", emptyList()))
            val first = KnowledgeChunk(documentId = id, ordinal = 0, text = "第一批", tokenEstimate = 4)
            val vector = embedding.embedDocuments(listOf(first.text)).first()
            repository.appendUnpublishedChunks(id, listOf(EmbeddedKnowledgeChunk(first, vector)))
            assertEquals(1, repository.prepareIndexJob(id, "same", "第一批 第二批", emptyList()))
            assertEquals(0, repository.prepareIndexJob(id, "changed", "新正文", emptyList()))
        }
    }
}
