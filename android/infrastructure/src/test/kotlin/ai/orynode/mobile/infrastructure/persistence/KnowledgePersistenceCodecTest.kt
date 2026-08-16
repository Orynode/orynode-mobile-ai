package ai.orynode.mobile.infrastructure.persistence

import ai.orynode.mobile.domain.KnowledgePageSpan
import ai.orynode.mobile.domain.SourceLocator
import kotlin.test.Test
import kotlin.test.assertEquals

class KnowledgePersistenceCodecTest {
    @Test
    fun roundTripsLocatorsAndPageSpans() {
        val pdf = SourceLocator.Pdf(page = 3, startOffset = 1, endOffset = 8)
        val markdown = SourceLocator.Markdown(
            headingPath = listOf("隐私"),
            startLine = 2,
            endLine = 4,
        )
        val plain = SourceLocator.PlainText(10, 20)
        assertEquals(pdf, KnowledgePersistenceCodec.decodeLocator(KnowledgePersistenceCodec.encodeLocator(pdf)))
        assertEquals(
            markdown,
            KnowledgePersistenceCodec.decodeLocator(KnowledgePersistenceCodec.encodeLocator(markdown)),
        )
        assertEquals(
            plain,
            KnowledgePersistenceCodec.decodeLocator(KnowledgePersistenceCodec.encodeLocator(plain)),
        )

        val spans = listOf(KnowledgePageSpan(1, 0, 40), KnowledgePageSpan(2, 40, 80))
        assertEquals(spans, KnowledgePersistenceCodec.decodePageSpans(KnowledgePersistenceCodec.encodePageSpans(spans)))
    }
}
