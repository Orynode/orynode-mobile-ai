package ai.orynode.mobile.app.ui

import ai.orynode.mobile.app.serving.GeneratorRuntimeState
import ai.orynode.mobile.app.serving.InstalledGeneratorInfo
import ai.orynode.mobile.app.serving.KnowledgeBaseServing
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

enum class AppPhase {
    Launching,
    NeedsModel,
    Ready,
}

data class ModelDownloadUiProgress(
    val bytesReceived: Long = 0L,
    val totalBytes: Long? = null,
    /** Instantaneous-ish throughput in bytes/sec; null until first sample. */
    val bytesPerSecond: Long? = null,
)

data class AppUiState(
    val phase: AppPhase = AppPhase.Launching,
    val runtimeState: GeneratorRuntimeState = GeneratorRuntimeState.NotInstalled,
    val prepPhase: ModelPrepPhase = ModelPrepPhase.Idle,
    val installed: InstalledGeneratorInfo? = null,
    /** Non-prep notices (e.g. model unloaded). */
    val noticeMessage: String? = null,
    val errorMessage: String? = null,
    val showsSettings: Boolean = false,
    val isDownloadingModel: Boolean = false,
    val downloadProgress: ModelDownloadUiProgress? = null,
    val embeddingBackendLabel: String = "…",
) {
    val isPreparingModel: Boolean get() = prepPhase.showsProgress
    val prepStatusMessage: String? get() = prepPhase.statusMessage
}

