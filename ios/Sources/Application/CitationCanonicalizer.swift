import Foundation

/// Closed-set citation check only: drop illegal `[n]`, leave legal markers and prose untouched.
/// Does not move markers, dedupe repeats, or rewrite line breaks — body stays model output.
public struct CitationCanonicalizer: Sendable {
    public struct Result: Equatable, Sendable {
        public let text: String
        public let referencedIndices: [Int]

        public init(text: String, referencedIndices: [Int]) {
            self.text = text
            self.referencedIndices = referencedIndices
        }
    }

    private static let markerPattern = try! NSRegularExpression(pattern: #"\[(\d+)\]"#)

    public init() {}

    public func canonicalize(_ text: String, allowedIndices: Set<Int>) -> Result {
        let normalized = text.replacingOccurrences(of: "\r\n", with: "\n")
        let ns = normalized as NSString
        let full = NSRange(location: 0, length: ns.length)

        var referenced: [Int] = []
        var seen = Set<Int>()
        var removals: [NSRange] = []

        Self.markerPattern.enumerateMatches(in: normalized, options: [], range: full) { match, _, _ in
            guard let match, match.numberOfRanges >= 2 else { return }
            let numberRange = match.range(at: 1)
            guard numberRange.location != NSNotFound,
                  let number = Int(ns.substring(with: numberRange)) else { return }

            if allowedIndices.contains(number) {
                if seen.insert(number).inserted {
                    referenced.append(number)
                }
            } else {
                removals.append(match.range)
            }
        }

        var cleaned = normalized
        for range in removals.reversed() {
            guard let swiftRange = Range(range, in: cleaned) else { continue }
            cleaned.replaceSubrange(swiftRange, with: "")
        }

        // Tidy only the scars left by dropped illegal markers; do not reshape legal prose.
        cleaned = cleaned
            .replacingOccurrences(of: #"[ \t\u00a0\u3000]+([。．.！!？?；;，,])"#, with: "$1", options: .regularExpression)
            .replacingOccurrences(of: #"[ \t\u00a0\u3000]{2,}"#, with: " ", options: .regularExpression)

        return Result(
            text: cleaned.trimmingCharacters(in: .whitespacesAndNewlines),
            referencedIndices: referenced
        )
    }
}
