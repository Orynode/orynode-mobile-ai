package ai.orynode.mobile.application

object KnowledgeAnswerSanitizer {
    private val controlToken =
        Regex("</?(?:bos|eos|pad|unk|s|start_of_turn|end_of_turn)\\s*>", RegexOption.IGNORE_CASE)

    fun stripControlTokens(text: String): String = controlToken.replace(text, "")
}
