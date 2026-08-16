package ai.orynode.mobile.infrastructure.extraction

import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import javax.xml.parsers.SAXParserFactory

internal fun saxParse(xml: String, handler: DefaultHandler) {
    val factory = SAXParserFactory.newInstance()
    factory.isNamespaceAware = true
    val parser = factory.newSAXParser()
    // XML declaration must be at byte 0; Kotlin trimIndent fixtures often leave a leading newline.
    val cleaned = xml.trimStart('\uFEFF', ' ', '\t', '\r', '\n')
    parser.parse(ByteArrayInputStream(cleaned.toByteArray(StandardCharsets.UTF_8)), handler)
}

internal fun localName(qName: String?, localName: String?): String {
    if (!localName.isNullOrEmpty()) return localName
    val name = qName.orEmpty()
    return name.substringAfterLast(':', missingDelimiterValue = name)
}

internal fun collapseWhitespace(text: String): String =
    text
        .replace('\u00A0', ' ')
        .lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .joinToString(" ")
        .trim()

internal fun markdownTable(rows: List<List<String>>): String {
    val normalized = rows.map { row -> row.map(::escapeTableCell) }
    val header = normalized.firstOrNull() ?: return ""
    if (header.isEmpty()) return ""
    val width = normalized.maxOf { it.size }
    fun pad(row: List<String>): List<String> =
        if (row.size >= width) row.take(width)
        else row + List(width - row.size) { "" }
    val padded = normalized.map(::pad)
    val head = padded.first()
    val separator = List(width) { "---" }
    val lines = mutableListOf(
        "| ${head.joinToString(" | ")} |",
        "| ${separator.joinToString(" | ")} |",
    )
    for (row in padded.drop(1)) {
        lines += "| ${row.joinToString(" | ")} |"
    }
    return lines.joinToString("\n")
}

private fun escapeTableCell(text: String): String =
    collapseWhitespace(text).replace("|", "\\|")

internal class DocxBodyParser : DefaultHandler() {
    private val paragraphs = mutableListOf<String>()
    private var currentParagraph = StringBuilder()
    private var currentHeadingLevel: Int? = null
    private var inParagraph = false
    private var inText = false
    private var tableRows = mutableListOf<List<String>>()
    private var currentRow = mutableListOf<String>()
    private var currentCell = StringBuilder()
    private var inTable = false
    private var inCell = false
    private var skipDepth = 0

    companion object {
        fun parse(xml: String): String {
            val handler = DocxBodyParser()
            saxParse(xml, handler)
            return handler.paragraphs.joinToString("\n\n")
        }
    }

    override fun startElement(
        uri: String?,
        localName: String?,
        qName: String?,
        attributes: Attributes,
    ) {
        val local = localName(qName, localName)
        if (skipDepth > 0) {
            skipDepth += 1
            return
        }
        when (local) {
            "tab", "br", "cr" -> {
                val token = if (local == "tab") "\t" else "\n"
                if (inCell) currentCell.append(token)
                else if (inParagraph) currentParagraph.append(token)
            }
            "p" -> {
                inParagraph = true
                currentParagraph = StringBuilder()
                currentHeadingLevel = null
            }
            "pStyle" -> {
                val value = attributes.getValue("w:val") ?: attributes.getValue("val")
                if (value != null) currentHeadingLevel = headingLevel(value)
            }
            "outlineLvl" -> {
                val raw = attributes.getValue("w:val") ?: attributes.getValue("val")
                val level = raw?.toIntOrNull()
                if (level != null) currentHeadingLevel = (level + 1).coerceIn(1, 6)
            }
            "t" -> inText = true
            "tbl" -> {
                inTable = true
                tableRows = mutableListOf()
            }
            "tr" -> currentRow = mutableListOf()
            "tc" -> {
                inCell = true
                currentCell = StringBuilder()
            }
            "drawing", "pict", "object" -> skipDepth = 1
        }
    }

