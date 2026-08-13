import Foundation
import OrynodeDomain

struct KnowledgeChatSession: Identifiable, Equatable, Codable, Sendable {
    let id: UUID
    var title: String
    var createdAt: Date
    var updatedAt: Date
    var messages: [KnowledgeChatTurn]
    var searchScope: KnowledgeSearchScope

    init(
        id: UUID = UUID(),
        title: String = "新对话",
        createdAt: Date = Date(),
        updatedAt: Date = Date(),
        messages: [KnowledgeChatTurn] = [],
        searchScope: KnowledgeSearchScope = .all
    ) {
        self.id = id
        self.title = title
        self.createdAt = createdAt
        self.updatedAt = updatedAt
        self.messages = messages
        self.searchScope = searchScope
    }

    private enum CodingKeys: String, CodingKey {
        case id, title, createdAt, updatedAt, messages, searchScope
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        id = try container.decode(UUID.self, forKey: .id)
        title = try container.decode(String.self, forKey: .title)
        createdAt = try container.decode(Date.self, forKey: .createdAt)
        updatedAt = try container.decode(Date.self, forKey: .updatedAt)
        messages = try container.decode([KnowledgeChatTurn].self, forKey: .messages)
        searchScope = try container.decodeIfPresent(
            KnowledgeSearchScope.self,
            forKey: .searchScope
        ) ?? .all
    }

    var preview: String {
        messages.first(where: { $0.role == .user })?.text
            ?? messages.first?.text
            ?? "空对话"
    }

    mutating func syncTitleFromMessages() {
        guard let firstUser = messages.first(where: { $0.role == .user })?.text else { return }
        let trimmed = firstUser.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        if trimmed.count <= 28 {
            title = trimmed
        } else {
            title = String(trimmed.prefix(28)) + "…"
        }
    }
}

/// JSON file under Application Support — chat history never leaves the device.
struct KnowledgeChatHistoryStore: Sendable {
    private let fileURL: URL
    private let encoder: JSONEncoder
    private let decoder: JSONDecoder

    init(fileManager: FileManager = .default) throws {
        let root = try fileManager.url(
            for: .applicationSupportDirectory,
            in: .userDomainMask,
            appropriateFor: nil,
            create: true
        )
        let directory = root.appendingPathComponent("KnowledgeChat", isDirectory: true)
        try fileManager.createDirectory(at: directory, withIntermediateDirectories: true)
        try Self.protect(directory, fileManager: fileManager)
        fileURL = directory.appendingPathComponent("sessions.json")
        encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
    }

    func load() throws -> [KnowledgeChatSession] {
        guard FileManager.default.fileExists(atPath: fileURL.path) else { return [] }
        let data = try Data(contentsOf: fileURL)
        return try decoder.decode([KnowledgeChatSession].self, from: data)
            .sorted { $0.updatedAt > $1.updatedAt }
    }

    func save(_ sessions: [KnowledgeChatSession]) throws {
        let data = try encoder.encode(sessions.sorted { $0.updatedAt > $1.updatedAt })
        try data.write(to: fileURL, options: [.atomic])
        try Self.protect(fileURL, fileManager: .default)
    }

    private static func protect(_ url: URL, fileManager: FileManager) throws {
        try fileManager.setAttributes(
            [.protectionKey: FileProtectionType.complete],
            ofItemAtPath: url.path
        )
    }
}
