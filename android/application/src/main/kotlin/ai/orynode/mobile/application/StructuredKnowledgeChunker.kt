package ai.orynode.mobile.application

import ai.orynode.mobile.domain.KnowledgeChunk
import ai.orynode.mobile.domain.KnowledgeChunker
import ai.orynode.mobile.domain.KnowledgeExtraction
import ai.orynode.mobile.domain.KnowledgeIndexContract
import ai.orynode.mobile.domain.KnowledgePageSpan
import ai.orynode.mobile.domain.SourceLocator
import ai.orynode.mobile.domain.Utf16TextIndex
import java.util.UUID
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

class StructuredKnowledgeChunker(
    targetCharacters: Int = 520,
    overlapCharacters: Int = 64,
) : KnowledgeChunker {
    val targetCharacters: Int = max(200, targetCharacters)
    val overlapCharacters: Int = max(0, min(overlapCharacters, this.targetCharacters / 3))

    val contractVersion: String
        get() = KnowledgeIndexContract.chunkerVersion(this.targetCharacters, this.overlapCharacters)

    override fun chunks(documentId: UUID, extraction: KnowledgeExtraction): List<KnowledgeChunk> {
        val normalized = extraction.indexedText.trim()
        if (normalized.isEmpty()) return emptyList()

        val text = extraction.indexedText.replace("\r\n", "\n").replace("\r", "\n")
        val output = mutableListOf<KnowledgeChunk>()
        var ordinal = 0
        for (section in sections(text)) {
            for (piece in split(section.body, section.bodyStart)) {
                val range = piece.startUtf16 until (piece.startUtf16 + piece.text.length)
                output += KnowledgeChunk(
                    documentId = documentId,
                    ordinal = ordinal,
                    heading = section.heading,
                    text = piece.text,
                    tokenEstimate = estimateTokens(piece.text),
                    locator = makeLocator(
                        kind = extraction.kind,
                        text = text,
                        range = range,
                        heading = section.heading,
                        pageSpans = extraction.pageSpans,
                    ),
                )
                ordinal += 1
            }
        }
        return output
    }

    private data class Section(val heading: String?, val body: String, val bodyStart: Int)
    private data class Piece(val text: String, val startUtf16: Int)

    private fun sections(text: String): List<Section> {
        val result = mutableListOf<Section>()
        var heading: String? = null
        val body = mutableListOf<String>()
        var bodyStart = 0
        var cursor = 0
        var collectingStart: Int? = null
        val lines = text.split("\n")

        fun flush() {
            val raw = body.joinToString("\n")
            val value = raw.trim()
            if (value.isNotEmpty()) {
                val hint = collectingStart ?: bodyStart
                val start = utf16Offset(value, text, hint) ?: hint
                result += Section(heading, value, start)
            }
            body.clear()
            collectingStart = null
        }

        for ((index, line) in lines.withIndex()) {
            val lineStart = cursor
            val trimmed = line.trim()
            if (trimmed.startsWith("#")) {
                flush()
                heading = trimmed.dropWhile { it == '#' || it == ' ' }
                bodyStart = lineStart + line.length + if (index < lines.lastIndex) 1 else 0
            } else {
                if (collectingStart == null) collectingStart = lineStart
                body += line
            }
            cursor += line.length
            if (index < lines.lastIndex) cursor += 1
        }
        flush()
        return if (result.isEmpty()) {
            listOf(Section(null, text.trim(), 0))
        } else {
            result
        }
    }

    private fun split(sectionBody: String, baseOffset: Int): List<Piece> {
        val trimmed = sectionBody.trim()
        if (trimmed.isEmpty()) return emptyList()

        val trimRelative = utf16Offset(trimmed, sectionBody, 0) ?: 0
        val trimmedBase = baseOffset + trimRelative
        if (trimmed.length <= targetCharacters) {
            return listOf(Piece(trimmed, trimmedBase))
        }

        val pieces = mutableListOf<Piece>()
        var current = ""
        var currentStartRel: Int? = null
        var paraUtf16Cursor = 0
        val paragraphs = trimmed.split("\n\n")

        fun emitCurrent() {
            val pieceText = current.trim()
            if (pieceText.isEmpty()) {
                current = ""
                currentStartRel = null
                return
            }
            val hint = currentStartRel ?: 0
            utf16Offset(pieceText, trimmed, hint)?.let { rel ->
                pieces += Piece(pieceText, trimmedBase + rel)
            }
            current = ""
            currentStartRel = null
        }

        for ((index, paragraph) in paragraphs.withIndex()) {
            val paraStart = paraUtf16Cursor
            val paraUtf16Len = paragraph.length
            if (current.isEmpty()) currentStartRel = paraStart
            val combinedLen = current.length + (if (current.isEmpty()) 0 else 2) + paraUtf16Len
            if (combinedLen <= targetCharacters) {
                current += (if (current.isEmpty()) "" else "\n\n") + paragraph
            } else {
                emitCurrent()
                if (paraUtf16Len <= targetCharacters) {
                    current = paragraph
                    currentStartRel = paraStart
                } else {
                    var local = 0
                    while (local < paraUtf16Len) {
                        val end = min(local + targetCharacters, paraUtf16Len)
                        val startIdx = paraStart + local
                        val endIdx = paraStart + end
                        if (startIdx < 0 || endIdx > trimmed.length) break
                        val slice = trimmed.substring(startIdx, endIdx).trim()
                        if (slice.isNotEmpty()) {
                            utf16Offset(slice, trimmed, paraStart + local)?.let { rel ->
                                pieces += Piece(slice, trimmedBase + rel)
                            }
                        }
                        if (end >= paraUtf16Len) break
                        local = max(end - overlapCharacters, local + 1)
                    }
                }
            }
            paraUtf16Cursor += paraUtf16Len
            if (index < paragraphs.lastIndex) paraUtf16Cursor += 2
        }
        emitCurrent()
        return pieces
    }

    private fun makeLocator(
        kind: KnowledgeExtraction.Kind,
        text: String,
        range: IntRange,
        heading: String?,
        pageSpans: List<KnowledgePageSpan>,
    ): SourceLocator {
        val lower = range.first
        val upper = range.last + 1
        return when (kind) {
            KnowledgeExtraction.Kind.Pdf -> {
                val page = pageSpans.firstOrNull { span ->
                    lower < span.end && upper > span.start
                } ?: pageSpans.firstOrNull()
                if (page != null) {
                    val localStart = max(0, lower - page.start)
                    val localEnd = max(localStart, min(upper, page.end) - page.start)
                    SourceLocator.Pdf(page = page.page, startOffset = localStart, endOffset = localEnd)
                } else {
                    SourceLocator.Pdf(page = 1, startOffset = lower, endOffset = upper)
                }
            }
            KnowledgeExtraction.Kind.Markdown -> {
                val startLine = lineNumber(lower, text)
                val endLine = lineNumber(max(lower, upper - 1), text)
                SourceLocator.Markdown(
                    headingPath = heading?.let { listOf(it) },
                    startLine = startLine,
                    endLine = max(startLine, endLine),
                )
            }
            KnowledgeExtraction.Kind.PlainText ->
                SourceLocator.PlainText(startOffset = lower, endOffset = upper)
        }
    }

    companion object {
        fun estimateTokens(text: String): Int {
            val scalars = text.codePointCount(0, text.length)
            return max(1, ceil(scalars / 3.2).toInt())
        }

        fun lineNumber(utf16Offset: Int, text: String): Int =
            Utf16TextIndex.lineNumber(utf16Offset, text)

        fun utf16Offset(needle: String, haystack: String, preferredStart: Int): Int? {
            if (needle.isEmpty() || haystack.length < needle.length) return null
            val from = preferredStart.coerceIn(0, max(0, haystack.length - needle.length))
            val index = haystack.indexOf(needle, startIndex = from)
            return if (index >= 0) index else null
        }
    }
}
