import Foundation

/// Text-only generation request for grounded knowledge-base answers.
public struct AnalysisRequest: Sendable {
    public let prompt: String

    public init(prompt: String) {
        self.prompt = prompt
    }
}

public struct ModelDescriptor: Equatable, Sendable {
    public let id: String
    public let version: String
    public let fileName: String
    public let expectedByteCount: Int64?
    public let expectedSHA256: String?

    public init(
        id: String,
        version: String,
        fileName: String,
        expectedByteCount: Int64? = nil,
        expectedSHA256: String? = nil
    ) {
        self.id = id
        self.version = version
        self.fileName = fileName
        self.expectedByteCount = expectedByteCount
        self.expectedSHA256 = expectedSHA256
    }

    public static let gemma4E2B = ModelDescriptor(
        id: "gemma-4-e2b-it",
        version: "litertlm-v1",
        fileName: "gemma-4-E2B-it.litertlm"
    )

    public var displayName: String {
        switch id {
        case "gemma-4-e2b-it": "Gemma 4 E2B"
        default: id
        }
    }
}

public struct InstalledModel: Equatable, Sendable {
    public let descriptor: ModelDescriptor
    public let fileURL: URL
    public let byteCount: Int64
    public let sha256: String

    public init(
        descriptor: ModelDescriptor,
        fileURL: URL,
        byteCount: Int64,
        sha256: String
    ) {
        self.descriptor = descriptor
        self.fileURL = fileURL
        self.byteCount = byteCount
        self.sha256 = sha256
    }
}

public enum ModelRuntimeState: Equatable, Sendable {
    case notInstalled
    case installed(InstalledModel)
    case loading
    case ready
    case failed(String)
}

public enum ModelRuntimeError: LocalizedError, Equatable {
    case modelNotInstalled
    case invalidModelFile
    case modelIntegrityCheckFailed
    case engineNotReady
    case engineBusy
    case emptyResponse

    public var errorDescription: String? {
        switch self {
        case .modelNotInstalled: "尚未导入模型。"
        case .invalidModelFile: "模型文件无效，必须是 .litertlm 文件。"
        case .modelIntegrityCheckFailed: "模型完整性校验失败。"
        case .engineNotReady: "模型尚未加载完成。"
        case .engineBusy: "模型正在执行其他任务。"
        case .emptyResponse: "模型没有返回内容。"
        }
    }
}
