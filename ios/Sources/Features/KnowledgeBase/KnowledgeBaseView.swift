import SwiftUI
import UniformTypeIdentifiers
import OrynodeDomain

struct KnowledgeBaseView: View {
    @ObservedObject var model: KnowledgeBaseModel
    @Binding var showsChat: Bool

    @State private var showsImporter = false
    @State private var previewIntent: DocumentPreviewIntent?

    var body: some View {
        VStack(alignment: .leading, spacing: 28) {
            hero
            importCard
            documentSection
        }
        .fileImporter(
            isPresented: $showsImporter,
            allowedContentTypes: KnowledgeImportContentTypes.allowed,
            allowsMultipleSelection: false
        ) { result in
            guard case let .success(urls) = result, let url = urls.first else {
                if case let .failure(error) = result {
                    model.errorMessage = error.localizedDescription
                }
                return
            }
            Task { await model.importDocument(from: url) }
        }
        .navigationDestination(item: $previewIntent) { intent in
            DocumentPreviewShell(intent: intent)
        }
        .task {
            if model.documents.isEmpty { await model.load() }
        }
    }

    private var hero: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("本机知识库")
                .font(.system(size: 40, weight: .semibold, design: .rounded))
                .foregroundStyle(OrynodeTheme.ink)
            Text("导入你的资料，在设备上检索、提问并核对来源。")
                .font(.system(size: 17))
                .foregroundStyle(OrynodeTheme.inkSoft)
            Label("完全本机处理 · 不上传 · 无云端补答", systemImage: "lock.shield")
                .font(.system(size: 12, weight: .semibold))
                .foregroundStyle(OrynodeTheme.accent)
                .padding(.top, 4)
        }
        .padding(.top, 12)
    }

    private var importCard: some View {
        VStack(spacing: 12) {
            Button {
                showsImporter = true
            } label: {
                Label(model.isImporting ? "正在建立索引…" : "导入资料", systemImage: "doc.badge.plus")
            }
            .buttonStyle(PrimaryButtonStyle(isEnabled: !model.isImporting))
            .disabled(model.isImporting)

            Button {
                model.startNewChat()
                showsChat = true
            } label: {
                Label("向知识库提问", systemImage: "bubble.left.and.text.bubble.right")
            }
            .buttonStyle(SecondaryButtonStyle())
            .disabled(model.readyDocumentCount == 0)

            Text(model.readyDocumentCount == 0
                 ? "导入完成后即可开始提问"
                 : "\(model.readyDocumentCount) 份资料可用于回答")
                .font(.system(size: 12))
                .foregroundStyle(OrynodeTheme.inkFaint)
        }
    }

    private var documentSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("我的文档")
                .font(.system(size: 12, weight: .semibold))
                .foregroundStyle(OrynodeTheme.inkFaint)
                .tracking(1)

            if model.isLoading {
                ProgressView("正在读取文档…")
            } else if model.documents.isEmpty {
                Text("还没有文档。资料只保存在这台设备上。")
                    .font(.system(size: 14))
                    .foregroundStyle(OrynodeTheme.inkSoft)
                    .padding(.vertical, 10)
            } else {
                ForEach(model.documents) { document in
                    documentRow(document)
                }
            }
        }
    }

    private func documentRow(_ document: KnowledgeDocumentItem) -> some View {
        HStack(spacing: 12) {
            Button {
                Task {
                    if let intent = await model.previewIntent(for: document) {
                        previewIntent = intent
                    }
                }
            } label: {
                HStack(spacing: 12) {
                    KnowledgeDocumentTypeIcon(fileName: document.fileName)
                    VStack(alignment: .leading, spacing: 5) {
                        Text(document.title)
                            .font(.system(size: 16, weight: .medium))
                            .foregroundStyle(OrynodeTheme.ink)
                            .lineLimit(1)
                        statusLabel(document.status)
                        if document.importedChunkCount > 0, case .ready = document.status {
                            Text("\(document.importedChunkCount) chunks")
                                .font(.system(size: 11, weight: .regular))
                                .foregroundStyle(OrynodeTheme.inkFaint)
                        }
                    }
                    Spacer(minLength: 0)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)

            Menu {
                Button("预览", systemImage: "doc.text.magnifyingglass") {
                    Task {
                        if let intent = await model.previewIntent(for: document) {
                            previewIntent = intent
                        }
                    }
                }
                if case .failed = document.status {
                    Button("重试索引", systemImage: "arrow.clockwise") {
                        Task { await model.retry(document) }
                    }
                }
                Button("删除", systemImage: "trash", role: .destructive) {
                    Task { await model.delete(document) }
                }
            } label: {
                Image(systemName: "ellipsis")
                    .foregroundStyle(OrynodeTheme.inkSoft)
                    .frame(width: 36, height: 36)
            }
        }
        .padding(14)
        .background(Color.white.opacity(0.55))
        .clipShape(RoundedRectangle(cornerRadius: 17, style: .continuous))
    }

    @ViewBuilder
    private func statusLabel(_ status: KnowledgeIndexStatus) -> some View {
        switch status {
        case .pending:
            Label("等待索引", systemImage: "clock")
                .font(.system(size: 12, weight: .medium))
                .foregroundStyle(OrynodeTheme.inkFaint)
                .labelStyle(.titleAndIcon)
        case .indexing:
            Label("正在索引", systemImage: "arrow.triangle.2.circlepath")
                .font(.system(size: 12, weight: .medium))
                .foregroundStyle(OrynodeTheme.accent)
                .labelStyle(.titleAndIcon)
        case .ready:
            Label("已索引", systemImage: "checkmark.circle.fill")
                .font(.system(size: 12, weight: .medium))
                .foregroundStyle(.green)
                .labelStyle(.titleAndIcon)
        case let .failed(message):
            Label(message, systemImage: "exclamationmark.circle")
                .font(.system(size: 12, weight: .medium))
                .foregroundStyle(OrynodeTheme.caution)
                .labelStyle(.titleAndIcon)
                .lineLimit(1)
        }
    }
}

