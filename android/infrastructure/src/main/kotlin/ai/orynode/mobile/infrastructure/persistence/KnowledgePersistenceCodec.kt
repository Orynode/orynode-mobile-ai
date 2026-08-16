package ai.orynode.mobile.infrastructure.persistence

import ai.orynode.mobile.domain.KnowledgePageSpan
import ai.orynode.mobile.domain.SourceLocator
import ai.orynode.mobile.domain.SourceLocatorCodec

/**
 * Tiny JSON codec for locator / page-span snapshots.
 * Locator encoding delegates to domain [SourceLocatorCodec].
 */
internal object KnowledgePersistenceCodec {
    fun encodeLocator(locator: SourceLocator?): String? = SourceLocatorCodec.encode(locator)

    fun decodeLocator(raw: String?): SourceLocator? = SourceLocatorCodec.decode(raw)

    fun encodePageSpans(spans: List<KnowledgePageSpan>): String? {
        if (spans.isEmpty()) return null
        return spans.joinToString(prefix = "[", postfix = "]") { span ->
            "{\"page\":${span.page},\"start\":${span.start},\"end\":${span.end}}"
        }
    }

    fun decodePageSpans(raw: String?): List<KnowledgePageSpan> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val body = raw.trim().removePrefix("[").removeSuffix("]")
            if (body.isBlank()) return emptyList()
            Regex("""\{[^{}]+\}""").findAll(body).mapNotNull { match ->
                val item = match.value
                KnowledgePageSpan(
                    page = intField(item, "page") ?: return@mapNotNull null,
                    start = intField(item, "start") ?: return@mapNotNull null,
                    end = intField(item, "end") ?: return@mapNotNull null,
                )
            }.toList()
        }.getOrDefault(emptyList())
    }

    private fun intField(json: String, key: String): Int? {
        val match = Regex(""""$key"\s*:\s*(-?\d+)""").find(json) ?: return null
        return match.groupValues[1].toIntOrNull()
    }
}
