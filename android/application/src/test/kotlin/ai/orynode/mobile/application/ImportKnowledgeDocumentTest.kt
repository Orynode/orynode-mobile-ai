package ai.orynode.mobile.application

import ai.orynode.mobile.domain.KnowledgeDocument
import ai.orynode.mobile.domain.KnowledgeExtraction
import ai.orynode.mobile.domain.KnowledgeTextExtractor
import ai.orynode.mobile.domain.TextEmbedding
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ImportKnowledgeDocumentTest {
    @Test
    fun publishesReadyDocumentAfterEmbedding() = runTest {
        val repository = FakeKnowledgeRepository()
        val importer = ImportKnowledgeDocument(
            extractor = FixedExtractor("标题\n\nOrynode 不会上传文档。"),
            chunker = StructuredKnowledgeChunker(),
            embedding = UnitEmbedding(),
            repository = repository,
        )
        val path = Files.createTempFile("orynode", ".txt")
        val document = importer(path)
        assertEquals(KnowledgeDocument.State.Ready, document.state)
        assertTrue(document.importedChunkCount >= 1)
        assertEquals(document.importedChunkCount, repository.chunkCount())
        assertTrue(document.contentHash.isNotEmpty())
    }

    @Test
    fun rejectsEmptyDocument() = runTest {
        val repository = FakeKnowledgeRepository()
        val importer = ImportKnowledgeDocument(
            extractor = FixedExtractor("   \n"),
            chunker = StructuredKnowledgeChunker(),
            embedding = UnitEmbedding(),
            repository = repository,
        )
        val path = Files.createTempFile("orynode", ".txt")
        val error = runCatching { importer(path) }.exceptionOrNull()
        assertTrue(error is ai.orynode.mobile.domain.KnowledgeBaseError.EmptyDocument)
        val stored = repository.documents().single()
        assertEquals(KnowledgeDocument.State.Failed, stored.state)
    }
}

private class FixedExtractor(private val text: String) : KnowledgeTextExtractor {
    override suspend fun extract(from: java.nio.file.Path) =
        KnowledgeExtraction(kind = KnowledgeExtraction.Kind.PlainText, indexedText = text)
}

private class UnitEmbedding : TextEmbedding {
    override val name = "unit"
    override val dimensions = 8
    override suspend fun embed(texts: List<String>): List<FloatArray> =
        texts.map { FloatArray(dimensions) { 0.1f } }
}
