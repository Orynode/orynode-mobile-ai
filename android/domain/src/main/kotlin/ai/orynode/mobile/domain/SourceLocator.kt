package ai.orynode.mobile.domain

/**
 * Stable jump target written at ingest. Citations copy it as a snapshot.
 * PDF page is 1-based. Offsets are UTF-16 units, matching Kotlin [String] indices.
 */
sealed class SourceLocator {
    data class Pdf(
        val page: Int,
        val startOffset: Int? = null,
        val endOffset: Int? = null,
    ) : SourceLocator()

    data class Markdown(
        val headingPath: List<String>? = null,
        val startLine: Int,
        val endLine: Int,
    ) : SourceLocator()

    data class PlainText(
        val startOffset: Int,
        val endOffset: Int,
    ) : SourceLocator()

    val shortLabel: String
        get() = when (this) {
            is Pdf -> "第 $page 页"
            is Markdown -> {
                val heading = headingPath?.lastOrNull().orEmpty()
                when {
                    heading.isNotEmpty() -> heading
                    startLine == endLine -> "第 $startLine 行"
                    else -> "第 $startLine–$endLine 行"
                }
            }
            is PlainText -> "字符 $startOffset–$endOffset"
        }
}

object Utf16TextIndex {
    fun lineNumber(utf16Offset: Int, text: String): Int {
        if (utf16Offset <= 0) return 1
        val clamped = utf16Offset.coerceIn(0, text.length)
        var line = 1
        var offset = 0
        while (offset < clamped) {
            if (text[offset] == '\n') line += 1
            offset += 1
        }
        return line
    }
}
