import Foundation

/// Vision-normalized bounding box: origin at bottom-left, values in 0...1.
public struct OCRNormalizedRect: Sendable, Equatable {
    public let x: Double
    public let y: Double
    public let width: Double
    public let height: Double

    public init(x: Double, y: Double, width: Double, height: Double) {
        self.x = x
        self.y = y
        self.width = width
        self.height = height
    }

    public var midX: Double { x + width / 2 }
    public var midY: Double { y + height / 2 }
    public var maxX: Double { x + width }
    public var maxY: Double { y + height }

    public func union(_ other: OCRNormalizedRect) -> OCRNormalizedRect {
        let minX = min(x, other.x)
        let minY = min(y, other.y)
        let maxX = max(self.maxX, other.maxX)
        let maxY = max(self.maxY, other.maxY)
        return OCRNormalizedRect(x: minX, y: minY, width: maxX - minX, height: maxY - minY)
    }
}

public struct OCRObservation: Sendable, Equatable {
    public let text: String
    public let boundingBox: OCRNormalizedRect

    public init(text: String, boundingBox: OCRNormalizedRect) {
        self.text = text
        self.boundingBox = boundingBox
    }
}

public struct OCRLine: Sendable, Equatable, Identifiable {
    public let id: Int
    public let observations: [OCRObservation]

    public init(id: Int, observations: [OCRObservation]) {
        self.id = id
        self.observations = observations
    }

    public var text: String {
        observations.map(\.text).joined(separator: " ")
    }

    public var boundingBox: OCRNormalizedRect {
        guard let first = observations.first else {
            return OCRNormalizedRect(x: 0, y: 0, width: 0, height: 0)
        }
        return observations.dropFirst().reduce(first.boundingBox) { $0.union($1.boundingBox) }
    }
}

public struct OCRDocument: Sendable, Equatable {
    public let observations: [OCRObservation]
    public let lines: [OCRLine]

    public init(observations: [OCRObservation], lines: [OCRLine]? = nil) {
        let cleaned = observations
            .map {
                OCRObservation(
                    text: $0.text.trimmingCharacters(in: .whitespacesAndNewlines),
                    boundingBox: $0.boundingBox
                )
            }
            .filter { !$0.text.isEmpty }
        self.observations = cleaned
        self.lines = lines ?? Self.clusterLines(cleaned)
    }

    public var plainText: String {
        lines.map(\.text).joined(separator: "\n")
    }

    private static func clusterLines(_ observations: [OCRObservation]) -> [OCRLine] {
        let sorted = observations.sorted {
            if abs($0.boundingBox.midY - $1.boundingBox.midY) > 0.02 {
                return $0.boundingBox.midY > $1.boundingBox.midY
            }
            return $0.boundingBox.midX < $1.boundingBox.midX
        }
        var lines: [[OCRObservation]] = []
        for observation in sorted {
            if var last = lines.last,
               let reference = last.first,
               abs(reference.boundingBox.midY - observation.boundingBox.midY) <= 0.03 {
                last.append(observation)
                lines[lines.count - 1] = last
            } else {
                lines.append([observation])
            }
        }
        return lines.enumerated().map { index, group in
            OCRLine(
                id: index,
                observations: group.sorted { $0.boundingBox.midX < $1.boundingBox.midX }
            )
        }
    }
}
