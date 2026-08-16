package ai.orynode.mobile.infrastructure.model

import ai.orynode.mobile.domain.InstalledModel
import ai.orynode.mobile.domain.ModelDescriptor
import ai.orynode.mobile.domain.ModelRuntimeError
import ai.orynode.mobile.domain.ModelStore
import org.json.JSONObject
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.UUID

/**
 * Copies a user-imported `.litertlm` into the app-private models directory.
 *
 * Import streams once into [modelsRoot] (no temp-on-other-volume double copy,
 * no full-file `readAllBytes`), matching iOS FileModelStore space behavior.
 */
class FileModelStore(
    private val modelsRoot: Path,
) : ModelStore {
    override suspend fun installedModel(forDescriptor: ModelDescriptor): InstalledModel? {
        val target = modelsRoot.resolve(forDescriptor.fileName)
        if (!Files.isRegularFile(target)) return null
        val meta = readMetadata(forDescriptor)
        if (meta != null &&
            meta.id == forDescriptor.id &&
            meta.version == forDescriptor.version
        ) {
            return InstalledModel(
                descriptor = forDescriptor,
                filePath = target,
                byteCount = meta.byteCount,
                sha256 = meta.sha256,
            )
        }
        val byteCount = Files.size(target)
        if (byteCount <= 0L) return null
        val digest = sha256OfFile(target)
        writeMetadata(
            forDescriptor,
            StoredMetadata(
                id = forDescriptor.id,
                version = forDescriptor.version,
                byteCount = byteCount,
                sha256 = digest,
            ),
        )
        return InstalledModel(
            descriptor = forDescriptor,
            filePath = target,
            byteCount = byteCount,
            sha256 = digest,
        )
    }

    override suspend fun importModel(from: Path, descriptor: ModelDescriptor): InstalledModel {
        if (!from.fileName.toString().endsWith(".litertlm", ignoreCase = true)) {
            throw ModelRuntimeError.InvalidModelFile
        }
        Files.newInputStream(from).use { input ->
            return importModel(input, from.fileName.toString(), descriptor)
        }
    }

    override suspend fun importModel(
        input: InputStream,
        sourceFileName: String,
        descriptor: ModelDescriptor,
    ): InstalledModel {
        if (!sourceFileName.endsWith(".litertlm", ignoreCase = true)) {
            throw ModelRuntimeError.InvalidModelFile
        }
        Files.createDirectories(modelsRoot)
        cleanupStaleImports()
        val destination = modelsRoot.resolve(descriptor.fileName)
        val staging = modelsRoot.resolve("${UUID.randomUUID()}.importing")
        try {
            Files.copy(input, staging, StandardCopyOption.REPLACE_EXISTING)
            val byteCount = Files.size(staging)
            if (byteCount <= 0L) throw ModelRuntimeError.InvalidModelFile
            val digest = sha256OfFile(staging)
            if (descriptor.expectedSha256 != null &&
                !descriptor.expectedSha256.equals(digest, ignoreCase = true)
            ) {
                throw ModelRuntimeError.ModelIntegrityCheckFailed
            }
            if (descriptor.expectedByteCount != null && descriptor.expectedByteCount != byteCount) {
                throw ModelRuntimeError.ModelIntegrityCheckFailed
            }
            moveReplace(staging, destination)
            writeMetadata(
                descriptor,
                StoredMetadata(
                    id = descriptor.id,
                    version = descriptor.version,
                    byteCount = byteCount,
                    sha256 = digest,
                ),
            )
            return InstalledModel(
                descriptor = descriptor,
                filePath = destination,
                byteCount = byteCount,
                sha256 = digest,
            )
        } catch (error: Exception) {
            Files.deleteIfExists(staging)
            throw mapSpaceError(error)
        } finally {
            Files.deleteIfExists(staging)
        }
    }

    override suspend fun promoteDownloadedModel(
        partial: Path,
        descriptor: ModelDescriptor,
    ): InstalledModel {
        if (!descriptor.fileName.endsWith(".litertlm", ignoreCase = true)) {
            throw ModelRuntimeError.InvalidModelFile
        }
        if (!Files.isRegularFile(partial)) {
            throw ModelRuntimeError.InvalidModelFile
        }
        Files.createDirectories(modelsRoot)
        cleanupStaleImports()
        val destination = modelsRoot.resolve(descriptor.fileName)
        try {
            val byteCount = Files.size(partial)
            if (byteCount <= 0L) throw ModelRuntimeError.InvalidModelFile
            val digest = sha256OfFile(partial)
            if (descriptor.expectedSha256 != null &&
                !descriptor.expectedSha256.equals(digest, ignoreCase = true)
            ) {
                throw ModelRuntimeError.ModelIntegrityCheckFailed
            }
            if (descriptor.expectedByteCount != null && descriptor.expectedByteCount != byteCount) {
                throw ModelRuntimeError.ModelIntegrityCheckFailed
            }
            moveReplace(partial, destination)
            Files.deleteIfExists(partialMetaBeside(partial))
            writeMetadata(
                descriptor,
                StoredMetadata(
                    id = descriptor.id,
                    version = descriptor.version,
                    byteCount = byteCount,
                    sha256 = digest,
                ),
            )
            return InstalledModel(
                descriptor = descriptor,
                filePath = destination,
                byteCount = byteCount,
                sha256 = digest,
            )
        } catch (error: Exception) {
            throw mapSpaceError(error)
        }
    }

    override suspend fun deleteModel(descriptor: ModelDescriptor) {
        Files.deleteIfExists(modelsRoot.resolve(descriptor.fileName))
        Files.deleteIfExists(metadataPath(descriptor))
    }

    private fun partialMetaBeside(partial: Path): Path =
        partial.resolveSibling("${partial.fileName}.meta")

    private fun cleanupStaleImports() {
        runCatching {
            Files.list(modelsRoot).use { stream ->
                stream.filter { it.fileName.toString().endsWith(".importing") }
                    .forEach { Files.deleteIfExists(it) }
            }
        }
    }

    private fun moveReplace(from: Path, to: Path) {
        try {
            Files.move(
                from,
                to,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun metadataPath(descriptor: ModelDescriptor): Path =
        modelsRoot.resolve("${descriptor.id}-${descriptor.version}.json")

    private fun readMetadata(descriptor: ModelDescriptor): StoredMetadata? {
        val path = metadataPath(descriptor)
        if (!Files.isRegularFile(path)) return null
        return runCatching {
            val text = String(Files.readAllBytes(path), Charsets.UTF_8)
            val json = JSONObject(text)
            StoredMetadata(
                id = json.getString("id"),
                version = json.getString("version"),
                byteCount = json.getLong("byteCount"),
                sha256 = json.getString("sha256"),
            )
        }.getOrNull()
    }

    private fun writeMetadata(descriptor: ModelDescriptor, metadata: StoredMetadata) {
        val json = JSONObject()
            .put("id", metadata.id)
            .put("version", metadata.version)
            .put("byteCount", metadata.byteCount)
            .put("sha256", metadata.sha256)
        Files.write(metadataPath(descriptor), json.toString().toByteArray(Charsets.UTF_8))
    }

    private fun sha256OfFile(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { raw ->
            DigestInputStream(raw, digest).use { stream ->
                val buffer = ByteArray(4 * 1024 * 1024)
                while (stream.read(buffer) != -1) {
                    // digest updated by DigestInputStream
                }
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun mapSpaceError(error: Exception): Exception {
        val message = error.message.orEmpty()
        if (message.contains("No space left", ignoreCase = true) ||
            message.contains("ENOSPC", ignoreCase = true)
        ) {
            return ModelRuntimeError.InsufficientStorage
        }
        return error
    }

    private data class StoredMetadata(
        val id: String,
        val version: String,
        val byteCount: Long,
        val sha256: String,
    )
}
