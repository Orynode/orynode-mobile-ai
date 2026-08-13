import SwiftUI

struct SettingsView: View {
    @ObservedObject var appModel: AppModel
    @Environment(\.dismiss) private var dismiss
    @State private var confirmsDelete = false

    var body: some View {
        NavigationStack {
            ZStack {
                PaperBackground()
                List {
                    Section {
                        settingRow(title: "状态", value: modelStatusText)
                        settingRow(title: "占用空间", value: appModel.modelStorageDescription)
                    } header: {
                        Text("本地模型")
                    } footer: {
                        Text("资料检索和回答生成都在本机完成。应用不提供联网补答。")
                    }

                    Section("隐私") {
                        Text("导入文档、索引、向量与回答只保存在这台 iPhone。不会创建账号，也不会同步云端。")
                            .font(.system(size: 14))
                            .foregroundStyle(OrynodeTheme.inkSoft)
                            .listRowBackground(OrynodeTheme.paperDeep.opacity(0.55))
                    }

                    Section("关于") {
                        settingRow(title: "产品", value: "Orynode Mobile AI")
                        settingRow(title: "模型", value: "Gemma 4 E2B")
                        settingRow(title: "引擎", value: "LiteRT-LM")
                    }

                    if appModel.installedModel != nil {
                        Section {
                            Button("删除本机模型", role: .destructive) {
                                confirmsDelete = true
                            }
                        } footer: {
                            Text("删除后需要重新导入模型才能继续使用知识库问答。")
                        }
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
                isPresented: $confirmsDelete,
                titleVisibility: .visible
            ) {
                Button("删除模型", role: .destructive) {
                    Task { await appModel.deleteInstalledModel() }
                }
                Button("取消", role: .cancel) {}
            } message: {
                Text("将删除本机模型（当前 \(appModel.modelStorageDescription)），并退出到模型准备页。")
            }
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
