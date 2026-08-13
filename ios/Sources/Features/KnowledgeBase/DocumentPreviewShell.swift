import PDFKit
import SwiftUI
import OrynodeDomain

struct DocumentPreviewIntent: Identifiable, Equatable, Hashable {
    let id: UUID
    let documentID: UUID
    let title: String
    let fileURL: URL
    let indexedText: String?
    let locator: SourceLocator?
    let excerpt: String?
    /// Optional override (e.g. PDF printed page label).
    let locatorLabel: String?

    init(
        documentID: UUID,
        title: String,
        fileURL: URL,
        indexedText: String? = nil,
        locator: SourceLocator? = nil,
        excerpt: String? = nil,
        locatorLabel: String? = nil
    ) {
        self.id = UUID()
        self.documentID = documentID
        self.title = title
        self.fileURL = fileURL
        self.indexedText = indexedText
        self.locator = locator
        self.excerpt = excerpt
        self.locatorLabel = locatorLabel
    }

    var subtitle: String? {
        if let locatorLabel, !locatorLabel.isEmpty { return locatorLabel }
        return locator?.shortLabel
    }
}

struct DocumentPreviewShell: View {
    let intent: DocumentPreviewIntent

    private var kind: PreviewKind {
        switch intent.fileURL.pathExtension.lowercased() {
        case "pdf":
            return .pdf
        case "md", "markdown", "docx", "docm", "xlsx", "xlsm", "pptx", "pptm":
            // OOXML is indexed as Markdown; preview the extracted text for citation jumps.
            return .markdown
        default:
            return .text
        }
    }

    var body: some View {
        Group {
            switch kind {
            case .pdf:
                PDFDocumentPreviewView(url: intent.fileURL, locator: intent.locator, excerpt: intent.excerpt)
            case .markdown, .text:
                TextDocumentPreviewView(
                    text: intent.indexedText ?? (try? String(contentsOf: intent.fileURL, encoding: .utf8)) ?? "",
                    locator: intent.locator,
                    excerpt: intent.excerpt,
                    treatsAsMarkdown: kind == .markdown
                )
            }
        }
        .background(PaperBackground())
        .navigationTitle(intent.title)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                KnowledgeDocumentTypeIcon(
                    fileName: intent.fileURL.lastPathComponent,
                    size: 28
                )
            }
        }
        .safeAreaInset(edge: .top, spacing: 0) {
            if let subtitle = intent.subtitle {
                Text(subtitle)
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundStyle(OrynodeTheme.inkSoft)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 20)
                    .padding(.vertical, 8)
                    .background(OrynodeTheme.paper.opacity(0.95))
            }
        }
    }

    private enum PreviewKind {
        case pdf, markdown, text
    }
}

struct PDFDocumentPreviewView: UIViewRepresentable {
    let url: URL
    let locator: SourceLocator?
    let excerpt: String?

    func makeUIView(context: Context) -> PDFView {
        let view = PDFView()
        view.autoScales = true
        view.displayMode = .singlePageContinuous
        view.displayDirection = .vertical
        view.backgroundColor = UIColor(OrynodeTheme.paper)
        view.document = PDFDocument(url: url)
        DispatchQueue.main.async {
            applyLocation(on: view)
        }
        return view
    }

    func updateUIView(_ uiView: PDFView, context: Context) {
        if uiView.document?.documentURL != url {
            uiView.document = PDFDocument(url: url)
        }
        applyLocation(on: uiView)
    }

    private func applyLocation(on view: PDFView) {
        guard let document = view.document else { return }
        guard case let .pdf(page, startOffset, endOffset) = locator else {
            if let excerpt, !excerpt.isEmpty,
               let selection = document.findString(excerpt, withOptions: [.caseInsensitive]).first {
                view.setCurrentSelection(selection, animate: true)
                view.go(to: selection)
            }
            return
        }
        let index = max(0, min(page - 1, document.pageCount - 1))
        guard let pdfPage = document.page(at: index) else { return }
        // Always land on the labeled page first. Never fall back to an off-page findString hit.
        view.go(to: pdfPage)

        if let startOffset, let endOffset, endOffset > startOffset,
           let selection = selection(on: pdfPage, start: startOffset, end: endOffset),
           selection.pages.contains(where: { $0.pageRef == pdfPage.pageRef }) {
            view.setCurrentSelection(selection, animate: true)
            view.go(to: selection)
            return
        }

        guard let excerpt, !excerpt.isEmpty else { return }
        let matches = document.findString(excerpt, withOptions: [.caseInsensitive])
        if let onPage = matches.first(where: { selection in
            selection.pages.contains(where: { $0.pageRef == pdfPage.pageRef })
        }) {
            view.setCurrentSelection(onPage, animate: true)
            view.go(to: onPage)
            return
        }

        // Try a shorter head of the excerpt still constrained to this page.
        let head = String(excerpt.prefix(24))
        if head.count >= 4 {
            let headMatches = document.findString(head, withOptions: [.caseInsensitive])
            if let onPage = headMatches.first(where: { selection in
                selection.pages.contains(where: { $0.pageRef == pdfPage.pageRef })
            }) {
                view.setCurrentSelection(onPage, animate: true)
                view.go(to: onPage)
            }
        }
    }

