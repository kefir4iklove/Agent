package com.kefir.agent

import android.content.Context
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

class HttpUnauthorized(message: String) : IOException(message)

class LlmClient(context: Context) {

    private val appContext = context.applicationContext

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

    private suspend fun streamOpenAiCompatible(
        base: AgentSettings,
        messages: List<ChatMessage>,
        onDelta: (String) -> Unit
    ): String {
        var settings = base
        val msgs = JSONArray()
        messages.forEach { m ->
            msgs.put(JSONObject().put("role", m.role).put("content", m.content))
        }
        val bodyBase = JSONObject().put("messages", msgs).put("stream", true)

        var model = ""
        var attempt = 0
        while (true) {
            if (settings.provider == "opencode") {
                model = settings.opencodeModel
            } else {
                model = settings.openaiModel
            }
            val body = JSONObject(bodyBase.toString()).put("model", model)
            val (url, token, extraHeaders) = endpointFor(settings)

            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $token")
                .apply {
                    for ((k, v) in extraHeaders) header(k, v)
                }
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()

            try {
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
            } catch (e: HttpUnauthorized) {
                // OpenCode OAuth: токен истёк — обновляем и пробуем ещё раз
                if (settings.provider != "opencode" ||
                    settings.opencodeRefreshToken.isBlank() || attempt > 0
                ) throw e
                val cred = OpenCodeOAuth.refresh(settings.opencodeRefreshToken)
                SettingsStore.saveOpencodeTokens(appContext, cred)
                settings = settings.copy(
                    opencodeAccessToken = cred.access,
                    opencodeRefreshToken = cred.refresh,
                    opencodeExpiresAt = cred.expiresAt
                )
                attempt++
            }
        }
    }

    private fun endpointFor(settings: AgentSettings): Triple<String, String, Map<String, String>> {
        return if (settings.provider == "opencode") {
            val token = if (settings.opencodeAccessToken.isNotBlank()) {
                settings.opencodeAccessToken
            } else settings.opencodeApiKey
            val headers = buildMap {
                put("User-Agent", OpenCodeOAuth.UA)
                if (settings.opencodeOrgId.isNotBlank()) put("x-opencode-org-id", settings.opencodeOrgId)
            }
            Triple(settings.opencodeBaseUrl.trimEnd('/') + "/chat/completions", token, headers)
        } else {
            Triple(
                settings.openaiBaseUrl.trimEnd('/') + "/chat/completions",
                settings.openaiApiKey,
                emptyMap()
            )
        }
    }

    private fun readableError(body: String): String {
        // Try to pull "message" / "error.message" / "error.status" out of a JSON error body
        return try {
            val json = JSONObject(body)
            val m = json.optString("message").ifBlank { json.optJSONObject("error")?.optString("message").orEmpty() }
            if (m.isNotBlank()) m.take(300) else body.take(300)
        } catch (_: Exception) {
            body.take(300)
        }
    }

    private fun readSse(request: Request, handler: (String) -> String): String {
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val err = response.body?.string()?.take(2000) ?: ""
                if (response.code == 401) throw HttpUnauthorized("HTTP 401: ${readableError(err)}")
                throw IOException("HTTP ${response.code}: ${readableError(err)}")
            }
            val source = response.body?.source() ?: throw IOException("Пустой ответ")
            val full = StringBuilder()
            var dataLines = 0
            var parsedLines = 0
            while (true) {
                val line = source.readUtf8Line() ?: break
                if (!line.startsWith("data:")) continue
                val data = line.removePrefix("data:").trim()
                if (data.isEmpty()) continue
                dataLines++
                try {
                    val handled = handler(data)
                    if (handled.isNotEmpty()) parsedLines++
                    full.append(handled)
                } catch (e: Throwable) {
                    // Keep streaming — one malformed event must not kill the response,
                    // but stop silently swallowing everything.
                }
            }
            if (full.isEmpty() && dataLines > 0 && parsedLines == 0) {
                throw IOException("Сервер прислал ответ, но он не распознан (не SSE-поток). Проверь модель/base URL.")
            }
            if (full.isEmpty() && dataLines == 0) {
                throw IOException("Пустой ответ от сервера. Проверь API ключ и модель.")
            }
            return full.toString()
        }
    }
}
