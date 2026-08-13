import Foundation
import OrynodeDomain
@preconcurrency import LiteRTLM

public actor LiteRTLMModelEngine: LocalModelEngine {
    private enum State {
        case idle
        case loading(UUID)
        case ready
        case generating(UUID)
    }

    private var state: State = .idle
    private var engine: Engine?
    private var currentConversation: Conversation?

    public init() {}

    public func load(modelAt url: URL) async throws {
        switch state {
        case .idle, .ready:
            break
        case .loading, .generating:
            throw ModelRuntimeError.engineBusy
        }

        let operationID = UUID()
        state = .loading(operationID)
        engine = nil

        ExperimentalFlags.optIntoExperimentalAPIs()
        // Speculative decoding has caused indefinite first-token stalls on device.
        ExperimentalFlags.enableSpeculativeDecoding = false

        let configuration = try EngineConfig(
            modelPath: url.path,
            backend: .gpu,
            visionBackend: .cpu(),
            // On-device RAG window for Gemma E2B: keep total sequence near 2K tokens.
            // Evidence/question/answer budgets live in OnDeviceRAGBudget.gemmaE2B.
            maxNumTokens: 2_048,
            cacheDir: try cacheDirectory().path
        )
        let newEngine = Engine(engineConfig: configuration)
        do {
            try await newEngine.initialize()
            guard case let .loading(currentID) = state, currentID == operationID else {
                throw CancellationError()
            }
            engine = newEngine
            state = .ready
        } catch {
            if case let .loading(currentID) = state, currentID == operationID {
                state = .idle
            }
            throw error
        }
    }

    public func generate(_ request: AnalysisRequest) async throws -> String {
        let stream = try await generateStream(request)
        var response = ""
        for try await delta in stream {
            response += delta
        }
        guard !response.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            throw ModelRuntimeError.emptyResponse
        }
        return response
    }

    public func generateStream(
        _ request: AnalysisRequest
    ) async throws -> AsyncThrowingStream<String, any Error> {
        guard case .ready = state, let engine else {
            if case .generating = state {
                throw ModelRuntimeError.engineBusy
            }
            throw ModelRuntimeError.engineNotReady
        }

        let operationID = UUID()
        state = .generating(operationID)

        // Keep generation close to deterministic so JSON stays parseable on-device.
        let sampler = try SamplerConfig(topK: 1, topP: 0.9, temperature: 0.0)
        let configuration = ConversationConfig(samplerConfig: sampler)
        let conversation = try await engine.createConversation(with: configuration)
        guard case let .generating(currentID) = state, currentID == operationID else {
            throw CancellationError()
        }
        currentConversation = conversation

        let message = Message(contents: [.text(request.prompt)])

        // Detached so the streaming loop does not keep this actor isolated for the
        // entire generation — inherited Task isolation previously stalled first token.
        return AsyncThrowingStream { continuation in
            let task = Task.detached { [operationID] in
                do {
                    var emitted = false
                    for try await chunk in conversation.sendMessageStream(message) {
                        try Task.checkCancellation()
                        let stillGenerating = await self.isCurrentGeneration(operationID)
                        guard stillGenerating else {
                            throw CancellationError()
                        }
                        let delta = chunk.toString
                        if !delta.isEmpty {
                            emitted = true
                            continuation.yield(delta)
                        }
                    }
                    if !emitted {
                        throw ModelRuntimeError.emptyResponse
                    }
                    await self.finishGeneration(operationID)
                    continuation.finish()
                } catch {
                    await self.finishGeneration(operationID)
                    continuation.finish(throwing: error)
                }
            }
            continuation.onTermination = { @Sendable _ in
                task.cancel()
                Task {
                    await self.cancel()
                }
            }
        }
    }

    private func isCurrentGeneration(_ operationID: UUID) -> Bool {
        if case let .generating(currentID) = state {
            return currentID == operationID
        }
        return false
    }

    private func finishGeneration(_ operationID: UUID) {
        if case let .generating(currentID) = state, currentID == operationID {
            currentConversation = nil
            state = .ready
        }
    }

    public func cancel() {
        if let currentConversation {
            try? currentConversation.cancel()
        }
        currentConversation = nil
        if case .generating = state {
            state = engine == nil ? .idle : .ready
        }
    }

    public func unload() {
        if let currentConversation {
            try? currentConversation.cancel()
        }
        currentConversation = nil
        engine = nil
        state = .idle
    }

    private func cacheDirectory() throws -> URL {
        let base = try FileManager.default.url(
            for: .cachesDirectory,
            in: .userDomainMask,
            appropriateFor: nil,
            create: true
        )
        let directory = base.appending(path: "LiteRTLM", directoryHint: .isDirectory)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        return directory
    }
}