    private func selection(on page: PDFPage, start: Int, end: Int) -> PDFSelection? {
        let raw = page.string ?? ""
        let ns = raw as NSString
        let length = ns.length
        guard length > 0 else { return nil }
        let loc = min(max(0, start), length - 1)
        let maxEnd = min(max(loc + 1, end), length)
        let range = NSRange(location: loc, length: maxEnd - loc)
        return page.selection(for: range)
    }
}

struct TextDocumentPreviewView: View {
    let text: String
    let locator: SourceLocator?
    let excerpt: String?
    var treatsAsMarkdown: Bool = false

    var body: some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 0) {
                    ForEach(lineItems) { item in
                        HStack(alignment: .top, spacing: 12) {
                            Text("\(item.lineNumber)")
                                .font(.system(size: 13, weight: item.isHighlighted ? .semibold : .regular, design: .monospaced))
                                .foregroundStyle(item.isHighlighted ? OrynodeTheme.accent : OrynodeTheme.inkFaint)
                                .frame(width: lineNumberColumnWidth, alignment: .trailing)
                                .padding(.top, 2)

                            Text(item.text)
                                .font(.system(size: 16))
                                .foregroundStyle(OrynodeTheme.ink)
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .padding(.vertical, 2)
                                .textSelection(.enabled)
                        }
                        .padding(.horizontal, 16)
                        .padding(.vertical, 1)
                        .background(
                            item.isHighlighted
                                ? OrynodeTheme.accent.opacity(0.22)
                                : Color.clear
                        )
                        .id(item.lineNumber)
                    }
                }
                .padding(.vertical, 16)
            }
            .onAppear {
                scrollToHighlight(using: proxy)
            }
        }
    }

    private var lineNumberColumnWidth: CGFloat {
        let digits = max(2, String(lineItems.count).count)
        return CGFloat(digits) * 10 + 8
    }

    private struct LineItem: Identifiable {
        let lineNumber: Int
        let text: String
        let isHighlighted: Bool

        var id: Int { lineNumber }
    }

    private var lineItems: [LineItem] {
        let lines = text.split(separator: "\n", omittingEmptySubsequences: false).map(String.init)
        let highlight = highlightedLineRange
        return lines.enumerated().map { index, line in
            let lineNumber = index + 1
            let isHighlighted = highlight.map { lineNumber >= $0.lowerBound && lineNumber <= $0.upperBound } ?? false
            return LineItem(lineNumber: lineNumber, text: line.isEmpty ? " " : line, isHighlighted: isHighlighted)
        }
    }

    private var highlightedLineRange: ClosedRange<Int>? {
        switch locator {
        case let .markdown(_, startLine, endLine):
            return startLine...max(startLine, endLine)
        case let .plainText(startOffset, endOffset):
            let startLine = UTF16TextIndex.lineNumber(utf16Offset: startOffset, in: text)
            let endLine = UTF16TextIndex.lineNumber(
                utf16Offset: max(startOffset, endOffset - 1),
                in: text
            )
            return startLine...max(startLine, endLine)
        case .pdf, .none:
            if let excerpt,
               let range = text.range(of: excerpt, options: [.caseInsensitive]) {
                let startLine = UTF16TextIndex.lineNumber(
                    utf16Offset: text.utf16.distance(from: text.startIndex, to: range.lowerBound),
                    in: text
                )
                let endLine = UTF16TextIndex.lineNumber(
                    utf16Offset: text.utf16.distance(from: text.startIndex, to: range.upperBound) - 1,
                    in: text
                )
                return startLine...max(startLine, endLine)
            }
            return nil
        }
    }

    private func scrollToHighlight(using proxy: ScrollViewProxy) {
        let targetLine = scrollTargetLine ?? highlightedLineRange?.lowerBound
        guard let targetLine else { return }
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.08) {
            withAnimation(.easeOut(duration: 0.25)) {
                proxy.scrollTo(targetLine, anchor: .center)
            }
        }
    }

    /// Prefer the most specific line inside a multi-line highlight (e.g. answer fact at chunk tail).
    private var scrollTargetLine: Int? {
        guard let range = highlightedLineRange, range.lowerBound != range.upperBound else {
            return highlightedLineRange?.lowerBound
        }
        if let excerpt,
           !excerpt.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
           let line = lineNumber(containing: excerpt) {
            if range.contains(line) { return line }
        }
        return range.upperBound
    }

    private func lineNumber(containing fragment: String) -> Int? {
        let trimmed = fragment.trimmingCharacters(in: .whitespacesAndNewlines)
        guard trimmed.count >= 4 else { return nil }
        let lines = text.split(separator: "\n", omittingEmptySubsequences: false)
        for (index, line) in lines.enumerated() {
            if line.range(of: trimmed, options: [.caseInsensitive]) != nil {
                return index + 1
            }
        }
        let prefix = String(trimmed.prefix(min(24, trimmed.count)))
        for (index, line) in lines.enumerated() where line.contains(prefix) {
            return index + 1
        }
        return nil
    }
}
