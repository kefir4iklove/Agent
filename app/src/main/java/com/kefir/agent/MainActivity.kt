package com.kefir.agent

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
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
        val send = findViewById<Button>(R.id.btnSend)

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

        findViewById<Button>(R.id.btnHistory).setOnClickListener {
            historyLauncher.launch(Intent(this, HistoryActivity::class.java))
        }
        findViewById<Button>(R.id.btnAccount).setOnClickListener {
            startActivity(Intent(this, AccountActivity::class.java))
        }
        findViewById<Button>(R.id.btnKeys).setOnClickListener {
            startActivity(Intent(this, ApiKeysActivity::class.java))
        }
        findViewById<Button>(R.id.btnFolder).setOnClickListener {
            folderPicker.launch(null)
        }
        findViewById<Button>(R.id.btnNewChat).setOnClickListener {
            if (!running) {
                currentChatId = null
                engine.history.clear()
                adapter.items.clear()
                adapter.notifyDataSetChanged()
            }
        }

        updateWorkspaceLabel()
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
        "opencode" -> settings.opencodeApiKey.isNotBlank()
        else -> settings.openaiApiKey.isNotBlank()
    }

    private fun updateWorkspaceLabel() {
        findViewById<TextView>(R.id.workspaceLabel).text =
            "Воркспейс: ${Tools.workspacePath()}"
    }

    private fun loadChat(id: String) {
        val loaded = ChatStorage.load(this, id) ?: return
        currentChatId = id
        engine.history.clear()
        engine.history.addAll(loaded.first)
        adapter.items.clear()
        adapter.items.addAll(loaded.second)
        adapter.notifyDataSetChanged()
    }

    private fun saveCurrentChat() {
        val title = adapter.items.firstOrNull { it.role == "user" }
            ?.text?.take(60) ?: "Чат"
        currentChatId = ChatStorage.save(this, currentChatId, title, engine.history, adapter.items)
    }

    private fun runAgent(text: String) {
        running = true
        findViewById<Button>(R.id.btnSend).isEnabled = false

        adapter.items.add(ChatItem("user", text))
        val agentItem = ChatItem("agent", "")
        adapter.items.add(agentItem)
        adapter.notifyItemRangeInserted(adapter.items.size - 2, 2)

        lifecycleScope.launch {
            try {
                engine.run(
                    text,
                    onDelta = { delta ->
                        runOnUiThread {
                            agentItem.text += delta
                            adapter.notifyItemChanged(adapter.items.size - 1)
                        }
                    },
                    onToolResult = { line ->
                        runOnUiThread {
                            adapter.items.add(adapter.items.size - 1, ChatItem("tool", line))
                            adapter.notifyItemRangeInserted(adapter.items.size - 2, 1)
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
                    findViewById<Button>(R.id.btnSend).isEnabled = true
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
        holder.roleLabel.text = when (item.role) {
            "user" -> "Ты"
            "tool" -> "Инструмент"
            else -> "Agent"
        }
        if (item.role == "user") {
            holder.messageText.text = item.text.ifBlank { "…" }
        } else {
            markdown(holder.itemView.context).setMarkdown(holder.messageText, item.text.ifBlank { "…" })
        }
        val bg = when (item.role) {
            "tool" -> R.drawable.bg_tool
            else -> R.drawable.bg_input
        }
        holder.bubble.setBackgroundResource(bg)
        val params = holder.bubble.layoutParams as FrameLayout.LayoutParams
        params.gravity = if (item.role == "user") Gravity.END else Gravity.START
        holder.bubble.layoutParams = params
    }

    override fun getItemCount(): Int = items.size
}
