package com.kefir.agent

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.noties.markwon.Markwon
import kotlinx.coroutines.launch

data class ChatItem(val role: String, var text: String)

class MainActivity : AppCompatActivity() {

    private lateinit var adapter: ChatAdapter
    private lateinit var engine: AgentEngine
    private lateinit var settings: AgentSettings
    private val llm by lazy { LlmClient(this) }
    private var running = false
    private var currentChatId: String? = null

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
            Tools.init(this, settings.workspaceTreeUri)
            updateWorkspaceLabel()
            Toast.makeText(this, "Воркспейс: ${Tools.workspacePath()}", Toast.LENGTH_SHORT).show()
        }
    }

    private val historyLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val id = result.data?.getStringExtra("chatId") ?: return@registerForActivityResult
        loadChat(id)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        settings = SettingsStore.load(this)
        Tools.init(this, settings.workspaceTreeUri)
        engine = AgentEngine(settings, llm)

        adapter = ChatAdapter()
        val list = findViewById<RecyclerView>(R.id.chatList)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        val input = findViewById<EditText>(R.id.inputField)
        val send = findViewById<ImageButton>(R.id.btnSend)

        send.setOnClickListener {
            val text = input.text.toString().trim()
            if (text.isEmpty() || running) return@setOnClickListener
            if (!hasKey()) {
                Toast.makeText(this, "Сначала подключи аккаунт и вставь API ключ", Toast.LENGTH_LONG).show()
                startActivity(Intent(this, AccountActivity::class.java))
                return@setOnClickListener
            }
            input.setText("")
            runAgent(text)
        }

        findViewById<ImageButton>(R.id.btnHistory).setOnClickListener {
            historyLauncher.launch(Intent(this, HistoryActivity::class.java))
        }

        findViewById<ImageButton>(R.id.btnFolder).setOnClickListener {
            folderPicker.launch(null)
        }

        findViewById<ImageButton>(R.id.btnMenu).setOnClickListener { anchor ->
            showMenu(anchor)
        }

        updateWorkspaceLabel()
        updateChatTitle()
    }

    private fun showMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, 1, 0, "Новый чат")
        popup.menu.add(0, 2, 1, "История")
        popup.menu.add(0, 3, 2, "Аккаунт")
        popup.menu.add(0, 4, 3, "API ключи")
        popup.menu.add(0, 5, 4, "Выбрать папку")
        popup.setOnMenuItemClickListener { item: MenuItem ->
            when (item.itemId) {
                1 -> newChat()
                2 -> historyLauncher.launch(Intent(this, HistoryActivity::class.java))
                3 -> startActivity(Intent(this, AccountActivity::class.java))
                4 -> startActivity(Intent(this, ApiKeysActivity::class.java))
                5 -> folderPicker.launch(null)
            }
            true
        }
        popup.show()
    }

    private fun newChat() {
        if (running) return
        currentChatId = null
        engine.history.clear()
        adapter.items.clear()
        adapter.notifyDataSetChanged()
        updateChatTitle()
    }

    override fun onResume() {
        super.onResume()
        settings = SettingsStore.load(this)
        engine.settings = settings
        Tools.init(this, settings.workspaceTreeUri)
        updateWorkspaceLabel()
    }

    private fun hasKey(): Boolean = when (settings.provider) {
        "gemini" -> settings.geminiApiKey.isNotBlank()
        "opencode" -> settings.opencodeAccessToken.isNotBlank() || settings.opencodeApiKey.isNotBlank()
        else -> settings.openaiApiKey.isNotBlank()
    }

    private fun updateWorkspaceLabel() {
        findViewById<TextView>(R.id.workspaceLabel).text =
            "Папка: ${Tools.workspacePath()}"
    }

    private fun updateChatTitle(title: String? = null) {
        val t = when {
            title != null -> title
            else -> {
                adapter.items.firstOrNull { it.role == "user" }?.text?.take(60)
                    ?: "Новый чат"
            }
        }
        findViewById<TextView>(R.id.chatTitleLabel).text = t
    }

    private fun loadChat(id: String) {
        val loaded = ChatStorage.load(this, id) ?: return
        currentChatId = id
        engine.history.clear()
        engine.history.addAll(loaded.first)
        adapter.items.clear()
        adapter.items.addAll(loaded.second)
        adapter.notifyDataSetChanged()
        updateChatTitle(
            ChatStorage.list(this).firstOrNull { it.id == id }?.title
        )
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
        updateChatTitle(title)
    }

    private fun runAgent(text: String) {
        running = true
        findViewById<ImageButton>(R.id.btnSend).isEnabled = false

        adapter.items.add(ChatItem("user", text))
        val agentItem = ChatItem("agent", "")
        adapter.items.add(agentItem)
        adapter.notifyItemRangeInserted(adapter.items.size - 2, 2)
        updateChatTitle(text.take(60))
        scrollToBottom()

        lifecycleScope.launch {
            try {
                engine.run(
                    text,
                    onDelta = { delta ->
                        runOnUiThread {
                            agentItem.text += delta
                            adapter.notifyItemChanged(adapter.items.size - 1)
                            scrollToBottom()
                        }
                    },
                    onToolResult = { line ->
                        runOnUiThread {
                            adapter.items.add(adapter.items.size - 1, ChatItem("tool", line))
                            adapter.notifyItemRangeInserted(adapter.items.size - 2, 1)
                            scrollToBottom()
                        }
                    }
                )
            } catch (e: Exception) {
                runOnUiThread {
                    agentItem.text = agentItem.text.ifBlank { "" } + "\n⚠️ " + (e.message ?: e.toString())
                    adapter.notifyItemChanged(adapter.items.size - 1)
                }
            } finally {
                runOnUiThread {
                    try {
                        saveCurrentChat()
                    } catch (_: Exception) {
                    }
                    running = false
                    findViewById<ImageButton>(R.id.btnSend).isEnabled = true
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
            else -> {
                holder.roleLabel.visibility = View.VISIBLE
                holder.roleLabel.text = "✦ Agent"
                holder.messageText.visibility = View.VISIBLE
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
