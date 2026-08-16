package ai.orynode.mobile.infrastructure.extraction

import ai.orynode.mobile.domain.KnowledgeBaseError
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.zip.ZipFile

/**
 * Minimal OOXML → Markdown extractors for on-device RAG ingest.
 * Modern Office Open XML only (docx / xlsx / pptx). Legacy binary formats remain unsupported.
 */
object OfficeOpenXMLMarkdownExtractor {
    fun supportsExtension(ext: String): Boolean =
        when (ext.lowercase()) {
            "docx", "docm", "xlsx", "xlsm", "pptx", "pptm" -> true
            else -> false
        }

    fun extract(from: Path): String {
        val name = from.fileName.toString().lowercase()
        return when {
            name.endsWith(".docx") || name.endsWith(".docm") -> extractDocx(from)
            name.endsWith(".xlsx") || name.endsWith(".xlsm") -> extractXlsx(from)
            name.endsWith(".pptx") || name.endsWith(".pptm") -> extractPptx(from)
            else -> {
                val ext = name.substringAfterLast('.', missingDelimiterValue = "")
                throw KnowledgeBaseError.UnsupportedFileType(ext.ifEmpty { name })
            }
        }
    }

    private fun extractDocx(from: Path): String {
        ZipFile(from.toFile()).use { zip ->
            val xml = readUtf8Entry(zip, "word/document.xml")
                ?: throw KnowledgeBaseError.Storage("无法读取 Word 文档内容。")
            return finalize(DocxBodyParser.parse(xml))
        }
    }

    private fun extractXlsx(from: Path): String {
        ZipFile(from.toFile()).use { zip ->
            val sharedStrings = readUtf8Entry(zip, "xl/sharedStrings.xml")
                ?.let { SharedStringsParser.parse(it) }
                ?: emptyList()
            val sheets = loadWorkbookSheets(zip)
            val sections = mutableListOf<String>()
            for (sheet in sheets) {
                val xml = readUtf8Entry(zip, sheet.path) ?: continue
                val table = XlsxSheetParser.parse(xml, sharedStrings)
                if (table.isBlank()) continue
                sections += "## ${sheet.name}\n\n$table"
            }
            return finalize(sections.joinToString("\n\n"))
        }
    }

    private fun extractPptx(from: Path): String {
        ZipFile(from.toFile()).use { zip ->
            val slidePaths = zip.entries().asSequence()
                .map { it.name }
                .filter { path ->
                    path.startsWith("ppt/slides/slide") &&
                        path.endsWith(".xml") &&
                        !path.contains("_rels")
                }
                .sortedBy { slideIndex(it) }
                .toList()
            val sections = mutableListOf<String>()
            for ((offset, path) in slidePaths.withIndex()) {
                val xml = readUtf8Entry(zip, path) ?: continue
                val body = PptxSlideParser.parse(xml)
                if (body.isBlank()) continue
                sections += "## Slide ${offset + 1}\n\n$body"
            }
            return finalize(sections.joinToString("\n\n"))
        }
    }

    private fun loadWorkbookSheets(zip: ZipFile): List<SheetRef> {
        val workbookXML = readUtf8Entry(zip, "xl/workbook.xml")
            ?: throw KnowledgeBaseError.Storage("无法读取 Excel 工作簿。")
        val sheets = WorkbookSheetParser.parse(workbookXML)
        val rels = readUtf8Entry(zip, "xl/_rels/workbook.xml.rels")
            ?.let { RelationshipsParser.parse(it) }
            ?: emptyMap()
        val resolved = sheets.mapNotNull { sheet ->
            val target = rels[sheet.relationshipId] ?: return@mapNotNull null
            val path = if (target.startsWith("/")) target.drop(1) else "xl/$target"
            SheetRef(sheet.name, path)
        }
        if (resolved.isNotEmpty()) return resolved
        return zip.entries().asSequence()
            .map { it.name }
            .filter { it.startsWith("xl/worksheets/sheet") && it.endsWith(".xml") }
            .sorted()
            .mapIndexed { index, path -> SheetRef("Sheet ${index + 1}", path) }
            .toList()
    }

    private fun finalize(text: String): String {
        val normalized = PdfOcrPolicy.normalize(text).trim()
        if (normalized.isEmpty()) throw KnowledgeBaseError.EmptyDocument
        return normalized
    }

    private fun slideIndex(path: String): Int {
        val name = path.substringAfterLast('/').substringBeforeLast('.')
        val digits = name.dropWhile { !it.isDigit() }.takeWhile { it.isDigit() }
        return digits.toIntOrNull() ?: Int.MAX_VALUE
    }

    internal fun readUtf8Entry(zip: ZipFile, path: String): String? {
        val entry = zip.getEntry(path) ?: return null
        return zip.getInputStream(entry).use { stream ->
            stream.readBytes().toString(StandardCharsets.UTF_8)
        }
    }

    private data class SheetRef(val name: String, val path: String)
}
