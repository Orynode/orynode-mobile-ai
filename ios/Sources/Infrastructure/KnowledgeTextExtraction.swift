import CoreGraphics
import Foundation
import ImageIO
import OrynodeDomain
import PDFKit
import UIKit
import UniformTypeIdentifiers

public struct LocalKnowledgeTextExtractor: KnowledgeTextExtractor {
    private let textRecognizer: (any TextRecognizer)?

    /// Pages with sparse/empty text layers fall back to on-device Vision OCR.
    public init(textRecognizer: (any TextRecognizer)? = VisionTextRecognizer()) {
        self.textRecognizer = textRecognizer
    }

    public func extract(from url: URL) async throws -> KnowledgeExtraction {
        let ext = url.pathExtension.lowercased()
        switch ext {
        case "txt":
            let text = try Self.normalize(Self.readPlainText(url))
            return KnowledgeExtraction(kind: .plainText, indexedText: text)
        case "md", "markdown":
            let text = try Self.normalize(Self.readPlainText(url))
            return KnowledgeExtraction(kind: .markdown, indexedText: text)
        case "pdf":
            return try await readPDF(url)
        case "docx", "docm", "xlsx", "xlsm", "pptx", "pptm":
            let markdown = try await Task.detached(priority: .utility) {
                try OfficeOpenXMLMarkdownExtractor.extract(from: url)
            }.value
            return KnowledgeExtraction(kind: .markdown, indexedText: Self.normalize(markdown))
        default:
            throw KnowledgeBaseError.unsupportedFileType(ext)
        }
    }

    private static func readPlainText(_ url: URL) throws -> String {
        let data = try Data(contentsOf: url, options: .mappedIfSafe)
        for encoding in [String.Encoding.utf8, .utf16, .unicode, .isoLatin1] {
            if let text = String(data: data, encoding: encoding) {
                return text
            }
        }
        throw CocoaError(.fileReadInapplicableStringEncoding)
    }

    private func readPDF(_ url: URL) async throws -> KnowledgeExtraction {
        guard let document = PDFDocument(url: url) else {
            throw CocoaError(.fileReadCorruptFile)
        }
        var pages: [String] = []
        var spans: [KnowledgePageSpan] = []
        var cursor = 0
        for index in 0..<document.pageCount {
            try Task.checkCancellation()
            let pdfPage = document.page(at: index)
            let native = Self.normalize(pdfPage?.string ?? "")
            var pageText = native
            if Self.shouldAttemptOCR(nativePageText: native),
               let recognizer = textRecognizer,
               let png = Self.renderPagePNG(pdfPage) {
                let ocr = Self.normalize(try await recognizer.recognizeImageData(png).plainText)
                pageText = Self.selectPageText(native: native, ocr: ocr)
            }
            if index > 0 {
                // Keep a stable page separator in indexedText for offset mapping.
                cursor += 2 // "\n\n"
            }
            let start = cursor
            let end = start + pageText.utf16.count
            pages.append(pageText)
            spans.append(KnowledgePageSpan(page: index + 1, start: start, end: end))
            cursor = end
        }
        let indexed = pages.joined(separator: "\n\n")
        guard !indexed.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            throw KnowledgeBaseError.emptyDocument
        }
        return KnowledgeExtraction(kind: .pdf, indexedText: indexed, pageSpans: spans)
    }

    /// Scanned PDFs often ship a tiny/garbage text layer; those must still OCR.
    static func shouldAttemptOCR(nativePageText: String) -> Bool {
        let trimmed = nativePageText.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty { return true }
        if trimmed.count < 48 { return true }
        let meaningful = trimmed.unicodeScalars.filter { scalar in
            CharacterSet.letters.contains(scalar)
                || CharacterSet.decimalDigits.contains(scalar)
                || (0x4E00...0x9FFF).contains(scalar.value)
                || (0x3400...0x4DBF).contains(scalar.value)
        }
        // Invisible/broken text layers are often punctuation-heavy with almost no letters.
        if meaningful.count < 16 { return true }
        return false
    }

    /// Prefer OCR when it recovers substantially more readable text than the native layer.
    static func selectPageText(native: String, ocr: String) -> String {
        let nativeTrimmed = native.trimmingCharacters(in: .whitespacesAndNewlines)
        let ocrTrimmed = ocr.trimmingCharacters(in: .whitespacesAndNewlines)
        if ocrTrimmed.isEmpty { return native }
        if nativeTrimmed.isEmpty { return ocr }
        if ocrTrimmed.count >= nativeTrimmed.count + 24 { return ocr }
        if ocrTrimmed.count >= max(40, nativeTrimmed.count * 2) { return ocr }
        let nativeMeaningful = meaningfulScalarCount(nativeTrimmed)
        let ocrMeaningful = meaningfulScalarCount(ocrTrimmed)
        if ocrMeaningful >= nativeMeaningful + 12 { return ocr }
        return native
    }

    private static func meaningfulScalarCount(_ text: String) -> Int {
        text.unicodeScalars.filter { scalar in
            CharacterSet.letters.contains(scalar)
                || CharacterSet.decimalDigits.contains(scalar)
                || (0x4E00...0x9FFF).contains(scalar.value)
                || (0x3400...0x4DBF).contains(scalar.value)
        }.count
    }

    /// Renders a PDF page to PNG for Vision OCR (handles page rotation via PDFKit thumbnail).
    static func renderPagePNG(_ page: PDFPage?, maxPixelWidth: CGFloat = 2000) -> Data? {
        guard let page else { return nil }
        let bounds = page.bounds(for: .mediaBox)
        guard bounds.width > 1, bounds.height > 1 else { return nil }
        let scale = min(maxPixelWidth / max(bounds.width, bounds.height), 3)
        let width = max(1, (bounds.width * scale).rounded(.down))
        let height = max(1, (bounds.height * scale).rounded(.down))
        let thumbnail = page.thumbnail(
            of: CGSize(width: width, height: height),
            for: .mediaBox
        )
        guard let data = thumbnail.pngData(), !data.isEmpty else {
            return renderPagePNGFallback(page, width: Int(width), height: Int(height), scale: scale)
        }
        return data
    }

    private static func renderPagePNGFallback(
        _ page: PDFPage,
        width: Int,
        height: Int,
        scale: CGFloat
    ) -> Data? {
        let colorSpace = CGColorSpaceCreateDeviceRGB()
        guard let context = CGContext(
            data: nil,
            width: width,
            height: height,
            bitsPerComponent: 8,
            bytesPerRow: 0,
            space: colorSpace,
            bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
        ) else {
            return nil
        }
        context.setFillColor(red: 1, green: 1, blue: 1, alpha: 1)
        context.fill(CGRect(x: 0, y: 0, width: width, height: height))
        context.saveGState()
        context.translateBy(x: 0, y: CGFloat(height))
        context.scaleBy(x: scale, y: -scale)
        page.draw(with: .mediaBox, to: context)
        context.restoreGState()
        guard let image = context.makeImage() else { return nil }
        return pngData(from: image)
    }

    private static func pngData(from image: CGImage) -> Data? {
        let data = NSMutableData()
        guard let destination = CGImageDestinationCreateWithData(
            data,
            UTType.png.identifier as CFString,
            1,
            nil
        ) else {
            return nil
        }
        CGImageDestinationAddImage(destination, image, nil)
        guard CGImageDestinationFinalize(destination) else { return nil }
        return data as Data
    }

    static func normalize(_ text: String) -> String {
        text
            .replacingOccurrences(of: "\r\n", with: "\n")
            .replacingOccurrences(of: "\r", with: "\n")
    }
}
