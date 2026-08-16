package ai.orynode.mobile.application

internal object QueryFocus {
    fun terms(from: String): List<String> {
        val needles = mutableListOf<String>()
        val seen = mutableSetOf<String>()

        fun append(value: String, minimum: Int = 2) {
            val trimmed = collapseWhitespace(value)
            if (trimmed.length >= minimum && seen.add(trimmed)) {
                needles += trimmed
            }
        }

        val trimmed = collapseWhitespace(from)
        append(trimmed)
        if (trimmed.endsWith("地址") && trimmed.length > 2) {
            append(trimmed.dropLast(2))
        }
        for (focus in questionFocusTerms(trimmed)) {
            append(focus)
        }
        return needles.sortedByDescending { it.length }
    }

    fun collapseWhitespace(text: String): String =
        text.replace('\u00A0', ' ')
            .split(Regex("\\s+"))
            .filter { it.isNotEmpty() }
            .joinToString(" ")
            .trim()

    private fun questionFocusTerms(question: String): List<String> {
        val patterns = listOf(
            Regex("^什么是\\s*(.+)$"),
            Regex("^(.+?)\\s*是什么[?？]?$"),
            Regex("^(.+?)\\s*(?:的)?(?:含义|意思|定义)[?？]?$"),
            Regex("^(.+?)\\s*指(?:的是)?什么[?？]?$"),
            Regex("^解释(?:一下)?\\s*(.+)$"),
            Regex("^请问\\s*(.+)$"),
            Regex("^介绍(?:一下)?\\s*(.+)$"),
        )
        return patterns.mapNotNull { pattern ->
            pattern.matchEntire(question)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }
        }
    }
}
