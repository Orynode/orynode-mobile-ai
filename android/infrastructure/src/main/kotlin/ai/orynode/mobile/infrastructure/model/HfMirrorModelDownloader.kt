package ai.orynode.mobile.infrastructure.model

import ai.orynode.mobile.domain.ModelDownloadProgress
import ai.orynode.mobile.domain.ResumableModelDownloader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.coroutineContext

/**
 * Resumable download of the Gemma `.litertlm` from hf-mirror into [modelsRoot].
 * Cancel keeps the `.partial` file for the next attempt.
 */
class HfMirrorModelDownloader(
    private val modelsRoot: Path,
    private val fileName: String = DEFAULT_FILE_NAME,
    private val downloadUrl: String = DEFAULT_URL,
    private val client: OkHttpClient = defaultClient(),
) : ResumableModelDownloader {
    private val activeCall = AtomicReference<okhttp3.Call?>(null)

    private val partialPath: Path get() = modelsRoot.resolve("$fileName.partial")
    private val metaPath: Path get() = modelsRoot.resolve("$fileName.partial.meta")

    /** Staging file used for resumable downloads. */
    override fun partialFile(): Path = partialPath

    override fun cancel() {
        activeCall.getAndSet(null)?.cancel()
    }

    /**
     * Downloads (or resumes) into `{fileName}.partial`. Returns that path when complete.
     * Does not install into the final model slot — call [FileModelStore.promoteDownloadedModel].
     *
     * If a previous run already finished the partial (download OK, install failed), skips the
     * network and returns the existing file so the caller can promote again.
     */
    override suspend fun download(
        onProgress: suspend (ModelDownloadProgress) -> Unit,
    ): Path = withContext(Dispatchers.IO) {
        Files.createDirectories(modelsRoot)
        var existing = if (Files.isRegularFile(partialPath)) Files.size(partialPath) else 0L
        if (existing < 0L) existing = 0L
        var totalBytes = readMeta()?.totalBytes

        // Download finished earlier but promote/load failed — reuse staging file.
        if (existing > 0L && totalBytes != null && existing >= totalBytes) {
            onProgress(ModelDownloadProgress(existing, totalBytes))
            return@withContext partialPath
        }

        val requestBuilder = Request.Builder().url(downloadUrl)
        if (existing > 0L) {
            requestBuilder.header("Range", "bytes=$existing-")
        }

        val call = client.newCall(requestBuilder.build())
        activeCall.set(call)
        try {
            val response = call.execute()
            if (call.isCanceled()) {
                throw kotlinx.coroutines.CancellationException("模型下载已取消")
            }
            if (!response.isSuccessful && response.code != HTTP_PARTIAL) {
                response.close()
                throw IOException("下载失败（HTTP ${response.code}）")
            }

            when (response.code) {
                HTTP_OK -> {
                    // Server ignored Range or first full response — rewrite from byte 0.
                    existing = 0L
                    Files.deleteIfExists(partialPath)
                    totalBytes = response.header("Content-Length")?.toLongOrNull()
                        ?: totalBytes
                    writeMeta(totalBytes)
                }
                HTTP_PARTIAL -> {
                    val fromRange = parseTotalFromContentRange(response.header("Content-Range"))
                    val contentLength = response.header("Content-Length")?.toLongOrNull()
                    totalBytes = fromRange
                        ?: contentLength?.let { existing + it }
                        ?: totalBytes
                    writeMeta(totalBytes)
                }
                else -> {
                    response.close()
                    throw IOException("下载失败（HTTP ${response.code}）")
                }
            }

            val body = response.body ?: run {
                response.close()
                throw IOException("下载失败（空响应）")
            }

            body.byteStream().use { input ->
                val options = if (existing > 0L) {
                    arrayOf(
                        StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE,
                        StandardOpenOption.APPEND,
                    )
                } else {
                    arrayOf(
                        StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE,
                        StandardOpenOption.TRUNCATE_EXISTING,
                    )
                }
                Files.newOutputStream(partialPath, *options).use { output ->
                    val buffer = ByteArray(256 * 1024)
                    var received = existing
                    onProgress(ModelDownloadProgress(received, totalBytes))
                    while (true) {
                        coroutineContext.ensureActive()
                        if (call.isCanceled()) {
                            throw kotlinx.coroutines.CancellationException("模型下载已取消")
                        }
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        received += n
                        onProgress(ModelDownloadProgress(received, totalBytes))
                    }
                    if (totalBytes != null && received < totalBytes) {
                        throw IOException("下载不完整（$received / $totalBytes 字节）")
                    }
                }
            }
            response.close()
            if (!Files.isRegularFile(partialPath) || Files.size(partialPath) <= 0L) {
                throw IOException("下载失败（文件为空）")
            }
            partialPath
        } catch (error: CancellationException) {
            throw error
        } catch (error: IOException) {
            if (call.isCanceled() || isCancelMessage(error.message)) {
                throw CancellationException("模型下载已取消", error)
            }
            throw error
        } finally {
            activeCall.compareAndSet(call, null)
        }
    }

    override fun clearPartial() {
        Files.deleteIfExists(partialPath)
        Files.deleteIfExists(metaPath)
    }

    private fun readMeta(): PartialMeta? {
        if (!Files.isRegularFile(metaPath)) return null
        return runCatching {
            val json = JSONObject(String(Files.readAllBytes(metaPath), Charsets.UTF_8))
            PartialMeta(
                totalBytes = if (json.has("totalBytes") && !json.isNull("totalBytes")) {
                    json.getLong("totalBytes")
                } else {
                    null
                },
            )
        }.getOrNull()
    }

    private fun writeMeta(totalBytes: Long?) {
        val json = JSONObject()
        if (totalBytes != null) json.put("totalBytes", totalBytes)
        else json.put("totalBytes", JSONObject.NULL)
        Files.write(metaPath, json.toString().toByteArray(Charsets.UTF_8))
    }

    private data class PartialMeta(val totalBytes: Long?)

    companion object {
        const val DEFAULT_FILE_NAME = "gemma-4-E2B-it.litertlm"
        const val DEFAULT_URL =
            "https://hf-mirror.com/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm"

        private const val HTTP_OK = 200
        private const val HTTP_PARTIAL = 206

        fun defaultClient(): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .build()

        /** `Content-Range: bytes 0-1023/2048` → 2048 */
        fun parseTotalFromContentRange(header: String?): Long? {
            if (header.isNullOrBlank()) return null
            val slash = header.lastIndexOf('/')
            if (slash < 0 || slash == header.lastIndex) return null
            val total = header.substring(slash + 1).trim()
            if (total == "*") return null
            return total.toLongOrNull()
        }

        private fun isCancelMessage(message: String?): Boolean {
            if (message.isNullOrBlank()) return false
            return message.contains("Canceled", ignoreCase = true) ||
                message.contains("Cancelled", ignoreCase = true) ||
                message.contains("Socket closed", ignoreCase = true)
        }
    }
}
