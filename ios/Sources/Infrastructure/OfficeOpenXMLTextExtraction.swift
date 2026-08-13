import Foundation
import OrynodeDomain
import ZIPFoundation

/// Minimal OOXML → Markdown extractors for on-device RAG ingest.
/// Covers modern Office Open XML only (docx / xlsx / pptx). Legacy binary
/// formats (.doc / .xls / .ppt) remain unsupported until a shared converter
/// (e.g. anydoc) is wired in.
enum OfficeOpenXMLMarkdownExtractor {
    static func extract(from url: URL) throws -> String {
        switch url.pathExtension.lowercased() {
        case "docx", "docm":
            return try extractDocx(from: url)
        case "xlsx", "xlsm":
            return try extractXlsx(from: url)
        case "pptx", "pptm":
            return try extractPptx(from: url)
        default:
            throw KnowledgeBaseError.unsupportedFileType(url.pathExtension)
        }
    }

    static func supportsExtension(_ ext: String) -> Bool {
        switch ext.lowercased() {
        case "docx", "docm", "xlsx", "xlsm", "pptx", "pptm":
            return true
        default:
            return false
        }
    }

    // MARK: - DOCX

    private static func extractDocx(from url: URL) throws -> String {
        let archive = try openArchive(url)
        guard let entry = archive["word/document.xml"],
              let data = try readEntry(entry, from: archive),
              let xml = String(data: data, encoding: .utf8) else {
            throw CocoaError(.fileReadCorruptFile)
        }
        let markdown = DocxBodyParser.parse(xml)
        return try finalize(markdown)
    }

    // MARK: - XLSX

    private static func extractXlsx(from url: URL) throws -> String {
        let archive = try openArchive(url)
        let sharedStrings = try loadSharedStrings(from: archive)
        let sheets = try loadWorkbookSheets(from: archive)

        var sections: [String] = []
        for sheet in sheets {
            guard let entry = archive[sheet.path],
                  let data = try readEntry(entry, from: archive),
                  let xml = String(data: data, encoding: .utf8) else {
                continue
            }
            let table = XlsxSheetParser.parse(xml, sharedStrings: sharedStrings)
            guard !table.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { continue }
            sections.append("## \(sheet.name)\n\n\(table)")
        }
        return try finalize(sections.joined(separator: "\n\n"))
    }

    // MARK: - PPTX

    private static func extractPptx(from url: URL) throws -> String {
        let archive = try openArchive(url)
        let slidePaths = archive
            .map(\.path)
            .filter { path in
                path.hasPrefix("ppt/slides/slide")
                    && path.hasSuffix(".xml")
                    && !path.contains("_rels")
            }
            .sorted { lhs, rhs in
                slideIndex(lhs) < slideIndex(rhs)
            }

        var sections: [String] = []
        for (offset, path) in slidePaths.enumerated() {
            guard let entry = archive[path],
                  let data = try readEntry(entry, from: archive),
                  let xml = String(data: data, encoding: .utf8) else {
                continue
            }
            let body = PptxSlideParser.parse(xml)
            guard !body.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { continue }
            sections.append("## Slide \(offset + 1)\n\n\(body)")
        }
        return try finalize(sections.joined(separator: "\n\n"))
    }

    // MARK: - ZIP helpers

    private static func openArchive(_ url: URL) throws -> Archive {
        try Archive(url: url, accessMode: .read)
    }

    private static func readEntry(_ entry: Entry, from archive: Archive) throws -> Data? {
        var data = Data()
        _ = try archive.extract(entry) { chunk in
            data.append(chunk)
        }
        return data
    }

    private static func finalize(_ text: String) throws -> String {
        let normalized = text
            .replacingOccurrences(of: "\r\n", with: "\n")
            .replacingOccurrences(of: "\r", with: "\n")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        guard !normalized.isEmpty else { throw KnowledgeBaseError.emptyDocument }
        return normalized
    }

    private static func slideIndex(_ path: String) -> Int {
        let name = URL(fileURLWithPath: path).deletingPathExtension().lastPathComponent
        let digits = name.drop(while: { !$0.isNumber }).prefix(while: \.isNumber)
        return Int(digits) ?? Int.max
    }

    private static func loadSharedStrings(from archive: Archive) throws -> [String] {
        guard let entry = archive["xl/sharedStrings.xml"],
              let data = try readEntry(entry, from: archive),
              let xml = String(data: data, encoding: .utf8) else {
            return []
        }
        return SharedStringsParser.parse(xml)
    }

