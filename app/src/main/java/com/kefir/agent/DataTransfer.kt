package com.kefir.agent

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Export / import of all app data as a single .zip file:
 *  - settings.json  (API ключи, токены, модели, воркспейс)
 *  - chats/<uuid>.json (история чатов)
 * Используется SAF (ContentResolver), так что файл можно сохранить
 * в любую папку и перенести на другое устройство.
 */
object DataTransfer {

    private const val SETTING_FILE = "settings.json"

    fun export(context: Context, dest: Uri): Result<Int> = runCatching {
        val resolver = context.contentResolver
        var chatCount = 0
        resolver.openOutputStream(dest)?.use { out ->
            ZipOutputStream(out).use { zip ->
                // 1. settings (включая API ключи и токены)
                val raw = rawPrefs(context)
                zip.putNextEntry(ZipEntry(SETTING_FILE))
                zip.write(raw.toString(2).toByteArray(Charsets.UTF_8))
                zip.closeEntry()

                // 2. chats
                val chatsDir = File(context.filesDir, "chats")
                if (chatsDir.isDirectory) {
                    chatsDir.listFiles()?.forEach { f ->
                        if (f.isFile) {
                            zip.putNextEntry(ZipEntry("chats/" + f.name))
                            zip.write(f.readBytes())
                            zip.closeEntry()
                            chatCount++
                        }
                    }
                }
            }
        } ?: error("Не удалось открыть файл")
        chatCount
    }

    fun import(context: Context, src: Uri): Result<Int> = runCatching {
        val resolver = context.contentResolver
        var chatCount = 0
        resolver.openInputStream(src)?.use { ins ->
            ZipInputStream(ins).use { zip ->
                var e: ZipEntry? = zip.nextEntry
                while (e != null) {
                    val name = e.name
                    val bytes = zip.readBytes()
                    when {
                        name == SETTING_FILE -> {
                            val txt = String(bytes, Charsets.UTF_8)
                            restorePrefs(context, JSONObject(txt))
                        }
                        name.startsWith("chats/") && name.endsWith(".json") -> {
                            val f = File(context.filesDir, name)
                            f.parentFile?.mkdirs()
                            f.writeBytes(bytes)
                            chatCount++
                        }
                    }
                    e = zip.nextEntry
                }
            }
        } ?: error("Не удалось открыть файл")
        chatCount
    }

    // Копируем все пары ключ-значение EncryptedSharedPreferences в JSON
    // (публичные геттеры-итерации: сохраняем всё, что есть).
    private fun rawPrefs(context: Context): JSONObject {
        val s = SettingsStore.load(context)
        val o = JSONObject()
        val fields = listOf(
            "provider", "geminiApiKey", "geminiModel",
            "opencodeBaseUrl", "opencodeApiKey", "opencodeModel",
            "opencodeAccessToken", "opencodeRefreshToken", "opencodeOrgId",
            "openaiBaseUrl", "openaiApiKey", "openaiModel",
            "workspaceTreeUri"
        )
        fun get(name: String): Any? = try {
            AgentSettings::class.java.getDeclaredField(name).apply { isAccessible = true }.get(s)
        } catch (_: Exception) { null }
        for (f in fields) {
            when (val v = get(f)) {
                is String -> o.put(f, v)
                is Int -> o.put(f, v)
                is Long -> o.put(f, v)
                is Boolean -> o.put(f, v)
            }
        }
        o.put("opencodeExpiresAt", s.opencodeExpiresAt)
        return o
    }

    private fun restorePrefs(context: Context, o: JSONObject) {
        val cur = SettingsStore.load(context)
        var s = cur.copy(
            provider = o.optString("provider", cur.provider),
            geminiApiKey = o.optString("geminiApiKey", cur.geminiApiKey),
            geminiModel = o.optString("geminiModel", cur.geminiModel),
            opencodeBaseUrl = o.optString("opencodeBaseUrl", cur.opencodeBaseUrl),
            opencodeApiKey = o.optString("opencodeApiKey", cur.opencodeApiKey),
            opencodeModel = o.optString("opencodeModel", cur.opencodeModel),
            opencodeAccessToken = o.optString("opencodeAccessToken", cur.opencodeAccessToken),
            opencodeRefreshToken = o.optString("opencodeRefreshToken", cur.opencodeRefreshToken),
            opencodeExpiresAt = o.optLong("opencodeExpiresAt", cur.opencodeExpiresAt),
            opencodeOrgId = o.optString("opencodeOrgId", cur.opencodeOrgId),
            openaiBaseUrl = o.optString("openaiBaseUrl", cur.openaiBaseUrl),
            openaiApiKey = o.optString("openaiApiKey", cur.openaiApiKey),
            openaiModel = o.optString("openaiModel", cur.openaiModel),
            workspaceTreeUri = o.optString("workspaceTreeUri", cur.workspaceTreeUri)
        )
        // проброс закэшированных списков моделей
        s = s.copy(
            cacheGeminiModels = cur.cacheGeminiModels,
            cacheOpenaiModels = cur.cacheOpenaiModels,
            cacheOpencodeModels = cur.cacheOpencodeModels
        )
        SettingsStore.save(context, s)
    }
}
