import Foundation
import OrynodeDomain

struct KnowledgeChatTurn: Identifiable, Equatable, Codable, Sendable {
    enum Role: String, Codable, Sendable {
        case user
        case assistant
    }

    let id: UUID
    let role: Role
    var text: String
    var citations: [CitedSource]

    init(id: UUID = UUID(), role: Role, text: String, citations: [CitedSource] = []) {
        self.id = id
        self.role = role
        self.text = text
        self.citations = citations
    }
}

@MainActor
final class KnowledgeBaseModel: ObservableObject {
    @Published private(set) var documents: [KnowledgeDocumentItem] = []
    @Published private(set) var sessions: [KnowledgeChatSession] = []
    @Published private(set) var activeSessionID: UUID?
    @Published private(set) var messages: [KnowledgeChatTurn] = []
    @Published private(set) var searchScope: KnowledgeSearchScope = .all
    @Published private(set) var isLoading = false
    @Published private(set) var isImporting = false
    @Published private(set) var isAnswering = false
    @Published private(set) var answeringPhase: String?
    @Published var errorMessage: String?

    private let service: any KnowledgeBaseServing
    private let historyStore: KnowledgeChatHistoryStore?

    init(service: any KnowledgeBaseServing, historyStore: KnowledgeChatHistoryStore? = nil) {
        self.service = service
        self.historyStore = historyStore ?? (try? KnowledgeChatHistoryStore())
    }

    var readyDocumentCount: Int {
        documents.filter { $0.status == .ready }.count
    }

    var indexedChunkCount: Int {
        documents.reduce(0) { $0 + max(0, $1.importedChunkCount) }
    }

    var persistedSessionCount: Int {
        sessions.filter { !$0.messages.isEmpty }.count
    }

    var activeSessionTitle: String {
        if let id = activeSessionID,
           let session = sessions.first(where: { $0.id == id }),
           !session.messages.isEmpty {
            return session.title
        }
        return "知识库问答"
    }

