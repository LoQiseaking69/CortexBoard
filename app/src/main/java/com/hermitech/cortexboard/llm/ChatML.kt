package com.hermitech.cortexboard.llm

/** Qwen-family chat formatting (ChatML + Hermes-style tool calls), built directly so it is deterministic. */
object ChatML {
    data class Msg(val role: String, val content: String)

    fun build(messages: List<Msg>, thinking: Boolean): String = buildString {
        for (m in messages) {
            append("<|im_start|>").append(m.role).append('\n').append(m.content.trim()).append("<|im_end|>\n")
        }
        append("<|im_start|>assistant\n")
        // Qwen3 hybrid-reasoning switch: an empty think block disables reasoning.
        if (!thinking) append("<think>\n\n</think>\n\n")
    }

    private val THINK = Regex("<think>.*?(</think>|$)", RegexOption.DOT_MATCHES_ALL)

    fun stripThinking(s: String): String = s.replace(THINK, "").trim()

    fun toolResponse(name: String, body: String): Msg =
        Msg("user", "<tool_response>\n{\"name\": \"$name\", \"content\": ${org.json.JSONObject.quote(body)}}\n</tool_response>")
}
