package com.kefir.agent

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
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch

data class ChatItem(val role: String, var text: String)

class MainActivity : AppCompatActivity() {

    private lateinit var adapter: ChatAdapter
    private lateinit var engine: AgentEngine
    private lateinit var settings: AgentSettings
    private val llm = LlmClient()
    private var running = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        Tools.init(this)
        settings = SettingsStore.load(this)
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
            if (settings.provider == "gemini" && settings.geminiApiKey.isBlank()) {
                Toast.makeText(this, "Сначала укажи API ключ в настройках", Toast.LENGTH_LONG).show()
                openSettings()
                return@setOnClickListener
            }
            input.setText("")
            runAgent(text)
        }

        findViewById<Button>(R.id.btnSettings).setOnClickListener { openSettings() }
        findViewById<Button>(R.id.btnNewChat).setOnClickListener {
            if (!running) {
                engine.history.clear()
                adapter.items.clear()
                adapter.notifyDataSetChanged()
            }
        }
    }

    private fun openSettings() {
        startActivity(android.content.Intent(this, SettingsActivity::class.java))
    }

    override fun onResume() {
        super.onResume()
        settings = SettingsStore.load(this)
        engine = AgentEngine(settings, llm)
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
                    running = false
                    findViewById<Button>(R.id.btnSend).isEnabled = true
                }
            }
        }
    }
}

class ChatAdapter : RecyclerView.Adapter<ChatAdapter.Holder>() {

    val items = mutableListOf<ChatItem>()

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
        holder.messageText.text = item.text.ifBlank { "…" }
        val bg = when (item.role) {
            "user" -> R.drawable.bg_input
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
