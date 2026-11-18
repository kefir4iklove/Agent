package com.kefir.agent

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.noties.markwon.Markwon
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

data class ChatItem(val role: String, var text: String)

class MainActivity : AppCompatActivity() {

    private lateinit var adapter: ChatAdapter
    private lateinit var engine: AgentEngine
    private lateinit var settings: AgentSettings
    private lateinit var drawer: DrawerLayout
    private lateinit var drawerAdapter: DrawerChats
    private val llm by lazy { LlmClient(this) }
    private var running = false
    private var currentChatId: String? = null
    private var modelFetchJob: Job? = null
    private var agentJob: kotlinx.coroutines.Job? = null
    private lateinit var btnSend: ImageButton

    // SAF: экспорт/импорт данных (zip с ключами и чатами)
    private val exportPicker = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri: Uri? ->
        if (uri != null) {
            val res = DataTransfer.export(this, uri)
            res.onSuccess { n ->
                Toast.makeText(this, "Сохранено: настройки + $n чат(ов)", Toast.LENGTH_LONG).show()
            }.onFailure {
                Toast.makeText(this, "Ошибка: ${it.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
    private val importPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            val res = DataTransfer.import(this, uri)
            res.onSuccess { n ->
                settings = SettingsStore.load(this)
                engine = AgentEngine(settings, llm)
                currentChatId = null
                adapter.items.clear()
                adapter.notifyDataSetChanged()
                Tools.init(this, settings.workspaceTreeUri)
                updateWorkspaceLabels()
                updateModelPill()
                refreshDrawer()
                Toast.makeText(this, "Импортировано: $n чат(ов) + настройки", Toast.LENGTH_LONG).show()
            }.onFailure {
                Toast.makeText(this, "Ошибка: ${it.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private val folderPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: Exception) {
            }
            SettingsStore.saveWorkspace(this, uri.toString())
            settings = SettingsStore.load(this)
            engine.settings = settings
            Tools.init(this, settings.workspaceTreeUri)
            updateWorkspaceLabels()
            Toast.makeText(this, "Воркспейс: ${Tools.workspacePath()}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        settings = SettingsStore.load(this)
        // Фолбэк: если модель пустая — ставим дефолт для провайдера
        val fixed = fixEmptyModel(settings)
        if (fixed != settings) {
            SettingsStore.save(this, fixed)
            settings = fixed
        }
        Tools.init(this, settings.workspaceTreeUri)
        engine = AgentEngine(settings, llm)

        drawer = findViewById(R.id.drawerLayout)

        adapter = ChatAdapter()
        val list = findViewById<RecyclerView>(R.id.chatList)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        // ----- Drawer: chats on top, settings at the bottom -----
        drawerAdapter = DrawerChats()
        val drawerList = findViewById<RecyclerView>(R.id.drawerChatList)
        drawerList.layoutManager = LinearLayoutManager(this)
        drawerList.adapter = drawerAdapter

        findViewById<View>(R.id.btnDrawer).setOnClickListener {
            refreshDrawer()
            drawer.openDrawer(Gravity.START)
        }
        findViewById<View>(R.id.btnDrawerNewChat).setOnClickListener {
            newChat()
            drawer.closeDrawer(Gravity.START)
        }
        findViewById<View>(R.id.btnDrawerAccount).setOnClickListener {
            startActivity(Intent(this, AccountActivity::class.java))
            drawer.closeDrawer(Gravity.START)
        }
        findViewById<View>(R.id.btnDrawerKeys).setOnClickListener {
            startActivity(Intent(this, ApiKeysActivity::class.java))
            drawer.closeDrawer(Gravity.START)
        }
        findViewById<View>(R.id.btnDrawerExport).setOnClickListener {
            drawer.closeDrawer(Gravity.START)
            val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.getDefault())
                .format(java.util.Date())
            exportPicker.launch("agent-backup-$stamp.zip")
        }
        findViewById<View>(R.id.btnDrawerImport).setOnClickListener {
            drawer.closeDrawer(Gravity.START)
            importPicker.launch(arrayOf("application/zip", "application/octet-stream"))
        }
        findViewById<View>(R.id.btnDrawerFolder).setOnClickListener {
            folderPicker.launch(null)
            drawer.closeDrawer(Gravity.START)
        }

        findViewById<ImageButton>(R.id.btnFolder).setOnClickListener { folderPicker.launch(null) }

        // ----- Top model selector -----
        findViewById<TextView>(R.id.btnModelSelector).setOnClickListener { showModelPicker() }

        val input = findViewById<EditText>(R.id.inputField)
        btnSend = findViewById(R.id.btnSend)
        btnSend.setOnClickListener {
            if (running) {
                // Стоп: отменяем запрос/агент
                agentJob?.cancel()
                return@setOnClickListener
            }
            val text = input.text.toString().trim()
            if (text.isEmpty()) return@setOnClickListener
            if (!hasKey()) {
                Toast.makeText(this, "Сначала подключи аккаунт или вставь API ключ", Toast.LENGTH_LONG).show()
                startActivity(Intent(this, AccountActivity::class.java))
                return@setOnClickListener
            }
            input.setText("")
            runAgent(text)
        }

        updateWorkspaceLabels()
        updateModelPill()
    }

    // ------------------------------------------------------------------
    // Model picker at the top, grouped by provider/API
    // ------------------------------------------------------------------

    private fun fixEmptyModel(s: AgentSettings): AgentSettings = when (s.provider) {
        "gemini" -> if (s.geminiModel.isBlank()) s.copy(geminiModel = "gemini-2.0-flash") else s
        "opencode" -> if (s.opencodeModel.isBlank()) s.copy(opencodeModel = "code-supernova") else s
        else -> if (s.openaiModel.isBlank()) s.copy(openaiModel = "gpt-4o-mini") else s
    }

    private fun providerLabel(id: String): String = when (id) {
        "gemini" -> "Gemini"
        "opencode" -> "OpenCode"
        else -> "Custom API"
    }

    private fun updateModelPill() {
        val model = when (settings.provider) {
            "gemini" -> settings.geminiModel
            "opencode" -> settings.opencodeModel
            else -> settings.openaiModel
        }
        findViewById<TextView>(R.id.btnModelSelector).text =
            "${providerLabel(settings.provider)} \u00b7 $model"
    }

    private class PickerRow(
        val kind: Int, // 0 = header, 1 = model, 2 = settings link
        val provider: String = "",
        val name: String = "",
        val selected: Boolean = false
    )


    private fun modelDefaults(id: String): List<String> = if (id == "gemini") listOf(
        "gemini-2.5-pro", "gemini-2.5-flash", "gemini-2.0-flash"
    ) else emptyList()

    private fun buildPickerRows(): MutableList<PickerRow> {
        val rows = mutableListOf<PickerRow>()
        fun section(id: String, current: String, cache: List<String>, configured: Boolean) {
            val list = if (configured) (cache.ifEmpty { modelDefaults(id) }) else cache
            rows.add(PickerRow(0, id, providerLabel(id)))
            if (!configured) {
                rows.add(PickerRow(1, id, "\u2014 не подключён, настрой в Аккаунте", false))
                return
            }
            if (list.isEmpty()) rows.add(PickerRow(3))
            (listOf(current) + list.filter { it != current }).distinct()
                .forEach { rows.add(PickerRow(1, id, it, it == current)) }
        }
        section("gemini", settings.geminiModel, settings.cacheGeminiModels, settings.geminiApiKey.isNotBlank())
        section("opencode", settings.opencodeModel, settings.cacheOpencodeModels,
            settings.opencodeAccessToken.isNotBlank() || settings.opencodeApiKey.isNotBlank())
        section("openai", settings.openaiModel, settings.cacheOpenaiModels, settings.openaiApiKey.isNotBlank())
        rows.add(PickerRow(2, name = "\u2699 Настроить аккаунты и ключи"))
        return rows
    }

    private fun showModelPicker() {
        settings = SettingsStore.load(this)
        val rows = buildPickerRows()

        val listAdapter = object : ArrayAdapter<PickerRow>(this, 0, rows) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val ctx = parent.context
                val row = rows.getOrNull(position) ?: return TextView(ctx)
                val tv = (convertView as? TextView) ?: TextView(ctx)
                tv.setSingleLine(false)
                tv.maxLines = 2
                val padL = if (row.kind == 0) 18 else 30
                when (row.kind) {
                    0 -> {
                        tv.text = row.name
                        tv.setTextColor(0xFF9AA0A6.toInt())
                        tv.textSize = 12f
                        tv.setAllCaps(true)
                        tv.setPadding(dp(padL), dp(14), dp(12), dp(4))
                    }
                    1 -> {
                        tv.text = row.name
                        tv.setTextColor(if (row.selected) 0xFF7DA2FF.toInt() else 0xFFE8EAED.toInt())
                        tv.textSize = 15f
                        tv.setPadding(dp(padL), dp(11), dp(12), dp(11))
                    }
                    2 -> {
                        tv.text = row.name
                        tv.setTextColor(0xFF8AB4F8.toInt())
                        tv.textSize = 14f
                        tv.setPadding(dp(padL), dp(14), dp(12), dp(10))
                    }
                    else -> {
                        tv.text = getString(R.string.loading_models)
                        tv.setTextColor(0xFF9AA0A6.toInt())
                        tv.textSize = 12f
                        tv.setPadding(dp(padL), dp(6), dp(12), dp(6))
                    }
                }
                tv.setBackgroundColor(Color.TRANSPARENT)
                return tv
            }
        }

        val lv = ListView(this)
        lv.adapter = listAdapter
        lv.setBackgroundColor(0xFF17191E.toInt())
        lv.divider = null

        val dialog = AlertDialog.Builder(this, R.style.Theme_Agent)
            .setTitle(getString(R.string.select_model))
            .setView(lv)
            .create()

        lv.setOnItemClickListener { _, _, pos, _ ->
            val row = rows.getOrNull(pos) ?: return@setOnItemClickListener
            if (row.kind == 2 || (row.kind == 1 && row.name.startsWith("\u2014"))) {
                dialog.dismiss()
                startActivity(Intent(this, AccountActivity::class.java))
                return@setOnItemClickListener
            }
            if (row.kind == 1) {
                val st = SettingsStore.load(this)
                val updated = when (row.provider) {
                    "gemini" -> st.copy(provider = "gemini", geminiModel = row.name)
                    "opencode" -> st.copy(provider = "opencode", opencodeModel = row.name)
                    else -> st.copy(provider = "openai", openaiModel = row.name)
                }
                SettingsStore.save(this, updated)
                settings = updated
                engine.settings = updated
                updateModelPill()
                dialog.dismiss()
            }
        }

        dialog.show()
        refreshingMissingCaches {
            settings = SettingsStore.load(this)
            rows.clear()
            rows.addAll(buildPickerRows())
            listAdapter.notifyDataSetChanged()
        }
    }


    /** Fetches model lists that are missing from the cache and caches them. */
    private fun refreshingMissingCaches(onDone: () -> Unit) {
        if (modelFetchJob?.isActive == true) return
        fun needed(): Boolean {
            val s = SettingsStore.load(this)
            if (s.geminiApiKey.isNotBlank() && s.cacheGeminiModels.isEmpty()) return true
            if (s.openaiApiKey.isNotBlank() && s.cacheOpenaiModels.isEmpty()) return true
            if ((s.opencodeAccessToken.isNotBlank() && s.opencodeOrgId.isNotBlank()) &&
                s.cacheOpencodeModels.isEmpty()
            ) return true
            return false
        }
        if (!needed()) return
        modelFetchJob = lifecycleScope.launch {
            var s = SettingsStore.load(this@MainActivity)
            try {
                if (s.geminiApiKey.isNotBlank() && s.cacheGeminiModels.isEmpty()) {
                    ModelCatalog.fetch("gemini", s.geminiApiKey, "").models.takeIf { it.isNotEmpty() }?.let {
                        SettingsStore.save(this@MainActivity, s.copy(cacheGeminiModels = it))
                        s = SettingsStore.load(this@MainActivity)
                    }
                }
                if (s.openaiApiKey.isNotBlank() && s.cacheOpenaiModels.isEmpty()) {
                    ModelCatalog.fetch("openai", s.openaiApiKey, s.openaiBaseUrl).models.takeIf { it.isNotEmpty() }?.let {
                        SettingsStore.save(this@MainActivity, s.copy(cacheOpenaiModels = it))
                        s = SettingsStore.load(this@MainActivity)
                    }
                }
                if (s.opencodeAccessToken.isNotBlank() && s.opencodeOrgId.isNotBlank() &&
                    s.cacheOpencodeModels.isEmpty()
                ) {
                    OpenCodeOAuth.models(s.opencodeAccessToken, s.opencodeOrgId).takeIf { it.isNotEmpty() }?.let {
                        SettingsStore.save(this@MainActivity, s.copy(cacheOpencodeModels = it))
                        s = SettingsStore.load(this@MainActivity)
                    }
                }
            } catch (_: Exception) {
            }
            runOnUiThread { onDone() }
        }
    }

    // ------------------------------------------------------------------
    // Drawer
    // ------------------------------------------------------------------

    private fun refreshDrawer() {
        drawerAdapter.refresh()
        findViewById<TextView>(R.id.drawerEmptyLabel).visibility =
            if (drawerAdapter.items.isEmpty()) View.VISIBLE else View.GONE
        findViewById<RecyclerView>(R.id.drawerChatList).visibility =
            if (drawerAdapter.items.isEmpty()) View.GONE else View.VISIBLE
    }

    inner class DrawerChats : RecyclerView.Adapter<DrawerChats.Holder>() {
        val items = mutableListOf<ChatStorage.SavedChat>()
        private val fmt = java.text.SimpleDateFormat("dd.MM HH:mm", java.util.Locale.getDefault())

        fun refresh() {
            items.clear()
            items.addAll(ChatStorage.list(this@MainActivity))
            notifyDataSetChanged()
        }

        inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val title: TextView = view.findViewById(R.id.chatTitle)
            val date: TextView = view.findViewById(R.id.chatDate)
            val delete: Button = view.findViewById(R.id.btnDeleteChat)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_chat, parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val chat = items[position]
            holder.title.text = chat.title
            holder.date.text = fmt.format(java.util.Date(chat.updated))
            holder.itemView.setOnClickListener {
                loadChat(chat.id)
                drawer.closeDrawer(Gravity.START)
            }
            holder.delete.setOnClickListener { v ->
                ChatStorage.delete(v.context, chat.id)
                val pos = holder.bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    items.removeAt(pos)
                    notifyItemRemoved(pos)
                    if (currentChatId == chat.id) newChat()
                    refreshDrawer()
                }
            }
        }

