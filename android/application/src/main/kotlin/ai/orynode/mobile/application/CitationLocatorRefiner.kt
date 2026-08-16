package ai.orynode.mobile.application

import ai.orynode.mobile.domain.KnowledgePageSpan
import ai.orynode.mobile.domain.SourceLocator
import ai.orynode.mobile.domain.Utf16TextIndex
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Narrows chunk-level locators to the answer-relevant span inside [indexedText].
 *
 * Ranges use half-open [IntRange] via `start until end` (start inclusive, end exclusive
 * as `range.last + 1`), matching Kotlin UTF-16 [String] indices.
 */
object CitationLocatorRefiner {
    fun refine(
        indexedText: String?,
        chunkLocator: SourceLocator?,
        question: String,
        answerText: String,
        pageSpans: List<KnowledgePageSpan> = emptyList(),
        evidenceExcerpt: String? = null,
    ): SourceLocator? {
        if (indexedText.isNullOrEmpty()) return null
        val normalized = indexedText
            .replace("\r\n", "\n")
            .replace("\r", "\n")

        val needles = searchNeedles(
            question = question,
            answerText = answerText,
            evidenceExcerpt = evidenceExcerpt,
        )
        val hint = preferredStart(normalized, chunkLocator, pageSpans)
        val preferChunkProximity = when (chunkLocator) {
            is SourceLocator.Pdf -> false
            else -> true
        }
        val answerFingerprint = collapseWhitespace(
            answerText
                .replace(Regex("""\[\d+]"""), "")
                .replace("**", "")
                .replace("*", "")
                .replace("`", ""),
        )

        // If the user's question literally appears in the document, that span wins.
        // Otherwise long paraphrased answer fragments near chunk-start steal the locator.
        var best = bestMatch(
            needles = QueryFocus.terms(from = question),
            text = normalized,
            answerFingerprint = answerFingerprint,
            preferredStart = hint,
            preferChunkProximity = preferChunkProximity,
            questionBoost = 8_000,
        )
        if (best == null) {
            best = bestMatch(
                needles = needles,
                text = normalized,
                answerFingerprint = answerFingerprint,
                preferredStart = hint,
                preferChunkProximity = preferChunkProximity,
                questionBoost = 0,
            )
        }

        // Evidence excerpts are literal chunk windows; use them when paraphrased answers miss.
        if (best == null) {
            val excerptRange = locateEvidenceExcerpt(
                evidenceExcerpt,
                text = normalized,
                preferredStart = hint,
            )
            if (excerptRange != null) {
                val excerptEnd = excerptRange.last + 1
                // Keep fallback to a single focus point; plainText expands to line bounds below.
                val focus = min(
                    max(hint, excerptRange.first),
                    max(excerptRange.first, excerptEnd - 1),
                )
                best = ScoredRange(focus until min(focus + 1, excerptEnd), 0)
            }
        }

        val hit = best ?: return null
        val narrowed = expandPlainTextToLineBoundsIfNeeded(
            range = hit.range,
            text = normalized,
            template = chunkLocator,
        )
        return makeLocator(
            range = narrowed,
            text = normalized,
            template = chunkLocator,
            pageSpans = pageSpans,
        )
    }

    fun excerpt(
        at: SourceLocator?,
        indexedText: String?,
        pageSpans: List<KnowledgePageSpan> = emptyList(),
    ): String? {
        if (indexedText == null || at == null) return null
        val normalized = indexedText
            .replace("\r\n", "\n")
            .replace("\r", "\n")
        val lines = normalized.split('\n')
        return when (at) {
            is SourceLocator.Markdown -> {
                val start = max(1, at.startLine) - 1
                val end = min(lines.size, max(at.startLine, at.endLine))
                if (start >= lines.size) return null
                lines.subList(start, end).joinToString("\n").trim()
            }
            is SourceLocator.PlainText ->
                slice(normalized, start = at.startOffset, end = at.endOffset)
            is SourceLocator.Pdf -> {
                val span = pageSpans.firstOrNull { it.page == at.page } ?: return null
                val localStart = at.startOffset ?: 0
                val localEnd = at.endOffset ?: max(localStart, span.end - span.start)
                slice(
                    normalized,
                    start = span.start + localStart,
                    end = span.start + localEnd,
                )
            }
        }
    }

