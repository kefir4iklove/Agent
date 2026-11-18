package com.kefir.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object OpenCodeOAuth {

    const val CONSOLE = "https://opencode.ai/console"
    const val INFERENCE_URL = "https://opencode.ai/inference/openai/v1"
    const val CLIENT_ID = "opencode-cli"
    const val UA = "opencode/cli"

    data class DeviceStart(
        val deviceCode: String,
        val userCode: String,
        val verificationUrl: String,
        val intervalSec: Int,
        val expiresSec: Int
    )

    data class Credential(val access: String, val refresh: String, val expiresAt: Long)

    data class Org(val id: String, val name: String)

    sealed class PollResult {
        object Pending : PollResult()
        object SlowDown : PollResult()
        data class Success(val cred: Credential) : PollResult()
        data class Failed(val message: String) : PollResult()
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private suspend fun postJson(url: String, body: JSONObject, allow400: Boolean = false): JSONObject =
        withContext(Dispatchers.IO) {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", UA)
                .header("Content-Type", "application/json")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
            http.newCall(req).execute().use { res ->
                val text = res.body?.string().orEmpty()
                if (!res.isSuccessful && !(allow400 && res.code == 400)) {
                    throw java.io.IOException("HTTP ${res.code}: ${text.take(300)}")
                }
                JSONObject(text)
            }
        }

    private suspend fun getJson(url: String, token: String, orgId: String? = null): JSONObject =
        withContext(Dispatchers.IO) {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", UA)
                .header("Authorization", "Bearer $token")
                .apply { if (orgId != null) header("x-org-id", orgId) }
                .get()
                .build()
            http.newCall(req).execute().use { res ->
                val text = res.body?.string().orEmpty()
                if (!res.isSuccessful) throw java.io.IOException("HTTP ${res.code}: ${text.take(300)}")
                JSONObject(text)
            }
        }

    suspend fun startDeviceFlow(): DeviceStart = withContext(Dispatchers.IO) {
        val json = postJson("$CONSOLE/auth/device/code", JSONObject().put("client_id", CLIENT_ID))
        DeviceStart(
            deviceCode = json.getString("device_code"),
            userCode = json.optString("user_code", ""),
            verificationUrl = json.optString("verification_uri_complete", "")
                .ifBlank { json.optString("verification_uri", "$CONSOLE/") },
            intervalSec = json.optInt("interval", 5),
            expiresSec = json.optInt("expires_in", 600)
        )
    }

    private fun credentialFrom(poll: JSONObject): Credential = Credential(
        access = poll.getString("access_token"),
        refresh = poll.optString("refresh_token", ""),
        expiresAt = System.currentTimeMillis() + poll.optLong("expires_in", 3600) * 1000 - 5 * 60 * 1000
    )

    suspend fun pollDeviceCode(deviceCode: String): PollResult = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("client_id", CLIENT_ID)
            .put("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
            .put("device_code", deviceCode)
        val json = postJson("$CONSOLE/auth/device/token", body, allow400 = true)
        when {
            json.has("access_token") -> PollResult.Success(credentialFrom(json))
            json.optString("error") == "authorization_pending" -> PollResult.Pending
            json.optString("error") == "slow_down" -> PollResult.SlowDown
            json.optString("error").isNotEmpty() ->
                PollResult.Failed(json.optString("error") + " " + json.optString("error_description"))
            else -> PollResult.Pending
        }
    }

    suspend fun refresh(refreshToken: String): Credential = withContext(Dispatchers.IO) {
        val json = postJson(
            "$CONSOLE/auth/device/token",
            JSONObject().put("client_id", CLIENT_ID).put("grant_type", "refresh_token").put("refresh_token", refreshToken)
        )
        credentialFrom(json)
    }

    suspend fun orgs(token: String): List<Org> = withContext(Dispatchers.IO) {
        val json = getJson("$CONSOLE/api/orgs", token)
        val arr: JSONArray = when {
            json.has("orgs") -> json.getJSONArray("orgs")
            else -> JSONArray()
        }
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Org(o.getString("id"), o.optString("name", o.getString("id")))
        }
    }

    /** Список моделей воркспейса: IDs из console config (только модели Zen-шлюза). */
    suspend fun models(token: String, orgId: String): List<String> = withContext(Dispatchers.IO) {
        try {
            val json = getJson("$CONSOLE/api/config", token, orgId)
            val provider = json.optJSONObject("config")?.optJSONObject("provider") ?: return@withContext emptyList()
            val out = mutableListOf<String>()
            val keys = provider.keys()
            for (key in keys) {
                val entry = provider.optJSONObject(key) ?: continue
                val baseURL = entry.optJSONObject("options")?.optString("baseURL", "") ?: ""
                val isByok = (baseURL.isNotEmpty() || entry.optJSONObject("options")?.optString("apiKey", "")?.isNotEmpty() == true) &&
                    !baseURL.contains("opencode.ai/inference")
                if (isByok) continue
                val models = entry.optJSONObject("models") ?: continue
                for (id in models.keys()) {
                    val m = models.optJSONObject(id) ?: continue
                    if (m.optBoolean("disabled", false)) continue
                    if (id.startsWith("test") || id.startsWith("jev")) continue
                    if (!out.contains(id)) out.add(id)
                }
            }
            out.sorted()
        } catch (_: Exception) {
            emptyList()
        }
    }
}
