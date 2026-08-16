package ai.orynode.mobile.application

import ai.orynode.mobile.domain.KnowledgeCitation
import ai.orynode.mobile.domain.KnowledgePageSpan
import ai.orynode.mobile.domain.SourceLocator
import ai.orynode.mobile.domain.Utf16TextIndex

/**
 * Application-owned Ask post-processing for locators/excerpts.
 * Features may still resolve PDF printed page labels via platform PDF APIs.
 */
data class EnrichedCitationLocator(
    val locator: SourceLocator?,
    val excerpt: String,
    val locatorLabel: String? = null,
)

object CitationLocatorEnricher {
    fun enrich(
        citation: KnowledgeCitation,
        indexedText: String?,
        pageSpans: List<KnowledgePageSpan>,
        question: String,
        answerText: String,
    ): EnrichedCitationLocator {
        val ingest = citation.locator
        val refined = CitationLocatorRefiner.refine(
            indexedText = indexedText,
            chunkLocator = ingest,
            question = question,
            answerText = answerText,
            pageSpans = pageSpans,
            evidenceExcerpt = citation.excerpt,
        )
        val locator = lockedPDFPage(refined = refined, ingest = ingest) ?: ingest
        val excerpt = CitationLocatorRefiner.excerpt(
            at = locator,
            indexedText = indexedText,
            pageSpans = pageSpans,
        ) ?: citation.excerpt
        return EnrichedCitationLocator(
            locator = locator,
            excerpt = excerpt,
            locatorLabel = displayLabel(locator = locator, indexedText = indexedText),
        )
    }

    /** Keep PDF citations on the ingest page; allow same-page offset narrowing only. */
    fun lockedPDFPage(
        refined: SourceLocator?,
        ingest: SourceLocator?,
    ): SourceLocator? {
        if (ingest !is SourceLocator.Pdf) return refined
        val ingestPage = ingest.page
        if (refined !is SourceLocator.Pdf) {
            return SourceLocator.Pdf(page = ingestPage, startOffset = null, endOffset = null)
        }
        return if (refined.page == ingestPage) {
            SourceLocator.Pdf(
                page = ingestPage,
                startOffset = refined.startOffset,
                endOffset = refined.endOffset,
            )
        } else {
            SourceLocator.Pdf(page = ingestPage, startOffset = null, endOffset = null)
        }
    }

    /** TXT character offsets are opaque in the UI; prefer the same line labels as Markdown. */
    fun displayLabel(locator: SourceLocator?, indexedText: String?): String? {
        if (locator == null) return null
        if (locator is SourceLocator.PlainText && !indexedText.isNullOrEmpty()) {
            val normalized = indexedText
                .replace("\r\n", "\n")
                .replace("\r", "\n")
            val startLine = Utf16TextIndex.lineNumber(
                utf16Offset = locator.startOffset,
                text = normalized,
            )
            val endLine = Utf16TextIndex.lineNumber(
                utf16Offset = maxOf(locator.startOffset, locator.endOffset - 1),
                text = normalized,
            )
            return if (startLine == endLine) {
                "第 $startLine 行"
            } else {
                "第 $startLine–$endLine 行"
            }
        }
        return locator.shortLabel
    }
}
