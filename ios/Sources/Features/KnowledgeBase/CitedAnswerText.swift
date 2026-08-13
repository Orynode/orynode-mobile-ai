import SwiftUI

/// Renders assistant answers as Markdown, with closed-set `[n]` citation chips kept tappable.
///
/// Line breaks follow the model/canonical text: each non-empty line is its own block.
/// Do not rely on `AttributedString(markdown:)` paragraph rules — they collapse soft newlines.
struct CitedAnswerText: View {
    let text: String
    let citations: [CitedSource]
    var isUser = false
    var onSelect: (CitedSource) -> Void

    private var citationByIndex: [Int: CitedSource] {
        Dictionary(uniqueKeysWithValues: citations.map { ($0.index, $0) })
    }

    private var paragraphs: [String] {
        Self.displayParagraphs(from: text)
    }

    nonisolated static func referencedIndices(in text: String) -> [Int] {
        let pattern = try! NSRegularExpression(pattern: #"\[(\d+)\]"#)
        let ns = text as NSString
        let full = NSRange(location: 0, length: ns.length)
        var values: [Int] = []
        var seen = Set<Int>()
        pattern.enumerateMatches(in: text, options: [], range: full) { match, _, _ in
            guard let match, match.numberOfRanges >= 2 else { return }
            let number = Int(ns.substring(with: match.range(at: 1)))
            guard let number, seen.insert(number).inserted else { return }
            values.append(number)
        }
        return values
    }

    /// Strip broken template labels; keep the model's line structure.
    nonisolated static func prepareMarkdown(_ text: String) -> String {
        displayParagraphs(from: text).joined(separator: "\n")
    }

    nonisolated static func displayParagraphs(from text: String) -> [String] {
        text
            .replacingOccurrences(of: "\r\n", with: "\n")
            .components(separatedBy: "\n")
            .map(stripTemplateLabels)
            .map { $0.trimmingCharacters(in: .whitespaces) }
            .filter { !$0.isEmpty }
    }

    nonisolated private static func stripTemplateLabels(_ line: String) -> String {
        let trimmed = line.trimmingCharacters(in: .whitespaces)
        for prefix in [
            "**结论：**", "**结论:**", "结论：", "结论:",
            "**依据：**", "**依据:**", "依据：", "依据:",
        ] {
            if trimmed.hasPrefix(prefix) {
                return String(trimmed.dropFirst(prefix.count))
                    .trimmingCharacters(in: .whitespaces)
            }
        }
        return line
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            ForEach(Array(paragraphs.enumerated()), id: \.offset) { _, paragraph in
                Text(attributed(for: paragraph))
                    .font(.system(size: 16))
                    .lineSpacing(5)
                    .fixedSize(horizontal: false, vertical: true)
                    .tint(isUser ? Color.white : OrynodeTheme.accent)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
        .environment(\.openURL, OpenURLAction { url in
            guard url.scheme == "cite",
                  let host = url.host,
                  let index = Int(host),
                  let citation = citationByIndex[index] else {
                return .discarded
            }
            onSelect(citation)
            return .handled
        })
    }

    private func attributed(for paragraph: String) -> AttributedString {
        let pattern = try! NSRegularExpression(pattern: #"\[(\d+)\]"#)
        let ns = paragraph as NSString
        let matches = pattern.matches(
            in: paragraph,
            options: [],
            range: NSRange(location: 0, length: ns.length)
        )

        var replaced = paragraph
        for match in matches.reversed() {
            guard match.numberOfRanges >= 2 else { continue }
            let number = ns.substring(with: match.range(at: 1))
            guard let index = Int(number), citationByIndex[index] != nil else { continue }
            let markdownLink = "[ \(index) ](cite://\(index))"
            guard let range = Range(match.range, in: replaced) else { continue }
            replaced.replaceSubrange(range, with: markdownLink)
        }

        var attributed: AttributedString
        do {
            // Inline only: bold/links without block markdown eating line structure.
            attributed = try AttributedString(
                markdown: replaced,
                options: AttributedString.MarkdownParsingOptions(
                    interpretedSyntax: .inlineOnlyPreservingWhitespace
                )
            )
        } catch {
            attributed = AttributedString(paragraph)
        }

        let prose = isUser ? Color.white : OrynodeTheme.ink
        let chip = isUser ? Color.white : OrynodeTheme.accent
        let chipBg = isUser ? Color.white.opacity(0.22) : OrynodeTheme.accent.opacity(0.14)

        for run in attributed.runs {
            let range = run.range
            if let url = run.link, url.scheme == "cite" {
                attributed[range].foregroundColor = chip
                attributed[range].backgroundColor = chipBg
                attributed[range].font = .system(size: 12, weight: .bold, design: .rounded)
                attributed[range].underlineStyle = nil
            } else if attributed[range].foregroundColor == nil {
                attributed[range].foregroundColor = prose
            }
        }
        return attributed
    }
}
