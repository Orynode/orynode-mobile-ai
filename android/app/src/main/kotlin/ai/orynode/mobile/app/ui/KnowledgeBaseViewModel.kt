package ai.orynode.mobile.app.ui

import ai.orynode.mobile.app.serving.DocumentPreviewIntent
import ai.orynode.mobile.app.serving.KnowledgeBaseServing
import ai.orynode.mobile.app.ui.chat.KnowledgeChatHistoryStore
import ai.orynode.mobile.app.ui.chat.KnowledgeChatSession
import ai.orynode.mobile.app.ui.chat.KnowledgeChatTurn
import ai.orynode.mobile.domain.KnowledgeAnswerStreamEvent
import ai.orynode.mobile.domain.KnowledgeBaseError
import ai.orynode.mobile.domain.KnowledgeCitation
import ai.orynode.mobile.domain.KnowledgeDocument
import ai.orynode.mobile.domain.KnowledgeSearchScope
import ai.orynode.mobile.domain.ModelRuntimeError
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.UUID

data class KnowledgeUiState(
    val documents: List<KnowledgeDocument> = emptyList(),
    val sessions: List<KnowledgeChatSession> = emptyList(),
    val activeSessionId: UUID? = null,
    val messages: List<KnowledgeChatTurn> = emptyList(),
    val searchScope: KnowledgeSearchScope = KnowledgeSearchScope.All,
    val isLoading: Boolean = false,
    val isImporting: Boolean = false,
    val isAnswering: Boolean = false,
    val answeringPhase: String? = null,
    val errorMessage: String? = null,
    val indexedChunkCount: Int = 0,
    val showsHistory: Boolean = false,
    val previewIntent: DocumentPreviewIntent? = null,
)

