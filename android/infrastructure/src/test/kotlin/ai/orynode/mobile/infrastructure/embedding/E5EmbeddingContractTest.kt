package ai.orynode.mobile.infrastructure.embedding

import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.math.abs

class E5PoolingTest {
    @Test
    fun meanPoolsMaskedTokensAndL2Normalizes() {
        // seq=2, hidden=2: token0=[3,0], token1=[0,4], mask both active → mean=[1.5,2]
        val hidden = floatArrayOf(3f, 0f, 0f, 4f)
        val result = E5Pooling.meanPoolAndNormalize(
            hidden = hidden,
            sequenceLength = 2,
            hiddenSize = 2,
            attentionMask = intArrayOf(1, 1),
        )
        val norm = kotlin.math.sqrt(1.5f * 1.5f + 2f * 2f)
        assertTrue(abs(result[0] - 1.5f / norm) < 1e-5f)
        assertTrue(abs(result[1] - 2f / norm) < 1e-5f)
    }

    @Test
    fun ignoresPaddingTokens() {
        val hidden = floatArrayOf(2f, 0f, 9f, 9f)
        val result = E5Pooling.meanPoolAndNormalize(
            hidden = hidden,
            sequenceLength = 2,
            hiddenSize = 2,
            attentionMask = intArrayOf(1, 0),
        )
        assertEquals(1f, result[0], 1e-5f)
        assertEquals(0f, result[1], 1e-5f)
    }
}

class XlMRUnigramTokenizerTest {
    @Test
    fun encodesWithBosEosAndPads() {
        val tsv = listOf(
            "<s>\t0",
            "<pad>\t0",
            "</s>\t0",
            "<unk>\t-10",
            "▁hi\t-1",
            "▁there\t-1",
        ).joinToString("\n")
        val specials = """{"bos_id":0,"eos_id":2,"unk_id":3,"pad_id":1,"max_length":8}"""
        val tokenizer = XlMRUnigramTokenizer.load(
            unigramTsv = ByteArrayInputStream(tsv.toByteArray()),
            specialsJson = ByteArrayInputStream(specials.toByteArray()),
        )
        val ids = tokenizer.encode("hi there", addSpecialTokens = true)
        assertEquals(0, ids.first())
        assertEquals(2, ids.last())
        assertTrue(ids.size >= 3)
        val (padded, mask) = tokenizer.padToMaximum(ids)
        assertEquals(8, padded.size)
        assertEquals(8, mask.size)
        assertEquals(1, mask.take(ids.size).minOrNull())
        assertEquals(0, mask.drop(ids.size).sum())
    }
}

class OnDeviceEmbeddingFactoryTest {
    @Test
    fun productionDescriptorMatchesContract() {
        val descriptor = OnDeviceEmbeddingFactory.productionDescriptor
        assertEquals("multilingual-e5-small-litert", descriptor.id)
        assertEquals(384, descriptor.dimensions)
        assertEquals("xlmr-unigram-v1", descriptor.tokenizerVersion)
        assertTrue(descriptor.indexVersion.contains("384"))
    }
}
