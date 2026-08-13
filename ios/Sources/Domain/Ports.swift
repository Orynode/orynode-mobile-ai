import Foundation

public protocol LocalModelEngine: Sendable {
    func load(modelAt url: URL) async throws
    func generate(_ request: AnalysisRequest) async throws -> String
    func generateStream(
        _ request: AnalysisRequest
    ) async throws -> AsyncThrowingStream<String, any Error>
    func cancel() async
    func unload() async
}

public extension LocalModelEngine {
    func generateStream(
        _ request: AnalysisRequest
    ) async throws -> AsyncThrowingStream<String, any Error> {
        let response = try await generate(request)
        return AsyncThrowingStream { continuation in
            continuation.yield(response)
            continuation.finish()
        }
    }
}

public protocol ModelStore: Sendable {
    func installedModel(for descriptor: ModelDescriptor) async -> InstalledModel?
    func importModel(
        from sourceURL: URL,
        descriptor: ModelDescriptor
    ) async throws -> InstalledModel
    func deleteModel(_ descriptor: ModelDescriptor) async throws
}

/// On-device OCR for knowledge-base ingest (scanned PDF pages / future camera capture).
public protocol TextRecognizer: Sendable {
    func recognizeDocument(in imageURL: URL) async throws -> OCRDocument
    /// PNG (or JPEG) bytes of a rendered page/photo. Prefer this over file URLs inside import.
    func recognizeImageData(_ data: Data) async throws -> OCRDocument
}
