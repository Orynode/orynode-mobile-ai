package ai.orynode.mobile.infrastructure.embedding

import ai.orynode.mobile.domain.EmbeddingDescriptor
import ai.orynode.mobile.domain.TextEmbedding
import android.content.res.AssetManager
import java.io.FileNotFoundException

sealed class EmbeddingArtifactError : Exception() {
    data object ArtifactMissing : EmbeddingArtifactError() {
        private fun readResolve(): Any = ArtifactMissing
        override val message: String = "缺少生产级中英文 embedding 模型或词表。"
    }

    data class IncompatibleModel(val detail: String) : EmbeddingArtifactError() {
        override val message: String = "Embedding LiteRT 模型不兼容：$detail"
    }
}

/**
 * Loads bundled multilingual-e5-small LiteRT assets.
 * Debug may fall back to [DeterministicHashEmbedding]; Release must ship assets.
 */
object OnDeviceEmbeddingFactory {
    const val ASSET_DIR = "embedding"
    const val MODEL_FILE = "multilingual-e5-small.tflite"
    const val TOKENIZER_DIR = "multilingual-e5-small-tokenizer"
    const val UNIGRAM_TSV = "unigram.tsv"
    const val SPECIALS_JSON = "specials.json"

    val productionDescriptor = EmbeddingDescriptor(
        id = "multilingual-e5-small-litert",
        version = "1",
        dimensions = 384,
        tokenizerVersion = "xlmr-unigram-v1",
    )

    fun make(
        assets: AssetManager,
        allowDebugFallback: Boolean,
    ): TextEmbedding {
        return try {
            openLiteRt(assets)
        } catch (error: Exception) {
            if (allowDebugFallback) {
                android.util.Log.e(
                    "OrynodeEmbedding",
                    "DEBUG fallback to DeterministicHashEmbedding — semantic retrieval is degraded. " +
                        "Run android/scripts/prepare-embedding-model.sh and rebuild. Cause: ${error.message}",
                    error,
                )
                DeterministicHashEmbedding(dimensions = productionDescriptor.dimensions)
            } else {
                throw when (error) {
                    is EmbeddingArtifactError -> error
                    is FileNotFoundException -> EmbeddingArtifactError.ArtifactMissing
                    else -> EmbeddingArtifactError.IncompatibleModel(error.message ?: error.toString())
                }
            }
        }
    }

    private fun openLiteRt(assets: AssetManager): LiteRtTextEmbedding {
        val modelPath = "$ASSET_DIR/$MODEL_FILE"
        val unigramPath = "$ASSET_DIR/$TOKENIZER_DIR/$UNIGRAM_TSV"
        val specialsPath = "$ASSET_DIR/$TOKENIZER_DIR/$SPECIALS_JSON"
        val modelBytes = assets.open(modelPath).use { it.readBytes() }
        if (modelBytes.isEmpty()) throw EmbeddingArtifactError.ArtifactMissing
        val tokenizer = assets.open(unigramPath).use { unigram ->
            assets.open(specialsPath).use { specials ->
                XlMRUnigramTokenizer.load(unigram, specials)
            }
        }
        return LiteRtTextEmbedding(
            modelBuffer = loadModelBuffer(modelBytes),
            tokenizer = tokenizer,
            descriptor = productionDescriptor,
        )
    }
}
