import Foundation

/// Output hygiene for on-device generation. Strips leaked runtime control tokens only.
/// Does not rewrite facts, move citations, or reshape prose — keeps the body model-owned.
public enum KnowledgeAnswerSanitizer {
    private static let controlTokenPattern = try! NSRegularExpression(
        pattern: #"</?(?:bos|eos|pad|unk|s|start_of_turn|end_of_turn)\s*>"#,
        options: [.caseInsensitive]
    )

    public static func stripControlTokens(_ text: String) -> String {
        let ns = text as NSString
        let full = NSRange(location: 0, length: ns.length)
        return controlTokenPattern.stringByReplacingMatches(
            in: text,
            options: [],
            range: full,
            withTemplate: ""
        )
    }
}
