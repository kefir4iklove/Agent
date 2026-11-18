package com.kefir.agent

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

data class AgentSettings(
    val provider: String = "gemini",
    val geminiApiKey: String = "",
    val geminiModel: String = "gemini-2.0-flash",
    val opencodeBaseUrl: String = OpenCodeOAuth.INFERENCE_URL,
    val opencodeApiKey: String = "",
    val opencodeModel: String = "code-supernova",
    val opencodeAccessToken: String = "",
    val opencodeRefreshToken: String = "",
    val opencodeExpiresAt: Long = 0,
    val opencodeOrgId: String = "",
    val openaiBaseUrl: String = "https://api.openai.com/v1",
    val openaiApiKey: String = "",
    val openaiModel: String = "gpt-4o-mini",
    val workspaceTreeUri: String = "",
    // Cached model lists (fetched from servers) for the top model picker
    val cacheGeminiModels: List<String> = emptyList(),
    val cacheOpenaiModels: List<String> = emptyList(),
    val cacheOpencodeModels: List<String> = emptyList()
) {
    fun modelsFor(provider: String): List<String> = when (provider) {
        "gemini" -> cacheGeminiModels
        "opencode" -> cacheOpencodeModels
        else -> cacheOpenaiModels
    }
}

object SettingsStore {

    fun load(context: Context): AgentSettings {
        val p = prefs(context)
        val d = AgentSettings()
        return AgentSettings(
            provider = p.getString("provider", d.provider) ?: d.provider,
            geminiApiKey = p.getString("geminiApiKey", "") ?: "",
            geminiModel = p.getString("geminiModel", d.geminiModel) ?: d.geminiModel,
            opencodeBaseUrl = p.getString("opencodeBaseUrl", d.opencodeBaseUrl) ?: d.opencodeBaseUrl,
            opencodeApiKey = p.getString("opencodeApiKey", "") ?: "",
            opencodeModel = p.getString("opencodeModel", d.opencodeModel) ?: d.opencodeModel,
            opencodeAccessToken = p.getString("opencodeAccessToken", "") ?: "",
            opencodeRefreshToken = p.getString("opencodeRefreshToken", "") ?: "",
            opencodeExpiresAt = p.getLong("opencodeExpiresAt", 0),
            opencodeOrgId = p.getString("opencodeOrgId", "") ?: "",
            openaiBaseUrl = p.getString("openaiBaseUrl", d.openaiBaseUrl) ?: d.openaiBaseUrl,
            openaiApiKey = p.getString("openaiApiKey", "") ?: "",
            openaiModel = p.getString("openaiModel", d.openaiModel) ?: d.openaiModel,
            workspaceTreeUri = p.getString("workspaceTreeUri", "") ?: "",
            cacheGeminiModels = (p.getString("cacheGeminiModels", "") ?: "").split("\n").filter { it.isNotBlank() },
            cacheOpenaiModels = (p.getString("cacheOpenaiModels", "") ?: "").split("\n").filter { it.isNotBlank() },
            cacheOpencodeModels = (p.getString("cacheOpencodeModels", "") ?: "").split("\n").filter { it.isNotBlank() }
        )
    }

    fun save(context: Context, s: AgentSettings) {
        prefs(context).edit()
            .putString("provider", s.provider)
            .putString("geminiApiKey", s.geminiApiKey)
            .putString("geminiModel", s.geminiModel)
            .putString("opencodeBaseUrl", s.opencodeBaseUrl)
            .putString("opencodeApiKey", s.opencodeApiKey)
            .putString("opencodeModel", s.opencodeModel)
            .putString("opencodeAccessToken", s.opencodeAccessToken)
            .putString("opencodeRefreshToken", s.opencodeRefreshToken)
            .putLong("opencodeExpiresAt", s.opencodeExpiresAt)
            .putString("opencodeOrgId", s.opencodeOrgId)
            .putString("openaiBaseUrl", s.openaiBaseUrl)
            .putString("openaiApiKey", s.openaiApiKey)
            .putString("openaiModel", s.openaiModel)
            .putString("workspaceTreeUri", s.workspaceTreeUri)
            .putString("cacheGeminiModels", s.cacheGeminiModels.joinToString("\n"))
            .putString("cacheOpenaiModels", s.cacheOpenaiModels.joinToString("\n"))
            .putString("cacheOpencodeModels", s.cacheOpencodeModels.joinToString("\n"))
            .apply()
    }

    fun saveOpencodeTokens(context: Context, cred: OpenCodeOAuth.Credential) {
        val s = load(context)
        save(
            context,
            s.copy(
                opencodeAccessToken = cred.access,
                opencodeRefreshToken = cred.refresh,
                opencodeExpiresAt = cred.expiresAt
            )
        )
    }

    fun saveWorkspace(context: Context, treeUri: String) =
        save(context, load(context).copy(workspaceTreeUri = treeUri))

    private fun prefs(context: Context) = EncryptedSharedPreferences.create(
        context,
        "agent_settings",
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )
}
