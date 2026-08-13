import Accelerate
import CoreML
import Foundation
import Hub
import OrynodeDomain
import Tokenizers

public enum CoreMLEmbeddingError: LocalizedError, Sendable {
    case artifactMissing
    case invalidVocabulary
    case incompatibleModel(String)

    public var errorDescription: String? {
        switch self {
        case .artifactMissing:
            "缺少生产级中英文 embedding 模型或词表。"
        case .invalidVocabulary:
            "Embedding tokenizer 词表无效。"
        case let .incompatibleModel(message):
            "Embedding Core ML 模型不兼容：\(message)"
        }
    }
}

/// E5-compatible Core ML adapter. Expected inputs are Int32 `input_ids` and
/// `attention_mask`; output is `[1, sequenceLength, dimensions]` hidden states.
public actor CoreMLTextEmbedding: TextEmbedding {
    public nonisolated let name: String
    public nonisolated let dimensions: Int
    public nonisolated let descriptor: EmbeddingDescriptor

    private let model: MLModel
    private let tokenizerFolderURL: URL
    private var tokenizer: (any Tokenizer)?
    private let maximumLength: Int
    private let inputIDsName: String
    private let attentionMaskName: String
    private let outputName: String

    public init(
        modelURL: URL,
        tokenizerFolderURL: URL,
        descriptor: EmbeddingDescriptor,
        maximumLength: Int = 256
    ) throws {
        let configuration = MLModelConfiguration()
        configuration.computeUnits = .cpuAndNeuralEngine
        model = try MLModel(contentsOf: modelURL, configuration: configuration)
        self.tokenizerFolderURL = tokenizerFolderURL
        name = descriptor.id
        dimensions = descriptor.dimensions
        self.descriptor = descriptor
        self.maximumLength = max(16, maximumLength)

        let inputs = model.modelDescription.inputDescriptionsByName
        guard let ids = inputs.keys.first(where: { $0.lowercased().contains("input_ids") }),
              let mask = inputs.keys.first(where: { $0.lowercased().contains("attention_mask") })
        else {
            throw CoreMLEmbeddingError.incompatibleModel(
                "必须包含 input_ids 和 attention_mask 输入"
            )
        }
        inputIDsName = ids
        attentionMaskName = mask
        guard let output = model.modelDescription.outputDescriptionsByName.first(where: {
            $0.value.type == .multiArray
        })?.key else {
            throw CoreMLEmbeddingError.incompatibleModel("缺少 hidden-state MLMultiArray 输出")
        }
        outputName = output
    }

    public func embed(_ texts: [String]) async throws -> [[Float]] {
        let tokenizer = try resolvedTokenizer()
        return try texts.map { try predict($0, tokenizer: tokenizer) }
    }

    public func embedDocuments(_ texts: [String]) async throws -> [[Float]] {
        try await embed(texts.map { "passage: \($0)" })
    }

    public func embedQuery(_ text: String) async throws -> [Float] {
        guard let vector = try await embed(["query: \(text)"]).first else {
            throw KnowledgeBaseError.storage("query embedding missing")
        }
        return vector
    }

    private func resolvedTokenizer() throws -> any Tokenizer {
        if let tokenizer { return tokenizer }
        // Load tokenizer.json locally. AutoTokenizer.from(modelFolder:) constructs
        // HubApi.shared (URLSession + NWPathMonitor) and triggers iOS network prompts.
        let loaded = try Self.loadLocalTokenizer(from: tokenizerFolderURL)
        tokenizer = loaded
        return loaded
    }

    private static func loadLocalTokenizer(from folder: URL) throws -> any Tokenizer {
        let configURL = folder.appending(path: "tokenizer_config.json")
        let dataURL = folder.appending(path: "tokenizer.json")
        guard FileManager.default.fileExists(atPath: configURL.path),
              FileManager.default.fileExists(atPath: dataURL.path) else {
            throw CoreMLEmbeddingError.artifactMissing
        }
        return try AutoTokenizer.from(
            tokenizerConfig: loadConfig(from: configURL),
            tokenizerData: loadConfig(from: dataURL)
        )
    }

    private static func loadConfig(from url: URL) throws -> Config {
        let data = try Data(contentsOf: url)
        let parsed = try JSONSerialization.jsonObject(with: data)
        if let dictionary = parsed as? [NSString: Any] {
            return Config(dictionary)
        }
        if let dictionary = parsed as? [String: Any] {
            return Config(
                Dictionary(uniqueKeysWithValues: dictionary.map { (NSString(string: $0.key), $0.value) })
            )
        }
        throw CoreMLEmbeddingError.invalidVocabulary
    }

    private func predict(_ text: String, tokenizer: any Tokenizer) throws -> [Float] {
        var tokenIDs = Array(tokenizer.encode(text: text).prefix(maximumLength))
        let activeCount = tokenIDs.count
        if tokenIDs.count < maximumLength {
            tokenIDs.append(contentsOf: repeatElement(0, count: maximumLength - tokenIDs.count))
        }
        let shape: [NSNumber] = [1, NSNumber(value: maximumLength)]
        let inputIDs = try MLMultiArray(shape: shape, dataType: .int32)
        let attentionMask = try MLMultiArray(shape: shape, dataType: .int32)
        for index in 0..<maximumLength {
            inputIDs[index] = NSNumber(value: tokenIDs[index])
            attentionMask[index] = NSNumber(value: index < activeCount ? 1 : 0)
        }
        let provider = try MLDictionaryFeatureProvider(dictionary: [
            inputIDsName: MLFeatureValue(multiArray: inputIDs),
            attentionMaskName: MLFeatureValue(multiArray: attentionMask),
        ])
        let prediction = try model.prediction(from: provider)
        guard let hidden = prediction.featureValue(for: outputName)?.multiArrayValue else {
            throw CoreMLEmbeddingError.incompatibleModel("无法读取 \(outputName)")
        }
        let mask = (0..<maximumLength).map { Int32($0 < activeCount ? 1 : 0) }
        return try meanPoolAndNormalize(hidden, mask: mask)
    }

    private func meanPoolAndNormalize(
        _ hidden: MLMultiArray,
        mask: [Int32]
    ) throws -> [Float] {
        let shape = hidden.shape.map(\.intValue)
        guard shape.count >= 2,
              let sequenceLength = shape.dropLast().last,
              let hiddenSize = shape.last,
              hiddenSize == dimensions
        else {
            throw CoreMLEmbeddingError.incompatibleModel(
                "输出维度应为 \(dimensions)，实际 shape=\(shape)"
            )
        }
        let usableTokens = min(sequenceLength, mask.count)
        var result = [Float](repeating: 0, count: hiddenSize)
        var count: Float = 0
        for token in 0..<usableTokens where mask[token] == 1 {
            count += 1
            let offset = token * hiddenSize
            for dimension in 0..<hiddenSize {
                result[dimension] += hidden[offset + dimension].floatValue
            }
        }
        guard count > 0 else { return result }
        var divisor = count
        vDSP_vsdiv(result, 1, &divisor, &result, 1, vDSP_Length(result.count))
        var squared: Float = 0
        vDSP_svesq(result, 1, &squared, vDSP_Length(result.count))
        var norm = sqrt(squared)
        if norm > 0 {
            vDSP_vsdiv(result, 1, &norm, &result, 1, vDSP_Length(result.count))
        }
        return result
    }
}

