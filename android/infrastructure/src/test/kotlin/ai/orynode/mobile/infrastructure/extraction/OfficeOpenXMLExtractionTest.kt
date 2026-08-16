package ai.orynode.mobile.infrastructure.extraction

import ai.orynode.mobile.domain.KnowledgeBaseError
import ai.orynode.mobile.domain.KnowledgeExtraction
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertFailsWith

class OfficeOpenXMLExtractionTest {
    @Test
    fun extractsDocxXlsxPptxAsMarkdown() = runBlocking {
        val directory = Files.createTempDirectory("office-extract")
        val docx = writeDocx(
            directory.resolve("guide.docx"),
            heading = "离线知识库",
            body = "本机索引不会上传文档。",
        )
        val xlsx = writeXlsx(
            directory.resolve("table.xlsx"),
            sheetName = "清单",
            rows = listOf(listOf("项目", "说明"), listOf("导入", "支持 docx")),
        )
        val pptx = writePptx(
            directory.resolve("deck.pptx"),
            slideText = "严格离线私人知识库",
        )

        val extractor = LocalKnowledgeTextExtractor(textRecognizer = null, context = null)

        val docxExtraction = extractor.extract(docx)
        assertEquals(KnowledgeExtraction.Kind.Markdown, docxExtraction.kind)
        assertTrue(docxExtraction.indexedText.contains("# 离线知识库"))
        assertTrue(docxExtraction.indexedText.contains("不会上传文档"))

        val xlsxExtraction = extractor.extract(xlsx)
        assertEquals(KnowledgeExtraction.Kind.Markdown, xlsxExtraction.kind)
        assertTrue(xlsxExtraction.indexedText.contains("## 清单"))
        assertTrue(xlsxExtraction.indexedText.contains("导入"))
        assertTrue(xlsxExtraction.indexedText.contains("支持 docx"))

        val pptxExtraction = extractor.extract(pptx)
        assertEquals(KnowledgeExtraction.Kind.Markdown, pptxExtraction.kind)
        assertTrue(pptxExtraction.indexedText.contains("## Slide 1"))
        assertTrue(pptxExtraction.indexedText.contains("严格离线私人知识库"))
    }

    @Test
    fun rejectsLegacyBinaryOfficeExtensions() {
        runBlocking {
            val legacy = Files.createTempFile("old", ".doc")
            Files.write(legacy, byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte()))
            val extractor = LocalKnowledgeTextExtractor(textRecognizer = null, context = null)
            assertFailsWith<KnowledgeBaseError.UnsupportedFileType> {
                extractor.extract(legacy)
            }
        }
    }

    private fun writeDocx(
        path: java.nio.file.Path,
        heading: String,
        body: String,
    ): java.nio.file.Path {
        val xml = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
              <w:body>
                <w:p>
                  <w:pPr><w:pStyle w:val="Heading1"/></w:pPr>
                  <w:r><w:t>$heading</w:t></w:r>
                </w:p>
                <w:p>
                  <w:r><w:t>$body</w:t></w:r>
                </w:p>
              </w:body>
            </w:document>
        """.trimIndent()
        writeZip(path, mapOf("word/document.xml" to xml))
        return path
    }

    private fun writeXlsx(
        path: java.nio.file.Path,
        sheetName: String,
        rows: List<List<String>>,
    ): java.nio.file.Path {
        val shared = mutableListOf<String>()
        fun sharedIndex(value: String): Int {
            val existing = shared.indexOf(value)
            if (existing >= 0) return existing
            shared += value
            return shared.lastIndex
        }
        val sheetRows = rows.mapIndexed { rowIndex, cols ->
            val cells = cols.mapIndexed { colIndex, value ->
                val ref = "${columnName(colIndex + 1)}${rowIndex + 1}"
                val index = sharedIndex(value)
                """<c r="$ref" t="s"><v>$index</v></c>"""
            }.joinToString("")
            """<row r="${rowIndex + 1}">$cells</row>"""
        }.joinToString("")
        val sharedXML = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" count="${shared.size}" uniqueCount="${shared.size}">
            ${shared.joinToString("\n") { "<si><t>$it</t></si>" }}
            </sst>
        """.trimIndent()
        val workbookXML = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"
                      xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
              <sheets>
                <sheet name="$sheetName" sheetId="1" r:id="rId1"/>
              </sheets>
            </workbook>
        """.trimIndent()
        val relsXML = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
              <Relationship Id="rId1"
                Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet"
                Target="worksheets/sheet1.xml"/>
            </Relationships>
        """.trimIndent()
        val sheetXML = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
              <sheetData>$sheetRows</sheetData>
            </worksheet>
        """.trimIndent()
        writeZip(
            path,
            mapOf(
                "xl/sharedStrings.xml" to sharedXML,
                "xl/workbook.xml" to workbookXML,
                "xl/_rels/workbook.xml.rels" to relsXML,
                "xl/worksheets/sheet1.xml" to sheetXML,
            ),
        )
        return path
    }

    private fun writePptx(path: java.nio.file.Path, slideText: String): java.nio.file.Path {
        val slideXML = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <p:sld xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"
                   xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main">
              <p:cSld>
                <p:spTree>
                  <p:sp>
                    <p:txBody>
                      <a:p>
                        <a:r><a:t>$slideText</a:t></a:r>
                      </a:p>
                    </p:txBody>
                  </p:sp>
                </p:spTree>
              </p:cSld>
            </p:sld>
        """.trimIndent()
        writeZip(path, mapOf("ppt/slides/slide1.xml" to slideXML))
        return path
    }

    private fun writeZip(path: java.nio.file.Path, entries: Map<String, String>) {
        Files.deleteIfExists(path)
        ZipOutputStream(Files.newOutputStream(path)).use { zip ->
            for ((name, xml) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(xml.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
    }

    private fun columnName(index: Int): String {
        var value = index
        var name = ""
        while (value > 0) {
            value -= 1
            val remainder = value % 26
            name = ('A'.code + remainder).toChar() + name
            value /= 26
        }
        return name
    }
}