        override fun getItemCount(): Int = items.size
    }

    // ------------------------------------------------------------------

    private fun newChat() {
        if (running) return
        currentChatId = null
        engine.history.clear()
        adapter.items.clear()
        adapter.notifyDataSetChanged()
        refreshDrawer()
    }

    override fun onResume() {
        super.onResume()
        settings = SettingsStore.load(this)
        engine.settings = settings
        Tools.init(this, settings.workspaceTreeUri)
        updateWorkspaceLabels()
        updateModelPill()
        refreshDrawer()
    }

    private fun hasKey(): Boolean = when (settings.provider) {
        "gemini" -> settings.geminiApiKey.isNotBlank()
        "opencode" -> settings.opencodeAccessToken.isNotBlank() || settings.opencodeApiKey.isNotBlank()
        else -> settings.openaiApiKey.isNotBlank()
    }

    private fun updateWorkspaceLabels() {
        val txt = "Папка: ${Tools.workspacePath()}"
        findViewById<TextView>(R.id.workspaceLabel).text = txt
        findViewById<TextView>(R.id.drawerWorkspace).text = txt
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun loadChat(id: String) {
        val loaded = ChatStorage.load(this, id) ?: return
        currentChatId = id
        engine.history.clear()
        engine.history.addAll(loaded.first)
        adapter.items.clear()
        adapter.items.addAll(loaded.second)
        adapter.notifyDataSetChanged()
        scrollToBottom()
    }

    private fun scrollToBottom() {
        val list = findViewById<RecyclerView>(R.id.chatList)
        list.scrollToPosition((adapter.itemCount - 1).coerceAtLeast(0))
    }

    private fun saveCurrentChat() {
        val title = adapter.items.firstOrNull { it.role == "user" }
            ?.text?.take(60) ?: "Чат"
        currentChatId = ChatStorage.save(this, currentChatId, title, engine.history, adapter.items)
        refreshDrawer()
    }

    private fun runAgent(text: String) {
        running = true
        btnSend.isEnabled = true
        btnSend.setImageResource(R.drawable.ic_stop)
        btnSend.contentDescription = getString(R.string.stop)

        adapter.items.add(ChatItem("user", text))
        val agentItem = ChatItem("agent", "")
        adapter.items.add(agentItem)
        adapter.notifyItemRangeInserted(adapter.items.size - 2, 2)
        scrollToBottom()

        agentJob = lifecycleScope.launch {
            try {
                engine.run(
                    text,
                    onDelta = { delta ->
                        runOnUiThread {
                            agentItem.text += delta
                            val idx = adapter.items.indexOf(agentItem)
                            if (idx >= 0) adapter.notifyItemChanged(idx)
                            scrollToBottom()
                        }
                    },
                    onToolResult = { line ->
                        runOnUiThread {
                            val idx = adapter.items.indexOf(agentItem)
                            if (idx >= 0) {
                                adapter.items.add(idx, ChatItem("tool", line))
                                adapter.notifyItemRangeInserted(idx, 1)
                            } else {
                                adapter.items.add(ChatItem("tool", line))
                                adapter.notifyItemInserted(adapter.items.size - 1)
                            }
                            scrollToBottom()
                        }
                    }
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                runOnUiThread {
                    if (agentItem.text.isBlank()) {
                        val idx = adapter.items.indexOf(agentItem)
                        if (idx >= 0) {
                            adapter.items.removeAt(idx)
                            adapter.notifyItemRemoved(idx)
                        }
                    } else {
                        agentItem.text += " \u2022 остановлено"
                    }
                    adapter.items.add(ChatItem("info", "Запрос остановлен"))
                    adapter.notifyItemInserted(adapter.items.size - 1)
                    scrollToBottom()
                }
            } catch (e: Exception) {
                val msg = "[${providerLabel(settings.provider)}] " + (e.message ?: e.toString()).take(400)
                runOnUiThread {
                    if (agentItem.text.isNotBlank()) {
                        adapter.items.add(ChatItem("error", msg))
                        adapter.notifyItemInserted(adapter.items.size - 1)
                    } else {
                        val idx = adapter.items.indexOf(agentItem)
                        if (idx >= 0) {
                            adapter.items.removeAt(idx)
                            adapter.notifyItemRemoved(idx)
                        }
                        adapter.items.add(ChatItem("error", msg))
                        adapter.notifyItemInserted(adapter.items.size - 1)
                    }
                    scrollToBottom()
                    Toast.makeText(this@MainActivity, "Ошибка: $msg", Toast.LENGTH_LONG).show()
                }
            } finally {
                runOnUiThread {
                    try {
                        saveCurrentChat()
                    } catch (_: Exception) {
                    }
                    running = false
                    btnSend.isEnabled = true
                    btnSend.setImageResource(R.drawable.ic_send)
                    btnSend.contentDescription = getString(R.string.send)
                }
            }
        }
    }
}

class ChatAdapter : RecyclerView.Adapter<ChatAdapter.Holder>() {

    val items = mutableListOf<ChatItem>()

    private var markwon: Markwon? = null

    private fun markdown(ctx: android.content.Context): Markwon {
        if (markwon == null) {
            markwon = Markwon.builder(ctx)
                .usePlugin(io.noties.markwon.linkify.LinkifyPlugin.create())
                .build()
        }
        return markwon!!
    }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val bubble: ViewGroup = view.findViewById(R.id.bubble)
        val roleLabel: TextView = view.findViewById(R.id.roleLabel)
        val messageText: TextView = view.findViewById(R.id.messageText)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_message, parent, false)
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        val params = holder.bubble.layoutParams as FrameLayout.LayoutParams

        when (item.role) {
            "user" -> {
                holder.roleLabel.visibility = View.GONE
                holder.messageText.text = item.text.ifBlank { "…" }
                holder.messageText.textSize = 15f
                holder.bubble.setBackgroundResource(R.drawable.bg_bubble_user)
                params.gravity = Gravity.END
                params.width = FrameLayout.LayoutParams.WRAP_CONTENT
                holder.bubble.setPadding(dp(holder, 14), dp(holder, 10), dp(holder, 14), dp(holder, 10))
            }
            "tool" -> {
                holder.roleLabel.visibility = View.VISIBLE
                holder.roleLabel.text = item.text.ifBlank { "…инструмент" }
                holder.messageText.visibility = View.GONE
                holder.bubble.setBackgroundResource(R.drawable.bg_chip)
                params.gravity = Gravity.CENTER_HORIZONTAL
                params.width = FrameLayout.LayoutParams.WRAP_CONTENT
                holder.bubble.setPadding(dp(holder, 12), dp(holder, 8), dp(holder, 12), dp(holder, 8))
            }
            "info" -> {
                holder.roleLabel.visibility = View.GONE
                holder.messageText.visibility = View.VISIBLE
                holder.messageText.text = item.text
                holder.messageText.textSize = 12f
                holder.messageText.setTextColor(0xFF9AA0A6.toInt())
                holder.bubble.setBackgroundResource(R.drawable.bg_chip)
                params.gravity = Gravity.CENTER_HORIZONTAL
                params.width = FrameLayout.LayoutParams.WRAP_CONTENT
                holder.bubble.setPadding(dp(holder, 12), dp(holder, 6), dp(holder, 12), dp(holder, 6))
            }
            "error" -> {
                holder.roleLabel.visibility = View.VISIBLE
                holder.roleLabel.text = "⚠ Ошибка"
                holder.roleLabel.setTextColor(0xFFFF8A80.toInt())
                holder.messageText.visibility = View.VISIBLE
                holder.messageText.text = item.text
                holder.messageText.textSize = 14f
                holder.messageText.setTextColor(0xFFFFB4A9.toInt())
                holder.bubble.setBackgroundResource(R.drawable.bg_chip)
                params.gravity = Gravity.START
                params.width = FrameLayout.LayoutParams.MATCH_PARENT
                holder.bubble.setPadding(dp(holder, 12), dp(holder, 8), dp(holder, 12), dp(holder, 8))
            }
            else -> {
                holder.roleLabel.visibility = View.VISIBLE
                holder.roleLabel.text = "✦ Agent"
                holder.roleLabel.setTextColor(0xFFE8EAED.toInt())
                holder.messageText.visibility = View.VISIBLE
                holder.messageText.setTextColor(0xFFE8EAED.toInt())
                markdown(holder.itemView.context).setMarkdown(
                    holder.messageText,
                    item.text.ifBlank { "…" }
                )
                holder.messageText.textSize = 15f
                holder.bubble.background = null
                params.gravity = Gravity.START
                params.width = FrameLayout.LayoutParams.MATCH_PARENT
                holder.bubble.setPadding(dp(holder, 4), dp(holder, 2), dp(holder, 4), dp(holder, 6))
            }
        }
        holder.bubble.layoutParams = params
    }

    private fun dp(holder: Holder, v: Int): Int =
        (v * holder.itemView.context.resources.displayMetrics.density).toInt()

    override fun getItemCount(): Int = items.size
}
