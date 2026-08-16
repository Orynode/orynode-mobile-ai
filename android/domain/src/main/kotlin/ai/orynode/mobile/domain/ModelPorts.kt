package ai.orynode.mobile.domain

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.InputStream
import java.nio.file.Path

data class AnalysisRequest(
    val prompt: String,
)

data class ModelDescriptor(
    val id: String,
    val version: String,
    val fileName: String,
    val expectedByteCount: Long? = null,
    val expectedSha256: String? = null,
) {
    val displayName: String
        get() = when (id) {
            "gemma-4-e2b-it" -> "Gemma 4 E2B"
            else -> id
        }

    companion object {
        val Gemma4E2B = ModelDescriptor(
            id = "gemma-4-e2b-it",
            version = "litertlm-v1",
            fileName = "gemma-4-E2B-it.litertlm",
        )
    }
}

data class InstalledModel(
    val descriptor: ModelDescriptor,
    val filePath: Path,
    val byteCount: Long,
    val sha256: String,
)

/**
 * Domain-level generator lifecycle (includes [Installed] with file path metadata).
 *
 * Android UI uses a path-free mirror in `app.serving.GeneratorRuntimeState`.
 * Keep this sealed class for Domain/Application semantics and iOS parity.
 */
sealed class ModelRuntimeState {
    data object NotInstalled : ModelRuntimeState()
    data class Installed(val model: InstalledModel) : ModelRuntimeState()
    data object Loading : ModelRuntimeState()
    data object Ready : ModelRuntimeState()
    data class Failed(val message: String) : ModelRuntimeState()
}

sealed class ModelRuntimeError : Exception() {
    data object ModelNotInstalled : ModelRuntimeError() {
        private fun readResolve(): Any = ModelNotInstalled
        override val message = "尚未导入模型。"
    }

    data object InvalidModelFile : ModelRuntimeError() {
        private fun readResolve(): Any = InvalidModelFile
        override val message = "模型文件无效，必须是 .litertlm 文件。"
    }

    data object ModelIntegrityCheckFailed : ModelRuntimeError() {
        private fun readResolve(): Any = ModelIntegrityCheckFailed
        override val message = "模型完整性校验失败。"
    }

    data object InsufficientStorage : ModelRuntimeError() {
        private fun readResolve(): Any = InsufficientStorage
        override val message =
            "手机可用空间不足，无法导入模型。请清理空间后重试（约需模型文件大小的同等空闲空间）。"
    }

    data object EngineNotReady : ModelRuntimeError() {
        private fun readResolve(): Any = EngineNotReady
        override val message = "模型尚未加载完成。"
    }

    data object EngineBusy : ModelRuntimeError() {
        private fun readResolve(): Any = EngineBusy
        override val message = "模型正在执行其他任务。"
    }

    data object EmptyResponse : ModelRuntimeError() {
        private fun readResolve(): Any = EmptyResponse
        override val message = "模型没有返回内容。"
    }
}

interface LocalModelEngine {
    suspend fun load(modelAt: Path)
    suspend fun generate(request: AnalysisRequest): String
    fun generateStream(request: AnalysisRequest): Flow<String> = flow {
        emit(generate(request))
    }
    suspend fun cancel()
    suspend fun unload()
}

interface ModelStore {
    suspend fun installedModel(forDescriptor: ModelDescriptor): InstalledModel?
    suspend fun importModel(from: Path, descriptor: ModelDescriptor): InstalledModel
    suspend fun importModel(
        input: InputStream,
        sourceFileName: String,
        descriptor: ModelDescriptor,
    ): InstalledModel

    /**
     * Install a completed download (typically `{fileName}.partial`) without a second full copy:
     * hash, write metadata, atomic-move into [ModelDescriptor.fileName].
     */
    suspend fun promoteDownloadedModel(partial: Path, descriptor: ModelDescriptor): InstalledModel

    suspend fun deleteModel(descriptor: ModelDescriptor)
}

data class ModelDownloadProgress(
    val bytesReceived: Long,
    val totalBytes: Long?,
)

/** Resumable acquisition of generator weights (App 外获取模型，非知识库核心路径). */
interface ResumableModelDownloader {
    suspend fun download(onProgress: suspend (ModelDownloadProgress) -> Unit): Path
    fun cancel()
    fun partialFile(): Path
    /** Drop unfinished staging files (e.g. after the user deletes the generator). */
    fun clearPartial()
}

/** On-device OCR for scanned PDF pages / future camera capture. */
interface TextRecognizer {
    suspend fun recognizeDocument(imagePath: Path): OcrDocument
    suspend fun recognizeImageData(data: ByteArray): OcrDocument
}
