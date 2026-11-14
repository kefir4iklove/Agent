package com.kefir.agent

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HistoryActivity : AppCompatActivity() {

    private lateinit var adapter: HistoryAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)

        adapter = HistoryAdapter { chat ->
            setResult(RESULT_OK, Intent().putExtra("chatId", chat.id))
            finish()
        }
        val list = findViewById<RecyclerView>(R.id.historyList)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        refresh()
    }

    private fun refresh() {
        adapter.items.clear()
        adapter.items.addAll(ChatStorage.list(this))
        adapter.notifyDataSetChanged()
        findViewById<TextView>(R.id.emptyLabel).visibility =
            if (adapter.items.isEmpty()) View.VISIBLE else View.GONE
    }

    class HistoryAdapter(val onClick: (ChatStorage.SavedChat) -> Unit) :
        RecyclerView.Adapter<HistoryAdapter.Holder>() {

        val items = mutableListOf<ChatStorage.SavedChat>()
        private val fmt = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())

        class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val title: TextView = view.findViewById(R.id.chatTitle)
            val date: TextView = view.findViewById(R.id.chatDate)
            val delete: Button = view.findViewById(R.id.btnDeleteChat)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_chat, parent, false)
            return Holder(view)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val chat = items[position]
            holder.title.text = chat.title
            holder.date.text = fmt.format(Date(chat.updated))
            holder.itemView.setOnClickListener { onClick(chat) }
            holder.delete.setOnClickListener { view ->
                ChatStorage.delete(view.context, chat.id)
                val pos = holder.bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    items.removeAt(pos)
                    notifyItemRemoved(pos)
                }
            }
        }

        override fun getItemCount(): Int = items.size
    }
}
