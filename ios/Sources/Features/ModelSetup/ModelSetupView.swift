import SwiftUI
import UniformTypeIdentifiers
import OrynodeDomain

struct ModelSetupView: View {
    @ObservedObject var appModel: AppModel
    @State private var isImportingModel = false

    private var isPreparingModel: Bool {
        if case .loading = appModel.runtimeState { return true }
        return false
    }

    var body: some View {
        OnboardingStageLayout(
            statusMessage: appModel.statusMessage ?? "正在加载本地模型…",
            showsProgress: isPreparingModel
        ) {
            if isPreparingModel {
                Color.clear
            } else {
                bottomActions
            }
        }
        .fileImporter(
            isPresented: $isImportingModel,
            allowedContentTypes: [UTType(filenameExtension: "litertlm") ?? .data],
            allowsMultipleSelection: false
        ) { result in
            guard case let .success(urls) = result, let url = urls.first else {
                return
            }
            Task { await appModel.importAndLoadModel(from: url) }
        }
        .alert(
            "模型准备失败",
            isPresented: Binding(
                get: { appModel.errorMessage != nil },
                set: { if !$0 { appModel.clearError() } }
            )
        ) {
            Button("好") { appModel.clearError() }
        } message: {
            Text(appModel.errorMessage ?? "")
        }
    }

    @ViewBuilder
    private var bottomActions: some View {
        VStack(spacing: 12) {
            if case .installed = appModel.runtimeState {
                Text(appModel.modelStorageDescription)
                    .font(.system(size: 13))
                    .foregroundStyle(OrynodeTheme.inkSoft)
                    .multilineTextAlignment(.center)

                Button("继续使用") {
                    Task { await appModel.loadInstalledModel() }
                }
                .buttonStyle(PrimaryButtonStyle())

                Button("更换模型") {
                    isImportingModel = true
                }
                .buttonStyle(SecondaryButtonStyle())
            } else {
                Button("导入模型") {
                    isImportingModel = true
                }
                .buttonStyle(PrimaryButtonStyle())

                Text("选择本机的 Gemma `.litertlm` 文件即可开始。")
                    .font(.system(size: 13))
                    .foregroundStyle(OrynodeTheme.inkFaint)
                    .multilineTextAlignment(.center)
            }
        }
    }
}
