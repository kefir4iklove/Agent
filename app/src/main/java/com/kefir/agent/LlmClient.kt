package com.kefir.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

data class ChatMessage(val role: String, val content: String)

class LlmClient {

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .build()

    suspend fun stream(
        settings: AgentSettings,
        messages: List<ChatMessage>,
        onDelta: (String) -> Unit
    ): String = withContext(Dispatchers.IO) {
        if (settings.provider == "gemini") {
            streamGemini(settings, messages, onDelta)
        } else {
            streamOpenAiCompatible(settings, messages, onDelta)
        }
    }

    private fun streamGemini(
        settings: AgentSettings,
        messages: List<ChatMessage>,
        onDelta: (String) -> Unit
    ): String {
        val system = messages.filter { it.role == "system" }.joinToString("\n\n") { it.content }
        val contents = JSONArray()
        messages.filter { it.role != "system" }.forEach { m ->
            contents.put(
                JSONObject()
                    .put("role", if (m.role == "assistant") "model" else "user")
                    .put("parts", JSONArray().put(JSONObject().put("text", m.content)))
            )
        }
        val body = JSONObject()
        if (system.isNotBlank()) {
            body.put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
        }
        body.put("contents", contents)

        val url = "https://generativelanguage.googleapis.com/v1beta/models/" +
            settings.geminiModel + ":streamGenerateContent?alt=sse"
        val request = Request.Builder()
            .url(url)
            .header("x-goog-api-key", settings.geminiApiKey)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        return readSse(request) { data ->
            val json = JSONObject(data)
            val parts = json.getJSONArray("candidates").getJSONObject(0)
                .getJSONObject("content").getJSONArray("parts")
            val sb = StringBuilder()
            for (i in 0 until parts.length()) {
                val part = parts.getJSONObject(i)
                if (part.has("text")) sb.append(part.getString("text"))
            }
            if (sb.isNotEmpty()) {
                onDelta(sb.toString())
                sb.toString()
            } else ""
        }
    }

    private fun streamOpenAiCompatible(
        settings: AgentSettings,
        messages: List<ChatMessage>,
        onDelta: (String) -> Unit
    ): String {
        val (baseUrl, apiKey, model) = if (settings.provider == "opencode") {
            Triple(settings.opencodeBaseUrl, settings.opencodeApiKey, settings.opencodeModel)
        } else {
            Triple(settings.openaiBaseUrl, settings.openaiApiKey, settings.openaiModel)
        }

        val msgs = JSONArray()
        messages.forEach { m ->
            msgs.put(JSONObject().put("role", m.role).put("content", m.content))
        }
        val body = JSONObject().put("model", model).put("messages", msgs).put("stream", true)

        val url = baseUrl.trimEnd('/') + "/chat/completions"
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        return readSse(request) { data ->
            if (data == "[DONE]") return@readSse ""
            val json = JSONObject(data)
            val delta = json.getJSONArray("choices").getJSONObject(0)
                .optJSONObject("delta")?.optString("content", "") ?: ""
            if (delta.isNotEmpty()) {
                onDelta(delta)
                delta
            } else ""
        }
    }

    private fun readSse(request: Request, handler: (String) -> String): String {
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val err = response.body?.string()?.take(500) ?: ""
                throw IOException("HTTP ${response.code}: $err")
            }
            val source = response.body?.source() ?: throw IOException("Пустой ответ")
            val full = StringBuilder()
            while (true) {
                val line = source.readUtf8Line() ?: break
                if (!line.startsWith("data:")) continue
                val data = line.removePrefix("data:").trim()
                if (data.isEmpty()) continue
                try {
                    full.append(handler(data))
                } catch (_: Exception) {
                }
            }
            return full.toString()
        }
    }
}
