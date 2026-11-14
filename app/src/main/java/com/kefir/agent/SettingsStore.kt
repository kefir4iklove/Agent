package com.kefir.agent

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

data class AgentSettings(
    val provider: String = "gemini",
    val geminiApiKey: String = "",
    val geminiModel: String = "gemini-2.0-flash",
    val opencodeBaseUrl: String = "https://opencode.ai/zen/v1",
    val opencodeApiKey: String = "",
    val opencodeModel: String = "claude-sonnet-4",
    val openaiBaseUrl: String = "https://api.openai.com/v1",
    val openaiApiKey: String = "",
    val openaiModel: String = "gpt-4o-mini",
    val workspaceTreeUri: String = ""
)

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
            openaiBaseUrl = p.getString("openaiBaseUrl", d.openaiBaseUrl) ?: d.openaiBaseUrl,
            openaiApiKey = p.getString("openaiApiKey", "") ?: "",
            openaiModel = p.getString("openaiModel", d.openaiModel) ?: d.openaiModel,
            workspaceTreeUri = p.getString("workspaceTreeUri", "") ?: ""
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
            .putString("openaiBaseUrl", s.openaiBaseUrl)
            .putString("openaiApiKey", s.openaiApiKey)
            .putString("openaiModel", s.openaiModel)
            .putString("workspaceTreeUri", s.workspaceTreeUri)
            .apply()
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