    // MARK: - Needles

    private fun searchNeedles(
        question: String,
        answerText: String,
        evidenceExcerpt: String?,
    ): List<String> {
        val needles = mutableListOf<String>()
        val seen = mutableSetOf<String>()

        fun append(value: String, minimum: Int = 2) {
            val trimmed = collapseWhitespace(value)
            if (trimmed.length >= minimum && seen.add(trimmed)) {
                needles += trimmed
            }
        }

        // Prefer literal evidence from the retrieved chunk — strongest TXT/MD anchor.
        // Do not add the full excerpt window: it is often ~chunk-sized and would keep
        // locators looking like "字符 0–511". Prefer sentence/prefix anchors instead.
        if (evidenceExcerpt != null) {
            val excerpt = evidenceExcerpt
                .replace("\r\n", "\n")
                .replace("\r", "\n")
                .trim()
            for (fragment in excerpt.split(Regex("""[\n。．.！!？?；;：:]"""))) {
                append(fragment, minimum = 4)
            }
            if (excerpt.length >= 8) {
                val prefix = excerpt.take(min(32, excerpt.length))
                if (seen.add(prefix)) {
                    needles += prefix
                }
                append(prefix, minimum = 8)
            }
        }

        val strippedAnswer = answerText
            .replace(Regex("""\[\d+]"""), "")
            .replace("**", "")
            .replace("*", "")
            .replace("`", "")

        // Answer fragments stay a bit longer to avoid noisy 1–2 char hits.
        for (fragment in strippedAnswer.split(Regex("""[\n。．.！!？?；;：:]"""))) {
            append(fragment, minimum = 4)
        }

        for (term in QueryFocus.terms(from = question)) {
            append(term)
        }

        return needles.sortedByDescending { it.length }
    }

    /** Find the evidence excerpt (or a durable prefix) near the ingest chunk. */
    private fun locateEvidenceExcerpt(
        evidenceExcerpt: String?,
        text: String,
        preferredStart: Int,
    ): IntRange? {
        if (evidenceExcerpt == null) return null
        val normalizedExcerpt = evidenceExcerpt
            .replace("\r\n", "\n")
            .replace("\r", "\n")
            .trim()
        if (normalizedExcerpt.length < 4) return null

        utf16Range(of = normalizedExcerpt, inText = text, preferredStart = preferredStart)?.let {
            return it
        }
        val prefixLen = min(48, normalizedExcerpt.length)
        if (prefixLen >= 8) {
            val prefix = normalizedExcerpt.take(prefixLen)
            utf16Range(of = prefix, inText = text, preferredStart = preferredStart)?.let {
                return it
            }
        }
        return null
    }

    /** Plain-text previews are line-oriented; expand a short hit to the containing line(s). */
    private fun expandPlainTextToLineBoundsIfNeeded(
        range: IntRange,
        text: String,
        template: SourceLocator?,
    ): IntRange {
        if (template !is SourceLocator.PlainText) return range
        val lowerBound = range.first
        val upperBound = range.last + 1
        val startLine = Utf16TextIndex.lineNumber(utf16Offset = lowerBound, text = text)
        val endLine = Utf16TextIndex.lineNumber(
            utf16Offset = max(lowerBound, upperBound - 1),
            text = text,
        )
        val lower = lineStartUTF16Offset(startLine, text)
        val lastLine = if (text.isEmpty()) {
            1
        } else {
            Utf16TextIndex.lineNumber(utf16Offset = max(0, text.length - 1), text = text)
        }
        val upper = if (endLine >= lastLine) {
            text.length
        } else {
            lineStartUTF16Offset(endLine + 1, text)
        }
        if (upper <= lower) return range
        return lower until upper
    }

