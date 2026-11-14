package com.kefir.agent

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val spinner = findViewById<Spinner>(R.id.spinnerProvider)
        val providers = listOf("gemini" to "Google Gemini (API key)", "opencode" to "OpenCode (аккаунт/API key)", "openai" to "OpenAI-совместимый API")
        spinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            providers.map { it.second }
        )

        val s = SettingsStore.load(this)
        spinner.setSelection(providers.indexOfFirst { it.first == s.provider }.coerceAtLeast(0))
        findViewById<EditText>(R.id.etGeminiKey).setText(s.geminiApiKey)
        findViewById<EditText>(R.id.etGeminiModel).setText(s.geminiModel)
        findViewById<EditText>(R.id.etOpencodeUrl).setText(s.opencodeBaseUrl)
        findViewById<EditText>(R.id.etOpencodeKey).setText(s.opencodeApiKey)
        findViewById<EditText>(R.id.etOpencodeModel).setText(s.opencodeModel)
        findViewById<EditText>(R.id.etOpenaiUrl).setText(s.openaiBaseUrl)
        findViewById<EditText>(R.id.etOpenaiKey).setText(s.openaiApiKey)
        findViewById<EditText>(R.id.etOpenaiModel).setText(s.openaiModel)

        findViewById<Button>(R.id.btnSave).setOnClickListener {
            SettingsStore.save(
                this,
                AgentSettings(
                    provider = providers[spinner.selectedItemPosition].first,
                    geminiApiKey = findViewById<EditText>(R.id.etGeminiKey).text.toString().trim(),
                    geminiModel = findViewById<EditText>(R.id.etGeminiModel).text.toString().trim().ifBlank { "gemini-2.0-flash" },
                    opencodeBaseUrl = findViewById<EditText>(R.id.etOpencodeUrl).text.toString().trim().ifBlank { "https://opencode.ai/zen/v1" },
                    opencodeApiKey = findViewById<EditText>(R.id.etOpencodeKey).text.toString().trim(),
                    opencodeModel = findViewById<EditText>(R.id.etOpencodeModel).text.toString().trim().ifBlank { "claude-sonnet-4" },
                    openaiBaseUrl = findViewById<EditText>(R.id.etOpenaiUrl).text.toString().trim().ifBlank { "https://api.openai.com/v1" },
                    openaiApiKey = findViewById<EditText>(R.id.etOpenaiKey).text.toString().trim(),
                    openaiModel = findViewById<EditText>(R.id.etOpenaiModel).text.toString().trim().ifBlank { "gpt-4o-mini" }
                )
            )
            Toast.makeText(this, "Сохранено", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