    override fun characters(ch: CharArray, start: Int, length: Int) {
        if (skipDepth != 0 || !inText) return
        val chunk = String(ch, start, length)
        if (inCell) currentCell.append(chunk)
        else if (inParagraph) currentParagraph.append(chunk)
    }

    override fun endElement(uri: String?, localName: String?, qName: String?) {
        val local = localName(qName, localName)
        if (skipDepth > 0) {
            skipDepth -= 1
            return
        }
        when (local) {
            "t" -> inText = false
            "tc" -> {
                currentRow += collapseWhitespace(currentCell.toString())
                inCell = false
                currentCell = StringBuilder()
            }
            "tr" -> {
                if (currentRow.isNotEmpty()) tableRows += currentRow.toList()
                currentRow = mutableListOf()
            }
            "tbl" -> {
                val markdown = markdownTable(tableRows)
                if (markdown.isNotEmpty()) paragraphs += markdown
                inTable = false
                tableRows = mutableListOf()
            }
            "p" -> {
                val text = collapseWhitespace(currentParagraph.toString())
                if (text.isNotEmpty()) {
                    val level = currentHeadingLevel
                    if (level != null) {
                        paragraphs += "${"#".repeat(level)} $text"
                    } else if (!inTable) {
                        paragraphs += text
                    }
                }
                inParagraph = false
                currentParagraph = StringBuilder()
                currentHeadingLevel = null
            }
        }
    }

    private fun headingLevel(style: String): Int? {
        val lower = style.lowercase()
        if (lower.startsWith("heading")) {
            val digits = lower.dropWhile { !it.isDigit() }.takeWhile { it.isDigit() }
            return digits.toIntOrNull()?.coerceIn(1, 6)
        }
        return when (lower) {
            "title" -> 1
            "subtitle" -> 2
            else -> null
        }
    }
}

internal class SharedStringsParser : DefaultHandler() {
    private val strings = mutableListOf<String>()
    private var current = StringBuilder()
    private var inText = false

    companion object {
        fun parse(xml: String): List<String> {
            val handler = SharedStringsParser()
            saxParse(xml, handler)
            return handler.strings
        }
    }

    override fun startElement(
        uri: String?,
        localName: String?,
        qName: String?,
        attributes: Attributes,
    ) {
        when (localName(qName, localName)) {
            "si" -> current = StringBuilder()
            "t" -> inText = true
        }
    }

    override fun characters(ch: CharArray, start: Int, length: Int) {
        if (inText) current.append(ch, start, length)
    }

    override fun endElement(uri: String?, localName: String?, qName: String?) {
        when (localName(qName, localName)) {
            "t" -> inText = false
            "si" -> {
                strings += current.toString()
                current = StringBuilder()
            }
        }
    }
}

internal data class WorkbookSheet(val name: String, val relationshipId: String)

internal class WorkbookSheetParser : DefaultHandler() {
    private val parsed = mutableListOf<WorkbookSheet>()

    companion object {
        fun parse(xml: String): List<WorkbookSheet> {
            val handler = WorkbookSheetParser()
            saxParse(xml, handler)
            return handler.parsed
        }
    }

    override fun startElement(
        uri: String?,
        localName: String?,
        qName: String?,
        attributes: Attributes,
    ) {
        if (localName(qName, localName) != "sheet") return
        val name = attributes.getValue("name") ?: "Sheet"
        val rid = attributes.getValue("r:id") ?: attributes.getValue("id").orEmpty()
        if (rid.isEmpty()) return
        parsed += WorkbookSheet(name, rid)
    }
}

internal class RelationshipsParser : DefaultHandler() {
    private val map = mutableMapOf<String, String>()

    companion object {
        fun parse(xml: String): Map<String, String> {
            val handler = RelationshipsParser()
            saxParse(xml, handler)
            return handler.map
        }
    }