class AppViewModel(
    private val service: KnowledgeBaseServing,
) : ViewModel() {
    private val _state = MutableStateFlow(AppUiState())
    val state: StateFlow<AppUiState> = _state.asStateFlow()
    private var downloadJob: Job? = null
    @Volatile private var downloadCancelledByUser = false
    private var speedSampleBytes: Long = 0L
    private var speedSampleAtMs: Long = 0L
    private var smoothedBytesPerSecond: Long? = null

    init {
        start()
    }

    fun start() {
        viewModelScope.launch {
            val embeddingLabel = runCatching { service.embeddingBackendLabel() }.getOrDefault("不可用")
            _state.update { it.copy(embeddingBackendLabel = embeddingLabel) }
            enterPrep(ModelPrepPhase.Checking, phaseApp = AppPhase.Launching)
            val installed = runCatching { service.installedGenerator() }.getOrNull()
            if (installed == null) {
                _state.update {
                    it.copy(
                        phase = AppPhase.NeedsModel,
                        runtimeState = GeneratorRuntimeState.NotInstalled,
                        prepPhase = ModelPrepPhase.Idle,
                        installed = null,
                        noticeMessage = null,
                    )
                }
                return@launch
            }
            _state.update { it.copy(installed = installed) }
            loadInstalledModel()
        }
    }

    /** Selection only — no prep UI until a URI is returned. */
    fun importAndLoadModel(uri: Uri) {
        if (_state.value.isDownloadingModel) return
        enterPrep(ModelPrepPhase.Importing)
        viewModelScope.launch {
            // Paint Importing UI before starting multi-GB IO.
            yield()
            runCatching {
                service.importGeneratorModel(uri)
                enterPrep(ModelPrepPhase.LoadingEngine)
                yield()
                service.loadGenerator()
                service.installedGenerator()
            }
                .onSuccess { installed ->
                    _state.update {
                        it.copy(
                            phase = AppPhase.Ready,
                            runtimeState = GeneratorRuntimeState.Ready,
                            prepPhase = ModelPrepPhase.Idle,
                            installed = installed,
                            noticeMessage = null,
                            errorMessage = null,
                        )
                    }
                }
                .onFailure { error -> finishPrepWithFailure(error) }
        }
    }

    fun downloadAndLoadModel() {
        val current = _state.value
        if (current.isDownloadingModel || current.isPreparingModel) return
        downloadCancelledByUser = false
        resetDownloadSpeed()
        _state.update {
            it.copy(
                isDownloadingModel = true,
                downloadProgress = ModelDownloadUiProgress(),
                noticeMessage = null,
                errorMessage = null,
            )
        }
        downloadJob = viewModelScope.launch {
            try {
                service.downloadGeneratorModel { bytesReceived, totalBytes ->
                    val bps = updateDownloadSpeed(bytesReceived)
                    _state.update {
                        it.copy(
                            downloadProgress = ModelDownloadUiProgress(
                                bytesReceived = bytesReceived,
                                totalBytes = totalBytes,
                                bytesPerSecond = bps,
                            ),
                        )
                    }
                }
                if (downloadCancelledByUser) {
                    clearDownloadUiQuietly()
                    return@launch
                }
                _state.update {
                    it.copy(isDownloadingModel = false, downloadProgress = null)
                }
                enterPrep(ModelPrepPhase.Importing)
                yield()
                service.installDownloadedGenerator()
                enterPrep(ModelPrepPhase.LoadingEngine)
                yield()
                service.loadGenerator()
                val installed = service.installedGenerator()
                _state.update {
                    it.copy(
                        phase = AppPhase.Ready,
                        runtimeState = GeneratorRuntimeState.Ready,
                        prepPhase = ModelPrepPhase.Idle,
                        installed = installed,
                        noticeMessage = null,
                        errorMessage = null,
                        isDownloadingModel = false,
                        downloadProgress = null,
                    )
                }
            } catch (error: CancellationException) {
                clearDownloadUiQuietly()
                throw error
            } catch (error: Throwable) {
                if (downloadCancelledByUser || isUserCancelError(error)) {
                    clearDownloadUiQuietly()
                } else {
                    _state.update {
                        it.copy(isDownloadingModel = false, downloadProgress = null)
                    }
                    finishPrepWithFailure(error, hintRetryDownloadInstall = true)
                }
            } finally {
                downloadJob = null
            }
        }
    }

    fun cancelModelDownload() {
        downloadCancelledByUser = true
        service.cancelGeneratorDownload()
        downloadJob?.cancel()
        downloadJob = null
        clearDownloadUiQuietly()
    }

    private fun clearDownloadUiQuietly() {
        resetDownloadSpeed()
        _state.update {
            it.copy(
                isDownloadingModel = false,
                downloadProgress = null,
                prepPhase = ModelPrepPhase.Idle,
                errorMessage = null,
                runtimeState = when {
                    it.installed != null -> GeneratorRuntimeState.Installed
                    else -> GeneratorRuntimeState.NotInstalled
                },
            )
        }
    }

    private fun resetDownloadSpeed() {
        speedSampleBytes = 0L
        speedSampleAtMs = 0L
        smoothedBytesPerSecond = null
    }

    /** Sample about every 400ms; EMA so the label does not jitter every buffer write. */
    private fun updateDownloadSpeed(bytesReceived: Long): Long? {
        val now = System.currentTimeMillis()
        if (speedSampleAtMs == 0L) {
            speedSampleBytes = bytesReceived
            speedSampleAtMs = now
            return smoothedBytesPerSecond
        }
        val elapsed = now - speedSampleAtMs
        if (elapsed < 400L) return smoothedBytesPerSecond
        val delta = bytesReceived - speedSampleBytes
        if (delta >= 0L && elapsed > 0L) {
            val instant = (delta * 1000L) / elapsed
            smoothedBytesPerSecond = when (val prev = smoothedBytesPerSecond) {
                null -> instant
                else -> ((prev * 7L) + (instant * 3L)) / 10L
            }
        }
        speedSampleBytes = bytesReceived
        speedSampleAtMs = now
        return smoothedBytesPerSecond
    }

    fun loadInstalledModel() {
        if (_state.value.isDownloadingModel) return
        enterPrep(ModelPrepPhase.LoadingEngine)
        viewModelScope.launch {
            yield()
            runCatching { service.loadGenerator() }
                .onSuccess {
                    _state.update {
                        it.copy(
                            phase = AppPhase.Ready,
                            runtimeState = GeneratorRuntimeState.Ready,
                            prepPhase = ModelPrepPhase.Idle,
                            noticeMessage = null,
                            errorMessage = null,
                        )
                    }
                }
                .onFailure { error ->
                    _state.update {
                        it.copy(
                            phase = AppPhase.NeedsModel,
                            runtimeState = GeneratorRuntimeState.Failed(humanizeModelError(error)),
                            prepPhase = ModelPrepPhase.Idle,
                            noticeMessage = null,
                            errorMessage = humanizeModelError(error),
                        )
                    }
                }
        }
    }

    fun unloadLoadedModel() {
        viewModelScope.launch {
            service.unloadGenerator()
            _state.update {
                it.copy(
                    runtimeState = GeneratorRuntimeState.Installed,
                    prepPhase = ModelPrepPhase.Idle,
                    noticeMessage = "模型已释放；下次使用前请重新加载。",
                )
            }
        }
    }

    fun deleteInstalledModel() {
        viewModelScope.launch {
            runCatching { service.deleteGenerator() }
                .onSuccess {
                    _state.update {
                        it.copy(
                            phase = AppPhase.NeedsModel,
                            runtimeState = GeneratorRuntimeState.NotInstalled,
                            prepPhase = ModelPrepPhase.Idle,
                            installed = null,
                            showsSettings = false,
                            noticeMessage = null,
                        )
                    }
                }
                .onFailure { error ->
                    _state.update { it.copy(errorMessage = error.message) }
                }
        }
    }

    fun openSettings() = _state.update { it.copy(showsSettings = true) }
    fun closeSettings() = _state.update { it.copy(showsSettings = false) }
    fun clearError() = _state.update { it.copy(errorMessage = null) }

    fun refreshRuntime() {
        viewModelScope.launch {
            if (_state.value.isPreparingModel || _state.value.isDownloadingModel) return@launch
            val runtime = runCatching { service.generatorRuntimeState() }
                .getOrDefault(GeneratorRuntimeState.NotInstalled)
            val installed = runCatching { service.installedGenerator() }.getOrNull()
            _state.update { it.copy(runtimeState = runtime, installed = installed) }
        }
    }

    private fun enterPrep(phase: ModelPrepPhase, phaseApp: AppPhase? = null) {
        _state.update {
            it.copy(
                phase = phaseApp ?: it.phase,
                prepPhase = phase,
                runtimeState = GeneratorRuntimeState.Loading,
                noticeMessage = null,
                errorMessage = null,
            )
        }
    }

    private suspend fun finishPrepWithFailure(
        error: Throwable,
        hintRetryDownloadInstall: Boolean = false,
    ) {
        val installed = runCatching { service.installedGenerator() }.getOrNull()
        val base = humanizeModelError(error)
        val message = if (hintRetryDownloadInstall && installed == null) {
            "$base\n若文件已下载完成，可再次点击「下载模型」完成安装，无需重新找文件。"
        } else {
            base
        }
        _state.update {
            it.copy(
                phase = if (installed == null) AppPhase.NeedsModel else AppPhase.Ready,
                runtimeState = when {
                    installed == null -> GeneratorRuntimeState.NotInstalled
                    else -> GeneratorRuntimeState.Installed
                },
                prepPhase = ModelPrepPhase.Idle,
                installed = installed,
                noticeMessage = null,
                errorMessage = message,
                isDownloadingModel = false,
                downloadProgress = null,
            )
        }
    }

    class Factory(private val service: KnowledgeBaseServing) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = AppViewModel(service) as T
    }
}

private fun humanizeModelError(error: Throwable): String {
    val message = error.message.orEmpty()
    if (error is ai.orynode.mobile.domain.ModelRuntimeError.InsufficientStorage) {
        return error.message
    }
    if (message.contains("No space left", ignoreCase = true) ||
        message.contains("ENOSPC", ignoreCase = true)
    ) {
        return ai.orynode.mobile.domain.ModelRuntimeError.InsufficientStorage.message
    }
    return message.ifBlank { "模型准备失败" }
}

private fun isUserCancelError(error: Throwable): Boolean {
    val message = error.message.orEmpty()
    return message.contains("取消", ignoreCase = true) ||
        message.contains("Canceled", ignoreCase = true) ||
        message.contains("Cancelled", ignoreCase = true) ||
        message.contains("Socket closed", ignoreCase = true)
}
