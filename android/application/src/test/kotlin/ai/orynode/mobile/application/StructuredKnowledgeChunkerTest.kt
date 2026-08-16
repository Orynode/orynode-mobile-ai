package ai.orynode.mobile.application

import ai.orynode.mobile.domain.KnowledgeExtraction
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StructuredKnowledgeChunkerTest {
    @Test
    fun splitsMarkdownByHeading() {
        val text = """
            # 隐私
            不会上传文档到云端。
            # 容量
            首版上限一万个片段。
        """.trimIndent()
        val chunks = StructuredKnowledgeChunker().chunks(
            documentId = UUID.randomUUID(),
            extraction = KnowledgeExtraction(KnowledgeExtraction.Kind.Markdown, text),
        )
        assertEquals(2, chunks.size)
        assertEquals("隐私", chunks[0].heading)
        assertTrue(chunks[0].text.contains("不会上传"))
        assertEquals("容量", chunks[1].heading)
    }

    @Test
    fun estimatesAtLeastOneToken() {
        assertEquals(1, StructuredKnowledgeChunker.estimateTokens("嗨"))
    }
}
