package com.kefir.agent

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class ApiKeysActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_api_keys)

        val s = SettingsStore.load(this)
        val etGeminiKey = findViewById<EditText>(R.id.etGeminiKey)
        val etGeminiModel = findViewById<EditText>(R.id.etGeminiModel)
        val etOpencodeUrl = findViewById<EditText>(R.id.etOpencodeUrl)
        val etOpencodeKey = findViewById<EditText>(R.id.etOpencodeKey)
        val etOpencodeModel = findViewById<EditText>(R.id.etOpencodeModel)
        val etOpenaiUrl = findViewById<EditText>(R.id.etOpenaiUrl)
        val etOpenaiKey = findViewById<EditText>(R.id.etOpenaiKey)
        val etOpenaiModel = findViewById<EditText>(R.id.etOpenaiModel)

        etGeminiKey.setText(s.geminiApiKey)
        etGeminiModel.setText(s.geminiModel)
        etOpencodeUrl.setText(s.opencodeBaseUrl)
        etOpencodeKey.setText(s.opencodeApiKey)
        etOpencodeModel.setText(s.opencodeModel)
        etOpenaiUrl.setText(s.openaiBaseUrl)
        etOpenaiKey.setText(s.openaiApiKey)
        etOpenaiModel.setText(s.openaiModel)

        findViewById<Button>(R.id.btnSaveKeys).setOnClickListener {
            SettingsStore.save(
                this,
                s.copy(
                    geminiApiKey = etGeminiKey.text.toString().trim(),
                    geminiModel = etGeminiModel.text.toString().trim().ifBlank { "gemini-2.0-flash" },
                    opencodeBaseUrl = etOpencodeUrl.text.toString().trim().ifBlank { OpenCodeOAuth.INFERENCE_URL },
                    opencodeApiKey = etOpencodeKey.text.toString().trim(),
                    opencodeModel = etOpencodeModel.text.toString().trim().ifBlank { "claude-sonnet-4" },
                    openaiBaseUrl = etOpenaiUrl.text.toString().trim().ifBlank { "https://api.openai.com/v1" },
                    openaiApiKey = etOpenaiKey.text.toString().trim(),
                    openaiModel = etOpenaiModel.text.toString().trim().ifBlank { "gpt-4o-mini" }
                )
            )
            Toast.makeText(this, "Ключи сохранены", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