    func load() async {
        isLoading = true
        defer { isLoading = false }
        do {
            documents = try await service.loadDocuments()
            normalizeSearchScope()
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func loadChatHistory() {
        guard let historyStore else { return }
        do {
            sessions = try historyStore.load()
            if let activeSessionID,
               let session = sessions.first(where: { $0.id == activeSessionID }) {
                messages = session.messages
            }
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func startNewChat() {
        guard !isAnswering else { return }
        persistActiveSessionIfNeeded()
        let session = KnowledgeChatSession()
        activeSessionID = session.id
        messages = []
        searchScope = .all
        // Keep empty drafts out of the list until the first message lands.
        sessions.removeAll { $0.messages.isEmpty }
    }

    func openSession(_ id: UUID) {
        guard !isAnswering else { return }
        persistActiveSessionIfNeeded()
        guard let session = sessions.first(where: { $0.id == id }) else { return }
        activeSessionID = session.id
        messages = session.messages
        searchScope = session.searchScope
        normalizeSearchScope()
    }

    func deleteSession(_ id: UUID) {
        sessions.removeAll { $0.id == id }
        if activeSessionID == id {
            activeSessionID = nil
            messages = []
            if let latest = sessions.first {
                activeSessionID = latest.id
                messages = latest.messages
                searchScope = latest.searchScope
                normalizeSearchScope()
            }
        }
        saveSessions()
    }

    func clearAllChatHistory() {
        guard !isAnswering else { return }
        sessions = []
        activeSessionID = nil
        messages = []
        searchScope = .all
        saveSessions()
    }

    func importDocument(from url: URL) async {
        guard !isImporting else { return }
        isImporting = true
        defer { isImporting = false }
        do {
            for try await item in service.importDocument(from: url) {
                upsert(item)
            }
        } catch {
            errorMessage = error.localizedDescription
            if let latest = try? await service.loadDocuments() {
                documents = latest
                normalizeSearchScope()
            }
        }
    }

    func retry(_ document: KnowledgeDocumentItem) async {
        var indexing = document
        indexing.status = .indexing
        replace(indexing)
        do {
            let updated = try await service.retryIndexing(documentID: document.id)
            replace(updated)
        } catch {
            errorMessage = error.localizedDescription
            if let latest = try? await service.loadDocuments() {
                documents = latest
                normalizeSearchScope()
            } else {
                var failed = indexing
                failed.status = .failed(error.localizedDescription)
                replace(failed)
            }
        }
    }

    func delete(_ document: KnowledgeDocumentItem) async {
        do {
            try await service.deleteDocument(documentID: document.id)
            documents.removeAll { $0.id == document.id }
            normalizeSearchScope()
            persistScope()
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func ask(_ question: String) async {
        let trimmed = question.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, !isAnswering else { return }
        ensureActiveSession()
        messages.append(KnowledgeChatTurn(role: .user, text: trimmed))
        touchActiveSession()
        let assistantID = UUID()
        messages.append(KnowledgeChatTurn(id: assistantID, role: .assistant, text: ""))
        isAnswering = true
        answeringPhase = "正在检索本机资料…"
        defer {
            isAnswering = false
            answeringPhase = nil
        }
        do {
            for try await update in service.askStream(trimmed, scope: searchScope) {
                guard let index = messages.firstIndex(where: { $0.id == assistantID }) else {
                    continue
                }
                switch update {
                case let .phase(message):
                    answeringPhase = message
                case let .delta(delta):
                    answeringPhase = nil
                    messages[index].text += delta
                case let .finished(answer):
                    answeringPhase = nil
                    messages[index].text = answer.text
                    messages[index].citations = answer.citations
                    touchActiveSession()
                }
            }
            touchActiveSession()
        } catch {
            // Never persist a partial, unvalidated model response.
            messages.removeAll { $0.id == assistantID }
            errorMessage = error.localizedDescription
            touchActiveSession()
        }
    }

    func clearError() {
        errorMessage = nil
    }

    func setSearchScope(_ scope: KnowledgeSearchScope) {
        searchScope = scope
        normalizeSearchScope()
        ensureActiveSession()
        persistScope()
    }

    func previewIntent(for document: KnowledgeDocumentItem) async -> DocumentPreviewIntent? {
        do {
            return try await service.previewIntent(forDocumentID: document.id)
        } catch {
            errorMessage = error.localizedDescription
            return nil
        }
    }

    func previewIntent(for citation: CitedSource) async -> DocumentPreviewIntent? {
        do {
            return try await service.previewIntent(for: citation)
        } catch {
            errorMessage = error.localizedDescription
            return nil
        }
    }

    private func ensureActiveSession() {
        if activeSessionID == nil {
            let session = KnowledgeChatSession(searchScope: searchScope)
            activeSessionID = session.id
            sessions.insert(session, at: 0)
        } else if !sessions.contains(where: { $0.id == activeSessionID }) {
            let session = KnowledgeChatSession(
                id: activeSessionID ?? UUID(),
                messages: messages,
                searchScope: searchScope
            )
            sessions.insert(session, at: 0)
        }
    }

    private func touchActiveSession() {
        guard let id = activeSessionID,
              let index = sessions.firstIndex(where: { $0.id == id }) else { return }
        sessions[index].messages = messages
        sessions[index].searchScope = searchScope
        sessions[index].updatedAt = Date()
        sessions[index].syncTitleFromMessages()
        let updated = sessions.remove(at: index)
        sessions.insert(updated, at: 0)
        saveSessions()
    }

    private func persistScope() {
        guard let id = activeSessionID,
              let index = sessions.firstIndex(where: { $0.id == id }) else { return }
        sessions[index].searchScope = searchScope
        sessions[index].updatedAt = Date()
        saveSessions()
    }

    private func normalizeSearchScope() {
        guard case let .documents(selected) = searchScope else { return }
        let ready = Set(documents.filter { $0.status == .ready }.map(\.id))
        let available = selected.intersection(ready)
        searchScope = available.isEmpty ? .all : .documents(available)
    }

    private func persistActiveSessionIfNeeded() {
        guard let id = activeSessionID else { return }
        if messages.isEmpty {
            sessions.removeAll { $0.id == id && $0.messages.isEmpty }
            saveSessions()
            return
        }
        touchActiveSession()
    }

    private func saveSessions() {
        let persisted = sessions.filter { !$0.messages.isEmpty }
        sessions = persisted.sorted { $0.updatedAt > $1.updatedAt }
        do {
            try historyStore?.save(sessions)
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    private func upsert(_ document: KnowledgeDocumentItem) {
        documents.removeAll { $0.id == document.id }
        documents.insert(document, at: 0)
    }

    private func replace(_ document: KnowledgeDocumentItem) {
        guard let index = documents.firstIndex(where: { $0.id == document.id }) else {
            documents.insert(document, at: 0)
            return
        }
        documents[index] = document
    }
}
