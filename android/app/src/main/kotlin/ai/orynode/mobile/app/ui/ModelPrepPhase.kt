package ai.orynode.mobile.app.ui

/**
 * User-visible model preparation after a file is chosen (or cold-start check/load).
 *
 * Selecting a file is not a phase — the system picker is just selection.
 * Idle → Importing → LoadingEngine → Idle
 * Cold start: Idle → Checking → (LoadingEngine | Idle)
 */
sealed class ModelPrepPhase {
    data object Idle : ModelPrepPhase()
    data object Checking : ModelPrepPhase()
    data object Importing : ModelPrepPhase()
    data object LoadingEngine : ModelPrepPhase()

    val showsProgress: Boolean
        get() = this !is Idle

    val statusMessage: String?
        get() = when (this) {
            Idle -> null
            Checking -> "正在检查本机模型…"
            Importing -> "正在安全导入模型…"
            LoadingEngine -> "正在加载本地模型…"
        }
}
