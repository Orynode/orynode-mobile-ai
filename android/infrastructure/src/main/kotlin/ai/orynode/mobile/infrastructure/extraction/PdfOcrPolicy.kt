package ai.orynode.mobile.infrastructure.extraction

/**
 * Shared OCR gate for sparse/empty PDF text layers (parity with iOS
 * `LocalKnowledgeTextExtractor.shouldAttemptOCR` / `selectPageText`).
 */
object PdfOcrPolicy {
    fun shouldAttemptOcr(nativePageText: String): Boolean {
        val trimmed = nativePageText.trim()
        if (trimmed.isEmpty()) return true
        if (trimmed.length < 48) return true
        if (meaningfulScalarCount(trimmed) < 16) return true
        return false
    }

    fun selectPageText(native: String, ocr: String): String {
        val nativeTrimmed = native.trim()
        val ocrTrimmed = ocr.trim()
        if (ocrTrimmed.isEmpty()) return native
        if (nativeTrimmed.isEmpty()) return ocr
        if (ocrTrimmed.length >= nativeTrimmed.length + 24) return ocr
        if (ocrTrimmed.length >= maxOf(40, nativeTrimmed.length * 2)) return ocr
        val nativeMeaningful = meaningfulScalarCount(nativeTrimmed)
        val ocrMeaningful = meaningfulScalarCount(ocrTrimmed)
        if (ocrMeaningful >= nativeMeaningful + 12) return ocr
        return native
    }

    fun normalize(text: String): String =
        text.replace("\r\n", "\n").replace("\r", "\n")

    private fun meaningfulScalarCount(text: String): Int {
        var count = 0
        var index = 0
        while (index < text.length) {
            val cp = text.codePointAt(index)
            if (Character.isLetterOrDigit(cp) ||
                cp in 0x4E00..0x9FFF ||
                cp in 0x3400..0x4DBF
            ) {
                count += 1
            }
            index += Character.charCount(cp)
        }
        return count
    }
}