struct KnowledgeChatView: View {
    @ObservedObject var model: KnowledgeBaseModel
    @FocusState private var isComposerFocused: Bool
    @State private var draft = ""
    @State private var previewIntent: DocumentPreviewIntent?
    @State private var showsScopePicker = false

    private var canSend: Bool {
        !draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && !model.isAnswering
    }

    var body: some View {
        ZStack {
            PaperBackground()
            ScrollViewReader { proxy in
                ScrollView(showsIndicators: false) {
                    LazyVStack(alignment: .leading, spacing: 14) {
                        if model.messages.isEmpty {
                            emptyState
                                .padding(.top, 48)
                        }
                        ForEach(model.messages) { message in
                            if message.role == .user || !message.text.isEmpty {
                                messageBubble(message)
                                    .id(message.id)
                            }
                        }
                        if model.isAnswering, model.messages.last?.text.isEmpty != false {
                            HStack(spacing: 10) {
                                ProgressView()
                                    .tint(OrynodeTheme.accent)
                                Text(model.answeringPhase ?? "正在检索本机资料…")
                                    .font(.system(size: 14))
                                    .foregroundStyle(OrynodeTheme.inkSoft)
                            }
                            .padding(.horizontal, 4)
                            .id("typing")
                        }
                        Color.clear.frame(height: 8).id("chat-end")
                    }
                    .padding(.horizontal, 20)
                    .padding(.top, 12)
                    .padding(.bottom, 24)
                }
                .chatKeyboardPolicy(.interactive, focused: $isComposerFocused)
                .onChange(of: model.messages.count) { _, _ in
                    withAnimation(.easeOut(duration: 0.2)) {
                        proxy.scrollTo(model.isAnswering ? "typing" : "chat-end", anchor: .bottom)
                    }
                }
                .onChange(of: model.messages.last?.text.count ?? 0) { _, _ in
                    guard model.isAnswering else { return }
                    proxy.scrollTo("chat-end", anchor: .bottom)
                }
                .onChange(of: model.isAnswering) { _, answering in
                    withAnimation(.easeOut(duration: 0.2)) {
                        proxy.scrollTo(answering ? "typing" : "chat-end", anchor: .bottom)
                    }
                }
                .onChange(of: model.activeSessionID) { _, _ in
                    withAnimation(.easeOut(duration: 0.2)) {
                        proxy.scrollTo("chat-end", anchor: .bottom)
                    }
                }
            }
        }
        .safeAreaInset(edge: .bottom, spacing: 0) {
            composer
        }
        .navigationTitle(model.activeSessionTitle)
        .navigationBarTitleDisplayMode(.inline)
        .toolbarBackground(.hidden, for: .navigationBar)
        .sheet(isPresented: $showsScopePicker) {
            KnowledgeScopePicker(
                documents: model.documents.filter { $0.status == .ready },
                scope: model.searchScope
            ) { scope in
                model.setSearchScope(scope)
                showsScopePicker = false
            }
        }
        .dismissKeyboard(when: model.isAnswering)
        .navigationDestination(item: $previewIntent) { intent in
            DocumentPreviewShell(intent: intent)
        }
    }

