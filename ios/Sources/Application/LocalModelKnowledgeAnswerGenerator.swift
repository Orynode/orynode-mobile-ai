import Foundation
import OrynodeDomain

public struct LocalModelKnowledgeAnswerGenerator: KnowledgeAnswerGenerator {
    let engine: any LocalModelEngine
    let budget: OnDeviceRAGBudget

    public init(engine: any LocalModelEngine, budget: OnDeviceRAGBudget = .gemmaE2B) {
        self.engine = engine
        self.budget = budget
    }

    public func answer(question: String, context: String) async throws -> String {
        try await engine.generate(request(question: question, context: context))
    }

    public func answerStream(
        question: String,
        context: String
    ) async throws -> AsyncThrowingStream<String, any Error> {
        try await engine.generateStream(request(question: question, context: context))
    }

    public func finalize(_ rawAnswer: String) -> String {
        Self.sanitizeAnswer(rawAnswer)
    }

    private func request(question: String, context: String) -> AnalysisRequest {
        // Use temperature-0 generate path (not chat sampling) for grounded short answers.
        AnalysisRequest(
                prompt: """
                你是本机私人知识库助手。只能依据下方“资料证据”回答。

                硬性规则：
                1. 证据不足时只回答：现有资料不足以回答。
                2. 禁止补充资料外常识，禁止编造来源。
                3. 用简洁中文回答，总长约 \(budget.preferredAnswerCharacters) 字以内。
                4. 可用轻量 Markdown：短段落、**加粗**关键词、必要时空一行分段；可用 - 列表。
                5. 禁止「结论：」「依据：」这类小标题模板，禁止 # 大标题，不要堆砌加粗。
                6. 地址、电话、名称、编号等事实字段：只照抄证据里与问题对应的那一条原文，禁止改写，禁止混入邻近相似条目。其余内容简洁概括，不要同义反复。
                7. 引用（稀疏）：需要标明依据时，只用下方已有编号，写成 [1] 或 [2]；整篇回答合计最多 2 个引用标记；同一编号在全文只出现一次；优先标在最后一句或关键论断末尾；列表每一项不要单独加引用；不要编造未提供的编号；没有把握可不标。

                用户问题：
                \(question)

                资料证据：
                \(context)
                """
            )
    }

    /// Hygiene only: strip leaked control tokens and broken template labels.
    /// Does not rewrite facts or move citation markers — body stays model output.
    private static func sanitizeAnswer(_ text: String) -> String {
        let cleaned = KnowledgeAnswerSanitizer.stripControlTokens(text)
        let lines = cleaned
            .replacingOccurrences(of: "\r\n", with: "\n")
            .components(separatedBy: "\n")
            .map { line -> String in
                var value = line
                let trimmed = value.trimmingCharacters(in: .whitespaces)
                for prefix in [
                    "**结论：**", "**结论:**", "结论：", "结论:",
                    "**依据：**", "**依据:**", "依据：", "依据:",
                ] {
                    if trimmed.hasPrefix(prefix) {
                        value = String(trimmed.dropFirst(prefix.count))
                            .trimmingCharacters(in: .whitespaces)
                        break
                    }
                }
                return value
            }

        var start = lines.startIndex
        var end = lines.endIndex
        while start < end, lines[start].trimmingCharacters(in: .whitespaces).isEmpty {
            start = lines.index(after: start)
        }
        while start < end, lines[lines.index(before: end)].trimmingCharacters(in: .whitespaces).isEmpty {
            end = lines.index(before: end)
        }
        return lines[start..<end]
            .joined(separator: "\n")
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }
}
