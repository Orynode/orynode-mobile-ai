import Foundation

/// Shared question-term extraction for evidence packing and locator refinement.
/// Keep both call sites on the same needles so retrieval and highlight cannot drift.
enum QueryFocus {
    static func terms(from question: String) -> [String] {
        var needles: [String] = []
        var seen = Set<String>()

        func append(_ value: String, minimum: Int = 2) {
            let trimmed = collapseWhitespace(value)
            guard trimmed.count >= minimum, seen.insert(trimmed).inserted else { return }
            needles.append(trimmed)
        }

        let trimmed = collapseWhitespace(question)
        append(trimmed)
        if trimmed.hasSuffix("地址"), trimmed.count > 2 {
            append(String(trimmed.dropLast(2)))
        }
        for focus in questionFocusTerms(trimmed) {
            append(focus)
        }
        return needles.sorted { $0.count > $1.count }
    }

    static func collapseWhitespace(_ text: String) -> String {
        text
            .replacingOccurrences(of: "\u{00A0}", with: " ")
            .split(whereSeparator: \.isWhitespace)
            .joined(separator: " ")
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private static func questionFocusTerms(_ question: String) -> [String] {
        let patterns = [
            #"^什么是\s*(.+)$"#,
            #"^(.+?)\s*是什么\??？?$"#,
            #"^请问\s*(.+)$"#,
            #"^介绍(?:一下)?\s*(.+)$"#,
        ]
        var terms: [String] = []
        for pattern in patterns {
            guard let regex = try? NSRegularExpression(pattern: pattern) else { continue }
            let ns = question as NSString
            let full = NSRange(location: 0, length: ns.length)
            guard let match = regex.firstMatch(in: question, options: [], range: full),
                  match.numberOfRanges >= 2 else { continue }
            let inner = ns.substring(with: match.range(at: 1))
                .trimmingCharacters(in: .whitespacesAndNewlines)
            if !inner.isEmpty {
                terms.append(inner)
            }
        }
        return terms
    }
}