    // MARK: - Matching

    private fun preferredStart(
        text: String,
        chunkLocator: SourceLocator?,
        pageSpans: List<KnowledgePageSpan>,
    ): Int = when (chunkLocator) {
        is SourceLocator.Markdown ->
            lineStartUTF16Offset(chunkLocator.startLine, text)
        is SourceLocator.PlainText ->
            max(0, chunkLocator.startOffset)
        is SourceLocator.Pdf -> {
            val span = pageSpans.firstOrNull { it.page == chunkLocator.page }
            if (span != null) {
                span.start + max(0, chunkLocator.startOffset ?: 0)
            } else {
                0
            }
        }
        null -> 0
    }

    private fun utf16Range(of: String, inText: String, preferredStart: Int): IntRange? {
        val needleLen = of.length
        if (needleLen < 2 || inText.length < needleLen) return null

        val upperBound = inText.length - needleLen
        val start = preferredStart.coerceIn(0, upperBound)
        match(of, inText, from = start)?.let { exact ->
            return exact until (exact + needleLen)
        }
        if (start > 0) {
            match(of, inText, from = 0)?.let { fallback ->
                return fallback until (fallback + needleLen)
            }
        }
        return null
    }

    private fun allMatches(needle: String, hay: String): List<Int> {
        if (needle.length < 2 || hay.length < needle.length) return emptyList()
        val starts = mutableListOf<Int>()
        var index = 0
        while (index <= hay.length - needle.length) {
            val found = match(needle, hay, from = index) ?: break
            starts += found
            index = found + max(1, needle.length)
        }
        return starts
    }

    /** Prefer answer-overlapping hits over merely being near the chunk page. */
    private fun bestMatch(
        needles: List<String>,
        text: String,
        answerFingerprint: String,
        preferredStart: Int,
        preferChunkProximity: Boolean,
        questionBoost: Int,
    ): ScoredRange? {
        var best: ScoredRange? = null
        for (needle in needles) {
            if (needle.length < 2) continue
            for (start in allMatches(needle, text)) {
                val range = start until (start + needle.length)
                val score = matchScore(
                    range = range,
                    text = text,
                    needleLength = needle.length,
                    answerFingerprint = answerFingerprint,
                    preferredStart = preferredStart,
                    preferChunkProximity = preferChunkProximity,
                ) + questionBoost
                if (best == null || score > best.score) {
                    best = ScoredRange(range, score)
                }
            }
        }
        return best
    }

    private fun matchScore(
        range: IntRange,
        text: String,
        needleLength: Int,
        answerFingerprint: String,
        preferredStart: Int,
        preferChunkProximity: Boolean,
    ): Int {
        var score = min(needleLength, 48) * 100
        val window = slice(
            text,
            start = max(0, range.first - 80),
            end = min(text.length, (range.last + 1) + 80),
        )
        if (window != null) {
            val collapsed = collapseWhitespace(window)
            score += sharedSubstringBonus(answerFingerprint, collapsed)
        }
        // PDF pages: answer overlap dominates. Chunk-page proximity is only a weak tie-break
        // for markdown/plain where the chunk span is trustworthy.
        if (preferChunkProximity) {
            val distance = abs(range.first - preferredStart)
            score -= min(distance / 50, 40)
        }
        return score
    }

    private fun sharedSubstringBonus(answer: String, window: String): Int {
        if (answer.length < 4 || window.length < 4) return 0
        var bonus = 0
        var index = 0
        val minLen = 4
        while (index + minLen <= answer.length) {
            val end = min(index + 12, answer.length)
            val piece = answer.substring(index, end)
            if (piece.length >= minLen && window.contains(piece)) {
                bonus += piece.length
                index += piece.length
            } else {
                index += 1
            }
        }
        return min(bonus, 200)
    }

