#!/usr/bin/env swift
import AppKit
import CoreText
import Foundation
import PDFKit

/// Generates extractable (non-scanned) Chinese text PDFs for the evalset.
/// Run: `swift ios/docs/verification/evalset/scripts/generate_text_pdfs.swift`

let scriptURL = URL(fileURLWithPath: CommandLine.arguments[0]).standardizedFileURL
let scriptsDir = scriptURL.deletingLastPathComponent()
let evalsetRoot = scriptsDir.deletingLastPathComponent()
let sourcesDir = evalsetRoot.appendingPathComponent("corpus/pdf/sources")
let outDir = evalsetRoot.appendingPathComponent("corpus/pdf")
let pageRect = CGRect(x: 0, y: 0, width: 612, height: 792)
let margin: CGFloat = 48

func loadSource(_ name: String) throws -> String {
    try String(contentsOf: sourcesDir.appendingPathComponent(name), encoding: .utf8)
}

func makeFont(size: CGFloat) -> CTFont {
    for name in ["ArialUnicodeMS", "Arial Unicode MS", "HiraginoSansGB-W3", "Hiragino Sans GB", "PingFangSC-Regular", "Helvetica"] {
        let font = CTFontCreateWithName(name as CFString, size, nil)
        let ps = CTFontCopyPostScriptName(font) as String
        if name == "Helvetica" || ps.lowercased().contains("arial") || ps.lowercased().contains("hiragino") || ps.lowercased().contains("pingfang") {
            return font
        }
    }
    return CTFontCreateWithName("Helvetica" as CFString, size, nil)
}

func attributed(_ text: String, size: CGFloat) -> CFAttributedString {
    let font = makeFont(size: size)
    return CFAttributedStringCreate(
        nil,
        text as CFString,
        [
            kCTFontAttributeName: font,
            kCTForegroundColorAttributeName: NSColor.black.cgColor,
        ] as CFDictionary
    )!
}

func draw(_ text: String, in rect: CGRect, context: CGContext, size: CGFloat = 12) {
    let framesetter = CTFramesetterCreateWithAttributedString(attributed(text, size: size))
    let path = CGPath(rect: rect, transform: nil)
    let frame = CTFramesetterCreateFrame(framesetter, CFRangeMake(0, 0), path, nil)
    CTFrameDraw(frame, context)
}

func contentRect() -> CGRect {
    CGRect(
        x: margin,
        y: margin,
        width: pageRect.width - margin * 2,
        height: pageRect.height - margin * 2
    )
}

/// Greedy pagination by paragraphs so long sources still produce extractable multi-page PDFs.
func paginate(_ text: String, size: CGFloat = 12) -> [String] {
    let paragraphs = text
        .replacingOccurrences(of: "\r\n", with: "\n")
        .components(separatedBy: "\n")
    var pages: [String] = []
    var current = ""
    let rect = contentRect()
    let framesetterProbe = { (candidate: String) -> Bool in
        let framesetter = CTFramesetterCreateWithAttributedString(attributed(candidate, size: size))
        var fitRange = CFRange()
        let suggested = CTFramesetterSuggestFrameSizeWithConstraints(
            framesetter,
            CFRangeMake(0, 0),
            nil,
            CGSize(width: rect.width, height: .greatestFiniteMagnitude),
            &fitRange
        )
        return suggested.height <= rect.height + 0.5
    }

    for line in paragraphs {
        let next = current.isEmpty ? line : current + "\n" + line
        if current.isEmpty || framesetterProbe(next) {
            current = next
        } else {
            pages.append(current)
            current = line
        }
    }
    if !current.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
        pages.append(current)
    }
    return pages.isEmpty ? [text] : pages
}

func writeSimplePDF(text: String, to name: String, autoPaginate: Bool) throws {
    let url = outDir.appendingPathComponent(name)
    var mediaBox = pageRect
    guard let context = CGContext(url as CFURL, mediaBox: &mediaBox, nil) else {
        throw makeError(1, "failed to create PDF context for \(name)")
    }
    let bodies = autoPaginate ? paginate(text) : [text]
    for body in bodies {
        context.beginPDFPage(nil)
        draw(body, in: contentRect(), context: context)
        context.endPDFPage()
    }
    context.closePDF()
    fputs("wrote \(url.path) pages=\(bodies.count)\n", stderr)
}

func writeFixedPages(_ pages: [String], to name: String) throws {
    let url = outDir.appendingPathComponent(name)
    var mediaBox = pageRect
    guard let context = CGContext(url as CFURL, mediaBox: &mediaBox, nil) else {
        throw makeError(1, "failed to create PDF context for \(name)")
    }
    for body in pages {
        context.beginPDFPage(nil)
        draw(body, in: contentRect(), context: context)
        context.endPDFPage()
    }
    context.closePDF()
    fputs("wrote \(url.path) pages=\(pages.count)\n", stderr)
}

