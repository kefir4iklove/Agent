package com.kefir.agent

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

object ChatStorage {

    data class SavedChat(val id: String, val title: String, val updated: Long)

    private fun dir(context: Context) = File(context.filesDir, "chats").apply { mkdirs() }

    fun save(
        context: Context,
        id: String?,
        title: String,
        messages: List<ChatMessage>,
        display: List<ChatItem>
    ): String {
        val chatId = id ?: UUID.randomUUID().toString()
        val root = JSONObject()
            .put("id", chatId)
            .put("title", title)
            .put("updated", System.currentTimeMillis())
        val msgs = JSONArray()
        messages.forEach { msgs.put(JSONObject().put("role", it.role).put("content", it.content)) }
        root.put("messages", msgs)
        val disp = JSONArray()
        display.forEach { disp.put(JSONObject().put("role", it.role).put("text", it.text)) }
        root.put("display", disp)
        File(dir(context), "$chatId.json").writeText(root.toString())
        return chatId
    }

    fun list(context: Context): List<SavedChat> =
        dir(context).listFiles { f -> f.name.endsWith(".json") }
            ?.mapNotNull { f ->
                try {
                    val o = JSONObject(f.readText())
                    SavedChat(o.getString("id"), o.getString("title"), o.getLong("updated"))
                } catch (_: Exception) {
                    null
                }
            }
            ?.sortedByDescending { it.updated }
            ?: emptyList()

    fun load(context: Context, id: String): Pair<List<ChatMessage>, List<ChatItem>>? {
        val f = File(dir(context), "$id.json")
        if (!f.exists()) return null
        return try {
            val o = JSONObject(f.readText())
            val messages = o.getJSONArray("messages").let { arr ->
                (0 until arr.length()).map { i ->
                    val m = arr.getJSONObject(i)
                    ChatMessage(m.getString("role"), m.getString("content"))
                }
            }
            val display = o.getJSONArray("display").let { arr ->
                (0 until arr.length()).map { i ->
                    val d = arr.getJSONObject(i)
                    ChatItem(d.getString("role"), d.getString("text"))
                }
            }
            messages to display
        } catch (_: Exception) {
            null
        }
    }

    fun delete(context: Context, id: String) {
        File(dir(context), "$id.json").delete()
    }
}