    override fun startElement(
        uri: String?,
        localName: String?,
        qName: String?,
        attributes: Attributes,
    ) {
        if (localName(qName, localName) != "Relationship") return
        val id = attributes.getValue("Id") ?: return
        val target = attributes.getValue("Target") ?: return
        map[id] = target
    }
}

internal class XlsxSheetParser(
    private val sharedStrings: List<String>,
) : DefaultHandler() {
    private val rows = mutableListOf<List<String>>()
    private var currentRow = mutableListOf<String>()
    private var currentCellRef = ""
    private var currentType = ""
    private var currentValue = StringBuilder()
    private var inValue = false
    private var inInlineString = false
    private var expectedColumn = 1

    companion object {
        fun parse(xml: String, sharedStrings: List<String>): String {
            val handler = XlsxSheetParser(sharedStrings)
            saxParse(xml, handler)
            return markdownTable(handler.rows)
        }
    }

    override fun startElement(
        uri: String?,
        localName: String?,
        qName: String?,
        attributes: Attributes,
    ) {
        when (localName(qName, localName)) {
            "row" -> {
                currentRow = mutableListOf()
                expectedColumn = 1
            }
            "c" -> {
                currentCellRef = attributes.getValue("r").orEmpty()
                currentType = attributes.getValue("t").orEmpty()
                currentValue = StringBuilder()
                val column = columnIndex(currentCellRef) ?: expectedColumn
                while (expectedColumn < column) {
                    currentRow += ""
                    expectedColumn += 1
                }
            }
            "v" -> inValue = true
            "t" -> {
                if (inInlineString || currentType == "inlineStr") inValue = true
            }
            "is" -> inInlineString = true
        }
    }

    override fun characters(ch: CharArray, start: Int, length: Int) {
        if (inValue) currentValue.append(ch, start, length)
    }

    override fun endElement(uri: String?, localName: String?, qName: String?) {
        when (localName(qName, localName)) {
            "v", "t" -> inValue = false
            "is" -> inInlineString = false
            "c" -> {
                val raw = currentValue.toString()
                val text = if (currentType == "s") {
                    val index = raw.toIntOrNull()
                    if (index != null && index in sharedStrings.indices) sharedStrings[index] else raw
                } else {
                    raw
                }
                currentRow += text
                expectedColumn += 1
                currentValue = StringBuilder()
                currentType = ""
            }
            "row" -> {
                if (currentRow.any { it.isNotBlank() }) rows += currentRow.toList()
                currentRow = mutableListOf()
            }
        }
    }

    private fun columnIndex(reference: String): Int? {
        val letters = reference.takeWhile { it.isLetter() }
        if (letters.isEmpty()) return null
        var value = 0
        for (ch in letters.uppercase()) {
            value = value * 26 + (ch.code - 64)
        }
        return value
    }
}

internal class PptxSlideParser : DefaultHandler() {
    private val lines = mutableListOf<String>()
    private var current = StringBuilder()
    private var inText = false

    companion object {
        fun parse(xml: String): String {
            val handler = PptxSlideParser()
            saxParse(xml, handler)
            handler.flush()
            return handler.lines.joinToString("\n\n")
        }
    }

    override fun startElement(
        uri: String?,
        localName: String?,
        qName: String?,
        attributes: Attributes,
    ) {
        when (localName(qName, localName)) {
            "t" -> inText = true
            "br" -> current.append('\n')
        }
    }

    override fun characters(ch: CharArray, start: Int, length: Int) {
        if (inText) current.append(ch, start, length)
    }

    override fun endElement(uri: String?, localName: String?, qName: String?) {
        when (localName(qName, localName)) {
            "t" -> inText = false
            "p" -> flush()
        }
    }

    private fun flush() {
        val text = collapseWhitespace(current.toString())
        if (text.isNotEmpty()) lines += text
        current = StringBuilder()
    }
}