public enum OnDeviceEmbeddingFactory {
    public static let productionDescriptor = EmbeddingDescriptor(
        id: "multilingual-e5-small-coreml",
        version: "1",
        dimensions: 384,
        tokenizerVersion: "wordpiece-v1"
    )

    public static func make(bundle: Bundle = .main) throws -> any TextEmbedding {
        guard let modelURL = bundle.url(
            forResource: "multilingual-e5-small",
            withExtension: "mlmodelc"
        ) else {
            #if DEBUG
            return DeterministicHashEmbedding(dimensions: productionDescriptor.dimensions)
            #else
            throw CoreMLEmbeddingError.artifactMissing
            #endif
        }
        let tokenizerFolderURL = bundle.url(
            forResource: "multilingual-e5-small-tokenizer",
            withExtension: nil
        ) ?? bundle.resourceURL
        guard let tokenizerFolderURL,
              FileManager.default.fileExists(
                atPath: tokenizerFolderURL.appending(path: "tokenizer.json").path
              ),
              FileManager.default.fileExists(
                atPath: tokenizerFolderURL.appending(path: "tokenizer_config.json").path
              )
        else {
            #if DEBUG
            return DeterministicHashEmbedding(dimensions: productionDescriptor.dimensions)
            #else
            throw CoreMLEmbeddingError.artifactMissing
            #endif
        }
        return try CoreMLTextEmbedding(
            modelURL: modelURL,
            tokenizerFolderURL: tokenizerFolderURL,
            descriptor: productionDescriptor
        )
    }
}