func writeTwoColumnPDF(left: String, right: String, to name: String) throws {
    let url = outDir.appendingPathComponent(name)
    var mediaBox = pageRect
    guard let context = CGContext(url as CFURL, mediaBox: &mediaBox, nil) else {
        throw makeError(1, "failed to create PDF context for \(name)")
    }
    context.beginPDFPage(nil)
    let gap: CGFloat = 18
    let colWidth = (pageRect.width - margin * 2 - gap) / 2
    let colHeight = pageRect.height - margin * 2
    let leftRect = CGRect(x: margin, y: margin, width: colWidth, height: colHeight)
    let rightRect = CGRect(x: margin + colWidth + gap, y: margin, width: colWidth, height: colHeight)
    draw(left, in: leftRect, context: context, size: 11)
    draw(right, in: rightRect, context: context, size: 11)
    context.endPDFPage()
    context.closePDF()
    fputs("wrote \(url.path) pages=1 (two-column)\n", stderr)
}

func splitMultipage(_ raw: String) -> [String] {
    raw.components(separatedBy: "===PAGE ")
        .compactMap { part -> String? in
            let trimmed = part.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !trimmed.isEmpty, let markerEnd = trimmed.range(of: "===") else { return nil }
            return String(trimmed[markerEnd.upperBound...]).trimmingCharacters(in: .whitespacesAndNewlines)
        }
}

func splitColumns(_ raw: String) -> (String, String) {
    let left = raw.components(separatedBy: "===COLUMN LEFT===")[1]
        .components(separatedBy: "===COLUMN RIGHT===")[0]
        .trimmingCharacters(in: .whitespacesAndNewlines)
    let right = raw.components(separatedBy: "===COLUMN RIGHT===")[1]
        .trimmingCharacters(in: .whitespacesAndNewlines)
    return (left, right)
}

func verifyExtractable(_ name: String, mustContain: [String], pageNeedles: [Int: String] = [:]) throws {
    let url = outDir.appendingPathComponent(name)
    guard let doc = PDFDocument(url: url) else {
        throw makeError(2, "PDFKit failed to open \(name)")
    }
    var combined = ""
    for index in 0..<doc.pageCount {
        combined += (doc.page(at: index)?.string ?? "") + "\n"
    }
    let collapsed = combined.replacingOccurrences(of: "\\s+", with: "", options: .regularExpression)
    for needle in mustContain {
        let needleCollapsed = needle.replacingOccurrences(of: "\\s+", with: "", options: .regularExpression)
        if !combined.contains(needle) && !collapsed.contains(needleCollapsed) {
            throw makeError(3, "\(name) missing extractable text: \(needle)")
        }
    }
    for (pageIndex, needle) in pageNeedles {
        let text = doc.page(at: pageIndex)?.string ?? ""
        if !text.contains(needle) {
            throw makeError(4, "\(name) page \(pageIndex + 1) missing \(needle)")
        }
    }
    fputs("verified \(name) pages=\(doc.pageCount)\n", stderr)
}

func makeError(_ code: Int, _ message: String) -> NSError {
    NSError(domain: "generate_text_pdfs", code: code, userInfo: [NSLocalizedDescriptionKey: message])
}

do {
    try writeSimplePDF(text: try loadSource("01_short.txt"), to: "01_short.pdf", autoPaginate: false)
    try verifyExtractable("01_short.pdf", mustContain: ["无需 OCR", "文档序页码从 1 起算"])

    try writeSimplePDF(text: try loadSource("02_long.txt"), to: "02_long.pdf", autoPaginate: true)
    try verifyExtractable("02_long.pdf", mustContain: ["切分页码绑定不得漂移", "长文档切分后仍按文档序保留页码绑定"])

    let multiPages = splitMultipage(try loadSource("03_multipage.txt"))
    guard multiPages.count == 3 else { throw makeError(5, "expected 3 multipage sections") }
    try writeFixedPages(multiPages, to: "03_multipage.pdf")
    try verifyExtractable(
        "03_multipage.pdf",
        mustContain: [
            "反向代理配置入口在网关层",
            "余弦 0.7 与 FTS 0.3",
            "拒答阈值不得为 seed 试跑单独下调",
        ],
        pageNeedles: [
            0: "反向代理配置入口在网关层",
            1: "余弦 0.7 与 FTS 0.3",
            2: "拒答阈值不得为 seed 试跑单独下调",
        ]
    )

    let cols = splitColumns(try loadSource("04_complex_layout.txt"))
    try writeTwoColumnPDF(left: cols.0, right: cols.1, to: "04_complex_layout.pdf")
    try verifyExtractable(
        "04_complex_layout.pdf",
        mustContain: ["沙箱复制先于解析", "发布前分片不可检索", "左右栏均可被抽取"]
    )

    print("OK: generated 4 text-layer PDFs under corpus/pdf/")
} catch {
    fputs("ERROR: \(error)\n", stderr)
    exit(1)
}
