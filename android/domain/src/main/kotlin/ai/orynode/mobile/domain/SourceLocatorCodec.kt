package ai.orynode.mobile.domain

/**
 * Stable JSON snapshot for [SourceLocator] — shared by SQLite persistence and chat history.
 * Schema matches historical infrastructure codec (`type` + type-specific fields).
 */
object SourceLocatorCodec {
    fun encode(locator: SourceLocator?): String? {
        locator ?: return null
        return when (locator) {
            is SourceLocator.Pdf -> buildString {
                append("{\"type\":\"pdf\",\"page\":")
                append(locator.page)
                locator.startOffset?.let {
                    append(",\"startOffset\":")
                    append(it)
                }
                locator.endOffset?.let {
                    append(",\"endOffset\":")
                    append(it)
                }
                append('}')
            }
            is SourceLocator.Markdown -> buildString {
                append("{\"type\":\"markdown\"")
                locator.headingPath?.let { path ->
                    append(",\"headingPath\":[")
                    append(path.joinToString(",") { "\"${escape(it)}\"" })
                    append(']')
                }
                append(",\"startLine\":")
                append(locator.startLine)
                append(",\"endLine\":")
                append(locator.endLine)
                append('}')
            }
            is SourceLocator.PlainText ->
                "{\"type\":\"plainText\",\"startOffset\":${locator.startOffset},\"endOffset\":${locator.endOffset}}"
        }
    }

    fun decode(raw: String?): SourceLocator? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            when (stringField(raw, "type")) {
                "pdf" -> SourceLocator.Pdf(
                    page = intField(raw, "page") ?: return null,
                    startOffset = intField(raw, "startOffset"),
                    endOffset = intField(raw, "endOffset"),
                )
                "markdown" -> SourceLocator.Markdown(
                    headingPath = stringArrayField(raw, "headingPath"),
                    startLine = intField(raw, "startLine") ?: return null,
                    endLine = intField(raw, "endLine") ?: return null,
                )
                "plainText" -> SourceLocator.PlainText(
                    startOffset = intField(raw, "startOffset") ?: return null,
                    endOffset = intField(raw, "endOffset") ?: return null,
                )
                else -> null
            }
        }.getOrNull()
    }

    private fun escape(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"")

    private fun stringField(json: String, key: String): String? {
        val match = Regex(""""$key"\s*:\s*"((?:\\.|[^"\\])*)"""").find(json) ?: return null
        return match.groupValues[1]
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
    }

    private fun intField(json: String, key: String): Int? {
        val match = Regex(""""$key"\s*:\s*(-?\d+)""").find(json) ?: return null
        return match.groupValues[1].toIntOrNull()
    }

    private fun stringArrayField(json: String, key: String): List<String>? {
        val match = Regex(""""$key"\s*:\s*\[([^]]*)]""").find(json) ?: return null
        val body = match.groupValues[1].trim()
        if (body.isEmpty()) return emptyList()
        return Regex(""""((?:\\.|[^"\\])*)"""").findAll(body).map {
            it.groupValues[1].replace("\\\"", "\"").replace("\\\\", "\\")
        }.toList()
    }
}
