package ai.orynode.mobile.application

import ai.orynode.mobile.domain.KnowledgeAnswer
import ai.orynode.mobile.domain.KnowledgeAnswerGenerator
import ai.orynode.mobile.domain.KnowledgeAnswerStreamEvent
import ai.orynode.mobile.domain.KnowledgeBaseError
import ai.orynode.mobile.domain.KnowledgeCitation
import ai.orynode.mobile.domain.KnowledgeRepository
import ai.orynode.mobile.domain.KnowledgeSearchScope
import ai.orynode.mobile.domain.OnDeviceRagBudget
import ai.orynode.mobile.domain.TextEmbedding
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import java.util.UUID
import kotlin.coroutines.coroutineContext

class AskKnowledgeBase(
    private val repository: KnowledgeRepository,
    private val embedding: TextEmbedding,
    private val generator: KnowledgeAnswerGenerator,
    budget: OnDeviceRagBudget = OnDeviceRagBudget.GemmaE2B,
    contextTokenBudget: Int? = null,
    retrievalLimit: Int? = null,
    minimumScore: Float? = null,
) {
    private val budget = budget.copy(
        evidenceTokenBudget = contextTokenBudget ?: budget.evidenceTokenBudget,
        retrievalLimit = retrievalLimit ?: budget.retrievalLimit,
        minimumScore = minimumScore ?: budget.minimumScore,
    )

    suspend operator fun invoke(
        question: String,
        scope: KnowledgeSearchScope = KnowledgeSearchScope.All,
    ): KnowledgeAnswer {
        val events = stream(question, scope).toList()
        val finished = events.filterIsInstance<KnowledgeAnswerStreamEvent.Finished>().lastOrNull()
            ?: throw KnowledgeBaseError.Storage("问答流未返回完成事件")
        return finished.answer
    }

    fun stream(
        question: String,
        scope: KnowledgeSearchScope = KnowledgeSearchScope.All,
    ): Flow<KnowledgeAnswerStreamEvent> = flow {
        emit(KnowledgeAnswerStreamEvent.Phase(KnowledgeAnswerStreamEvent.PhaseKind.Retrieving))
        val vector = embedding.embedQuery(question)
        val retrieved = repository.search(
            query = question,
            embedding = vector,
            limit = budget.retrievalLimit,
            scope = scope,
        )
        val hits = EvidencePackFocus.prioritize(retrieved, question)
        var used = 0
        val perDocument = mutableMapOf<UUID, Int>()
        val blocks = mutableListOf<String>()
        val citations = mutableListOf<KnowledgeCitation>()
        val evidenceBudget = budget.clampedEvidenceTokenBudget

        for (hit in hits) {
            if (hit.score < budget.minimumScore) continue
            if (citations.size >= budget.maxCitations) break
            val documentCount = perDocument[hit.chunk.documentId] ?: 0
            if (documentCount >= budget.maxChunksPerDocument) continue

            val excerpt = EvidencePackFocus.excerpt(
                from = hit.chunk.text,
                query = question,
                maxCharacters = budget.evidenceExcerptCharacters,
            )
            val estimate = StructuredKnowledgeChunker.estimateTokens(excerpt)
            if (used + estimate > evidenceBudget && blocks.isNotEmpty()) continue

            used += estimate
            perDocument[hit.chunk.documentId] = documentCount + 1
            val index = citations.size + 1
            val heading = hit.chunk.heading?.let { " / $it" }.orEmpty()
            val place = hit.chunk.locator?.let { " · ${it.shortLabel}" }.orEmpty()
            blocks += "[$index] ${hit.documentTitle}$heading$place\n$excerpt"
            citations += KnowledgeCitation(
                index = index,
                documentId = hit.chunk.documentId,
                documentTitle = hit.documentTitle,
                chunkId = hit.chunk.id,
                excerpt = excerpt,
                locator = hit.chunk.locator,
                locatorLabel = null,
            )
        }

        if (blocks.isEmpty()) {
            emit(
                KnowledgeAnswerStreamEvent.Finished(
                    KnowledgeAnswer(
                        text = REFUSAL_TEXT,
                        citations = emptyList(),
                    ),
                ),
            )
            return@flow
        }

        emit(KnowledgeAnswerStreamEvent.Phase(KnowledgeAnswerStreamEvent.PhaseKind.Generating))
        val context = blocks.joinToString("\n\n")
        val allowed = citations.map { it.index }.toSet()
        val deltas = generator.answerStream(question, context)
        val raw = StringBuilder()
        deltas.collect { delta ->
            coroutineContext.ensureActive()
            raw.append(delta)
            emit(KnowledgeAnswerStreamEvent.Delta(delta))
        }
        val finalized = generator.finalize(raw.toString())
        val canonical = CitationCanonicalizer().canonicalize(finalized, allowed)
        val referenced = canonical.referencedIndices.toSet()
        emit(
            KnowledgeAnswerStreamEvent.Finished(
                KnowledgeAnswer(
                    text = canonical.text,
                    citations = citations.filter { it.index in referenced },
                ),
            ),
        )
    }

    companion object {
        const val REFUSAL_TEXT = "现有资料不足以回答这个问题。请补充资料，或换一个更具体的问题。"
    }
}
