package ai.orynode.mobile.infrastructure.model

import ai.orynode.mobile.domain.AnalysisRequest
import ai.orynode.mobile.domain.LocalModelEngine
import ai.orynode.mobile.domain.ModelRuntimeError
import android.os.Build
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.MessageCallback
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/**
 * On-device Gemma generator via LiteRT-LM (parity with iOS `LiteRTLMModelEngine`).
 *
 * Speculative decoding / MTP stays off: iOS saw indefinite first-token stalls on device.
 * GPU needs OpenCL at **generation** time (not only initialize); emulators typically lack it,
 * so we select CPU up front and also hot-fallback if a GPU session still fails.
 */
class LiteRtLmModelEngine(
    private val cacheDir: Path,
    private val maxNumTokens: Int = 2_048,
    private val preferGpu: Boolean = true,
) : LocalModelEngine {
    private val mutex = Mutex()
    private var state: State = State.Idle
    private var engine: Engine? = null
    private var currentConversation: Conversation? = null
    private var loadedModelPath: Path? = null
    private var usingGpu: Boolean = false

    private sealed class State {
        data object Idle : State()
        data class Loading(val operationId: UUID) : State()
        data object Ready : State()
        data class Generating(val operationId: UUID) : State()
    }

    override suspend fun load(modelAt: Path) {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                when (state) {
                    is State.Idle, is State.Ready -> Unit
                    is State.Loading, is State.Generating -> throw ModelRuntimeError.EngineBusy
                }
                if (!Files.isRegularFile(modelAt)) {
                    throw ModelRuntimeError.ModelNotInstalled
                }

                val operationId = UUID.randomUUID()
                state = State.Loading(operationId)
                closeEngineLocked()

                try {
                    configureExperimentalFlags()
                    Files.createDirectories(cacheDir)
                    val wantGpu = preferGpu && canUseGpuBackend()
                    val opened = openEngine(
                        modelPath = modelAt.toAbsolutePath().toString(),
                        cacheDir = cacheDir.toAbsolutePath().toString(),
                        useGpu = wantGpu,
                    )
                    val stillLoading = state is State.Loading &&
                        (state as State.Loading).operationId == operationId
                    if (!stillLoading) {
                        opened.engine.close()
                        throw ModelRuntimeError.EngineBusy
                    }
                    engine = opened.engine
                    usingGpu = opened.usingGpu
                    loadedModelPath = modelAt
                    state = State.Ready
                } catch (error: Throwable) {
                    if (state is State.Loading &&
                        (state as State.Loading).operationId == operationId
                    ) {
                        state = State.Idle
                    }
                    throw error
                }
            }
        }
    }

    override suspend fun generate(request: AnalysisRequest): String {
        val pieces = mutableListOf<String>()
        generateStream(request).collect { pieces += it }
        val response = pieces.joinToString("")
        if (response.isBlank()) throw ModelRuntimeError.EmptyResponse
        return response
    }

    override fun generateStream(request: AnalysisRequest): Flow<String> = flow {
        try {
            collectGeneration(request) { emit(it) }
        } catch (error: Throwable) {
            if (!usingGpu || !isGpuUnavailable(error)) throw error
            // GPU initialize can succeed while OpenCL sampler fails on first token (emulator).
            reloadWithCpuLocked()
            collectGeneration(request) { emit(it) }
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun cancel() {
        mutex.withLock {
            currentConversation?.runCatching { cancelProcess() }
            currentConversation?.runCatching { close() }
            currentConversation = null
            if (state is State.Generating) {
                state = if (engine == null) State.Idle else State.Ready
            }
        }
    }

    override suspend fun unload() {
        mutex.withLock {
            currentConversation?.runCatching { cancelProcess() }
            currentConversation?.runCatching { close() }
            currentConversation = null
            closeEngineLocked()
            loadedModelPath = null
            usingGpu = false
            state = State.Idle
        }
    }

    private suspend fun collectGeneration(
        request: AnalysisRequest,
        emit: suspend (String) -> Unit,
    ) {
        val conversation = mutex.withLock {
            when (state) {
                is State.Ready -> Unit
                is State.Generating -> throw ModelRuntimeError.EngineBusy
                else -> throw ModelRuntimeError.EngineNotReady
            }
            val active = engine ?: throw ModelRuntimeError.EngineNotReady
            val operationId = UUID.randomUUID()
            state = State.Generating(operationId)
            val sampler = SamplerConfig(topK = 1, topP = 0.9, temperature = 0.0)
            val created = active.createConversation(
                ConversationConfig(samplerConfig = sampler),
            )
            currentConversation = created
            GenerationHandle(operationId, created)
        }

        var emitted = false
        try {
            // Use callback API + our own callbackFlow. LiteRT-LM's Flow wrapper calls
            // SendChannel.close$default and crashes under coroutines < 1.11.
            callbackFlow {
                conversation.session.sendMessageAsync(
                    request.prompt,
                    object : MessageCallback {
                        override fun onMessage(message: Message) {
                            trySend(message)
                        }

                        override fun onDone() {
                            close()
                        }

                        override fun onError(throwable: Throwable) {
                            close(throwable)
                        }
                    },
                )
                awaitClose {
                    conversation.session.runCatching { cancelProcess() }
                }
            }.collect { message ->
                val stillGenerating = mutex.withLock {
                    state is State.Generating &&
                        (state as State.Generating).operationId == conversation.operationId
                }
                if (!stillGenerating) throw ModelRuntimeError.EngineBusy
                val delta = message.plainText()
                if (delta.isNotEmpty()) {
                    emitted = true
                    emit(delta)
                }
            }
            if (!emitted) throw ModelRuntimeError.EmptyResponse
        } finally {
            mutex.withLock {
                finishGenerationLocked(conversation.operationId)
            }
        }
    }

    private suspend fun reloadWithCpuLocked() {
        mutex.withLock {
            val path = loadedModelPath ?: throw ModelRuntimeError.ModelNotInstalled
            currentConversation?.runCatching { cancelProcess() }
            currentConversation?.runCatching { close() }
            currentConversation = null
            closeEngineLocked()
            configureExperimentalFlags()
            val opened = openEngine(
                modelPath = path.toAbsolutePath().toString(),
                cacheDir = cacheDir.toAbsolutePath().toString(),
                useGpu = false,
            )
            engine = opened.engine
            usingGpu = false
            state = State.Ready
        }
    }

    @OptIn(ExperimentalApi::class)
    private fun configureExperimentalFlags() {
        // Match iOS: avoid speculative decoding stalls on first token.
        ExperimentalFlags.enableSpeculativeDecoding = false
    }

    private data class OpenedEngine(val engine: Engine, val usingGpu: Boolean)

    private fun openEngine(
        modelPath: String,
        cacheDir: String,
        useGpu: Boolean,
    ): OpenedEngine {
        if (!useGpu) {
            return OpenedEngine(createAndInitialize(modelPath, cacheDir, Backend.CPU()), usingGpu = false)
        }
        return try {
            OpenedEngine(createAndInitialize(modelPath, cacheDir, Backend.GPU()), usingGpu = true)
        } catch (error: Throwable) {
            if (!isGpuUnavailable(error)) throw error
            OpenedEngine(createAndInitialize(modelPath, cacheDir, Backend.CPU()), usingGpu = false)
        }
    }

    private fun createAndInitialize(
        modelPath: String,
        cacheDir: String,
        backend: Backend,
    ): Engine {
        val config = EngineConfig(
            modelPath = modelPath,
            backend = backend,
            maxNumTokens = maxNumTokens,
            cacheDir = cacheDir,
        )
        val engine = Engine(config)
        engine.initialize()
        return engine
    }

    private fun canUseGpuBackend(): Boolean {
        if (isEmulator()) return false
        return isOpenClLibraryPresent()
    }

    private fun isEmulator(): Boolean {
        val fingerprint = Build.FINGERPRINT
        val model = Build.MODEL
        val product = Build.PRODUCT
        val hardware = Build.HARDWARE
        return fingerprint.startsWith("generic") ||
            fingerprint.contains("emulator") ||
            model.contains("Emulator", ignoreCase = true) ||
            model.contains("Android SDK built for", ignoreCase = true) ||
            product.contains("sdk", ignoreCase = true) ||
            product.contains("emulator", ignoreCase = true) ||
            hardware.contains("goldfish", ignoreCase = true) ||
            hardware.contains("ranchu", ignoreCase = true)
    }

    private fun isOpenClLibraryPresent(): Boolean {
        val candidates = listOf(
            "/vendor/lib64/libOpenCL.so",
            "/vendor/lib/libOpenCL.so",
            "/system/vendor/lib64/libOpenCL.so",
            "/system/vendor/lib/libOpenCL.so",
            "/system/lib64/libOpenCL.so",
            "/system/lib/libOpenCL.so",
        )
        return candidates.any { File(it).exists() }
    }

    private fun isGpuUnavailable(error: Throwable): Boolean {
        val message = generateSequence(error) { it.cause }
            .mapNotNull { it.message }
            .joinToString(" ")
            .lowercase()
        return "opencl" in message ||
            "libopencl" in message ||
            "gpu delegate" in message ||
            ("gpu" in message && ("not found" in message || "unavailable" in message || "failed" in message))
    }

    private fun closeEngineLocked() {
        engine?.runCatching { close() }
        engine = null
    }

    private fun finishGenerationLocked(operationId: UUID) {
        if (state is State.Generating &&
            (state as State.Generating).operationId == operationId
        ) {
            currentConversation?.runCatching { close() }
            currentConversation = null
            state = if (engine == null) State.Idle else State.Ready
        }
    }

    private data class GenerationHandle(
        val operationId: UUID,
        val session: Conversation,
    )

    private fun Message.plainText(): String =
        contents.contents.mapNotNull { content ->
            (content as? Content.Text)?.text
        }.joinToString("")
}
