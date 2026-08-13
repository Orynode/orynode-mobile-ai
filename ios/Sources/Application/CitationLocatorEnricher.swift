import Foundation
import OrynodeDomain

/// Application-owned Ask post-processing for locators/excerpts.
/// Features may still resolve PDF printed page labels via PDFKit.
public struct EnrichedCitationLocator: Equatable, Sendable {
    public let locator: SourceLocator?
    public let excerpt: String
    public let locatorLabel: String?

    public init(locator: SourceLocator?, excerpt: String, locatorLabel: String? = nil) {
        self.locator = locator
        self.excerpt = excerpt
        self.locatorLabel = locatorLabel
    }
}

public enum CitationLocatorEnricher {
    public static func enrich(
        citation: KnowledgeCitation,
        indexedText: String?,
        pageSpans: [KnowledgePageSpan],
        question: String,
        answerText: String
    ) -> EnrichedCitationLocator {
        let ingest = citation.locator
        let refined = CitationLocatorRefiner.refine(
            indexedText: indexedText,
            chunkLocator: ingest,
            question: question,
            answerText: answerText,
            pageSpans: pageSpans,
            evidenceExcerpt: citation.excerpt
        )
        let locator = lockedPDFPage(refined: refined, ingest: ingest) ?? ingest
        let excerpt = CitationLocatorRefiner.excerpt(
            at: locator,
            in: indexedText,
            pageSpans: pageSpans
        ) ?? citation.excerpt
        return EnrichedCitationLocator(
            locator: locator,
            excerpt: excerpt,
            locatorLabel: displayLabel(locator: locator, indexedText: indexedText)
        )
    }

    /// Keep PDF citations on the ingest page; allow same-page offset narrowing only.
    public static func lockedPDFPage(
        refined: SourceLocator?,
        ingest: SourceLocator?
    ) -> SourceLocator? {
        guard case let .pdf(ingestPage, _, _) = ingest else {
            return refined
        }
        guard case let .pdf(refinedPage, start, end) = refined else {
            return .pdf(page: ingestPage, startOffset: nil, endOffset: nil)
        }
        if refinedPage == ingestPage {
            return .pdf(page: ingestPage, startOffset: start, endOffset: end)
        }
        return .pdf(page: ingestPage, startOffset: nil, endOffset: nil)
    }

    /// TXT character offsets are opaque in the UI; prefer the same line labels as Markdown.
    public static func displayLabel(locator: SourceLocator?, indexedText: String?) -> String? {
        guard let locator else { return nil }
        if case let .plainText(start, end) = locator, let indexedText, !indexedText.isEmpty {
            let normalized = indexedText
                .replacingOccurrences(of: "\r\n", with: "\n")
                .replacingOccurrences(of: "\r", with: "\n")
            let startLine = UTF16TextIndex.lineNumber(utf16Offset: start, in: normalized)
            let endLine = UTF16TextIndex.lineNumber(
                utf16Offset: max(start, end - 1),
                in: normalized
            )
            return startLine == endLine
                ? "第 \(startLine) 行"
                : "第 \(startLine)–\(endLine) 行"
        }
        return locator.shortLabel
    }
}
