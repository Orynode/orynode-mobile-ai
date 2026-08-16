package ai.orynode.mobile.domain

import kotlin.test.Test
import kotlin.test.assertEquals

class SourceLocatorCodecTest {
    @Test
    fun roundTripsPdfMarkdownAndPlain() {
        val samples = listOf(
            SourceLocator.Pdf(page = 2, startOffset = 4, endOffset = 18),
            SourceLocator.Markdown(headingPath = listOf("A", "B"), startLine = 3, endLine = 5),
            SourceLocator.PlainText(startOffset = 10, endOffset = 40),
        )
        for (locator in samples) {
            val encoded = SourceLocatorCodec.encode(locator)
            assertEquals(locator, SourceLocatorCodec.decode(encoded))
        }
    }
}
