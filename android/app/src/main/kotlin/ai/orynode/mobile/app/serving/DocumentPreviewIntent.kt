package ai.orynode.mobile.app.serving

import ai.orynode.mobile.domain.SourceLocator
import java.util.UUID

data class DocumentPreviewIntent(
    val documentId: UUID,
    val title: String,
    val filePath: String,
    val indexedText: String? = null,
    val locator: SourceLocator? = null,
    val excerpt: String? = null,
    val locatorLabel: String? = null,
) {
    val subtitle: String?
        get() = locatorLabel?.takeIf { it.isNotBlank() } ?: locator?.shortLabel

    val fileName: String
        get() = filePath.substringAfterLast('/')
}