    private static func loadWorkbookSheets(from archive: Archive) throws -> [(name: String, path: String)] {
        guard let workbookEntry = archive["xl/workbook.xml"],
              let workbookData = try readEntry(workbookEntry, from: archive),
              let workbookXML = String(data: workbookData, encoding: .utf8) else {
            throw CocoaError(.fileReadCorruptFile)
        }
        let sheets = WorkbookSheetParser.parse(workbookXML)
        let rels = try loadRelationships(from: archive, path: "xl/_rels/workbook.xml.rels")

        var resolved: [(String, String)] = []
        for sheet in sheets {
            guard let target = rels[sheet.relationshipID] else { continue }
            let path = target.hasPrefix("/")
                ? String(target.dropFirst())
                : "xl/\(target)"
            resolved.append((sheet.name, path))
        }
        if resolved.isEmpty {
            // Fallback: enumerate worksheet parts in archive order.
            let paths = archive.map(\.path)
                .filter { $0.hasPrefix("xl/worksheets/sheet") && $0.hasSuffix(".xml") }
                .sorted()
            return paths.enumerated().map { ("Sheet \($0.offset + 1)", $0.element) }
        }
        return resolved
    }

    private static func loadRelationships(from archive: Archive, path: String) throws -> [String: String] {
        guard let entry = archive[path],
              let data = try readEntry(entry, from: archive),
              let xml = String(data: data, encoding: .utf8) else {
            return [:]
        }
        return RelationshipsParser.parse(xml)
    }
}

// MARK: - XML parsers

private final class DocxBodyParser: NSObject, XMLParserDelegate {
    private var paragraphs: [String] = []
    private var currentParagraph = ""
    private var currentHeadingLevel: Int?
    private var inParagraph = false
    private var inText = false
    private var tableRows: [[String]] = []
    private var currentRow: [String] = []
    private var currentCell = ""
    private var inTable = false
    private var inCell = false
    private var skipDepth = 0

    static func parse(_ xml: String) -> String {
        let parser = DocxBodyParser()
        guard let data = xml.data(using: .utf8) else { return "" }
        let xmlParser = XMLParser(data: data)
        xmlParser.delegate = parser
        xmlParser.parse()
        return parser.paragraphs.joined(separator: "\n\n")
    }

    func parser(
        _ parser: XMLParser,
        didStartElement elementName: String,
        namespaceURI: String?,
        qualifiedName qName: String?,
        attributes attributeDict: [String: String] = [:]
    ) {
        let local = localName(elementName)
        if skipDepth > 0 {
            skipDepth += 1
            return
        }
        switch local {
        case "tab", "br", "cr":
            if inCell {
                currentCell += local == "tab" ? "\t" : "\n"
            } else if inParagraph {
                currentParagraph += local == "tab" ? "\t" : "\n"
            }
        case "p":
            inParagraph = true
            currentParagraph = ""
            currentHeadingLevel = nil
        case "pStyle":
            if let val = attributeDict["w:val"] ?? attributeDict["val"] {
                currentHeadingLevel = headingLevel(from: val)
            }
        case "outlineLvl":
            if let raw = attributeDict["w:val"] ?? attributeDict["val"], let level = Int(raw) {
                currentHeadingLevel = min(max(level + 1, 1), 6)
            }
        case "t":
            inText = true
        case "tbl":
            inTable = true
            tableRows = []
        case "tr":
            currentRow = []
        case "tc":
            inCell = true
            currentCell = ""
        case "drawing", "pict", "object":
            skipDepth = 1
        default:
            break
        }
    }

    func parser(_ parser: XMLParser, foundCharacters string: String) {
        guard skipDepth == 0, inText else { return }
        if inCell {
            currentCell += string
        } else if inParagraph {
            currentParagraph += string
        }
    }

    func parser(
        _ parser: XMLParser,
        didEndElement elementName: String,
        namespaceURI: String?,
        qualifiedName qName: String?
    ) {
        let local = localName(elementName)
        if skipDepth > 0 {
            skipDepth -= 1
            return
        }
        switch local {
        case "t":
            inText = false
        case "tc":
            currentRow.append(collapseWhitespace(currentCell))
            inCell = false
            currentCell = ""
        case "tr":
            if !currentRow.isEmpty { tableRows.append(currentRow) }
            currentRow = []
        case "tbl":
            let markdown = markdownTable(tableRows)
            if !markdown.isEmpty { paragraphs.append(markdown) }
            inTable = false
            tableRows = []
        case "p":
            let text = collapseWhitespace(currentParagraph)
            if !text.isEmpty {
                if let level = currentHeadingLevel {
                    let hashes = String(repeating: "#", count: level)
                    paragraphs.append("\(hashes) \(text)")
                } else if !inTable {
                    paragraphs.append(text)
                } else if inCell {
                    // cell text already collected via runs inside tc
                }
            }
            inParagraph = false
            currentParagraph = ""
            currentHeadingLevel = nil
        default:
            break
        }
    }

