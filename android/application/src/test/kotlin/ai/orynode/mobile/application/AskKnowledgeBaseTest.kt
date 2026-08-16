package ai.orynode.mobile.application

import ai.orynode.mobile.domain.KnowledgeAnswerGenerator
import ai.orynode.mobile.domain.KnowledgeChunk
import ai.orynode.mobile.domain.KnowledgeSearchHit
import ai.orynode.mobile.domain.KnowledgeSearchScope
import ai.orynode.mobile.domain.OnDeviceRagBudget
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AskKnowledgeBaseTest {
    @Test
    fun refusesWithoutCallingGeneratorWhenNoHits() = runTest {
        val generator = RecordingGenerator()
        val asker = AskKnowledgeBase(
            repository = HitRepository(emptyList()),
            embedding = FixedEmbedding(),
            generator = generator,
            budget = OnDeviceRagBudget(minimumScore = 0.15f),
        )
        val answer = asker("这个问题资料里没有")
        assertEquals(AskKnowledgeBase.REFUSAL_TEXT, answer.text)
        assertEquals(emptyList(), answer.citations)
        assertEquals(0, generator.calls)
    }

    @Test
    fun onlyExposesModelReferencedCitations() = runTest {
        val documentId = UUID.randomUUID()
        val asker = AskKnowledgeBase(
            repository = HitRepository(
                listOf(
                    hit(documentId, 0, "证据一内容足够长"),
                    hit(documentId, 1, "证据二内容足够长"),
                ),
            ),
            embedding = FixedEmbedding(),
            generator = CiteSecondOnlyGenerator(),
            budget = OnDeviceRagBudget(
                evidenceTokenBudget = 200,
                retrievalLimit = 5,
                maxCitations = 3,
                maxChunksPerDocument = 2,
                minimumScore = 0f,
            ),
        )
        val answer = asker("问题")
        assertEquals(listOf(2), answer.citations.map { it.index })
        assertTrue(answer.text.contains("[2]"))
        assertTrue(!answer.text.contains("[1]"))
    }

    @Test
    fun restrictsEvidenceToSelectedDocuments() = runTest {
        val first = UUID.randomUUID()
        val second = UUID.randomUUID()
        val generator = RecordingGenerator()
        val asker = AskKnowledgeBase(
            repository = HitRepository(
                listOf(
                    hit(first, 0, "第一份资料"),
                    hit(second, 0, "第二份资料"),
                ),
            ),
            embedding = FixedEmbedding(),
            generator = generator,
            budget = OnDeviceRagBudget(minimumScore = 0f),
        )
        asker("问题", KnowledgeSearchScope.Documents(setOf(second)))
        assertTrue(generator.lastContext.contains("第二份资料"))
        assertTrue(!generator.lastContext.contains("第一份资料"))
    }

    private fun hit(documentId: UUID, ordinal: Int, text: String) = KnowledgeSearchHit(
        chunk = KnowledgeChunk(
            documentId = documentId,
            ordinal = ordinal,
            text = text,
            tokenEstimate = 40,
        ),
        documentTitle = "doc-$ordinal",
        score = 0.9f,
    )
}

private class HitRepository(
    private val hits: List<KnowledgeSearchHit>,
) : FakeKnowledgeRepository() {
    override suspend fun search(
        query: String,
        embedding: FloatArray,
        limit: Int,
        scope: KnowledgeSearchScope,
    ): List<KnowledgeSearchHit> {
        val allowed = scope.documentIds
        return hits.filter { allowed == null || it.chunk.documentId in allowed }.take(limit)
    }
}

private class FixedEmbedding : ai.orynode.mobile.domain.TextEmbedding {
    override val name = "fixed"
    override val dimensions = 4
    override suspend fun embed(texts: List<String>): List<FloatArray> =
        texts.map { FloatArray(dimensions) { 1f } }
}

private class RecordingGenerator : KnowledgeAnswerGenerator {
    var calls = 0
    var lastContext: String = ""
    override suspend fun answer(question: String, context: String): String {
        calls += 1
        lastContext = context
        return "根据资料 [1]"
    }
    override fun answerStream(question: String, context: String): Flow<String> {
        calls += 1
        lastContext = context
        return flowOf("根据资料 [1]")
    }
}

private class CiteSecondOnlyGenerator : KnowledgeAnswerGenerator {
    override suspend fun answer(question: String, context: String) = "见 [2]"
    override fun answerStream(question: String, context: String) = flowOf("见 [2]")
}
