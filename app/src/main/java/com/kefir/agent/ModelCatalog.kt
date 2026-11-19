package com.kefir.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Fetches model lists from provider servers so the user can pick a model
 * from a dropdown instead of typing model names by hand.
 */
object ModelCatalog {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .dns(ResilientDns)
        .build()

    data class Result(val models: List<String>, val error: String? = null)

    /** Fetch models for the given provider id ("gemini", "openai"). */
    suspend fun fetch(provider: String, apiKey: String, baseUrl: String): Result =
        withContext(Dispatchers.IO) {
            try {
                when (provider) {
                    "gemini" -> gemini(apiKey)
                    else -> openAiCompatible(baseUrl, apiKey)
                }
            } catch (e: Exception) {
                Result(emptyList(), e.message ?: e.toString())
            }
        }

    private fun gemini(apiKey: String): Result {
        if (apiKey.isBlank()) return Result(emptyList(), "нужен API key")
        val url = "https://generativelanguage.googleapis.com/v1beta/models?key=$apiKey&pageSize=200"
        val body = get(url)
        val json = JSONObject(body)
        val out = mutableListOf<String>()
        val arr = json.optJSONArray("models") ?: return Result(emptyList(), json.optString("error"))
        for (i in 0 until arr.length()) {
            val m = arr.getJSONObject(i)
            val supported = m.optJSONArray("supportedGenerationMethods")
            var ok = true
            if (supported != null) {
                ok = false
                for (j in 0 until supported.length()) {
                    if (supported.getString(j) == "generateContent") { ok = true; break }
                }
            }
            if (ok) {
                val name = m.getString("name").removePrefix("models/")
                out.add(name)
            }
        }
        return Result(out.sorted())
    }

    private fun openAiCompatible(baseUrl: String, apiKey: String): Result {
        if (apiKey.isBlank()) return Result(emptyList(), "нужен API key")
        val base = baseUrl.trim().removeSuffix("/")
        val url = if (base.endsWith("/v1")) "$base/models" else "$base/v1/models"
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
            .header("User-Agent", OpenCodeOAuth.UA)
            .build()
        client.newCall(req).execute().use { resp ->
            val bodyStr = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) return Result(emptyList(), "HTTP ${resp.code}")
            val json = JSONObject(bodyStr)
            val ids = mutableListOf<String>()
            val arr = json.optJSONArray("data") ?: return Result(emptyList(), "нет поля data")
            for (i in 0 until arr.length()) {
                ids.add(arr.getJSONObject(i).getString("id"))
            }
            return Result(ids.sorted())
        }
    }

    private fun get(url: String): String {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", OpenCodeOAuth.UA)
            .build()
        client.newCall(req).execute().use { resp ->
            val bodyStr = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw java.io.IOException("HTTP ${resp.code}")
            return bodyStr
        }
    }
}
