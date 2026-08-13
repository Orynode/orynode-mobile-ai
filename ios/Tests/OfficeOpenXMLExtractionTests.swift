import XCTest
import ZIPFoundation
import OrynodeApplication
import OrynodeDomain
@testable import OrynodeInfrastructure

final class OfficeOpenXMLExtractionTests: XCTestCase {
    func testExtractsDocxXlsxPptxAsMarkdown() async throws {
        let directory = FileManager.default.temporaryDirectory
            .appending(path: "office-extract-\(UUID().uuidString)", directoryHint: .isDirectory)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }

        let docx = try Self.writeDocx(
            at: directory.appending(path: "guide.docx"),
            heading: "离线知识库",
            body: "本机索引不会上传文档。"
        )
        let xlsx = try Self.writeXlsx(
            at: directory.appending(path: "table.xlsx"),
            sheetName: "清单",
            rows: [["项目", "说明"], ["导入", "支持 docx"]]
        )
        let pptx = try Self.writePptx(
            at: directory.appending(path: "deck.pptx"),
            slideText: "严格离线私人知识库"
        )

        let extractor = LocalKnowledgeTextExtractor()

        let docxExtraction = try await extractor.extract(from: docx)
        XCTAssertEqual(docxExtraction.kind, .markdown)
        XCTAssertTrue(docxExtraction.indexedText.contains("# 离线知识库"))
        XCTAssertTrue(docxExtraction.indexedText.contains("不会上传文档"))

        let xlsxExtraction = try await extractor.extract(from: xlsx)
        XCTAssertEqual(xlsxExtraction.kind, .markdown)
        XCTAssertTrue(xlsxExtraction.indexedText.contains("## 清单"))
        XCTAssertTrue(xlsxExtraction.indexedText.contains("导入"))
        XCTAssertTrue(xlsxExtraction.indexedText.contains("支持 docx"))

        let pptxExtraction = try await extractor.extract(from: pptx)
        XCTAssertEqual(pptxExtraction.kind, .markdown)
        XCTAssertTrue(pptxExtraction.indexedText.contains("## Slide 1"))
        XCTAssertTrue(pptxExtraction.indexedText.contains("严格离线私人知识库"))

        let chunks = StructuredKnowledgeChunker().chunks(
            documentID: UUID(),
            extraction: docxExtraction
        )
        XCTAssertFalse(chunks.isEmpty)
        XCTAssertTrue(chunks.contains { chunk in
            if case .markdown = chunk.locator { return true }
            return false
        })
    }

    func testRejectsLegacyBinaryOfficeExtensions() async {
        let directory = FileManager.default.temporaryDirectory
            .appending(path: "office-legacy-\(UUID().uuidString)", directoryHint: .isDirectory)
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }

        let legacy = directory.appending(path: "old.doc")
        try? Data([0xD0, 0xCF, 0x11, 0xE0]).write(to: legacy)
        do {
            _ = try await LocalKnowledgeTextExtractor().extract(from: legacy)
            XCTFail("expected unsupportedFileType")
        } catch let error as KnowledgeBaseError {
            XCTAssertEqual(error, .unsupportedFileType("doc"))
        } catch {
            XCTFail("unexpected error \(error)")
        }
    }

    // MARK: - Fixtures

    private static func writeDocx(at url: URL, heading: String, body: String) throws -> URL {
        try? FileManager.default.removeItem(at: url)
        let archive = try Archive(url: url, accessMode: .create)
        let xml = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
          <w:body>
            <w:p>
              <w:pPr><w:pStyle w:val="Heading1"/></w:pPr>
              <w:r><w:t>\(heading)</w:t></w:r>
            </w:p>
            <w:p>
              <w:r><w:t>\(body)</w:t></w:r>
            </w:p>
          </w:body>
        </w:document>
        """
        try addEntry(archive, path: "word/document.xml", xml: xml)
        return url
    }

    private static func writeXlsx(
        at url: URL,
        sheetName: String,
        rows: [[String]]
    ) throws -> URL {
        try? FileManager.default.removeItem(at: url)
        let archive = try Archive(url: url, accessMode: .create)

        var shared: [String] = []
        func sharedIndex(_ value: String) -> Int {
            if let existing = shared.firstIndex(of: value) { return existing }
            shared.append(value)
            return shared.count - 1
        }

        let sheetRows = rows.enumerated().map { rowIndex, cols -> String in
            let cells = cols.enumerated().map { colIndex, value -> String in
                let ref = "\(columnName(colIndex + 1))\(rowIndex + 1)"
                let index = sharedIndex(value)
                return #"<c r="\#(ref)" t="s"><v>\#(index)</v></c>"#
            }.joined()
            return "<row r=\"\(rowIndex + 1)\">\(cells)</row>"
        }.joined()

        let sharedXML = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" count="\(shared.count)" uniqueCount="\(shared.count)">
        \(shared.map { "<si><t>\($0)</t></si>" }.joined(separator: "\n"))
        </sst>
        """
        let workbookXML = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"
                  xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
          <sheets>
            <sheet name="\(sheetName)" sheetId="1" r:id="rId1"/>
          </sheets>
        </workbook>
        """
        let relsXML = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
          <Relationship Id="rId1"
            Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet"
            Target="worksheets/sheet1.xml"/>
        </Relationships>
        """
        let sheetXML = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
          <sheetData>\(sheetRows)</sheetData>
        </worksheet>
        """

        try addEntry(archive, path: "xl/sharedStrings.xml", xml: sharedXML)
        try addEntry(archive, path: "xl/workbook.xml", xml: workbookXML)
        try addEntry(archive, path: "xl/_rels/workbook.xml.rels", xml: relsXML)
        try addEntry(archive, path: "xl/worksheets/sheet1.xml", xml: sheetXML)
        return url
    }

    private static func writePptx(at url: URL, slideText: String) throws -> URL {
        try? FileManager.default.removeItem(at: url)
        let archive = try Archive(url: url, accessMode: .create)
        let slideXML = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <p:sld xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"
               xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main">
          <p:cSld>
            <p:spTree>
              <p:sp>
                <p:txBody>
                  <a:p>
                    <a:r><a:t>\(slideText)</a:t></a:r>
                  </a:p>
                </p:txBody>
              </p:sp>
            </p:spTree>
          </p:cSld>
        </p:sld>
        """
        try addEntry(archive, path: "ppt/slides/slide1.xml", xml: slideXML)
        return url
    }

    private static func addEntry(_ archive: Archive, path: String, xml: String) throws {
        let data = Data(xml.utf8)
        try archive.addEntry(
            with: path,
            type: .file,
            uncompressedSize: Int64(data.count)
        ) { position, size in
            let start = Int(position)
            let end = min(start + size, data.count)
            return data.subdata(in: start..<end)
        }
    }

    private static func columnName(_ index: Int) -> String {
        var value = index
        var name = ""
        while value > 0 {
            value -= 1
            let remainder = value % 26
            name = String(UnicodeScalar(65 + remainder)!) + name
            value /= 26
        }
        return name
    }
}
