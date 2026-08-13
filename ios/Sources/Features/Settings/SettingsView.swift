import SwiftUI
import OrynodeDomain

struct SettingsView: View {
    @ObservedObject var appModel: AppModel
    @ObservedObject private var knowledgeModel: KnowledgeBaseModel
    @Environment(\.dismiss) private var dismiss
    @State private var confirmsDeleteModel = false
    @State private var confirmsClearHistory = false
    @State private var knowledgeBaseBytes: Int64 = 0
    @State private var chatHistoryBytes: Int64 = 0

    init(appModel: AppModel) {
        self.appModel = appModel
        knowledgeModel = appModel.knowledgeBaseModel
    }

    var body: some View {
        NavigationStack {
            ZStack {
                PaperBackground()
                List {
                    modelSection
                    knowledgeSection
                    dataSection
                    privacySection
                    aboutSection
                    if appModel.installedModel != nil {
                        deleteModelSection
                    }
                }
                .scrollContentBackground(.hidden)
            }
            .navigationTitle("设置")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("完成") { dismiss() }
                        .fontWeight(.semibold)
                        .foregroundStyle(OrynodeTheme.accent)
                }
            }
            .confirmationDialog(
                "删除本机模型？",
                isPresented: $confirmsDeleteModel,
                titleVisibility: .visible
            ) {
                Button("删除模型", role: .destructive) {
                    Task { await appModel.deleteInstalledModel() }
                }
                Button("取消", role: .cancel) {}
            } message: {
                Text("将删除本机模型（当前 \(appModel.modelStorageDescription)），并退出到模型准备页。")
            }
            .confirmationDialog(
                "清空全部聊天记录？",
                isPresented: $confirmsClearHistory,
                titleVisibility: .visible
            ) {
                Button("清空聊天记录", role: .destructive) {
                    knowledgeModel.clearAllChatHistory()
                    refreshFootprints()
                }
                Button("取消", role: .cancel) {}
            } message: {
                Text("将删除本机保存的全部对话，文档与索引不受影响。")
            }
            .task {
                await knowledgeModel.load()
                knowledgeModel.loadChatHistory()
                refreshFootprints()
            }
        }
    }

    private var modelSection: some View {
        Section {
            settingRow(title: "状态", value: modelStatusText)
            settingRow(title: "占用空间", value: appModel.modelStorageDescription)

            if case .ready = appModel.runtimeState {
                Button("释放内存中的模型") {
                    Task { await appModel.unloadLoadedModel() }
                }
                .foregroundStyle(OrynodeTheme.accent)
            } else if case .installed = appModel.runtimeState, appModel.installedModel != nil {
                Button("重新加载模型") {
                    Task { await appModel.loadInstalledModel() }
                }
                .foregroundStyle(OrynodeTheme.accent)
            }
        } header: {
            Text("本地模型")
        } footer: {
            Text("资料检索和回答生成都在本机完成。释放内存不会删除磁盘上的模型文件。")
        }
    }

    private var knowledgeSection: some View {
        Section {
            settingRow(title: "文档", value: "\(knowledgeModel.documents.count) 篇")
            settingRow(
                title: "索引用量",
                value: "\(knowledgeModel.indexedChunkCount) / \(KnowledgeBaseLimits.maxChunks) chunks"
            )
            settingRow(
                title: "知识库占用",
                value: LocalDataFootprint.formattedByteCount(knowledgeBaseBytes)
            )
        } header: {
            Text("知识库")
        } footer: {
            Text("占用含源文件、SQLite 索引与向量。上限 \(KnowledgeBaseLimits.maxChunks) chunks。")
        }
    }

    private var dataSection: some View {
        Section {
            settingRow(
                title: "聊天记录",
                value: historySummaryText
            )
            if knowledgeModel.persistedSessionCount > 0 {
                Button("清空聊天记录", role: .destructive) {
                    confirmsClearHistory = true
                }
            }
        } header: {
            Text("数据")
        } footer: {
            Text("聊天记录只保存在这台 iPhone，不会上传。")
        }
    }

    private var privacySection: some View {
        Section("隐私") {
            Text("导入文档、索引、向量与回答只保存在这台 iPhone。不会创建账号，也不会同步云端。")
                .font(.system(size: 14))
                .foregroundStyle(OrynodeTheme.inkSoft)
                .listRowBackground(OrynodeTheme.paperDeep.opacity(0.55))
        }
    }

    private var aboutSection: some View {
        Section {
            settingRow(title: "产品", value: "Orynode Mobile AI")
            settingRow(title: "版本", value: appModel.appVersionDescription)
            settingRow(title: "模型", value: "Gemma 4 E2B")
            settingRow(title: "引擎", value: "LiteRT-LM")
            settingRow(title: "许可证", value: OrynodeOpenSource.licenseName)
            Link(destination: OrynodeOpenSource.repositoryURL) {
                HStack {
                    Text("源码")
                        .foregroundStyle(OrynodeTheme.ink)
                    Spacer()
                    HStack(spacing: 6) {
                        Image("GitHubMark")
                            .resizable()
                            .renderingMode(.template)
                            .scaledToFit()
                            .frame(width: 16, height: 16)
                            .foregroundStyle(OrynodeTheme.accent)
                        Text(OrynodeOpenSource.repositoryName)
                            .font(.system(size: 15, weight: .medium))
                            .foregroundStyle(OrynodeTheme.accent)
                    }
                }
            }
            .listRowBackground(OrynodeTheme.paperDeep.opacity(0.55))
            .accessibilityLabel("源码，\(OrynodeOpenSource.repositoryName)")

            NavigationLink {
                OpenSourceNoticesView()
            } label: {
                Text("开源许可")
                    .foregroundStyle(OrynodeTheme.ink)
            }
            .listRowBackground(OrynodeTheme.paperDeep.opacity(0.55))
        } header: {
            Text("关于")
        } footer: {
            Text("源码托管在 GitHub（MIT）。打开链接会离开本应用并使用系统浏览器。")
        }
    }

    private var deleteModelSection: some View {
        Section {
            Button("删除本机模型", role: .destructive) {
                confirmsDeleteModel = true
            }
        } footer: {
            Text("删除后需要重新导入模型才能继续使用知识库问答。")
        }
    }

    private var modelStatusText: String {
        switch appModel.runtimeState {
        case .ready: "已就绪"
        case .loading: "加载中"
        case .installed: "已安装，待加载"
        case .failed: "加载失败"
        case .notInstalled: "未安装"
        }
    }

    private var historySummaryText: String {
        let count = knowledgeModel.persistedSessionCount
        let size = LocalDataFootprint.formattedByteCount(chatHistoryBytes)
        if count == 0 {
            return "无 · \(size)"
        }
        return "\(count) 条 · \(size)"
    }

    private func refreshFootprints() {
        knowledgeBaseBytes = LocalDataFootprint.knowledgeBaseByteCount()
        chatHistoryBytes = LocalDataFootprint.chatHistoryByteCount()
    }

    private func settingRow(title: String, value: String) -> some View {
        HStack {
            Text(title)
                .foregroundStyle(OrynodeTheme.ink)
            Spacer()
            Text(value)
                .foregroundStyle(OrynodeTheme.inkSoft)
                .multilineTextAlignment(.trailing)
        }
        .listRowBackground(OrynodeTheme.paperDeep.opacity(0.55))
    }
}