    private var scopeLabel: String {
        switch model.searchScope {
        case .all:
            return "全部资料"
        case let .documents(ids):
            if ids.count == 1,
               let document = model.documents.first(where: { ids.contains($0.id) }) {
                return document.title
            }
            return "\(ids.count) 份资料"
        }
    }

    private var emptyState: some View {
        VStack(alignment: .leading, spacing: 12) {
            Image(systemName: "text.magnifyingglass")
                .font(.system(size: 28, weight: .medium))
                .foregroundStyle(OrynodeTheme.accent)
            Text("向本机资料提问")
                .font(.system(size: 22, weight: .semibold, design: .rounded))
                .foregroundStyle(OrynodeTheme.ink)
            Text("回答只会依据已索引文档，并附上可核对的来源。")
                .font(.system(size: 15))
                .foregroundStyle(OrynodeTheme.inkSoft)
                .fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(18)
        .background(Color.white.opacity(0.55))
        .overlay(
            RoundedRectangle(cornerRadius: 18, style: .continuous)
                .stroke(OrynodeTheme.rule, lineWidth: 1)
        )
        .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
    }

    private var composer: some View {
        VStack(spacing: 0) {
            Rectangle()
                .fill(OrynodeTheme.rule)
                .frame(height: 1)
            VStack(spacing: 8) {
                if case .documents = model.searchScope {
                    scopeChip
                }
                HStack(alignment: .bottom, spacing: 10) {
                    Button {
                        isComposerFocused = false
                        ChatKeyboard.dismiss()
                        showsScopePicker = true
                    } label: {
                        Image(systemName: "plus")
                            .font(.system(size: 18, weight: .semibold))
                            .foregroundStyle(OrynodeTheme.accent)
                            .frame(width: 40, height: 40)
                            .background(Color.white.opacity(0.78))
                            .overlay(
                                Circle()
                                    .stroke(OrynodeTheme.rule, lineWidth: 1)
                            )
                            .clipShape(Circle())
                    }
                    .buttonStyle(.plain)
                    .disabled(model.isAnswering)
                    .accessibilityLabel("选择检索资料")

                    TextField(composerPlaceholder, text: $draft, axis: .vertical)
                        .font(.system(size: 16))
                        .foregroundStyle(OrynodeTheme.ink)
                        .lineLimit(1...5)
                        .padding(.horizontal, 14)
                        .padding(.vertical, 12)
                        .background(Color.white.opacity(0.78))
                        .overlay(
                            RoundedRectangle(cornerRadius: 18, style: .continuous)
                                .stroke(OrynodeTheme.rule, lineWidth: 1)
                        )
                        .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
                        .focused($isComposerFocused)
                        .submitLabel(.send)
                        .onSubmit(sendIfPossible)

                    Button(action: sendIfPossible) {
                        Image(systemName: "arrow.up")
                            .font(.system(size: 16, weight: .semibold))
                            .foregroundStyle(Color.white.opacity(canSend ? 1 : 0.7))
                            .frame(width: 40, height: 40)
                            .background {
                                if canSend {
                                    OrynodeTheme.brandGradient
                                } else {
                                    OrynodeTheme.brandBlue.opacity(0.35)
                                }
                            }
                            .clipShape(Circle())
                    }
                    .buttonStyle(.plain)
                    .disabled(!canSend)
                    .accessibilityLabel("发送")
                }
            }
            .padding(.horizontal, 16)
            .padding(.top, 10)
            .padding(.bottom, 10)
            .background(OrynodeTheme.paper.opacity(0.97))
        }
    }

    private var composerPlaceholder: String {
        switch model.searchScope {
        case .all:
            return "向本机资料提问…"
        case .documents:
            return "向已选资料提问…"
        }
    }

    private var scopeChip: some View {
        HStack(spacing: 8) {
            scopeChipIcons
            Text(scopeLabel)
                .font(.system(size: 12, weight: .medium))
                .lineLimit(1)
            Spacer(minLength: 0)
            Button {
                model.setSearchScope(.all)
            } label: {
                Image(systemName: "xmark")
                    .font(.system(size: 10, weight: .bold))
                    .foregroundStyle(OrynodeTheme.inkFaint)
                    .frame(width: 22, height: 22)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel("清除资料范围")
        }
        .foregroundStyle(OrynodeTheme.accent)
        .padding(.horizontal, 10)
        .padding(.vertical, 7)
        .background(OrynodeTheme.accent.opacity(0.10))
        .overlay(
            Capsule(style: .continuous)
                .stroke(OrynodeTheme.accent.opacity(0.22), lineWidth: 1)
        )
        .clipShape(Capsule(style: .continuous))
        .onTapGesture {
            showsScopePicker = true
        }
    }

    @ViewBuilder
    private var scopeChipIcons: some View {
        let selected = selectedScopeDocuments
        if selected.isEmpty {
            Image(systemName: "doc.on.doc")
                .font(.system(size: 11, weight: .semibold))
        } else if selected.count == 1 {
            KnowledgeDocumentTypeIcon(fileName: selected[0].fileName, size: 18)
        } else {
            HStack(spacing: -6) {
                ForEach(selected.prefix(3)) { document in
                    KnowledgeDocumentTypeIcon(fileName: document.fileName, size: 18)
                        .background(Circle().fill(OrynodeTheme.paper))
                        .clipShape(Circle())
                }
            }
        }
    }

    private var selectedScopeDocuments: [KnowledgeDocumentItem] {
        guard case let .documents(ids) = model.searchScope else { return [] }
        return model.documents.filter { ids.contains($0.id) }
    }

    private func sendIfPossible() {
        guard canSend else { return }
        let question = draft
        draft = ""
        isComposerFocused = false
        ChatKeyboard.dismiss()
        Task { await model.ask(question) }
    }

    private func messageBubble(_ message: KnowledgeChatTurn) -> some View {
        let isUser = message.role == .user
        return VStack(alignment: isUser ? .trailing : .leading, spacing: 8) {
            ChatBubble(isUser: isUser, copyText: message.text) {
                if isUser {
                    Text(message.text)
                        .font(.system(size: 16))
                        .foregroundStyle(Color.white)
                        .lineSpacing(6)
                } else {
                    CitedAnswerText(
                        text: message.text,
                        citations: message.citations,
                        isUser: false
                    ) { citation in
                        openCitation(citation)
                    }
                }
            }

            if !message.citations.isEmpty {
                VStack(alignment: .leading, spacing: 6) {
                    Text("来源")
                        .font(.system(size: 11, weight: .semibold))
                        .foregroundStyle(OrynodeTheme.inkFaint)
                        .padding(.leading, 4)
                    ForEach(message.citations.sorted(by: { $0.index < $1.index })) { citation in
                        Button {
                            openCitation(citation)
                        } label: {
                            HStack(alignment: .center, spacing: 8) {
                                KnowledgeDocumentTypeIcon(
                                    fileName: fileName(for: citation),
                                    size: 28
                                )
                                Text("[\(citation.index)]")
                                    .font(.system(size: 12, weight: .bold, design: .rounded))
                                    .foregroundStyle(OrynodeTheme.accent)
                                    .frame(minWidth: 28, alignment: .leading)
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(citation.documentTitle)
                                        .font(.system(size: 13, weight: .medium))
                                        .lineLimit(1)
                                    if let label = citation.displayLocatorLabel {
                                        Text(label)
                                            .font(.system(size: 11))
                                            .foregroundStyle(OrynodeTheme.inkFaint)
                                    }
                                }
                                Spacer(minLength: 0)
                                Image(systemName: "chevron.right")
                                    .font(.system(size: 11, weight: .semibold))
                                    .foregroundStyle(OrynodeTheme.inkFaint)
                            }
                            .foregroundStyle(OrynodeTheme.ink)
                            .padding(.horizontal, 12)
                            .padding(.vertical, 9)
                            .background(Color.white.opacity(0.78))
                            .overlay(
                                RoundedRectangle(cornerRadius: 12, style: .continuous)
                                    .stroke(OrynodeTheme.rule, lineWidth: 1)
                            )
                            .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                        }
                        .buttonStyle(.plain)
                    }
                }
                .padding(.trailing, 52)
            }
        }
    }

    private func openCitation(_ citation: CitedSource) {
        isComposerFocused = false
        ChatKeyboard.dismiss()
        Task {
            if let intent = await model.previewIntent(for: citation) {
                previewIntent = intent
            }
        }
    }

    private func fileName(for citation: CitedSource) -> String {
        if let fileURL = citation.fileURL {
            return fileURL.lastPathComponent
        }
        if let document = model.documents.first(where: { $0.id == citation.documentID }) {
            return document.fileName
        }
        return citation.documentTitle
    }
}

private struct KnowledgeScopePicker: View {
    let documents: [KnowledgeDocumentItem]
    let onApply: (KnowledgeSearchScope) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var usesAll: Bool
    @State private var selectedIDs: Set<UUID>

