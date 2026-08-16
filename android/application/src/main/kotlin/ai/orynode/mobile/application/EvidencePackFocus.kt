package ai.orynode.mobile.application

import ai.orynode.mobile.domain.KnowledgeSearchHit

/**
 * Query-aware evidence packaging: prefer hits that literally overlap the question,
 * and window excerpts around that overlap. Must not starve the model with a thin heading.
 */
object EvidencePackFocus {
    const val SCORING_WINDOW_CHARACTERS = 160

    private val definitionCueRegex = Regex(
        "是指|指的是|定义为|又称|全称|即是|也就是|是一[种个类]|retrieval[- ]augmented",
        RegexOption.IGNORE_CASE,
    )

    fun prioritize(hits: List<KnowledgeSearchHit>, query: String): List<KnowledgeSearchHit> {
        val needles = QueryFocus.terms(from = query)
        if (needles.isEmpty()) return hits
        return hits.withIndex()
            .sortedWith { lhs, rhs ->
                val left = overlapScore(lhs.value.chunk.text, needles)
                val right = overlapScore(rhs.value.chunk.text, needles)
                when {
                    left != right -> right.compareTo(left)
                    lhs.value.score != rhs.value.score -> rhs.value.score.compareTo(lhs.value.score)
                    else -> lhs.index.compareTo(rhs.index)
                }
            }
            .map { it.value }
    }

    fun excerpt(from: String, query: String, maxCharacters: Int): String {
        val budget = maxOf(32, maxCharacters)
        val normalized = from.replace("\r\n", "\n").replace("\r", "\n")
        if (normalized.isEmpty()) return ""
        val needles = QueryFocus.terms(from = query)
        val best = bestMatch(normalized, needles)
        if (best != null) {
            return characterWindow(
                text = normalized,
                matchStart = best.start,
                matchEnd = best.end,
                maxCharacters = budget,
            )
        }
        return if (normalized.length <= budget) normalized else normalized.take(budget)
    }

    private fun overlapScore(text: String, needles: List<String>): Int =
        bestMatch(text, needles)?.score ?: 0

    private data class Match(val start: Int, val end: Int, val score: Int)

    private fun bestMatch(text: String, needles: List<String>): Match? {
        var best: Match? = null
        for (needle in needles) {
            var from = 0
            while (true) {
                val index = text.indexOf(needle, startIndex = from)
                if (index < 0) break
                val end = index + needle.length
                val window = characterWindow(
                    text = text,
                    matchStart = index,
                    matchEnd = end,
                    maxCharacters = SCORING_WINDOW_CHARACTERS,
                )
                var score = needle.length * 10 + minOf(window.length, SCORING_WINDOW_CHARACTERS)
                val line = lineContaining(text, index, end)
                if (line.endsWith("：") || line.endsWith(":")) score -= 80
                if (line.length < 20) score -= 40
                if (definitionCueRegex.containsMatchIn(window)) score += 60
                if (best == null || score > best.score) {
                    best = Match(index, end, score)
                }
                from = index + 1
            }
        }
        return best
    }

    private fun lineContaining(text: String, matchStart: Int, matchEnd: Int): String {
        val lineStart = text.lastIndexOf('\n', startIndex = (matchStart - 1).coerceAtLeast(0))
            .let { if (it < 0 || matchStart == 0) 0 else it + 1 }
        val lineEnd = text.indexOf('\n', startIndex = matchEnd).let { if (it < 0) text.length else it }
        return text.substring(lineStart, lineEnd).trim()
    }

    private fun characterWindow(
        text: String,
        matchStart: Int,
        matchEnd: Int,
        maxCharacters: Int,
    ): String {
        val matchLen = matchEnd - matchStart
        val backPad = maxOf(0, (maxCharacters - matchLen) / 4)
        val forwardPad = maxOf(0, maxCharacters - matchLen - backPad)
        var start = (matchStart - backPad).coerceAtLeast(0)
        var end = (matchEnd + forwardPad).coerceAtMost(text.length)
        val lineStart = text.lastIndexOf('\n', startIndex = (start - 1).coerceAtLeast(0))
        if (lineStart >= 0) {
            val candidate = lineStart + 1
            if (end - candidate <= maxCharacters + 48) start = candidate
        } else if (start != 0 && end <= maxCharacters + 48) {
            start = 0
        }
        val lineEnd = text.indexOf('\n', startIndex = end)
        if (lineEnd >= 0 && lineEnd - start <= maxCharacters + 48) {
            end = lineEnd
        }
        var sliced = text.substring(start, end).trim()
        if (sliced.length > maxCharacters) sliced = sliced.take(maxCharacters)
        return sliced
    }
}
