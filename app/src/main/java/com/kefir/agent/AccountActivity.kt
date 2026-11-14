package com.kefir.agent

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class AccountActivity : AppCompatActivity() {

    private val providers = listOf(
        Provider("gemini", "Google Gemini", "https://aistudio.google.com/apikey"),
        Provider("opencode", "OpenCode", "https://opencode.ai"),
        Provider("openai", "OpenAI-совместимый API", "https://platform.openai.com/api-keys")
    )

    data class Provider(val id: String, val label: String, val keysUrl: String)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_account)

        val spinner = findViewById<Spinner>(R.id.spinnerProvider)
        spinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            providers.map { it.label }
        )

        val etKey = findViewById<EditText>(R.id.etAccountKey)
        val etUrl = findViewById<EditText>(R.id.etAccountUrl)
        val etModel = findViewById<EditText>(R.id.etAccountModel)
        val hint = findViewById<TextView>(R.id.accountHint)

        fun applyDefaults(index: Int) {
            val s = SettingsStore.load(this)
            when (providers[index].id) {
                "gemini" -> {
                    etKey.setText(s.geminiApiKey)
                    etUrl.isEnabled = false
                    etUrl.setText("https://generativelanguage.googleapis.com")
                    etModel.setText(s.geminiModel)
                    hint.text = "Вставь API-ключ Google Gemini. Аккаунт Google подключается при получении ключа на AI Studio."
                }
                "opencode" -> {
                    etKey.setText(s.opencodeApiKey)
                    etUrl.isEnabled = true
                    etUrl.setText(s.opencodeBaseUrl)
                    etModel.setText(s.opencodeModel)
                    hint.text = "Войди в аккаунт OpenCode на сайте, получи API key и вставь сюда. Base URL можно не менять."
                }
                else -> {
                    etKey.setText(s.openaiApiKey)
                    etUrl.isEnabled = true
                    etUrl.setText(s.openaiBaseUrl)
                    etModel.setText(s.openaiModel)
                    hint.text = "Любой OpenAI-совместимый API: укажи base URL, API key и модель."
                }
            }
        }

        val initial = providers.indexOfFirst { it.id == SettingsStore.load(this).provider }
        spinner.setSelection(initial.coerceAtLeast(0))
        applyDefaults(initial.coerceAtLeast(0))
        spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>, v: View?, pos: Int, id: Long) = applyDefaults(pos)
            override fun onNothingSelected(p: android.widget.AdapterView<*>) {}
        }

        findViewById<Button>(R.id.btnGetKey).setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(providers[spinner.selectedItemPosition].keysUrl)))
            } catch (_: Exception) {
                Toast.makeText(this, "Нет браузера", Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<Button>(R.id.btnSaveAccount).setOnClickListener {
            val pos = spinner.selectedItemPosition
            val old = SettingsStore.load(this)
            val updated = when (providers[pos].id) {
                "gemini" -> old.copy(
                    provider = "gemini",
                    geminiApiKey = etKey.text.toString().trim(),
                    geminiModel = etModel.text.toString().trim().ifBlank { "gemini-2.0-flash" }
                )
                "opencode" -> old.copy(
                    provider = "opencode",
                    opencodeApiKey = etKey.text.toString().trim(),
                    opencodeBaseUrl = etUrl.text.toString().trim().ifBlank { "https://opencode.ai/zen/v1" },
                    opencodeModel = etModel.text.toString().trim().ifBlank { "claude-sonnet-4" }
                )
                else -> old.copy(
                    provider = "openai",
                    openaiApiKey = etKey.text.toString().trim(),
                    openaiBaseUrl = etUrl.text.toString().trim().ifBlank { "https://api.openai.com/v1" },
                    openaiModel = etModel.text.toString().trim().ifBlank { "gpt-4o-mini" }
                )
            }
            SettingsStore.save(this, updated)
            Toast.makeText(this, "Аккаунт подключён: ${providers[pos].label}", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
