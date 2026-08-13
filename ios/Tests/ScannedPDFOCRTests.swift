import XCTest
import PDFKit
import OrynodeApplication
import OrynodeDomain
@testable import OrynodeInfrastructure

final class ScannedPDFOCRTests: XCTestCase {
    func testBlankPDFUsesOCRFallbackAndKeepsPageSpans() async throws {
        let directory = FileManager.default.temporaryDirectory
            .appending(path: "pdf-ocr-\(UUID().uuidString)", directoryHint: .isDirectory)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }

        let pdfURL = directory.appending(path: "blank.pdf")
        try Self.writeBlankPDF(to: pdfURL, pageCount: 2)

        let ocr = StubTextRecognizer(text: "扫描页识别出的离线知识库文字")
        let extraction = try await LocalKnowledgeTextExtractor(textRecognizer: ocr).extract(from: pdfURL)

        XCTAssertEqual(extraction.kind, .pdf)
        XCTAssertTrue(extraction.indexedText.contains("扫描页识别出的离线知识库文字"))
        XCTAssertEqual(extraction.pageSpans.count, 2)
        XCTAssertEqual(extraction.pageSpans.map(\.page), [1, 2])
        let ocrCalls = await ocr.callCount
        XCTAssertEqual(ocrCalls, 2)
    }

    func testBlankPDFWithoutOCRStillFailsEmpty() async throws {
        let directory = FileManager.default.temporaryDirectory
            .appending(path: "pdf-empty-\(UUID().uuidString)", directoryHint: .isDirectory)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }

        let pdfURL = directory.appending(path: "blank.pdf")
        try Self.writeBlankPDF(to: pdfURL, pageCount: 1)

        do {
            _ = try await LocalKnowledgeTextExtractor(textRecognizer: nil).extract(from: pdfURL)
            XCTFail("expected emptyDocument")
        } catch let error as KnowledgeBaseError {
            XCTAssertEqual(error, .emptyDocument)
        }
    }

    func testRenderPagePNGProducesData() throws {
        let page = PDFPage()
        let png = LocalKnowledgeTextExtractor.renderPagePNG(page)
        XCTAssertNotNil(png)
        XCTAssertGreaterThan(png?.count ?? 0, 100)
    }

    func testSparseNativeTextLayerStillTriggersOCR() async throws {
        XCTAssertTrue(LocalKnowledgeTextExtractor.shouldAttemptOCR(nativePageText: ""))
        XCTAssertTrue(LocalKnowledgeTextExtractor.shouldAttemptOCR(nativePageText: "  \n\t "))
        XCTAssertTrue(LocalKnowledgeTextExtractor.shouldAttemptOCR(nativePageText: "...."))
        XCTAssertTrue(LocalKnowledgeTextExtractor.shouldAttemptOCR(nativePageText: "第1页"))
        XCTAssertFalse(
            LocalKnowledgeTextExtractor.shouldAttemptOCR(
                nativePageText: String(repeating: "离线知识库不会上传文档。", count: 8)
            )
        )

        let selected = LocalKnowledgeTextExtractor.selectPageText(
            native: "第1页",
            ocr: "吉卜赛人又来了。这是扫描页上的正文。"
        )
        XCTAssertTrue(selected.contains("吉卜赛人又来了"))
    }

    private static func writeBlankPDF(to url: URL, pageCount: Int) throws {
        let document = PDFDocument()
        for index in 0..<pageCount {
            document.insert(PDFPage(), at: index)
        }
        guard document.write(to: url) else {
            throw CocoaError(.fileWriteUnknown)
        }
    }
}

private actor StubTextRecognizer: TextRecognizer {
    let text: String
    private(set) var callCount = 0

    init(text: String) {
        self.text = text
    }

    func recognizeDocument(in imageURL: URL) async throws -> OCRDocument {
        try await recognizeImageData(Data())
    }

    func recognizeImageData(_ data: Data) async throws -> OCRDocument {
        callCount += 1
        XCTAssertFalse(data.isEmpty)
        return OCRDocument(
            observations: [
                OCRObservation(
                    text: text,
                    boundingBox: OCRNormalizedRect(x: 0.1, y: 0.5, width: 0.8, height: 0.1)
                )
            ]
        )
    }
}