    private func headingLevel(from style: String) -> Int? {
        let lower = style.lowercased()
        if lower.hasPrefix("heading") {
            let digits = lower.drop(while: { !$0.isNumber }).prefix(while: \.isNumber)
            if let value = Int(digits) { return min(max(value, 1), 6) }
        }
        switch lower {
        case "title": return 1
        case "subtitle": return 2
        default: return nil
        }
    }
}

private final class SharedStringsParser: NSObject, XMLParserDelegate {
    private var strings: [String] = []
    private var current = ""
    private var inText = false

    static func parse(_ xml: String) -> [String] {
        let parser = SharedStringsParser()
        guard let data = xml.data(using: .utf8) else { return [] }
        let xmlParser = XMLParser(data: data)
        xmlParser.delegate = parser
        xmlParser.parse()
        return parser.strings
    }

    func parser(
        _ parser: XMLParser,
        didStartElement elementName: String,
        namespaceURI: String?,
        qualifiedName qName: String?,
        attributes attributeDict: [String: String] = [:]
    ) {
        let local = localName(elementName)
        if local == "si" {
            current = ""
        } else if local == "t" {
            inText = true
        }
    }

    func parser(_ parser: XMLParser, foundCharacters string: String) {
        if inText { current += string }
    }

    func parser(
        _ parser: XMLParser,
        didEndElement elementName: String,
        namespaceURI: String?,
        qualifiedName qName: String?
    ) {
        let local = localName(elementName)
        if local == "t" {
            inText = false
        } else if local == "si" {
            strings.append(current)
            current = ""
        }
    }
}

private final class WorkbookSheetParser: NSObject, XMLParserDelegate {
    struct Sheet {
        let name: String
        let relationshipID: String
    }

    private var sheets: [Sheet] = []

    static func parse(_ xml: String) -> [Sheet] {
        let parser = WorkbookSheetParser()
        guard let data = xml.data(using: .utf8) else { return [] }
        let xmlParser = XMLParser(data: data)
        xmlParser.delegate = parser
        xmlParser.parse()
        return parser.sheets
    }

    func parser(
        _ parser: XMLParser,
        didStartElement elementName: String,
        namespaceURI: String?,
        qualifiedName qName: String?,
        attributes attributeDict: [String: String] = [:]
    ) {
        guard localName(elementName) == "sheet" else { return }
        let name = attributeDict["name"] ?? "Sheet"
        let rid = attributeDict["r:id"] ?? attributeDict["id"] ?? ""
        guard !rid.isEmpty else { return }
        sheets.append(Sheet(name: name, relationshipID: rid))
    }
}

private final class RelationshipsParser: NSObject, XMLParserDelegate {
    private var map: [String: String] = [:]

    static func parse(_ xml: String) -> [String: String] {
        let parser = RelationshipsParser()
        guard let data = xml.data(using: .utf8) else { return [:] }
        let xmlParser = XMLParser(data: data)
        xmlParser.delegate = parser
        xmlParser.parse()
        return parser.map
    }

    func parser(
        _ parser: XMLParser,
        didStartElement elementName: String,
        namespaceURI: String?,
        qualifiedName qName: String?,
        attributes attributeDict: [String: String] = [:]
    ) {
        guard localName(elementName) == "Relationship" else { return }
        guard let id = attributeDict["Id"], let target = attributeDict["Target"] else { return }
        map[id] = target
    }
}

private final class XlsxSheetParser: NSObject, XMLParserDelegate {
    private let sharedStrings: [String]
    private var rows: [[String]] = []
    private var currentRow: [String] = []
    private var currentCellRef = ""
    private var currentType = ""
    private var currentValue = ""
    private var inValue = false
    private var inInlineString = false
    private var expectedColumn = 1

    static func parse(_ xml: String, sharedStrings: [String]) -> String {
        let parser = XlsxSheetParser(sharedStrings: sharedStrings)
        guard let data = xml.data(using: .utf8) else { return "" }
        let xmlParser = XMLParser(data: data)
        xmlParser.delegate = parser
        xmlParser.parse()
        return markdownTable(parser.rows)
    }

    init(sharedStrings: [String]) {
        self.sharedStrings = sharedStrings
    }

