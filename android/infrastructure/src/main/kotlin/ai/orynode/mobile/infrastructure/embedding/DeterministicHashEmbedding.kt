package ai.orynode.mobile.infrastructure.embedding

import ai.orynode.mobile.domain.EmbeddingDescriptor
import ai.orynode.mobile.domain.KnowledgeBaseError
import ai.orynode.mobile.domain.TextEmbedding
import kotlin.math.sqrt

/**
 * Offline deterministic feature-hashing baseline. Debug / architecture wiring only.
 * Release must use a real on-device embedding implementing [TextEmbedding].
 */
class DeterministicHashEmbedding(
    dimensions: Int = 384,
) : TextEmbedding {
    override val name: String = "deterministic-feature-hash-fallback"
    override val dimensions: Int = dimensions.coerceAtLeast(32)

    override val descriptor: EmbeddingDescriptor = EmbeddingDescriptor(
        id = name,
        version = "1",
        dimensions = this.dimensions,
        tokenizerVersion = "unicode-ngram-v1",
    )

    override suspend fun embed(texts: List<String>): List<FloatArray> =
        texts.map { embedOne(it, this.dimensions) }

    companion object {
        fun embedOne(text: String, dimensions: Int): FloatArray {
            val scalars = text.lowercase().codePoints().toArray()
            val vector = FloatArray(dimensions)
            if (scalars.isEmpty()) return vector
            val maxWidth = minOf(3, scalars.size)
            for (width in 1..maxWidth) {
                for (start in 0..scalars.size - width) {
                    var hash = 14_695_981_039_346_656_037uL
                    for (index in start until start + width) {
                        hash = hash xor scalars[index].toULong()
                        hash *= 1_099_511_628_211uL
                    }
                    val slot = (hash % dimensions.toULong()).toInt()
                    val sign = if (hash and 1uL == 0uL) 1f else -1f
                    vector[slot] += sign / width
                }
            }
            var norm = 0f
            for (value in vector) norm += value * value
            norm = sqrt(norm)
            if (norm > 0f) {
                for (i in vector.indices) vector[i] /= norm
            }
            return vector
        }
    }
}

object ExactCosineSimilarity {
    fun score(lhs: FloatArray, rhs: FloatArray): Float {
        if (lhs.size != rhs.size) {
            throw KnowledgeBaseError.InvalidEmbeddingDimensions(
                expected = lhs.size,
                actual = rhs.size,
            )
        }
        if (lhs.isEmpty()) return 0f
        var dot = 0f
        var lhsSquared = 0f
        var rhsSquared = 0f
        for (i in lhs.indices) {
            dot += lhs[i] * rhs[i]
            lhsSquared += lhs[i] * lhs[i]
            rhsSquared += rhs[i] * rhs[i]
        }
        val denom = sqrt(lhsSquared) * sqrt(rhsSquared)
        if (denom == 0f) return 0f
        return (dot / denom).coerceIn(-1f, 1f)
    }
}
