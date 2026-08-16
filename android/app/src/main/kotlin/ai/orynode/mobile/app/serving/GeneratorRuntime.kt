package ai.orynode.mobile.app.serving

/** UI-facing generator lifecycle — mirrors Domain `ModelRuntimeState` without leaking path / LiteRT types. */
sealed class GeneratorRuntimeState {
    data object NotInstalled : GeneratorRuntimeState()
    data object Installed : GeneratorRuntimeState()
    data object Loading : GeneratorRuntimeState()
    data object Ready : GeneratorRuntimeState()
    data class Failed(val message: String) : GeneratorRuntimeState()
}

data class InstalledGeneratorInfo(
    val displayName: String,
    val byteCount: Long,
) {
    val storageDescription: String
        get() {
            val gb = byteCount / 1_000_000_000.0
            return String.format("%.2f GB · 已安装", gb)
        }
}
