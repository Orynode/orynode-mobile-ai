import Accelerate
import Foundation
import OrynodeDomain

/// Offline, deterministic feature-hashing baseline. It is intentionally not presented as a trained model.
/// Replace it with a Core ML/LiteRT adapter implementing `TextEmbedding` for production semantic quality.
public struct DeterministicHashEmbedding: TextEmbedding {
    public let name = "deterministic-feature-hash-fallback"
    public let dimensions: Int

    public init(dimensions: Int = 384) {
        self.dimensions = max(32, dimensions)
    }

    public var descriptor: EmbeddingDescriptor {
        EmbeddingDescriptor(
            id: name,
            version: "1",
            dimensions: dimensions,
            tokenizerVersion: "unicode-ngram-v1"
        )
    }

    public func embed(_ texts: [String]) async throws -> [[Float]] {
        let dimensions = dimensions
        return await Task.detached(priority: .utility) {
            texts.map { Self.embedOne($0, dimensions: dimensions) }
        }.value
    }

    private static func embedOne(_ text: String, dimensions: Int) -> [Float] {
        let scalars = Array(text.lowercased().unicodeScalars)
        var vector = [Float](repeating: 0, count: dimensions)
        guard !scalars.isEmpty else { return vector }
        for width in 1...min(3, scalars.count) {
            for start in 0...(scalars.count - width) {
                var hash: UInt64 = 14_695_981_039_346_656_037
                for scalar in scalars[start..<(start + width)] {
                    hash ^= UInt64(scalar.value)
                    hash &*= 1_099_511_628_211
                }
                let index = Int(hash % UInt64(dimensions))
                vector[index] += (hash & 1 == 0 ? 1 : -1) / Float(width)
            }
        }
        var norm: Float = 0
        vDSP_svesq(vector, 1, &norm, vDSP_Length(vector.count))
        norm = sqrt(norm)
        if norm > 0 {
            var divisor = norm
            vDSP_vsdiv(vector, 1, &divisor, &vector, 1, vDSP_Length(vector.count))
        }
        return vector
    }
}

public enum ExactCosineSimilarity {
    public static func score(_ lhs: [Float], _ rhs: [Float]) throws -> Float {
        guard lhs.count == rhs.count else {
            throw KnowledgeBaseError.invalidEmbeddingDimensions(
                expected: lhs.count,
                actual: rhs.count
            )
        }
        guard !lhs.isEmpty else { return 0 }
        var dot: Float = 0
        var lhsSquared: Float = 0
        var rhsSquared: Float = 0
        vDSP_dotpr(lhs, 1, rhs, 1, &dot, vDSP_Length(lhs.count))
        vDSP_svesq(lhs, 1, &lhsSquared, vDSP_Length(lhs.count))
        vDSP_svesq(rhs, 1, &rhsSquared, vDSP_Length(rhs.count))
        let denominator = sqrt(lhsSquared) * sqrt(rhsSquared)
        return denominator > 0 ? dot / denominator : 0
    }
}
