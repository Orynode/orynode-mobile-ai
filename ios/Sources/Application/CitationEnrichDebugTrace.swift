import Foundation
import OSLog
import OrynodeDomain

/// DEBUG-only citation enrich probe. Does not change locators — only explains decisions.
public enum CitationEnrichDebugTrace {
    private static let logger = Logger(
        subsystem: "ai.orynode.mobile",
        category: "Cite.Enrich"
    )

    /// Opt-in. LLDB: `CitationEnrichDebugTrace.isEnabled = true`
    /// Also follows `AskKnowledgeBaseDebugTrace.isEnabled`.
    nonisolated(unsafe) public static var isEnabled = false

    public struct PDFDecision: Sendable {
        public let citationIndex: Int
        public let ingestPage: Int
        public let finalPage: Int
        public let refinedChangedPage: Bool
        public let answerOverlapOnIngestPage: Int
        public let bestOverlapPage: Int?
        public let bestOverlapScore: Int
        public let diagnosis: String

        public init(
            citationIndex: Int,
            ingestPage: Int,
            finalPage: Int,
            refinedChangedPage: Bool,
            answerOverlapOnIngestPage: Int,
            bestOverlapPage: Int?,
            bestOverlapScore: Int,
            diagnosis: String
        ) {
            self.citationIndex = citationIndex
            self.ingestPage = ingestPage
            self.finalPage = finalPage
            self.refinedChangedPage = refinedChangedPage
            self.answerOverlapOnIngestPage = answerOverlapOnIngestPage
            self.bestOverlapPage = bestOverlapPage
            self.bestOverlapScore = bestOverlapScore
            self.diagnosis = diagnosis
        }
    }

    public static func logPDF(_ decision: PDFDecision) {
        guard isEnabled || AskKnowledgeBaseDebugTrace.isEnabled else { return }
        let line = """
            Cite.Enrich PDF [\(decision.citationIndex)] \
            ingestPage=\(decision.ingestPage) \
            finalPage=\(decision.finalPage) \
            refinedChangedPage=\(decision.refinedChangedPage) \
            overlapIngest=\(decision.answerOverlapOnIngestPage) \
            bestPage=\(decision.bestOverlapPage.map(String.init) ?? "∅") \
            bestScore=\(decision.bestOverlapScore) \
            diagnosis=\(decision.diagnosis)
            """
        logger.info("\(line, privacy: .public)")
        #if DEBUG
        print(line)
        #endif
    }

    /// Score how much `answer` lexically overlaps `pageText` (deterministic, no PDFKit).
    public static func answerOverlapScore(answer: String, pageText: String) -> Int {
        let answerFP = collapse(
            answer
                .replacingOccurrences(of: #"\[\d+\]"#, with: "", options: .regularExpression)
                .replacingOccurrences(of: "**", with: "")
                .replacingOccurrences(of: "*", with: "")
        )
        let pageFP = collapse(pageText)
        guard answerFP.count >= 4, pageFP.count >= 4 else { return 0 }
        let chars = Array(answerFP)
        var bonus = 0
        var index = 0
        while index + 4 <= chars.count {
            let end = min(index + 12, chars.count)
            let piece = String(chars[index..<end])
            if pageFP.contains(piece) {
                bonus += piece.count
                index += piece.count
            } else {
                index += 1
            }
        }
        return min(bonus, 400)
    }

    public static func diagnosePDF(
        ingestPage: Int,
        finalPage: Int,
        overlapIngest: Int,
        bestPage: Int?,
        bestScore: Int
    ) -> String {
        if let bestPage, bestScore >= 40, bestPage != ingestPage, overlapIngest * 2 < bestScore {
            return "MODEL_OR_RETRIEVAL: answer overlaps page \(bestPage) far more than cited ingest page \(ingestPage) — do not remap [n]; fix pack ranking / model cite"
        }
        if finalPage != ingestPage {
            return "CONTRACT_VIOLATION: final page diverged from ingest page — enrich must not rebind PDF page"
        }
        if overlapIngest < 12 {
            return "WEAK_GROUNDS: cited page has little lexical overlap with answer — check evidence excerpt for that [n]"
        }
        return "OK: final page == ingest page"
    }

    private static func collapse(_ text: String) -> String {
        text
            .replacingOccurrences(of: "\u{00A0}", with: " ")
            .split(whereSeparator: \.isWhitespace)
            .joined(separator: " ")
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }
}
