import Foundation
import OrynodeDomain

/// Query-aware evidence packaging: prefer hits that literally overlap the question,
/// and window excerpts around that overlap. Generic lexical focus — not entity correction.
///
/// Contract: focusing must not starve the model. A query hit on a short heading line
/// still expands to a budgeted context window inside the chunk (AskKB.Trace showed
/// page-43 evidence collapsed to `编辑反向代理服务器配置文件：` / 5 tokens — unusable).
public enum EvidencePackFocus {
    /// Soft floor used when scoring which hit is "richer" around the query.
    public static let scoringWindowCharacters = 160

    /// Stable re-rank: richer query-overlapping windows first, then retrieval score.
    public static func prioritize(
        _ hits: [KnowledgeSearchHit],
        query: String
    ) -> [KnowledgeSearchHit] {
        let needles = queryNeedles(query)
        guard !needles.isEmpty else { return hits }
        return hits.enumerated()
            .sorted { lhs, rhs in
                let left = overlapScore(lhs.element.chunk.text, needles: needles)
                let right = overlapScore(rhs.element.chunk.text, needles: needles)
                if left != right { return left > right }
                if lhs.element.score != rhs.element.score {
                    return lhs.element.score > rhs.element.score
                }
                return lhs.offset < rhs.offset
            }
            .map(\.element)
    }

    /// Center a budgeted window on the best query overlap; fall back to prefix.
    /// Never returns a lone short heading line when the chunk has more text.
    public static func excerpt(
        from text: String,
        query: String,
        maxCharacters: Int
    ) -> String {
        let budget = max(32, maxCharacters)
        let normalized = text
            .replacingOccurrences(of: "\r\n", with: "\n")
            .replacingOccurrences(of: "\r", with: "\n")
        guard !normalized.isEmpty else { return "" }

        let needles = queryNeedles(query)
        for needle in needles {
            guard let match = normalized.range(of: needle) else { continue }
            return characterWindow(around: match, in: normalized, maxCharacters: budget)
        }

        if normalized.count <= budget {
            return normalized
        }
        return String(normalized.prefix(budget))
    }

    // MARK: - Needles

    static func queryNeedles(_ query: String) -> [String] {
        QueryFocus.terms(from: query)
    }

    /// Prefer hits whose local window around the query is substantive (not a thin heading).
    private static func overlapScore(_ text: String, needles: [String]) -> Int {
        var best = 0
        for needle in needles {
            guard let match = text.range(of: needle) else { continue }
            let window = characterWindow(
                around: match,
                in: text,
                maxCharacters: scoringWindowCharacters
            )
            var score = needle.count * 10 + min(window.count, scoringWindowCharacters)
            let line = lineContaining(match, in: text)
            if line.hasSuffix("：") || line.hasSuffix(":") {
                score -= 80
            }
            if line.count < 20 {
                score -= 40
            }
            best = max(best, score)
        }
        return best
    }

    private static func lineContaining(
        _ match: Range<String.Index>,
        in text: String
    ) -> String {
        let lineStart = text[..<match.lowerBound].lastIndex(of: "\n").map { text.index(after: $0) }
            ?? text.startIndex
        let lineEnd = text[match.upperBound...].firstIndex(of: "\n") ?? text.endIndex
        return String(text[lineStart..<lineEnd]).trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private static func characterWindow(
        around match: Range<String.Index>,
        in text: String,
        maxCharacters: Int
    ) -> String {
        let matchLen = text.distance(from: match.lowerBound, to: match.upperBound)
        // Bias pad forward so a heading hit still pulls following body into the pack.
        let backPad = max(0, (maxCharacters - matchLen) / 4)
        let forwardPad = max(0, maxCharacters - matchLen - backPad)
        var start = match.lowerBound
        var remaining = backPad
        while remaining > 0, start > text.startIndex {
            start = text.index(before: start)
            remaining -= 1
        }
        var end = match.upperBound
        remaining = forwardPad
        while remaining > 0, end < text.endIndex {
            end = text.index(after: end)
            remaining -= 1
        }
        if let lineStart = text[..<start].lastIndex(of: "\n") {
            let candidate = text.index(after: lineStart)
            if text.distance(from: candidate, to: end) <= maxCharacters + 48 {
                start = candidate
            }
        } else if start != text.startIndex, text.distance(from: text.startIndex, to: end) <= maxCharacters + 48 {
            start = text.startIndex
        }
        if let lineEnd = text[end...].firstIndex(of: "\n") {
            let candidateEnd = lineEnd
            if text.distance(from: start, to: candidateEnd) <= maxCharacters + 48 {
                end = candidateEnd
            }
        }
        var sliced = String(text[start..<end]).trimmingCharacters(in: .whitespacesAndNewlines)
        if sliced.count > maxCharacters {
            sliced = String(sliced.prefix(maxCharacters))
        }
        return sliced
    }

    private static func collapseWhitespace(_ text: String) -> String {
        text
            .replacingOccurrences(of: "\u{00A0}", with: " ")
            .split(whereSeparator: \.isWhitespace)
            .joined(separator: " ")
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }
}
