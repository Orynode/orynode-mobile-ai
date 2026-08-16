package ai.orynode.mobile.application

/** Closed-set citation check: drop illegal `[n]`, leave legal markers and prose untouched. */
class CitationCanonicalizer {
    data class Result(
        val text: String,
        val referencedIndices: List<Int>,
    )

    fun canonicalize(text: String, allowedIndices: Set<Int>): Result {
        val normalized = text.replace("\r\n", "\n")
        val marker = Regex("""\[(\d+)]""")
        val referenced = mutableListOf<Int>()
        val seen = mutableSetOf<Int>()
        val removals = mutableListOf<IntRange>()

        for (match in marker.findAll(normalized)) {
            val number = match.groupValues[1].toIntOrNull() ?: continue
            if (number in allowedIndices) {
                if (seen.add(number)) referenced += number
            } else {
                removals += match.range
            }
        }

        val builder = StringBuilder(normalized)
        for (range in removals.asReversed()) {
            builder.deleteRange(range.first, range.last + 1)
        }
        var cleaned = builder.toString()
            .replace(Regex("""[ \t\u00a0\u3000]+([。．.！!？?；;，,])"""), "$1")
            .replace(Regex("""[ \t\u00a0\u3000]{2,}"""), " ")
        return Result(
            text = cleaned.trim(),
            referencedIndices = referenced,
        )
    }
}