class KnowledgeBaseViewModel(
    private val service: KnowledgeBaseServing,
    private val historyStore: KnowledgeChatHistoryStore,
) : ViewModel() {
    private val _state = MutableStateFlow(KnowledgeUiState())
    val state: StateFlow<KnowledgeUiState> = _state.asStateFlow()

    val readyDocumentCount: Int
        get() = _state.value.documents.count { it.state == KnowledgeDocument.State.Ready }

    val persistedSessionCount: Int
        get() = _state.value.sessions.count { it.messages.isNotEmpty() }

    val activeSessionTitle: String
        get() {
            val id = _state.value.activeSessionId ?: return "知识库问答"
            val session = _state.value.sessions.firstOrNull { it.id == id }
            return if (session != null && session.messages.isNotEmpty()) session.title else "知识库问答"
        }

    init {
        refresh()
        loadChatHistory()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }
            runCatching {
                val documents = service.loadDocuments()
                val chunks = service.indexedChunkCount()
                documents to chunks
            }.onSuccess { (documents, chunks) ->
                _state.update {
                    it.copy(
                        documents = documents,
                        indexedChunkCount = chunks,
                        isLoading = false,
                        errorMessage = null,
                        searchScope = normalizeScope(it.searchScope, documents),
                    )
                }
            }.onFailure { error ->
                _state.update { it.copy(isLoading = false, errorMessage = error.message) }
            }
        }
    }

    fun loadChatHistory() {
        runCatching { historyStore.load() }
            .onSuccess { sessions ->
                _state.update { current ->
                    val active = current.activeSessionId?.let { id -> sessions.firstOrNull { it.id == id } }
                    current.copy(
                        sessions = sessions,
                        messages = active?.messages ?: current.messages,
                        searchScope = active?.searchScope ?: current.searchScope,
                    )
                }
            }
            .onFailure { error ->
                _state.update { it.copy(errorMessage = error.message) }
            }
    }

    fun openHistory() {
        loadChatHistory()
        _state.update { it.copy(showsHistory = true) }
    }

    fun closeHistory() = _state.update { it.copy(showsHistory = false) }

    fun startNewChat() {
        if (_state.value.isAnswering) return
        persistActiveSessionIfNeeded()
        val session = KnowledgeChatSession()
        _state.update {
            it.copy(
                sessions = it.sessions.filter { s -> s.messages.isNotEmpty() },
                activeSessionId = session.id,
                messages = emptyList(),
                searchScope = KnowledgeSearchScope.All,
                showsHistory = false,
            )
        }
    }

    fun openSession(id: UUID) {
        if (_state.value.isAnswering) return
        persistActiveSessionIfNeeded()
        val session = _state.value.sessions.firstOrNull { it.id == id } ?: return
        _state.update {
            it.copy(
                activeSessionId = session.id,
                messages = session.messages,
                searchScope = normalizeScope(session.searchScope, it.documents),
                showsHistory = false,
            )
        }
    }

    fun deleteSession(id: UUID) {
        val next = _state.value.sessions.filterNot { it.id == id }
        _state.update { current ->
            if (current.activeSessionId == id) {
                val latest = next.firstOrNull()
                current.copy(
                    sessions = next,
                    activeSessionId = latest?.id,
                    messages = latest?.messages.orEmpty(),
                    searchScope = latest?.searchScope ?: KnowledgeSearchScope.All,
                )
            } else {
                current.copy(sessions = next)
            }
        }
        saveSessions()
    }

    fun clearAllChatHistory() {
        if (_state.value.isAnswering) return
        _state.update {
            it.copy(
                sessions = emptyList(),
                activeSessionId = null,
                messages = emptyList(),
                searchScope = KnowledgeSearchScope.All,
            )
        }
        saveSessions()
    }

    fun setSearchScope(scope: KnowledgeSearchScope) {
        _state.update {
            it.copy(searchScope = normalizeScope(scope, it.documents))
        }
        persistScope()
    }

    fun importDocument(uri: Uri, displayName: String?) {
        if (_state.value.isAnswering || _state.value.isImporting) return
        viewModelScope.launch {
            _state.update { it.copy(isImporting = true, errorMessage = null) }
            // Prefer releasing the generator before picker-return work resumes.
            runCatching { service.unloadGenerator() }
            runCatching {
                service.importDocument(uri, displayName).collect { document ->
                    upsert(document)
                }
            }.onFailure { error ->
                _state.update {
                    it.copy(
                        errorMessage = error.message
                            ?: if (error is OutOfMemoryError) {
                                "内存不足，导入失败。请先在设置中释放模型后再试。"
                            } else {
                                "导入失败"
                            },
                    )
                }
                refresh()
            }
            _state.update { it.copy(isImporting = false) }
            refresh()
        }
    }

    fun retry(document: KnowledgeDocument) {
        if (_state.value.isAnswering || _state.value.isImporting) return
        viewModelScope.launch {
            _state.update { it.copy(isImporting = true, errorMessage = null) }
            upsert(document.copy(state = KnowledgeDocument.State.Importing, errorMessage = null))
            runCatching { service.retryIndexing(document.id) }
                .onSuccess { upsert(it) }
                .onFailure { error ->
                    _state.update { it.copy(errorMessage = error.message) }
                    refresh()
                }
            _state.update { it.copy(isImporting = false) }
            refresh()
        }
    }

    fun delete(document: KnowledgeDocument) {
        viewModelScope.launch {
            runCatching { service.deleteDocument(document.id) }
                .onSuccess {
                    _state.update { state ->
                        val documents = state.documents.filterNot { it.id == document.id }
                        state.copy(
                            documents = documents,
                            searchScope = normalizeScope(state.searchScope, documents),
                        )
                    }
                    persistScope()
                    refresh()
                }
                .onFailure { error ->
                    _state.update { it.copy(errorMessage = error.message) }
                }
        }
    }

    fun ask(question: String) {
        val trimmed = question.trim()
        if (trimmed.isEmpty() || _state.value.isAnswering || _state.value.isImporting) return
        ensureActiveSession()
        val assistantId = UUID.randomUUID()
        _state.update { state ->
            state.copy(
                messages = state.messages +
                    KnowledgeChatTurn(role = KnowledgeChatTurn.Role.User, text = trimmed) +
                    KnowledgeChatTurn(id = assistantId, role = KnowledgeChatTurn.Role.Assistant, text = ""),
                isAnswering = true,
                answeringPhase = KnowledgeAnswerStreamEvent.Phase.defaultMessage(
                    KnowledgeAnswerStreamEvent.PhaseKind.Retrieving,
                ),
                errorMessage = null,
            )
        }
        touchActiveSession()
        viewModelScope.launch {
            runCatching {
                service.askStream(trimmed, _state.value.searchScope).collect { event ->
                    when (event) {
                        is KnowledgeAnswerStreamEvent.Phase ->
                            _state.update { it.copy(answeringPhase = event.message) }
                        is KnowledgeAnswerStreamEvent.Delta ->
                            updateAssistant(assistantId) { turn ->
                                turn.copy(text = turn.text + event.text)
                            }
                        is KnowledgeAnswerStreamEvent.Finished ->
                            updateAssistant(assistantId) {
                                KnowledgeChatTurn(
                                    id = assistantId,
                                    role = KnowledgeChatTurn.Role.Assistant,
                                    text = event.answer.text,
                                    citations = event.answer.citations,
                                )
                            }
                    }
                }
            }.onFailure { error ->
                val message = when (error) {
                    is ModelRuntimeError.ModelNotInstalled,
                    is ModelRuntimeError.EngineNotReady,
                    -> "生成模型未就绪。请先导入并加载 Gemma `.litertlm`。"
                    is KnowledgeBaseError.NoIndexedDocuments -> error.message
                    else -> error.message
                }
                // Match iOS: drop the unfinished assistant bubble; surface error in banner only.
                _state.update { state ->
                    state.copy(
                        messages = state.messages.filterNot { it.id == assistantId },
                        errorMessage = message,
                    )
                }
            }
            _state.update { it.copy(isAnswering = false, answeringPhase = null) }
            persistActiveSessionIfNeeded()
        }
    }

    fun clearError() = _state.update { it.copy(errorMessage = null) }

    /** Release Gemma before the system document picker so LMK does not kill us mid-pick. */
    fun prepareForImport() {
        if (_state.value.isAnswering || _state.value.isImporting) return
        viewModelScope.launch {
            runCatching { service.unloadGenerator() }
        }
    }

    fun openDocumentPreview(document: KnowledgeDocument) {
        viewModelScope.launch {
            runCatching { service.previewDocument(document.id) }
                .onSuccess { intent -> _state.update { it.copy(previewIntent = intent) } }
                .onFailure { error -> _state.update { it.copy(errorMessage = error.message) } }
        }
    }

    fun openCitationPreview(citation: KnowledgeCitation) {
        viewModelScope.launch {
            runCatching { service.previewCitation(citation) }
                .onSuccess { intent -> _state.update { it.copy(previewIntent = intent) } }
                .onFailure { error -> _state.update { it.copy(errorMessage = error.message) } }
        }
    }

    fun clearPreview() = _state.update { it.copy(previewIntent = null) }

    private fun upsert(document: KnowledgeDocument) {
        _state.update { state ->
            state.copy(
                documents = state.documents.filterNot { it.id == document.id } + document,
            )
        }
    }

    private fun updateAssistant(id: UUID, transform: (KnowledgeChatTurn) -> KnowledgeChatTurn) {
        _state.update { state ->
            state.copy(
                messages = state.messages.map { if (it.id == id) transform(it) else it },
            )
        }
    }

    private fun ensureActiveSession() {
        if (_state.value.activeSessionId != null) return
        val session = KnowledgeChatSession()
        _state.update { it.copy(activeSessionId = session.id) }
    }

    private fun touchActiveSession() {
        val id = _state.value.activeSessionId ?: return
        val messages = _state.value.messages
        val scope = _state.value.searchScope
        val existing = _state.value.sessions.firstOrNull { it.id == id }
        val session = (existing ?: KnowledgeChatSession(id = id)).copy(
            messages = messages,
            searchScope = scope,
            updatedAt = Instant.now(),
        ).withSyncedTitle()
        _state.update { state ->
            state.copy(
                sessions = listOf(session) + state.sessions.filterNot { it.id == id },
            )
        }
        saveSessions()
    }

    private fun persistActiveSessionIfNeeded() {
        val id = _state.value.activeSessionId ?: return
        val messages = _state.value.messages
        if (messages.isEmpty()) {
            _state.update { it.copy(sessions = it.sessions.filterNot { s -> s.id == id && s.messages.isEmpty() }) }
            saveSessions()
            return
        }
        touchActiveSession()
    }

    private fun persistScope() {
        val id = _state.value.activeSessionId ?: return
        _state.update { state ->
            state.copy(
                sessions = state.sessions.map { session ->
                    if (session.id == id) session.copy(searchScope = state.searchScope) else session
                },
            )
        }
        saveSessions()
    }

    private fun saveSessions() {
        runCatching { historyStore.save(_state.value.sessions.filter { it.messages.isNotEmpty() }) }
    }

    private fun normalizeScope(
        scope: KnowledgeSearchScope,
        documents: List<KnowledgeDocument>,
    ): KnowledgeSearchScope {
        val readyIds = documents.filter { it.state == KnowledgeDocument.State.Ready }.map { it.id }.toSet()
        return when (scope) {
            KnowledgeSearchScope.All -> KnowledgeSearchScope.All
            is KnowledgeSearchScope.Documents -> {
                val kept = scope.ids.intersect(readyIds)
                if (kept.isEmpty()) KnowledgeSearchScope.All else KnowledgeSearchScope.Documents(kept)
            }
        }
    }

    class Factory(
        private val service: KnowledgeBaseServing,
        private val historyStore: KnowledgeChatHistoryStore,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            KnowledgeBaseViewModel(service, historyStore) as T
    }
}
