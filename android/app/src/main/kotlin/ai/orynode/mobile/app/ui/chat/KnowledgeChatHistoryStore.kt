package ai.orynode.mobile.app.ui.chat

import ai.orynode.mobile.domain.KnowledgeCitation
import ai.orynode.mobile.domain.KnowledgeSearchScope
import ai.orynode.mobile.domain.SourceLocatorCodec
import org.json.JSONArray
import org.json.JSONObject
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

data class KnowledgeChatTurn(
    val id: UUID = UUID.randomUUID(),
    val role: Role,
    val text: String,
    val citations: List<KnowledgeCitation> = emptyList(),
) {
    enum class Role { User, Assistant }
}

data class KnowledgeChatSession(
    val id: UUID = UUID.randomUUID(),
    var title: String = "新对话",
    val createdAt: Instant = Instant.now(),
    var updatedAt: Instant = Instant.now(),
    var messages: List<KnowledgeChatTurn> = emptyList(),
    var searchScope: KnowledgeSearchScope = KnowledgeSearchScope.All,
) {
    val preview: String
        get() = messages.firstOrNull { it.role == KnowledgeChatTurn.Role.User }?.text
            ?: messages.firstOrNull()?.text
            ?: "空对话"

    fun withSyncedTitle(): KnowledgeChatSession {
        val firstUser = messages.firstOrNull { it.role == KnowledgeChatTurn.Role.User }?.text?.trim()
            ?: return this
        val nextTitle = if (firstUser.length <= 28) firstUser else firstUser.take(28) + "…"
        return copy(title = nextTitle)
    }
}

/** JSON under app filesDir — chat history never leaves the device. */
class KnowledgeChatHistoryStore(private val file: Path) {
    fun load(): List<KnowledgeChatSession> {
        if (!Files.isRegularFile(file)) return emptyList()
        val root = JSONArray(String(Files.readAllBytes(file), Charsets.UTF_8))
        val sessions = mutableListOf<KnowledgeChatSession>()
        for (i in 0 until root.length()) {
            sessions += decodeSession(root.getJSONObject(i))
        }
        return sessions.sortedByDescending { it.updatedAt }
    }

    fun save(sessions: List<KnowledgeChatSession>) {
        Files.createDirectories(file.parent)
        val array = JSONArray()
        sessions.sortedByDescending { it.updatedAt }.forEach { array.put(encodeSession(it)) }
        Files.write(file, array.toString(2).toByteArray(Charsets.UTF_8))
    }

    private fun encodeSession(session: KnowledgeChatSession): JSONObject =
        JSONObject()
            .put("id", session.id.toString())
            .put("title", session.title)
            .put("createdAt", session.createdAt.toString())
            .put("updatedAt", session.updatedAt.toString())
            .put("searchScope", encodeScope(session.searchScope))
            .put(
                "messages",
                JSONArray().also { arr ->
                    session.messages.forEach { turn ->
                        arr.put(
                            JSONObject()
                                .put("id", turn.id.toString())
                                .put("role", turn.role.name)
                                .put("text", turn.text)
                                .put(
                                    "citations",
                                    JSONArray().also { cites ->
                                        turn.citations.forEach { citation ->
                                            val cite = JSONObject()
                                                .put("index", citation.index)
                                                .put("documentId", citation.documentId.toString())
                                                .put("documentTitle", citation.documentTitle)
                                                .put("chunkId", citation.chunkId.toString())
                                                .put("excerpt", citation.excerpt)
                                            val label = citation.locatorLabel
                                                ?: citation.locator?.shortLabel
                                            if (!label.isNullOrBlank()) {
                                                cite.put("locatorLabel", label)
                                            }
                                            SourceLocatorCodec.encode(citation.locator)?.let {
                                                cite.put("locator", JSONObject(it))
                                            }
                                            cites.put(cite)
                                        }
                                    },
                                ),
                        )
                    }
                },
            )

    private fun decodeSession(obj: JSONObject): KnowledgeChatSession {
        val messagesArr = obj.optJSONArray("messages") ?: JSONArray()
        val messages = mutableListOf<KnowledgeChatTurn>()
        for (i in 0 until messagesArr.length()) {
            val m = messagesArr.getJSONObject(i)
            val citesArr = m.optJSONArray("citations") ?: JSONArray()
            val citations = mutableListOf<KnowledgeCitation>()
            for (j in 0 until citesArr.length()) {
                val c = citesArr.getJSONObject(j)
                citations += KnowledgeCitation(
                    index = c.getInt("index"),
                    documentId = UUID.fromString(c.getString("documentId")),
                    documentTitle = c.getString("documentTitle"),
                    chunkId = UUID.fromString(c.getString("chunkId")),
                    excerpt = c.getString("excerpt"),
                    locator = decodeLocatorField(c),
                    locatorLabel = c.optString("locatorLabel").takeIf { it.isNotBlank() },
                )
            }
            messages += KnowledgeChatTurn(
                id = UUID.fromString(m.getString("id")),
                role = KnowledgeChatTurn.Role.valueOf(m.getString("role")),
                text = m.getString("text"),
                citations = citations,
            )
        }
        return KnowledgeChatSession(
            id = UUID.fromString(obj.getString("id")),
            title = obj.getString("title"),
            createdAt = Instant.parse(obj.getString("createdAt")),
            updatedAt = Instant.parse(obj.getString("updatedAt")),
            messages = messages,
            searchScope = decodeScope(obj.optJSONObject("searchScope")),
        )
    }

    private fun decodeLocatorField(obj: JSONObject): ai.orynode.mobile.domain.SourceLocator? {
        if (!obj.has("locator") || obj.isNull("locator")) return null
        val value = obj.get("locator")
        val raw = when (value) {
            is JSONObject -> value.toString()
            is String -> value
            else -> return null
        }
        return SourceLocatorCodec.decode(raw)
    }

    private fun encodeScope(scope: KnowledgeSearchScope): JSONObject =
        when (scope) {
            KnowledgeSearchScope.All -> JSONObject().put("type", "all")
            is KnowledgeSearchScope.Documents -> JSONObject()
                .put("type", "documents")
                .put(
                    "ids",
                    JSONArray().also { arr -> scope.ids.forEach { arr.put(it.toString()) } },
                )
        }

    private fun decodeScope(obj: JSONObject?): KnowledgeSearchScope {
        if (obj == null || obj.optString("type") != "documents") return KnowledgeSearchScope.All
        val ids = mutableSetOf<UUID>()
        val arr = obj.optJSONArray("ids") ?: return KnowledgeSearchScope.All
        for (i in 0 until arr.length()) ids += UUID.fromString(arr.getString(i))
        return if (ids.isEmpty()) KnowledgeSearchScope.All else KnowledgeSearchScope.Documents(ids)
    }
}
