import Foundation
import OrynodeDomain
import OrynodeInfrastructure

enum AppPhase: Equatable {
    case launching
    case needsModel
    case ready
}

@MainActor
final class AppModel: ObservableObject {
    let knowledgeBaseModel: KnowledgeBaseModel

    @Published private(set) var phase: AppPhase = .launching
    @Published private(set) var runtimeState: ModelRuntimeState = .notInstalled
    @Published private(set) var installedModel: InstalledModel?
    @Published private(set) var errorMessage: String?
    @Published private(set) var statusMessage: String?
    @Published var showsSettings = false

    private let engine: any LocalModelEngine
    private let modelStore: any ModelStore
    private let modelDescriptor: ModelDescriptor
    private var loadingTask: Task<Void, Never>?

    init(
        engine: any LocalModelEngine,
        modelStore: any ModelStore,
        modelDescriptor: ModelDescriptor = .gemma4E2B
    ) {
        self.engine = engine
        self.modelStore = modelStore
        self.modelDescriptor = modelDescriptor
        knowledgeBaseModel = KnowledgeBaseModel(
            service: KnowledgeBaseComposition.makeService(engine: engine)
        )
    }

    var modelStorageDescription: String {
        guard let installedModel else { return "尚未安装" }
        let gb = Double(installedModel.byteCount) / 1_000_000_000
        return String(format: "%.2f GB · 已安装", gb)
    }

    var appVersionDescription: String {
        let short = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String
            ?? "—"
        let build = Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String
        if let build, !build.isEmpty {
            return "\(short)（\(build)）"
        }
        return short
    }

    /// Free RAM held by the loaded weights; file on disk stays. Reload before the next answer.
    func unloadLoadedModel() async {
        loadingTask?.cancel()
        loadingTask = nil
        await engine.unload()
        runtimeState = installedModel.map(ModelRuntimeState.installed) ?? .notInstalled
        statusMessage = "模型已释放；下次使用前请重新加载。"
    }

    func start() async {
        phase = .launching
        statusMessage = "正在检查本机模型…"
        guard let model = await modelStore.installedModel(for: modelDescriptor) else {
            installedModel = nil
            runtimeState = .notInstalled
            phase = .needsModel
            statusMessage = nil
            return
        }
        installedModel = model
        await load(model)
    }

    func importAndLoadModel(from sourceURL: URL) async {
        guard loadingTask == nil else { return }
        loadingTask = Task { [weak self] in
            guard let self else { return }
            defer { loadingTask = nil }
            do {
                runtimeState = .loading
                statusMessage = "正在安全导入模型…"
                let model = try await modelStore.importModel(
                    from: sourceURL,
                    descriptor: modelDescriptor
                )
                installedModel = model
                await load(model)
            } catch is CancellationError {
                runtimeState = installedModel.map(ModelRuntimeState.installed) ?? .notInstalled
            } catch {
                presentError(error.localizedDescription)
                runtimeState = .failed(error.localizedDescription)
                phase = installedModel == nil ? .needsModel : .ready
                statusMessage = nil
            }
        }
        await loadingTask?.value
    }

    func loadInstalledModel() async {
        guard let installedModel else {
            runtimeState = .notInstalled
            phase = .needsModel
            return
        }
        await load(installedModel)
    }

    func deleteInstalledModel() async {
        loadingTask?.cancel()
        loadingTask = nil
        await engine.unload()
        do {
            try await modelStore.deleteModel(modelDescriptor)
            installedModel = nil
            runtimeState = .notInstalled
            phase = .needsModel
            showsSettings = false
        } catch {
            presentError(error.localizedDescription)
        }
    }

    func handleMemoryWarning() {
        Task { await unloadLoadedModel() }
    }

    func clearError() {
        errorMessage = nil
    }

    private func load(_ model: InstalledModel) async {
        runtimeState = .loading
        statusMessage = "正在加载本地模型…"
        do {
            try await engine.load(modelAt: model.fileURL)
            runtimeState = .ready
            phase = .ready
            statusMessage = nil
        } catch {
            runtimeState = .failed(error.localizedDescription)
            phase = .needsModel
            statusMessage = nil
            presentError(error.localizedDescription)
        }
    }

    private func presentError(_ message: String) {
        errorMessage = message
    }
}
