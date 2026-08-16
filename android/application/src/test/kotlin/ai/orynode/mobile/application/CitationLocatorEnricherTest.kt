package ai.orynode.mobile.application

import ai.orynode.mobile.domain.KnowledgeCitation
import ai.orynode.mobile.domain.SourceLocator
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CitationLocatorEnricherTest {
    @Test
    fun lockedPdfPageKeepsIngestPage() {
        val ingest = SourceLocator.Pdf(page = 3, startOffset = null, endOffset = null)
        val refined = SourceLocator.Pdf(page = 7, startOffset = 10, endOffset = 20)
        val locked = CitationLocatorEnricher.lockedPDFPage(refined, ingest)
        assertEquals(SourceLocator.Pdf(page = 3, startOffset = null, endOffset = null), locked)
    }

    @Test
    fun enrichNarrowsPlainTextToLineLabel() {
        val text = "第一行\nRAG 是指检索增强生成。\n第三行"
        val citation = KnowledgeCitation(
            index = 1,
            documentId = UUID.randomUUID(),
            documentTitle = "doc",
            chunkId = UUID.randomUUID(),
            excerpt = "RAG 是指检索增强生成。",
            locator = SourceLocator.PlainText(0, text.length),
        )
        val result = CitationLocatorEnricher.enrich(
            citation = citation,
            indexedText = text,
            pageSpans = emptyList(),
            question = "什么是RAG",
            answerText = "RAG 是指检索增强生成。[1]",
        )
        assertTrue(result.locatorLabel?.contains("行") == true, result.locatorLabel)
        assertTrue(result.excerpt.contains("RAG"))
    }
}
