package ai.orynode.mobile.application

import ai.orynode.mobile.domain.KnowledgeChunk
import ai.orynode.mobile.domain.KnowledgeSearchHit
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EvidencePackFocusTest {
    @Test
    fun queryFocusExtractsDefinitionNeedles() {
        assertTrue(QueryFocus.terms(from = "RAG的含义").any { it == "RAG" })
        assertTrue(QueryFocus.terms(from = "解释一下RAG").any { it == "RAG" })
        assertTrue(QueryFocus.terms(from = "RAG指什么").any { it == "RAG" })
        assertTrue(QueryFocus.terms(from = "什么是RAG").any { it == "RAG" })
    }

    @Test
    fun excerptPrefersDefinitionalOccurrenceOverEarlyMention() {
        val text = buildString {
            append("产品支持 RAG 检索问答。\n")
            append("更多功能见后文。\n")
            append("RAG 是指 Retrieval-Augmented Generation，先检索再生成。")
        }
        val excerpt = EvidencePackFocus.excerpt(from = text, query = "什么是RAG", maxCharacters = 120)
        assertTrue(excerpt.contains("是指") || excerpt.contains("Retrieval-Augmented"), excerpt)
    }

    @Test
    fun prioritizePrefersDefinitionChunkOverThinHeading() {
        val documentId = UUID.randomUUID()
        val heading = KnowledgeSearchHit(
            chunk = KnowledgeChunk(
                documentId = documentId,
                ordinal = 0,
                text = "RAG：\n",
                tokenEstimate = 4,
            ),
            documentTitle = "doc",
            score = 0.95f,
        )
        val definition = KnowledgeSearchHit(
            chunk = KnowledgeChunk(
                documentId = documentId,
                ordinal = 1,
                text = "RAG 是指结合检索与生成的问答方式，先查资料再回答。",
                tokenEstimate = 40,
            ),
            documentTitle = "doc",
            score = 0.80f,
        )
        val ranked = EvidencePackFocus.prioritize(listOf(heading, definition), query = "什么是RAG")
        assertEquals(1, ranked.first().chunk.ordinal)
    }
}
