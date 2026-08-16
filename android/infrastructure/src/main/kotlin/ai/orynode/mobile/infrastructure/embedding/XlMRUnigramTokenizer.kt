package ai.orynode.mobile.infrastructure.embedding

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import kotlin.math.ln

/**
 * Offline XLM-R Unigram tokenizer for multilingual-e5-small.
 * Loads compact assets produced by `android/scripts/prepare-embedding-model.sh`
 * (`unigram.tsv` + `specials.json`) — never downloads at runtime.
 */
class XlMRUnigramTokenizer(
    private val pieces: List<String>,
    private val scores: FloatArray,
    private val pieceToId: Map<String, Int>,
    val bosId: Int,
    val eosId: Int,
    val unkId: Int,
    val maximumLength: Int,
) {
    init {
        require(pieces.size == scores.size)
        require(maximumLength >= 8)
    }

    fun encode(text: String, addSpecialTokens: Boolean = true): IntArray {
        val normalized = normalize(text)
        val segmented = segment(normalized)
        val ids = ArrayList<Int>(segmented.size + 2)
        if (addSpecialTokens) ids += bosId
        for (piece in segmented) {
            ids += pieceToId[piece] ?: unkId
        }
        if (addSpecialTokens) ids += eosId
        if (ids.size > maximumLength) {
            val truncated = ids.subList(0, maximumLength).toMutableList()
            if (addSpecialTokens) {
                truncated[truncated.lastIndex] = eosId
            }
            return truncated.toIntArray()
        }
        return ids.toIntArray()
    }

    fun padToMaximum(tokenIds: IntArray): Pair<IntArray, IntArray> {
        val ids = IntArray(maximumLength)
        val mask = IntArray(maximumLength)
        val count = minOf(tokenIds.size, maximumLength)
        for (index in 0 until count) {
            ids[index] = tokenIds[index]
            mask[index] = 1
        }
        return ids to mask
    }

    private fun normalize(text: String): String {
        val collapsed = text
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .trim()
            .replace(Regex("\\s+"), " ")
        if (collapsed.isEmpty()) return META
        return META + collapsed.replace(" ", META)
    }

    /**
     * SentencePiece Unigram Viterbi over Unicode code points.
     * Pieces may be multi-codepoint; unknown single code points map to `<unk>`.
     */
    private fun segment(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        val codePoints = text.codePoints().toArray()
        val n = codePoints.size
        val bestScore = FloatArray(n + 1) { Float.NEGATIVE_INFINITY }
        val bestId = IntArray(n + 1) { -1 }
        val bestLen = IntArray(n + 1) { 0 }
        bestScore[0] = 0f

        for (start in 0 until n) {
            if (bestScore[start] == Float.NEGATIVE_INFINITY) continue
            var matched = false
            // Limit piece length scans; XLM-R pieces are short in practice.
            val maxEnd = minOf(n, start + 16)
            for (end in (start + 1)..maxEnd) {
                val piece = String(codePoints, start, end - start)
                val id = pieceToId[piece] ?: continue
                val candidate = bestScore[start] + scores[id]
                if (candidate > bestScore[end]) {
                    bestScore[end] = candidate
                    bestId[end] = id
                    bestLen[end] = end - start
                }
                matched = true
            }
            if (!matched && bestScore[start + 1] < bestScore[start] + unkScore) {
                bestScore[start + 1] = bestScore[start] + unkScore
                bestId[start + 1] = unkId
                bestLen[start + 1] = 1
            }
        }

        if (bestScore[n] == Float.NEGATIVE_INFINITY) {
            return List(n) { pieces.getOrElse(unkId) { "<unk>" } }
        }

        val reversed = ArrayList<String>()
        var cursor = n
        while (cursor > 0) {
            val length = bestLen[cursor].coerceAtLeast(1)
            val id = bestId[cursor]
            reversed += if (id in pieces.indices) pieces[id] else pieces.getOrElse(unkId) { "<unk>" }
            cursor -= length
        }
        return reversed.asReversed()
    }

    companion object {
        private const val META = "\u2581"
        private val unkScore = ln(1e-10).toFloat()

        fun load(
            unigramTsv: InputStream,
            specialsJson: InputStream,
            maximumLengthOverride: Int? = null,
        ): XlMRUnigramTokenizer {
            val pieces = ArrayList<String>()
            val scores = ArrayList<Float>()
            BufferedReader(InputStreamReader(unigramTsv, StandardCharsets.UTF_8)).use { reader ->
                var line = reader.readLine()
                while (line != null) {
                    if (line.isNotBlank() && !line.startsWith("#")) {
                        val tab = line.indexOf('\t')
                        require(tab > 0) { "invalid unigram.tsv line" }
                        pieces += line.substring(0, tab)
                        scores += line.substring(tab + 1).toFloat()
                    }
                    line = reader.readLine()
                }
            }
            val specials = specialsJson.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            fun intField(key: String, default: Int): Int {
                val match = Regex(""""$key"\s*:\s*(-?\d+)""").find(specials)
                return match?.groupValues?.get(1)?.toIntOrNull() ?: default
            }
            val scoreArray = FloatArray(scores.size) { scores[it] }
            val pieceToId = HashMap<String, Int>(pieces.size * 2)
            for (index in pieces.indices) {
                pieceToId.putIfAbsent(pieces[index], index)
            }
            return XlMRUnigramTokenizer(
                pieces = pieces,
                scores = scoreArray,
                pieceToId = pieceToId,
                bosId = intField("bos_id", 0),
                eosId = intField("eos_id", 2),
                unkId = intField("unk_id", 3),
                maximumLength = maximumLengthOverride ?: intField("max_length", 256),
            )
        }
    }
}
