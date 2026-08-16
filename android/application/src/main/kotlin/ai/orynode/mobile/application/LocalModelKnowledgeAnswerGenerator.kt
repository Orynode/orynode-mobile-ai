package ai.orynode.mobile.application

import ai.orynode.mobile.domain.AnalysisRequest
import ai.orynode.mobile.domain.KnowledgeAnswerGenerator
import ai.orynode.mobile.domain.LocalModelEngine
import ai.orynode.mobile.domain.OnDeviceRagBudget
import kotlinx.coroutines.flow.Flow

class LocalModelKnowledgeAnswerGenerator(
    private val engine: LocalModelEngine,
    private val budget: OnDeviceRagBudget = OnDeviceRagBudget.GemmaE2B,
) : KnowledgeAnswerGenerator {
    override suspend fun answer(question: String, context: String): String =
        engine.generate(request(question, context))

    override fun answerStream(question: String, context: String): Flow<String> =
        engine.generateStream(request(question, context))

    override fun finalize(rawAnswer: String): String = sanitizeAnswer(rawAnswer)

    private fun request(question: String, context: String): AnalysisRequest =
        AnalysisRequest(
            prompt = """
                你是本机私人知识库助手。只能依据下方“资料证据”回答。

                硬性规则：
                1. 证据不足时只回答：现有资料不足以回答。
                2. 禁止补充资料外常识，禁止编造来源。
                3. 用简洁中文回答，总长约 ${budget.preferredAnswerCharacters} 字以内。
                4. 可用轻量 Markdown：短段落、**加粗**关键词、必要时空一行分段；可用 - 列表。
                5. 禁止「结论：」「依据：」这类小标题模板，禁止 # 大标题，不要堆砌加粗。
                6. 地址、电话、名称、编号等事实字段：只照抄证据里与问题对应的那一条原文，禁止改写，禁止混入邻近相似条目。其余内容简洁概括，不要同义反复。
                7. 引用（稀疏）：需要标明依据时，只用下方已有编号，写成 [1] 或 [2]；整篇回答合计最多 2 个引用标记；同一编号在全文只出现一次；优先标在最后一句或关键论断末尾；列表每一项不要单独加引用；不要编造未提供的编号；没有把握可不标。

                用户问题：
                $question

                资料证据：
                $context
            """.trimIndent(),
        )

    companion object {
        private val templatePrefixes = listOf(
            "**结论：**", "**结论:**", "结论：", "结论:",
            "**依据：**", "**依据:**", "依据：", "依据:",
        )

        fun sanitizeAnswer(text: String): String {
            val cleaned = KnowledgeAnswerSanitizer.stripControlTokens(text)
            val lines = cleaned.replace("\r\n", "\n").split("\n").map { line ->
                val trimmed = line.trimStart()
                val prefix = templatePrefixes.firstOrNull { trimmed.startsWith(it) }
                if (prefix != null) trimmed.removePrefix(prefix).trim() else line
            }
            return lines
                .dropWhile { it.isBlank() }
                .dropLastWhile { it.isBlank() }
                .joinToString("\n")
                .trim()
        }
    }
}
