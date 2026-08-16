package ai.orynode.mobile.application

import java.nio.file.Paths
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class DocumentDisplayNameTest {
    @Test
    fun stripsLeadingDocumentIdFromStoredFileName() {
        val id = UUID.fromString("b25d812f-ff4e-436b-932e-23194e4db5cd")
        assertEquals(
            "orynode-smoke",
            DocumentDisplayName.fromFileName("$id-orynode-smoke.pdf", id),
        )
        assertEquals(
            "动手学大模型应用开发",
            DocumentDisplayName.normalizeStoredTitle(
                title = "$id-动手学大模型应用开发",
                sourcePath = "/tmp/$id-动手学大模型应用开发.pdf",
                documentId = id,
            ),
        )
    }

    @Test
    fun prefersOriginalDisplayName() {
        val id = UUID.randomUUID()
        assertEquals(
            "季度报告",
            DocumentDisplayName.title(
                preferred = "季度报告.pdf",
                path = Paths.get("/tmp/$id-季度报告.pdf"),
                documentId = id,
            ),
        )
    }
}
