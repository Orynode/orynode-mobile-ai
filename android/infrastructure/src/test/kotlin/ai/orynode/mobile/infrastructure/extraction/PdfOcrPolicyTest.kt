package ai.orynode.mobile.infrastructure.extraction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfOcrPolicyTest {
    @Test
    fun emptyOrSparseTextTriggersOcr() {
        assertTrue(PdfOcrPolicy.shouldAttemptOcr(""))
        assertTrue(PdfOcrPolicy.shouldAttemptOcr("  \n\t "))
        assertTrue(PdfOcrPolicy.shouldAttemptOcr("...."))
        assertTrue(PdfOcrPolicy.shouldAttemptOcr("第1页"))
        assertFalse(
            PdfOcrPolicy.shouldAttemptOcr(
                "这是一段足够长且有意义的中文正文，用来确认不会误触发 OCR 回退路径的完整句子，再补一些汉字确保超过四十八个字符。",
            ),
        )
    }

    @Test
    fun prefersRicherOcrText() {
        assertEquals("native", PdfOcrPolicy.selectPageText("native", ""))
        assertEquals("ocr-only", PdfOcrPolicy.selectPageText("", "ocr-only"))
        val native = "短"
        val ocr = "扫描页识别出的离线知识库文字内容明显更长"
        assertEquals(ocr, PdfOcrPolicy.selectPageText(native, ocr))
    }
}