    private fun match(needle: String, hay: String, from: Int): Int? {
        val upperBound = hay.length - needle.length
        if (upperBound < 0 || from > upperBound) return null
        for (start in from..upperBound) {
            var ok = true
            for (i in needle.indices) {
                if (hay[start + i] != needle[i]) {
                    ok = false
                    break
                }
            }
            if (ok) return start
        }
        return null
    }

    private fun makeLocator(
        range: IntRange,
        text: String,
        template: SourceLocator?,
        pageSpans: List<KnowledgePageSpan>,
    ): SourceLocator {
        val lower = range.first
        val upper = range.last + 1
        return when (template) {
            is SourceLocator.Pdf -> {
                // Contract: [n] stays bound to the ingest chunk page. Refinement may only
                // narrow offsets *within that page*. Never rebind to another page because
                // the answer text also appears elsewhere (TOC / earlier mention).
                val mapped = pdfLocator(range, pageSpans)
                if (mapped is SourceLocator.Pdf && mapped.page == template.page) {
                    SourceLocator.Pdf(
                        page = mapped.page,
                        startOffset = mapped.startOffset,
                        endOffset = mapped.endOffset,
                    )
                } else {
                    SourceLocator.Pdf(page = template.page, startOffset = null, endOffset = null)
                }
            }
            is SourceLocator.Markdown -> {
                val startLine = Utf16TextIndex.lineNumber(utf16Offset = lower, text = text)
                val endLine = Utf16TextIndex.lineNumber(
                    utf16Offset = max(lower, upper - 1),
                    text = text,
                )
                SourceLocator.Markdown(
                    headingPath = template.headingPath,
                    startLine = startLine,
                    endLine = max(startLine, endLine),
                )
            }
            null -> {
                val startLine = Utf16TextIndex.lineNumber(utf16Offset = lower, text = text)
                val endLine = Utf16TextIndex.lineNumber(
                    utf16Offset = max(lower, upper - 1),
                    text = text,
                )
                SourceLocator.Markdown(
                    headingPath = null,
                    startLine = startLine,
                    endLine = max(startLine, endLine),
                )
            }
            is SourceLocator.PlainText ->
                SourceLocator.PlainText(startOffset = lower, endOffset = upper)
        }
    }

    /** Maps a global `indexedText` UTF-16 range back to a page-local PDF locator. */
    fun pdfLocator(
        range: IntRange,
        pageSpans: List<KnowledgePageSpan>,
    ): SourceLocator? {
        if (pageSpans.isEmpty()) return null
        val lower = range.first
        val upper = range.last + 1
        val page = pageSpans.firstOrNull { span ->
            lower < span.end && upper > span.start
        } ?: pageSpans.firstOrNull() ?: return null
        val localStart = max(0, lower - page.start)
        val localEnd = max(localStart, min(upper, page.end) - page.start)
        return SourceLocator.Pdf(page = page.page, startOffset = localStart, endOffset = localEnd)
    }

    private fun slice(text: String, start: Int, end: Int): String? {
        val lower = start.coerceIn(0, text.length)
        val upper = end.coerceIn(lower, text.length)
        val value = text.substring(lower, upper).trim()
        return value.takeIf { it.isNotEmpty() }
    }

    private fun lineStartUTF16Offset(line: Int, text: String): Int {
        if (line <= 1) return 0
        var currentLine = 1
        var offset = 0
        while (offset < text.length) {
            if (text[offset] == '\n') {
                currentLine += 1
                offset += 1
                if (currentLine == line) return offset
                continue
            }
            offset += 1
        }
        return offset
    }

    private fun collapseWhitespace(text: String): String =
        text
            .replace('\u00A0', ' ')
            .split(Regex("\\s+"))
            .filter { it.isNotEmpty() }
            .joinToString(" ")
            .trim()

    private data class ScoredRange(val range: IntRange, val score: Int)
}
