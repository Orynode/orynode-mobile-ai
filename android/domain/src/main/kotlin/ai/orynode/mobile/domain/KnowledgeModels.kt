package ai.orynode.mobile.domain

import java.time.Instant
import java.util.UUID

data class KnowledgeDocument(
    val id: UUID = UUID.randomUUID(),
    val sourcePath: String,
    val title: String,
    val contentHash: String,
    val state: State = State.Importing,
    val importedChunkCount: Int = 0,
    val errorMessage: String? = null,
    val indexedText: String? = null,
    val pageSpans: List<KnowledgePageSpan> = emptyList(),
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
) {
    enum class State {
        Importing,
        Ready,
        Failed,
    }
}

data class KnowledgePageSpan(
    val page: Int,
    val start: Int,
    val end: Int,
)

data class KnowledgeExtraction(
    val kind: Kind,
    val indexedText: String,
    val pageSpans: List<KnowledgePageSpan> = emptyList(),
) {
    enum class Kind {
        PlainText,
        Markdown,
        Pdf,
    }
}

data class KnowledgeChunk(
    val id: UUID = UUID.randomUUID(),
    val documentId: UUID,
    val ordinal: Int,
    val heading: String? = null,
    val text: String,
    val tokenEstimate: Int,
    val locator: SourceLocator? = null,
)

data class EmbeddedKnowledgeChunk(
    val chunk: KnowledgeChunk,
    val embedding: FloatArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EmbeddedKnowledgeChunk) return false
        return chunk == other.chunk && embedding.contentEquals(other.embedding)
    }

    override fun hashCode(): Int = 31 * chunk.hashCode() + embedding.contentHashCode()
}

data class KnowledgeSearchHit(
    val chunk: KnowledgeChunk,
    val documentTitle: String,
    val score: Float,
)

data class KnowledgeCitation(
    val index: Int,
    val documentId: UUID,
    val documentTitle: String,
    val chunkId: UUID,
    val excerpt: String,
    val locator: SourceLocator? = null,
    val locatorLabel: String? = null,
)

data class KnowledgeAnswer(
    val text: String,
    val citations: List<KnowledgeCitation>,
)

data class EmbeddingDescriptor(
    val id: String,
    val version: String,
    val dimensions: Int,
    val tokenizerVersion: String,
) {
    val indexVersion: String
        get() = "$id@$version:$dimensions:$tokenizerVersion"
}

sealed class KnowledgeSearchScope {
    data object All : KnowledgeSearchScope()

    data class Documents(val ids: Set<UUID>) : KnowledgeSearchScope()

    val documentIds: Set<UUID>?
        get() = when (this) {
            All -> null
            is Documents -> ids
        }
}

sealed class KnowledgeAnswerStreamEvent {
    /** UI progress phase. Control flow (e.g. warm LLM) must key off [kind], not [message]. */
    enum class PhaseKind {
        Retrieving,
        Generating,
    }

    data class Phase(
        val kind: PhaseKind,
        val message: String = defaultMessage(kind),
    ) : KnowledgeAnswerStreamEvent() {
        companion object {
            fun defaultMessage(kind: PhaseKind): String = when (kind) {
                PhaseKind.Retrieving -> "正在检索本机资料…"
                PhaseKind.Generating -> "正在生成本机回答…"
            }
        }
    }

    data class Delta(val text: String) : KnowledgeAnswerStreamEvent()
    data class Finished(val answer: KnowledgeAnswer) : KnowledgeAnswerStreamEvent()
}
