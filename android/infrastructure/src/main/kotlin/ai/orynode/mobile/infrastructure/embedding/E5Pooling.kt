package ai.orynode.mobile.infrastructure.embedding

import kotlin.math.sqrt

/** E5 / XLM-R pooling: masked mean over sequence, then L2 normalize. */
object E5Pooling {
    fun meanPoolAndNormalize(
        hidden: FloatArray,
        sequenceLength: Int,
        hiddenSize: Int,
        attentionMask: IntArray,
    ): FloatArray {
        require(hiddenSize > 0)
        require(hidden.size >= sequenceLength * hiddenSize)
        require(attentionMask.size >= sequenceLength)
        val result = FloatArray(hiddenSize)
        var count = 0f
        for (token in 0 until sequenceLength) {
            if (attentionMask[token] == 0) continue
            count += 1f
            val offset = token * hiddenSize
            for (dimension in 0 until hiddenSize) {
                result[dimension] += hidden[offset + dimension]
            }
        }
        if (count <= 0f) return result
        for (dimension in 0 until hiddenSize) {
            result[dimension] /= count
        }
        var squared = 0f
        for (value in result) squared += value * value
        val norm = sqrt(squared)
        if (norm > 0f) {
            for (dimension in 0 until hiddenSize) {
                result[dimension] /= norm
            }
        }
        return result
    }
}
