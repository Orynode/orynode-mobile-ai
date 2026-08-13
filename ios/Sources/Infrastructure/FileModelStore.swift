import Foundation
import CryptoKit
import OrynodeDomain

public actor FileModelStore: ModelStore {
    private let fileManager: FileManager

    public init(fileManager: FileManager = .default) {
        self.fileManager = fileManager
    }

    public func installedModel(for descriptor: ModelDescriptor) -> InstalledModel? {
        guard let destination = try? modelURL(for: descriptor),
              let metadataURL = try? metadataURL(for: descriptor),
              fileManager.fileExists(atPath: destination.path),
              let data = try? Data(contentsOf: metadataURL),
              let metadata = try? JSONDecoder().decode(StoredMetadata.self, from: data),
              metadata.id == descriptor.id,
              metadata.version == descriptor.version else {
            return nil
        }
        return InstalledModel(
            descriptor: descriptor,
            fileURL: destination,
            byteCount: metadata.byteCount,
            sha256: metadata.sha256
        )
    }

    public func importModel(
        from sourceURL: URL,
        descriptor: ModelDescriptor
    ) throws -> InstalledModel {
        guard sourceURL.pathExtension.lowercased() == "litertlm" else {
            throw ModelRuntimeError.invalidModelFile
        }

        let hasAccess = sourceURL.startAccessingSecurityScopedResource()
        defer {
            if hasAccess {
                sourceURL.stopAccessingSecurityScopedResource()
            }
        }

        let destination = try modelURL(for: descriptor)
        let temporary = destination
            .deletingLastPathComponent()
            .appending(path: "\(UUID().uuidString).importing")
        defer { try? fileManager.removeItem(at: temporary) }
        try fileManager.copyItem(at: sourceURL, to: temporary)

        let attributes = try fileManager.attributesOfItem(atPath: temporary.path)
        guard let size = attributes[.size] as? NSNumber, size.int64Value > 0 else {
            throw ModelRuntimeError.invalidModelFile
        }
        let byteCount = size.int64Value
        let sha256 = try Self.sha256(of: temporary)

        if let expectedByteCount = descriptor.expectedByteCount,
           expectedByteCount != byteCount {
            throw ModelRuntimeError.modelIntegrityCheckFailed
        }
        if let expectedSHA256 = descriptor.expectedSHA256,
           expectedSHA256.caseInsensitiveCompare(sha256) != .orderedSame {
            throw ModelRuntimeError.modelIntegrityCheckFailed
        }

        if fileManager.fileExists(atPath: destination.path) {
            _ = try fileManager.replaceItemAt(
                destination,
                withItemAt: temporary,
                backupItemName: nil,
                options: .usingNewMetadataOnly
            )
        } else {
            try fileManager.moveItem(at: temporary, to: destination)
        }

        let metadata = StoredMetadata(
            id: descriptor.id,
            version: descriptor.version,
            byteCount: byteCount,
            sha256: sha256
        )
        let metadataData = try JSONEncoder().encode(metadata)
        try metadataData.write(to: metadataURL(for: descriptor), options: .atomic)

        return InstalledModel(
            descriptor: descriptor,
            fileURL: destination,
            byteCount: byteCount,
            sha256: sha256
        )
    }

    public func deleteModel(_ descriptor: ModelDescriptor) throws {
        let destination = try modelURL(for: descriptor)
        if fileManager.fileExists(atPath: destination.path) {
            try fileManager.removeItem(at: destination)
        }
        let metadata = try metadataURL(for: descriptor)
        if fileManager.fileExists(atPath: metadata.path) {
            try fileManager.removeItem(at: metadata)
        }
    }

    private func modelURL(for descriptor: ModelDescriptor) throws -> URL {
        try modelsDirectory().appending(path: descriptor.fileName)
    }

    private func metadataURL(for descriptor: ModelDescriptor) throws -> URL {
        try modelsDirectory().appending(path: "\(descriptor.id)-\(descriptor.version).json")
    }

    private func modelsDirectory() throws -> URL {
        let appSupport = try fileManager.url(
            for: .applicationSupportDirectory,
            in: .userDomainMask,
            appropriateFor: nil,
            create: true
        )
        let directory = appSupport.appending(path: "Models", directoryHint: .isDirectory)
        try fileManager.createDirectory(at: directory, withIntermediateDirectories: true)
        return directory
    }

    private static func sha256(of url: URL) throws -> String {
        let handle = try FileHandle(forReadingFrom: url)
        defer { try? handle.close() }

        var hasher = SHA256()
        while true {
            let data = try handle.read(upToCount: 4 * 1_024 * 1_024) ?? Data()
            if data.isEmpty {
                break
            }
            hasher.update(data: data)
        }
        return hasher.finalize().map { String(format: "%02x", $0) }.joined()
    }
}

private struct StoredMetadata: Codable {
    let id: String
    let version: String
    let byteCount: Int64
    let sha256: String
}
