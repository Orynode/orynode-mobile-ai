package ai.orynode.mobile.application

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CitationCanonicalizerTest {
    private val canonicalizer = CitationCanonicalizer()

    @Test
    fun dropsIllegalMarkersAndKeepsLegalOnes() {
        val result = canonicalizer.canonicalize(
            text = "结论见 [1]，不要看 [9]。",
            allowedIndices = setOf(1, 2),
        )
        assertEquals("结论见 [1]，不要看。", result.text)
        assertEquals(listOf(1), result.referencedIndices)
        assertFalse(result.text.contains("[9]"))
        assertTrue(result.text.contains("[1]"))
    }

    @Test
    fun doesNotRewriteLegalProse() {
        val body = "仓库承诺不上传文档 [1]"
        val result = canonicalizer.canonicalize(body, setOf(1))
        assertEquals(body, result.text)
        assertEquals(listOf(1), result.referencedIndices)
    }
}