    func parser(
        _ parser: XMLParser,
        didStartElement elementName: String,
        namespaceURI: String?,
        qualifiedName qName: String?,
        attributes attributeDict: [String: String] = [:]
    ) {
        switch localName(elementName) {
        case "row":
            currentRow = []
            expectedColumn = 1
        case "c":
            currentCellRef = attributeDict["r"] ?? ""
            currentType = attributeDict["t"] ?? ""
            currentValue = ""
            let column = columnIndex(from: currentCellRef) ?? expectedColumn
            while expectedColumn < column {
                currentRow.append("")
                expectedColumn += 1
            }
        case "v":
            inValue = true
        case "t":
            if inInlineString || currentType == "inlineStr" {
                inValue = true
            }
        case "is":
            inInlineString = true
        default:
            break
        }
    }

    func parser(_ parser: XMLParser, foundCharacters string: String) {
        if inValue { currentValue += string }
    }

    func parser(
        _ parser: XMLParser,
        didEndElement elementName: String,
        namespaceURI: String?,
        qualifiedName qName: String?
    ) {
        switch localName(elementName) {
        case "v", "t":
            inValue = false
        case "is":
            inInlineString = false
        case "c":
            let text: String
            if currentType == "s", let index = Int(currentValue), sharedStrings.indices.contains(index) {
                text = sharedStrings[index]
            } else {
                text = currentValue
            }
            currentRow.append(text)
            expectedColumn += 1
            currentValue = ""
            currentType = ""
        case "row":
            if currentRow.contains(where: { !$0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }) {
                rows.append(currentRow)
            }
            currentRow = []
        default:
            break
        }
    }

    private func columnIndex(from reference: String) -> Int? {
        let letters = reference.prefix(while: { $0.isLetter })
        guard !letters.isEmpty else { return nil }
        var value = 0
        for scalar in letters.uppercased().unicodeScalars {
            value = value * 26 + Int(scalar.value - 64)
        }
        return value
    }
}

private final class PptxSlideParser: NSObject, XMLParserDelegate {
    private var lines: [String] = []
    private var current = ""
    private var inText = false

    static func parse(_ xml: String) -> String {
        let parser = PptxSlideParser()
        guard let data = xml.data(using: .utf8) else { return "" }
        let xmlParser = XMLParser(data: data)
        xmlParser.delegate = parser
        xmlParser.parse()
        parser.flush()
        return parser.lines.joined(separator: "\n\n")
    }

    func parser(
        _ parser: XMLParser,
        didStartElement elementName: String,
        namespaceURI: String?,
        qualifiedName qName: String?,
        attributes attributeDict: [String: String] = [:]
    ) {
        switch localName(elementName) {
        case "t":
            inText = true
        case "br":
            current += "\n"
        default:
            break
        }
    }

    func parser(_ parser: XMLParser, foundCharacters string: String) {
        if inText { current += string }
    }

    func parser(
        _ parser: XMLParser,
        didEndElement elementName: String,
        namespaceURI: String?,
        qualifiedName qName: String?
    ) {
        switch localName(elementName) {
        case "t":
            inText = false
        case "p":
            flush()
        default:
            break
        }
    }

    private func flush() {
        let text = collapseWhitespace(current)
        if !text.isEmpty { lines.append(text) }
        current = ""
    }
}

// MARK: - Shared helpers

private func localName(_ elementName: String) -> String {
    if let index = elementName.lastIndex(of: ":") {
        return String(elementName[elementName.index(after: index)...])
    }
    return elementName
}

private func collapseWhitespace(_ text: String) -> String {
    text
        .replacingOccurrences(of: "\u{00A0}", with: " ")
        .split(whereSeparator: { $0.isNewline })
        .map { $0.trimmingCharacters(in: .whitespaces) }
        .filter { !$0.isEmpty }
        .joined(separator: " ")
        .trimmingCharacters(in: .whitespacesAndNewlines)
}

private func markdownTable(_ rows: [[String]]) -> String {
    let normalized = rows.map { row in row.map(escapeTableCell) }
    guard let header = normalized.first, !header.isEmpty else { return "" }
    let width = normalized.map(\.count).max() ?? header.count
    func pad(_ row: [String]) -> [String] {
        if row.count >= width { return Array(row.prefix(width)) }
        return row + Array(repeating: "", count: width - row.count)
    }
    let padded = normalized.map(pad)
    let head = padded[0]
    let separator = Array(repeating: "---", count: width)
    var lines = [
        "| \(head.joined(separator: " | ")) |",
        "| \(separator.joined(separator: " | ")) |",
    ]
    for row in padded.dropFirst() {
        lines.append("| \(row.joined(separator: " | ")) |")
    }
    return lines.joined(separator: "\n")
}

private func escapeTableCell(_ text: String) -> String {
    collapseWhitespace(text)
        .replacingOccurrences(of: "|", with: "\\|")
}
