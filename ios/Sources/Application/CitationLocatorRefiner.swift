import Foundation
import OrynodeDomain

/// Narrows chunk-level locators to the answer-relevant span inside `indexedText`.
public enum CitationLocatorRefiner {
    public static func refine(
        indexedText: String?,
        chunkLocator: SourceLocator?,
        question: String,
        answerText: String,
        pageSpans: [KnowledgePageSpan] = [],
        evidenceExcerpt: String? = nil
    ) -> SourceLocator? {
        guard let indexedText, !indexedText.isEmpty else { return nil }
        let normalized = indexedText
            .replacingOccurrences(of: "\r\n", with: "\n")
            .replacingOccurrences(of: "\r", with: "\n")

        let needles = searchNeedles(
            question: question,
            answerText: answerText,
            evidenceExcerpt: evidenceExcerpt
        )
        let hint = preferredStart(in: normalized, chunkLocator: chunkLocator, pageSpans: pageSpans)
        let preferChunkProximity: Bool = {
            switch chunkLocator {
            case .pdf: return false
            default: return true
            }
        }()
        let answerFingerprint = collapseWhitespace(
            answerText
                .replacingOccurrences(of: #"\[\d+\]"#, with: "", options: .regularExpression)
                .replacingOccurrences(of: "**", with: "")
                .replacingOccurrences(of: "*", with: "")
                .replacingOccurrences(of: "`", with: "")
        )

        // If the user's question literally appears in the document, that span wins.
        // Otherwise long paraphrased answer fragments near chunk-start steal the locator
        // (e.g. query「黄兴路步行街」→ early「美食/烟火气」paragraph).
        var best: (range: Range<Int>, score: Int)? = bestMatch(
            needles: QueryFocus.terms(from: question),
            in: normalized,
            answerFingerprint: answerFingerprint,
            preferredStart: hint,
            preferChunkProximity: preferChunkProximity,
            questionBoost: 8_000
        )
        if best == nil {
            best = bestMatch(
                needles: needles,
                in: normalized,
                answerFingerprint: answerFingerprint,
                preferredStart: hint,
                preferChunkProximity: preferChunkProximity,
                questionBoost: 0
            )
        }

        // Evidence excerpts are literal chunk windows; use them when paraphrased answers miss.
        if best == nil, let excerptRange = locateEvidenceExcerpt(
            evidenceExcerpt,
            in: normalized,
            preferredStart: hint
        ) {
            // Keep fallback to a single focus point; plainText expands to line bounds below.
            let focus = min(
                max(hint, excerptRange.lowerBound),
                max(excerptRange.lowerBound, excerptRange.upperBound - 1)
            )
            best = (focus..<min(focus + 1, excerptRange.upperBound), 0)
        }

        guard let best else { return nil }
        let narrowed = expandPlainTextToLineBoundsIfNeeded(
            range: best.range,
            in: normalized,
            template: chunkLocator
        )
        return makeLocator(
            for: narrowed,
            in: normalized,
            template: chunkLocator,
            pageSpans: pageSpans
        )
    }

    public static func excerpt(
        at locator: SourceLocator?,
        in indexedText: String?,
        pageSpans: [KnowledgePageSpan] = []
    ) -> String? {
        guard let indexedText, let locator else { return nil }
        let normalized = indexedText
            .replacingOccurrences(of: "\r\n", with: "\n")
            .replacingOccurrences(of: "\r", with: "\n")
        let lines = normalized.split(separator: "\n", omittingEmptySubsequences: false).map(String.init)
        switch locator {
        case let .markdown(_, startLine, endLine):
            let start = max(1, startLine) - 1
            let end = min(lines.count, max(startLine, endLine))
            guard start < lines.count else { return nil }
            return lines[start..<end].joined(separator: "\n").trimmingCharacters(in: .whitespacesAndNewlines)
        case let .plainText(start, end):
            return slice(normalized, start: start, end: end)
        case let .pdf(page, startOffset, endOffset):
            guard let span = pageSpans.first(where: { $0.page == page }) else { return nil }
            let localStart = startOffset ?? 0
            let localEnd = endOffset ?? max(localStart, span.end - span.start)
            return slice(
                normalized,
                start: span.start + localStart,
                end: span.start + localEnd
            )
        }
    }

    // MARK: - Needles

    private static func searchNeedles(
        question: String,
        answerText: String,
        evidenceExcerpt: String?
    ) -> [String] {
        var needles: [String] = []
        var seen = Set<String>()

        func append(_ value: String, minimum: Int = 2) {
            let trimmed = collapseWhitespace(value)
            guard trimmed.count >= minimum, seen.insert(trimmed).inserted else { return }
            needles.append(trimmed)
        }

        // Prefer literal evidence from the retrieved chunk — strongest TXT/MD anchor.
        // Do not add the full excerpt window: it is often ~chunk-sized and would keep
        // locators looking like "字符 0–511". Prefer sentence/prefix anchors instead.
        if let evidenceExcerpt {
            let excerpt = evidenceExcerpt
                .replacingOccurrences(of: "\r\n", with: "\n")
                .replacingOccurrences(of: "\r", with: "\n")
                .trimmingCharacters(in: .whitespacesAndNewlines)
            for fragment in excerpt.components(separatedBy: CharacterSet(charactersIn: "\n。．.！!？?；;：:")) {
                append(fragment, minimum: 4)
            }
            if excerpt.count >= 8 {
                let prefix = String(excerpt.prefix(min(32, excerpt.count)))
                if seen.insert(prefix).inserted {
                    needles.append(prefix)
                }
                append(prefix, minimum: 8)
            }
        }

        let strippedAnswer = answerText
            .replacingOccurrences(of: #"\[\d+\]"#, with: "", options: .regularExpression)
            .replacingOccurrences(of: "**", with: "")
            .replacingOccurrences(of: "*", with: "")
            .replacingOccurrences(of: "`", with: "")

        // Answer fragments stay a bit longer to avoid noisy 1–2 char hits.
        for fragment in strippedAnswer.components(separatedBy: CharacterSet(charactersIn: "\n。．.！!？?；;：:")) {
            append(fragment, minimum: 4)
        }

        for term in QueryFocus.terms(from: question) {
            append(term)
        }

        return needles.sorted { $0.count > $1.count }
    }

    /// Find the evidence excerpt (or a durable prefix) near the ingest chunk.
    private static func locateEvidenceExcerpt(
        _ evidenceExcerpt: String?,
        in text: String,
        preferredStart: Int
    ) -> Range<Int>? {
        guard let evidenceExcerpt else { return nil }
        let normalizedExcerpt = evidenceExcerpt
            .replacingOccurrences(of: "\r\n", with: "\n")
            .replacingOccurrences(of: "\r", with: "\n")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        guard normalizedExcerpt.count >= 4 else { return nil }

        if let exact = utf16Range(of: normalizedExcerpt, in: text, preferredStart: preferredStart) {
            return exact
        }
        let prefixLen = min(48, normalizedExcerpt.count)
        if prefixLen >= 8 {
            let prefix = String(normalizedExcerpt.prefix(prefixLen))
            if let range = utf16Range(of: prefix, in: text, preferredStart: preferredStart) {
                return range
            }
        }
        return nil
    }

    /// Plain-text previews are line-oriented; expand a short hit to the containing line(s).
    private static func expandPlainTextToLineBoundsIfNeeded(
        range: Range<Int>,
        in text: String,
        template: SourceLocator?
    ) -> Range<Int> {
        guard case .plainText = template else { return range }
        let startLine = UTF16TextIndex.lineNumber(utf16Offset: range.lowerBound, in: text)
        let endLine = UTF16TextIndex.lineNumber(
            utf16Offset: max(range.lowerBound, range.upperBound - 1),
            in: text
        )
        let lower = lineStartUTF16Offset(startLine, in: text)
        let lastLine = text.isEmpty
            ? 1
            : UTF16TextIndex.lineNumber(utf16Offset: max(0, text.utf16.count - 1), in: text)
        let upper = endLine >= lastLine
            ? text.utf16.count
            : lineStartUTF16Offset(endLine + 1, in: text)
        guard upper > lower else { return range }
        return lower..<upper
    }

    // MARK: - Matching

    private static func preferredStart(
        in text: String,
        chunkLocator: SourceLocator?,
        pageSpans: [KnowledgePageSpan]
    ) -> Int {
        switch chunkLocator {
        case let .markdown(_, startLine, _):
            return lineStartUTF16Offset(startLine, in: text)
        case let .plainText(start, _):
            return max(0, start)
        case let .pdf(page, startOffset, _):
            if let span = pageSpans.first(where: { $0.page == page }) {
                return span.start + max(0, startOffset ?? 0)
            }
            return 0
        case .none:
            return 0
        }
    }

    private static func utf16Range(of needle: String, in haystack: String, preferredStart: Int) -> Range<Int>? {
        let hay = Array(haystack.utf16)
        let needleUTF16 = Array(needle.utf16)
        guard needleUTF16.count >= 2, hay.count >= needleUTF16.count else { return nil }

        let upperBound = hay.count - needleUTF16.count
        let start = min(max(0, preferredStart), upperBound)
        if let exact = match(needleUTF16, in: hay, from: start) {
            return exact..<(exact + needleUTF16.count)
        }
        if start > 0, let fallback = match(needleUTF16, in: hay, from: 0) {
            return fallback..<(fallback + needleUTF16.count)
        }
        return nil
    }

    private static func allMatches(_ needle: [UInt16], in hay: [UInt16]) -> [Int] {
        guard needle.count >= 2, hay.count >= needle.count else { return [] }
        var starts: [Int] = []
        let upperBound = hay.count - needle.count
        var index = 0
        while index <= upperBound {
            if let found = match(needle, in: hay, from: index) {
                starts.append(found)
                index = found + max(1, needle.count)
            } else {
                break
            }
        }
        return starts
    }

    /// Prefer answer-overlapping hits over merely being near the chunk page.
    private static func bestMatch(
        needles: [String],
        in text: String,
        answerFingerprint: String,
        preferredStart: Int,
        preferChunkProximity: Bool,
        questionBoost: Int
    ) -> (range: Range<Int>, score: Int)? {
        var best: (range: Range<Int>, score: Int)?
        let hay = Array(text.utf16)
        for needle in needles {
            let needleUTF16 = Array(needle.utf16)
            guard needleUTF16.count >= 2 else { continue }
            for start in allMatches(needleUTF16, in: hay) {
                let range = start..<(start + needleUTF16.count)
                let score = matchScore(
                    range: range,
                    in: text,
                    needleLength: needleUTF16.count,
                    answerFingerprint: answerFingerprint,
                    preferredStart: preferredStart,
                    preferChunkProximity: preferChunkProximity
                ) + questionBoost
                if best == nil || score > best!.score {
                    best = (range, score)
                }
            }
        }
        return best
    }

    private static func matchScore(
        range: Range<Int>,
        in text: String,
        needleLength: Int,
        answerFingerprint: String,
        preferredStart: Int,
        preferChunkProximity: Bool
    ) -> Int {
        var score = min(needleLength, 48) * 100
        if let window = slice(
            text,
            start: max(0, range.lowerBound - 80),
            end: min(text.utf16.count, range.upperBound + 80)
        ) {
            let collapsed = collapseWhitespace(window)
            score += sharedSubstringBonus(answerFingerprint, collapsed)
        }
        // PDF pages: answer overlap dominates. Chunk-page proximity is only a weak tie-break
        // for markdown/plain where the chunk span is trustworthy.
        if preferChunkProximity {
            let distance = abs(range.lowerBound - preferredStart)
            score -= min(distance / 50, 40)
        }
        return score
    }

    private static func sharedSubstringBonus(_ answer: String, _ window: String) -> Int {
        guard answer.count >= 4, window.count >= 4 else { return 0 }
        let answerChars = Array(answer)
        var bonus = 0
        var index = 0
        let minLen = 4
        while index + minLen <= answerChars.count {
            let end = min(index + 12, answerChars.count)
            let piece = String(answerChars[index..<end])
            if piece.count >= minLen, window.contains(piece) {
                bonus += piece.count
                index += piece.count
            } else {
                index += 1
            }
        }
        return min(bonus, 200)
    }

    private static func match(_ needle: [UInt16], in hay: [UInt16], from: Int) -> Int? {
        let upperBound = hay.count - needle.count
        guard upperBound >= 0, from <= upperBound else { return nil }
        for start in from...upperBound {
            var ok = true
            for index in 0..<needle.count where hay[start + index] != needle[index] {
                ok = false
                break
            }
            if ok { return start }
        }
        return nil
    }

    private static func makeLocator(
        for range: Range<Int>,
        in text: String,
        template: SourceLocator?,
        pageSpans: [KnowledgePageSpan]
    ) -> SourceLocator {
        switch template {
        case let .pdf(page, _, _):
            // Contract: [n] stays bound to the ingest chunk page. Refinement may only
            // narrow offsets *within that page*. Never rebind to another page because
            // the answer text also appears elsewhere (TOC / earlier mention).
            if let mapped = pdfLocator(for: range, pageSpans: pageSpans),
               case let .pdf(mappedPage, start, end) = mapped,
               mappedPage == page {
                return .pdf(page: mappedPage, startOffset: start, endOffset: end)
            }
            return .pdf(page: page, startOffset: nil, endOffset: nil)
        case let .markdown(path, _, _):
            let startLine = UTF16TextIndex.lineNumber(utf16Offset: range.lowerBound, in: text)
            let endLine = UTF16TextIndex.lineNumber(
                utf16Offset: max(range.lowerBound, range.upperBound - 1),
                in: text
            )
            return .markdown(headingPath: path, startLine: startLine, endLine: max(startLine, endLine))
        case .none:
            let startLine = UTF16TextIndex.lineNumber(utf16Offset: range.lowerBound, in: text)
            let endLine = UTF16TextIndex.lineNumber(
                utf16Offset: max(range.lowerBound, range.upperBound - 1),
                in: text
            )
            return .markdown(headingPath: nil, startLine: startLine, endLine: max(startLine, endLine))
        case .plainText:
            return .plainText(startOffset: range.lowerBound, endOffset: range.upperBound)
        }
    }

    /// Maps a global `indexedText` UTF-16 range back to a page-local PDF locator.
    public static func pdfLocator(
        for range: Range<Int>,
        pageSpans: [KnowledgePageSpan]
    ) -> SourceLocator? {
        guard !pageSpans.isEmpty else { return nil }
        let page = pageSpans.first { span in
            range.lowerBound < span.end && range.upperBound > span.start
        } ?? pageSpans.first
        guard let page else { return nil }
        let localStart = max(0, range.lowerBound - page.start)
        let localEnd = max(localStart, min(range.upperBound, page.end) - page.start)
        return .pdf(page: page.page, startOffset: localStart, endOffset: localEnd)
    }

    private static func slice(_ text: String, start: Int, end: Int) -> String? {
        let utf16 = text.utf16
        let lower = min(max(0, start), utf16.count)
        let upper = min(max(lower, end), utf16.count)
        guard let startIndex = String.Index(utf16.index(utf16.startIndex, offsetBy: lower), within: text),
              let endIndex = String.Index(utf16.index(utf16.startIndex, offsetBy: upper), within: text) else {
            return nil
        }
        let value = String(text[startIndex..<endIndex]).trimmingCharacters(in: .whitespacesAndNewlines)
        return value.isEmpty ? nil : value
    }

    private static func lineStartUTF16Offset(_ line: Int, in text: String) -> Int {
        guard line > 1 else { return 0 }
        var currentLine = 1
        var offset = 0
        let utf16 = text.utf16
        var index = utf16.startIndex
        while index < utf16.endIndex {
            if utf16[index] == 10 {
                currentLine += 1
                index = utf16.index(after: index)
                offset += 1
                if currentLine == line {
                    return offset
                }
                continue
            }
            index = utf16.index(after: index)
            offset += 1
        }
        return offset
    }

    private static func collapseWhitespace(_ text: String) -> String {
        text
            .replacingOccurrences(of: "\u{00A0}", with: " ")
            .split(whereSeparator: \.isWhitespace)
            .joined(separator: " ")
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }
}