    init(
        documents: [KnowledgeDocumentItem],
        scope: KnowledgeSearchScope,
        onApply: @escaping (KnowledgeSearchScope) -> Void
    ) {
        self.documents = documents
        self.onApply = onApply
        switch scope {
        case .all:
            _usesAll = State(initialValue: true)
            _selectedIDs = State(initialValue: [])
        case let .documents(ids):
            _usesAll = State(initialValue: false)
            _selectedIDs = State(initialValue: ids)
        }
    }

    var body: some View {
        NavigationStack {
            List {
                Button {
                    usesAll = true
                    selectedIDs.removeAll()
                } label: {
                    scopeRow(
                        title: "全部资料",
                        subtitle: "在所有已索引文档中检索",
                        fileName: nil,
                        selected: usesAll
                    )
                }
                .buttonStyle(.plain)

                Section("选择文档") {
                    ForEach(documents) { document in
                        Button {
                            usesAll = false
                            if !selectedIDs.insert(document.id).inserted {
                                selectedIDs.remove(document.id)
                            }
                            if selectedIDs.isEmpty {
                                usesAll = true
                            }
                        } label: {
                            scopeRow(
                                title: document.title,
                                subtitle: document.fileName,
                                fileName: document.fileName,
                                selected: selectedIDs.contains(document.id)
                            )
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
            .navigationTitle("检索范围")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("完成") {
                        onApply(usesAll ? .all : .documents(selectedIDs))
                    }
                }
            }
        }
    }

    private func scopeRow(
        title: String,
        subtitle: String,
        fileName: String?,
        selected: Bool
    ) -> some View {
        HStack(spacing: 12) {
            if let fileName {
                KnowledgeDocumentTypeIcon(fileName: fileName, size: 32)
            } else {
                Image(systemName: "square.stack.3d.up.fill")
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(OrynodeTheme.accent)
                    .frame(width: 32, height: 32)
                    .background(OrynodeTheme.accentSoft)
                    .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
            }
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.system(size: 16, weight: .medium))
                    .foregroundStyle(OrynodeTheme.ink)
                    .lineLimit(1)
                Text(subtitle)
                    .font(.system(size: 12))
                    .foregroundStyle(OrynodeTheme.inkFaint)
                    .lineLimit(1)
            }
            Spacer()
            Image(systemName: selected ? "checkmark.circle.fill" : "circle")
                .foregroundStyle(selected ? OrynodeTheme.accent : OrynodeTheme.inkFaint)
        }
        .contentShape(Rectangle())
    }
}

enum KnowledgeImportContentTypes {
    static var allowed: [UTType] {
        var types: [UTType] = [
            .pdf,
            .plainText,
            .utf8PlainText,
            .text,
        ]
        for ext in ["md", "markdown", "docx", "docm", "xlsx", "xlsm", "pptx", "pptm"] {
            if let type = UTType(filenameExtension: ext) {
                types.append(type)
            }
        }
        return types
    }
}

enum KnowledgeDocumentKind: Equatable {
    case plainText
    case markdown
    case pdf
    case word
    case spreadsheet
    case presentation
    case unknown

