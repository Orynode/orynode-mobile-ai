import CoreGraphics
import Foundation
import ImageIO
import OrynodeDomain
import Vision

public actor VisionTextRecognizer: TextRecognizer {
    public init() {}

    public func recognizeDocument(in imageURL: URL) async throws -> OCRDocument {
        let data = try Data(contentsOf: imageURL)
        return try await recognizeImageData(data)
    }

    public func recognizeImageData(_ data: Data) async throws -> OCRDocument {
        guard let image = Self.makeCGImage(from: data) else {
            throw CocoaError(.fileReadCorruptFile)
        }
        return try await recognize(cgImage: image)
    }

    private func recognize(cgImage: CGImage) async throws -> OCRDocument {
        let request = VNRecognizeTextRequest()
        request.recognitionLevel = .accurate
        // Keep raw tokens; correction often mangles names/numbers.
        request.usesLanguageCorrection = false
        request.recognitionLanguages = ["zh-Hans", "zh-Hant", "en-US"]
        request.minimumTextHeight = 0.005
        if #available(iOS 16.0, *) {
            request.automaticallyDetectsLanguage = true
        }

        let handler = VNImageRequestHandler(cgImage: cgImage, options: [:])
        try handler.perform([request])

        let observations = (request.results ?? []).compactMap { observation -> OCRObservation? in
            guard let candidate = observation.topCandidates(1).first else { return nil }
            let box = observation.boundingBox
            return OCRObservation(
                text: candidate.string,
                boundingBox: OCRNormalizedRect(
                    x: box.origin.x,
                    y: box.origin.y,
                    width: box.size.width,
                    height: box.size.height
                )
            )
        }

        return OCRDocument(observations: observations)
    }

    private static func makeCGImage(from data: Data) -> CGImage? {
        guard let source = CGImageSourceCreateWithData(data as CFData, nil) else { return nil }
        return CGImageSourceCreateImageAtIndex(source, 0, nil)
    }
}
