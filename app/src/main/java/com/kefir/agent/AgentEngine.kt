package com.kefir.agent

import org.json.JSONObject

class AgentEngine(
    var settings: AgentSettings,
    private val llm: LlmClient
) {

    val history = mutableListOf<ChatMessage>()

    suspend fun run(
        userText: String,
        onDelta: (String) -> Unit,
        onToolResult: (String) -> Unit
    ): String {
        history += ChatMessage("user", userText)
        var lastReply = ""

        repeat(MAX_STEPS) { step ->
            val reply = llm.stream(
                settings,
                listOf(ChatMessage("system", systemPrompt())) + history,
                onDelta
            )
            lastReply = reply
            history += ChatMessage("assistant", reply)

            val calls = parseToolCalls(reply)
            if (calls.isEmpty()) return visibleText(reply)

            val results = StringBuilder()
            for (call in calls) {
                val result = try {
                    Tools.execute(call.optString("tool"), call)
                } catch (e: Exception) {
                    "ERROR: ${e.message}"
                }
                results.append("### ").append(call.optString("tool")).append("\n")
                    .append(result).append("\n\n")
                onToolResult("🔧 ${call.optString("tool")} → ${result.lineSequence().firstOrNull() ?: ""}")
            }
            history += ChatMessage("user", "TOOL RESULTS:\n$results\nПродолжай задачу.")
        }
        return visibleText(lastReply)
    }

    private fun systemPrompt(): String = """
        Ты — Agent, автономный агент-разработчик, работающий на Android-устройстве пользователя (как opencode / claude code, но в телефоне). У тебя есть личная папка-воркспейс, где ты создаёшь и правишь файлы проектов.

        Доступные инструменты. Чтобы вызвать инструмент, верни fenced-блок вида:
        ```tool
        {"tool": "write_file", "path": "src/main.py", "content": "полный код файла"}
        ```

        Инструменты:
        ${Tools.descriptions}

        Правила:
        - Работай пошагово: сначала короткий план, затем вызовы инструментов (можно несколько блоков ```tool``` в одном ответе).
        - После каждого ответа с инструментами ты получишь TOOL RESULTS и сможешь продолжить.
        - Когда задача полностью выполнена, дай финальный ответ БЕЗ tool-блоков: что сделано, список файлов, как запустить.
        - Пиши полные файлы без заглушек и "..." внутри кода.
        - Пути относительные, выходить за пределы воркспейса нельзя.
        - run_shell выполняется в воркспейсе через busybox+sh Android: доступны wget, sed, awk, tar, gzip, vi, find, grep, sort, uniq, diff, patch, md5sum, base64 и весь Unix-набор. Компиляторов и git нет, но можешь скачивать исходники и данные wget-ом.
        - Отвечай на языке пользователя.
    """.trimIndent()

    companion object {
        const val MAX_STEPS = 20
        private val TOOL_BLOCK = Regex("```tool\\s*\\n(.*?)```", RegexOption.DOT_MATCHES_ALL)

        fun parseToolCalls(reply: String): List<JSONObject> {
            val calls = mutableListOf<JSONObject>()
            TOOL_BLOCK.findAll(reply).forEach { m ->
                try {
                    val obj = JSONObject(m.groupValues[1].trim())
                    if (obj.has("tool")) calls.add(obj)
                } catch (_: Exception) {
                }
            }
            return calls
        }

        fun visibleText(reply: String): String =
            TOOL_BLOCK.replace(reply, "").trim()
    }
}
