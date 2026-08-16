package ai.orynode.mobile.app.ui

import android.content.Context
import java.io.File
import java.util.Locale

/** On-device footprints used by Settings (parity with iOS LocalDataFootprint). */
object LocalDataFootprint {
    fun knowledgeBaseByteCount(context: Context): Long =
        directoryByteCount(File(context.filesDir, "KnowledgeBase"))

    fun chatHistoryByteCount(context: Context): Long =
        directoryByteCount(File(context.filesDir, "KnowledgeChat"))

    fun formattedByteCount(bytes: Long): String {
        val value = maxOf(0L, bytes).toDouble()
        return when {
            value >= 1_000_000_000 -> String.format(Locale.US, "%.2f GB", value / 1_000_000_000)
            value >= 1_000_000 -> String.format(Locale.US, "%.1f MB", value / 1_000_000)
            value >= 1_000 -> String.format(Locale.US, "%.0f KB", value / 1_000)
            else -> "$bytes B"
        }
    }

    private fun directoryByteCount(root: File): Long {
        if (!root.exists()) return 0
        if (root.isFile) return root.length()
        var total = 0L
        root.walkTopDown().forEach { file ->
            if (file.isFile) total += file.length()
        }
        return total
    }
}
