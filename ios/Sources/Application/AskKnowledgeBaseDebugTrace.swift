import Foundation
import OSLog

/// Read-only Ask pipeline probe: pack / raw / final.
/// Does not change generation or citation validation — only logs when enabled.
public enum AskKnowledgeBaseDebugTrace {
    private static let logger = Logger(
        subsystem: "ai.orynode.mobile",
        category: "AskKB.Trace"
    )

    /// Opt-in only. Enable in DEBUG via LLDB:
    /// `AskKnowledgeBaseDebugTrace.isEnabled = true`
    /// Also enables `CitationEnrichDebugTrace` PDF page diagnostics.
    /// Call sites are `#if DEBUG` in `AskKnowledgeBase` — compiled out of Release.
    nonisolated(unsafe) public static var isEnabled = false

    public static func log(
        question: String,
        pack: String,
        citationIndices: [Int],
        hitSummaries: [String],
        raw: String,
        final: String,
        referencedIndices: [Int]
    ) {
        guard isEnabled else { return }

        let rawMarkers = markerInventory(in: raw)
        let finalMarkers = markerInventory(in: final)
        let separator = String(repeating: "─", count: 48)

        let header = """
            \(separator)
            AskKB.Trace questionChars=\(question.count) packChars=\(pack.count)
            hits:
            \(hitSummaries.joined(separator: "\n"))
            packIndices=\(citationIndices.map(String.init).joined(separator: ","))
            """
        let body = """
            pack:
            \(pack)
            --- raw (pre-canonicalize) markers=\(rawMarkers.summary) newlines=\(newlineCount(raw))
            \(raw)
            --- final (post-canonicalize) markers=\(finalMarkers.summary) newlines=\(newlineCount(final)) referenced=\(referencedIndices.map(String.init).joined(separator: ","))
            \(final)
            diagnosisHint=\(diagnosisHint(raw: rawMarkers, final: finalMarkers))
            \(separator)
            """

        // Structural line is public for Console.app; full body mirrors to stdout for Xcode.
        logger.info("\(header, privacy: .public)")
        logger.debug("\(body, privacy: .private)")
        #if DEBUG
        print(header)
        print(body)
        #endif
    }

    private static func newlineCount(_ text: String) -> Int {
        text.filter { $0 == "\n" }.count
    }

    private struct MarkerInventory {
        var counts: [Int: Int]
        var ordered: [Int]

        var summary: String {
            if ordered.isEmpty { return "∅" }
            return ordered.map { n in
                let c = counts[n, default: 0]
                return c == 1 ? "[\(n)]" : "[\(n)]×\(c)"
            }.joined(separator: " ")
        }
    }

    private static let markerPattern = try! NSRegularExpression(pattern: #"\[(\d+)\]"#)

    private static func markerInventory(in text: String) -> MarkerInventory {
        let ns = text as NSString
        let full = NSRange(location: 0, length: ns.length)
        var counts: [Int: Int] = [:]
        var ordered: [Int] = []
        markerPattern.enumerateMatches(in: text, options: [], range: full) { match, _, _ in
            guard let match, match.numberOfRanges >= 2,
                  let number = Int(ns.substring(with: match.range(at: 1))) else { return }
            if counts[number] == nil { ordered.append(number) }
            counts[number, default: 0] += 1
        }
        return MarkerInventory(counts: counts, ordered: ordered)
    }

    private static func diagnosisHint(raw: MarkerInventory, final: MarkerInventory) -> String {
        let rawTotal = raw.counts.values.reduce(0, +)
        let finalTotal = final.counts.values.reduce(0, +)
        if rawTotal == 0 && finalTotal == 0 {
            return "A/C? no markers in raw or final — check UI source-list highlighting vs body"
        }
        if rawTotal > 0 && rawTotal == finalTotal && raw.counts == final.counts {
            return "A: raw already dense/same as final → L3 model (or prompt), not L5"
        }
        if finalTotal > rawTotal {
            return "B: final denser than raw → investigate L5 Canonicalizer"
        }
        if finalTotal < rawTotal {
            return "L5 dropped some markers (illegal indices or line dedupe) — expected for closed set"
        }
        return "compare raw vs final marker positions; if UI looks denser than final → L6"
    }
}
