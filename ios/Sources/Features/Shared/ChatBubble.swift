import SwiftUI
import UIKit

/// Messages-style bubble. SwiftUI has no built-in chat bubble —
/// use `UnevenRoundedRectangle` for the asymmetric “mouth” corner.
struct ChatBubbleShape: Shape {
    enum Side {
        case leading
        case trailing
    }

    var side: Side
    var cornerRadius: CGFloat = 20
    var tipRadius: CGFloat = 6

    func path(in rect: CGRect) -> Path {
        UnevenRoundedRectangle(
            topLeadingRadius: cornerRadius,
            bottomLeadingRadius: side == .leading ? tipRadius : cornerRadius,
            bottomTrailingRadius: side == .trailing ? tipRadius : cornerRadius,
            topTrailingRadius: cornerRadius,
            style: .continuous
        )
        .path(in: rect)
    }
}

struct ChatBubble<Content: View>: View {
    var isUser: Bool
    var copyText: String? = nil
    @ViewBuilder var content: () -> Content

    private var side: ChatBubbleShape.Side { isUser ? .trailing : .leading }

    var body: some View {
        HStack(alignment: .bottom, spacing: 0) {
            if isUser { Spacer(minLength: 52) }

            content()
                .font(.system(size: 16))
                .multilineTextAlignment(.leading)
                .padding(.horizontal, 14)
                .padding(.vertical, 11)
                .background {
                    if isUser {
                        OrynodeTheme.brandGradient
                    } else {
                        Color.white.opacity(0.90)
                    }
                }
                .overlay {
                    ChatBubbleShape(side: side)
                        .stroke(isUser ? Color.clear : OrynodeTheme.rule.opacity(0.9), lineWidth: 1)
                }
                .clipShape(ChatBubbleShape(side: side))
                .contextMenu {
                    if let copyText, !copyText.isEmpty {
                        Button {
                            UIPasteboard.general.string = ChatCopyText.plain(from: copyText)
                        } label: {
                            Label("复制", systemImage: "doc.on.doc")
                        }
                    }
                }

            if !isUser { Spacer(minLength: 52) }
        }
    }
}

/// Clipboard copy should be readable plain text, not raw Markdown source.
enum ChatCopyText {
    static func plain(from markdown: String) -> String {
        let prepared = CitedAnswerText.prepareMarkdown(markdown)
        if let attributed = try? AttributedString(
            markdown: prepared,
            options: AttributedString.MarkdownParsingOptions(interpretedSyntax: .full)
        ) {
            return String(attributed.characters)
                .trimmingCharacters(in: .whitespacesAndNewlines)
        }
        return prepared
            .replacingOccurrences(of: #"\*\*(.+?)\*\*"#, with: "$1", options: .regularExpression)
            .replacingOccurrences(of: #"__(.+?)__"#, with: "$1", options: .regularExpression)
            .replacingOccurrences(of: #"(?m)^\s*[-*]\s+"#, with: "• ", options: .regularExpression)
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }
}
