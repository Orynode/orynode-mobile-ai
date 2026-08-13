import Foundation

/// On-device Application Support footprints used by Settings.
enum LocalDataFootprint {
    static func knowledgeBaseByteCount(fileManager: FileManager = .default) -> Int64 {
        directoryByteCount(named: "KnowledgeBase", fileManager: fileManager)
    }

    static func chatHistoryByteCount(fileManager: FileManager = .default) -> Int64 {
        directoryByteCount(named: "KnowledgeChat", fileManager: fileManager)
    }

    static func formattedByteCount(_ bytes: Int64) -> String {
        ByteCountFormatter.string(fromByteCount: max(0, bytes), countStyle: .file)
    }

    private static func directoryByteCount(
        named name: String,
        fileManager: FileManager
    ) -> Int64 {
        guard let root = try? fileManager.url(
            for: .applicationSupportDirectory,
            in: .userDomainMask,
            appropriateFor: nil,
            create: false
        ) else { return 0 }
        let url = root.appendingPathComponent(name, isDirectory: true)
        return recursiveByteCount(at: url, fileManager: fileManager)
    }

    private static func recursiveByteCount(at url: URL, fileManager: FileManager) -> Int64 {
        var isDirectory: ObjCBool = false
        guard fileManager.fileExists(atPath: url.path, isDirectory: &isDirectory) else { return 0 }
        if !isDirectory.boolValue {
            let values = try? url.resourceValues(forKeys: [.fileSizeKey])
            return Int64(values?.fileSize ?? 0)
        }
        guard let enumerator = fileManager.enumerator(
            at: url,
            includingPropertiesForKeys: [.isRegularFileKey, .fileSizeKey],
            options: [.skipsHiddenFiles]
        ) else { return 0 }
        var total: Int64 = 0
        for case let fileURL as URL in enumerator {
            guard let values = try? fileURL.resourceValues(forKeys: [.isRegularFileKey, .fileSizeKey]),
                  values.isRegularFile == true else { continue }
            total += Int64(values.fileSize ?? 0)
        }
        return total
    }
}