    init(fileExtension: String) {
        switch fileExtension.lowercased() {
        case "txt":
            self = .plainText
        case "md", "markdown":
            self = .markdown
        case "pdf":
            self = .pdf
        case "docx", "docm":
            self = .word
        case "xlsx", "xlsm":
            self = .spreadsheet
        case "pptx", "pptm":
            self = .presentation
        default:
            self = .unknown
        }
    }

    /// Bundled Material Icon Theme assets (MIT); see ios/NOTICE.
    var assetName: String {
        switch self {
        case .plainText: "FileIconText"
        case .markdown: "FileIconMarkdown"
        case .pdf: "FileIconPDF"
        case .word: "FileIconWord"
        case .spreadsheet: "FileIconExcel"
        case .presentation: "FileIconPowerPoint"
        case .unknown: "FileIconText"
        }
    }

    var accessibilityLabel: String {
        switch self {
        case .plainText: "纯文本文档"
        case .markdown: "Markdown 文档"
        case .pdf: "PDF 文档"
        case .word: "Word 文档"
        case .spreadsheet: "Excel 文档"
        case .presentation: "PowerPoint 文档"
        case .unknown: "文档"
        }
    }
}

struct KnowledgeDocumentTypeIcon: View {
    let fileName: String
    var size: CGFloat = 40

    private var kind: KnowledgeDocumentKind {
        KnowledgeDocumentKind(fileExtension: (fileName as NSString).pathExtension)
    }

    var body: some View {
        Image(kind.assetName)
            .resizable()
            .interpolation(.high)
            .scaledToFit()
            .frame(width: size, height: size)
            .accessibilityLabel(kind.accessibilityLabel)
    }
}
