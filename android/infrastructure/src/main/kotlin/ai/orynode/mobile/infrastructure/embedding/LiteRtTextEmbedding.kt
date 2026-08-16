package ai.orynode.mobile.infrastructure.embedding

import ai.orynode.mobile.domain.EmbeddingDescriptor
import ai.orynode.mobile.domain.TextEmbedding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * multilingual-e5-small via LiteRT / TFLite Interpreter.
 *
 * Contract (aligned with iOS CoreMLTextEmbedding):
 * - prefixes `query:` / `passage:`
 * - attention-mask mean pooling + L2 normalize
 * - 384-d vectors, max length 256
 */
class LiteRtTextEmbedding(
    modelBuffer: ByteBuffer,
    private val tokenizer: XlMRUnigramTokenizer,
    override val descriptor: EmbeddingDescriptor,
    threads: Int = 2,
) : TextEmbedding, AutoCloseable {
    override val name: String = descriptor.id
    override val dimensions: Int = descriptor.dimensions

    private val mutex = Mutex()
    private val interpreter: Interpreter = Interpreter(
        modelBuffer,
        Interpreter.Options().apply {
            setNumThreads(threads.coerceIn(1, 4))
        },
    )
    private val maximumLength: Int = tokenizer.maximumLength
    private val idsInputIndex: Int
    private val maskInputIndex: Int
    private val outputIndex: Int
    private val useInt64Inputs: Boolean
    private val inputIdsInt: Array<IntArray>?
    private val attentionMaskInt: Array<IntArray>?
    private val inputIdsLong: Array<LongArray>?
    private val attentionMaskLong: Array<LongArray>?
    private val outputBuffer: Array<Array<FloatArray>>

    init {
        require(descriptor.dimensions > 0)
        require(interpreter.inputTensorCount >= 2) {
            "E5 LiteRT model must expose input_ids and attention_mask"
        }
        var idsIndex = 0
        var maskIndex = 1
        for (index in 0 until interpreter.inputTensorCount) {
            val tensorName = interpreter.getInputTensor(index).name().lowercase()
            when {
                tensorName.contains("input_ids") -> idsIndex = index
                tensorName.contains("attention_mask") -> maskIndex = index
            }
        }
        idsInputIndex = idsIndex
        maskInputIndex = maskIndex

        val idsType = interpreter.getInputTensor(idsInputIndex).dataType()
        val maskType = interpreter.getInputTensor(maskInputIndex).dataType()
        require(idsType == maskType) {
            "input_ids ($idsType) and attention_mask ($maskType) dtypes must match"
        }
        useInt64Inputs = when (idsType) {
            DataType.INT64 -> true
            DataType.INT32 -> false
            else -> error("Unsupported embedding input dtype: $idsType")
        }
        if (useInt64Inputs) {
            inputIdsLong = Array(1) { LongArray(maximumLength) }
            attentionMaskLong = Array(1) { LongArray(maximumLength) }
            inputIdsInt = null
            attentionMaskInt = null
        } else {
            inputIdsInt = Array(1) { IntArray(maximumLength) }
            attentionMaskInt = Array(1) { IntArray(maximumLength) }
            inputIdsLong = null
            attentionMaskLong = null
        }

        var hiddenIndex = 0
        for (index in 0 until interpreter.outputTensorCount) {
            val shape = interpreter.getOutputTensor(index).shape()
            if (shape.isNotEmpty() && shape.last() == dimensions) {
                hiddenIndex = index
                break
            }
        }
        outputIndex = hiddenIndex
        val outShape = interpreter.getOutputTensor(outputIndex).shape()
        outputBuffer = when (outShape.size) {
            3 -> Array(outShape[0]) { Array(outShape[1]) { FloatArray(outShape[2]) } }
            2 -> Array(1) { Array(1) { FloatArray(outShape[1]) } }
            else -> error("Unexpected embedding output shape: ${outShape.toList()}")
        }
    }

    override suspend fun embed(texts: List<String>): List<FloatArray> = mutex.withLock {
        withContext(Dispatchers.Default) {
            texts.map { predict(it) }
        }
    }

    override suspend fun embedDocuments(texts: List<String>): List<FloatArray> =
        embed(texts.map { "passage: $it" })

    override suspend fun embedQuery(text: String): FloatArray =
        embed(listOf("query: $text")).first()

    override fun close() {
        interpreter.close()
    }

    private fun predict(text: String): FloatArray {
        val tokenIds = tokenizer.encode(text, addSpecialTokens = true)
        val (ids, mask) = tokenizer.padToMaximum(tokenIds)
        val inputs = arrayOfNulls<Any>(interpreter.inputTensorCount)
        if (useInt64Inputs) {
            val idRow = inputIdsLong!![0]
            val maskRow = attentionMaskLong!![0]
            for (index in 0 until maximumLength) {
                idRow[index] = ids[index].toLong()
                maskRow[index] = mask[index].toLong()
            }
            inputs[idsInputIndex] = inputIdsLong
            inputs[maskInputIndex] = attentionMaskLong
        } else {
            System.arraycopy(ids, 0, inputIdsInt!![0], 0, maximumLength)
            System.arraycopy(mask, 0, attentionMaskInt!![0], 0, maximumLength)
            inputs[idsInputIndex] = inputIdsInt
            inputs[maskInputIndex] = attentionMaskInt
        }
        val outputs = hashMapOf<Int, Any>(outputIndex to outputBuffer)
        interpreter.runForMultipleInputsOutputs(inputs, outputs)

        val shape = interpreter.getOutputTensor(outputIndex).shape()
        return when (shape.size) {
            3 -> {
                val sequenceLength = shape[1]
                val hiddenSize = shape[2]
                val flat = FloatArray(sequenceLength * hiddenSize)
                var offset = 0
                for (token in 0 until sequenceLength) {
                    val row = outputBuffer[0][token]
                    for (dimension in 0 until hiddenSize) {
                        flat[offset++] = row[dimension]
                    }
                }
                E5Pooling.meanPoolAndNormalize(flat, sequenceLength, hiddenSize, mask)
            }
            2 -> {
                val vector = outputBuffer[0][0].copyOf(dimensions)
                E5Pooling.meanPoolAndNormalize(
                    hidden = vector,
                    sequenceLength = 1,
                    hiddenSize = dimensions,
                    attentionMask = intArrayOf(1),
                )
            }
            else -> error("Unexpected embedding output shape: ${shape.toList()}")
        }
    }
}

internal fun loadModelBuffer(bytes: ByteArray): ByteBuffer {
    val buffer = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
    buffer.put(bytes)
    buffer.rewind()
    return buffer
}
