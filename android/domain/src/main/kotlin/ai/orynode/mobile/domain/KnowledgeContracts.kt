package ai.orynode.mobile.domain

object KnowledgeBaseLimits {
    const val MAX_CHUNKS = 10_000
    const val INDEX_BATCH_SIZE = 16
}

/**
 * Versions stored in knowledge metadata. Bump when changing the contract;
 * never silently mix with old indexes.
 */
object KnowledgeIndexContract {
    const val RETRIEVAL_VERSION = "hybrid-cosine0.7-fts0.3-v1"
    const val DEFAULT_CHUNKER_VERSION = "structured-520-64-v1"
    const val CONTENT_HASH_VERSION = "sha256-v1"
    const val LEGACY_CONTENT_HASH_VERSION = "fnv-64-v0"

    fun chunkerVersion(targetCharacters: Int, overlapCharacters: Int): String =
        "structured-$targetCharacters-$overlapCharacters-v1"
}

/**
 * Token budget for on-device RAG with a small local generator (e.g. Gemma 4 E2B).
 * Designed for ~2048 total model tokens.
 */
data class OnDeviceRagBudget(
    val engineMaxTokens: Int = 2_048,
    val systemPromptTokens: Int = 220,
    val questionTokens: Int = 120,
    val answerReserveTokens: Int = 280,
    val evidenceTokenBudget: Int = 900,
    val retrievalLimit: Int = 5,
    val maxCitations: Int = 3,
    val maxChunksPerDocument: Int = 2,
    val minimumScore: Float = 0.15f,
    val preferredAnswerCharacters: Int = 220,
    val evidenceExcerptCharacters: Int = 420,
) {
    init {
        require(retrievalLimit >= 1)
        require(maxCitations >= 1)
        require(maxChunksPerDocument >= 1)
        require(preferredAnswerCharacters >= 80)
        require(evidenceExcerptCharacters >= 120)
    }

    val clampedEvidenceTokenBudget: Int
        get() = minOf(
            evidenceTokenBudget,
            maxOf(128, engineMaxTokens - systemPromptTokens - questionTokens - answerReserveTokens),
        )

    companion object {
        val GemmaE2B = OnDeviceRagBudget()
    }
}

sealed class KnowledgeBaseError : Exception() {
    data class UnsupportedFileType(val extension: String) : KnowledgeBaseError() {
        override val message: String = "暂不支持 .$extension 格式。"
    }

    data object EmptyDocument : KnowledgeBaseError() {
        private fun readResolve(): Any = EmptyDocument
        override val message: String = "文档中没有可索引的文字。"
    }

    data class DuplicateDocument(val existingTitle: String) : KnowledgeBaseError() {
        override val message: String = "该资料已导入：$existingTitle"
    }

    data class InvalidEmbeddingDimensions(
        val expected: Int,
        val actual: Int,
    ) : KnowledgeBaseError() {
        override val message: String = "向量维度不匹配（期望 $expected，实际 $actual）。"
    }

    data class ChunkCapacityExceeded(
        val current: Int,
        val incoming: Int,
        val limit: Int,
    ) : KnowledgeBaseError() {
        override val message: String =
            "知识库现有 $current 个片段，本次还需 $incoming 个，超过上限 $limit。请删除部分资料后再导入。"
    }

    data class Storage(val detail: String) : KnowledgeBaseError() {
        override val message: String = detail
    }

    data class IndexVersionMismatch(val detail: String) : KnowledgeBaseError() {
        override val message: String = detail
    }

    data object NoIndexedDocuments : KnowledgeBaseError() {
        private fun readResolve(): Any = NoIndexedDocuments
        override val message: String = "请先导入并完成至少一个文档的索引。"
    }
}
