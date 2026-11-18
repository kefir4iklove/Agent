package com.kefir.agent

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class AccountActivity : AppCompatActivity() {

    private data class Provider(val id: String, val label: String)

    private val providers = listOf(
        Provider("gemini", "Google Gemini"),
        Provider("opencode", "OpenCode (вход через Google)"),
        Provider("openai", "OpenAI-совместимый API")
    )

    private lateinit var spinnerProvider: Spinner
    private lateinit var secKeyBased: LinearLayout
    private lateinit var secOpencode: LinearLayout
    private lateinit var etKey: EditText
    private lateinit var etUrl: EditText
    private lateinit var etModel: EditText
    private lateinit var spinnerOrg: Spinner
    private lateinit var spinnerModel: Spinner
    private lateinit var tvLoginStatus: TextView
    private lateinit var btnOpenLogin: Button
    private lateinit var btnGoogleLogin: Button

    private var loginJob: Job? = null
    private var verificationUrl: String? = null
    private var orgs: List<OpenCodeOAuth.Org> = emptyList()
    private var models: List<String> = emptyList()
    private var loggedIn = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_account)

        spinnerProvider = findViewById(R.id.spinnerProvider)
        secKeyBased = findViewById(R.id.secKeyBased)
        secOpencode = findViewById(R.id.secOpencode)
        etKey = findViewById(R.id.etAccountKey)
        etUrl = findViewById(R.id.etAccountUrl)
        etModel = findViewById(R.id.etAccountModel)
        spinnerOrg = findViewById(R.id.spinnerOrg)
        spinnerModel = findViewById(R.id.spinnerModel)
        tvLoginStatus = findViewById(R.id.tvLoginStatus)
        btnOpenLogin = findViewById(R.id.btnOpenLogin)
        btnGoogleLogin = findViewById(R.id.btnGoogleLogin)
        val hint = findViewById<TextView>(R.id.accountHint)

        spinnerProvider.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, providers.map { it.label }
        )

        val s = SettingsStore.load(this)
        spinnerProvider.setSelection(providers.indexOfFirst { it.id == s.provider }.coerceAtLeast(0))
        etModel.setText(s.opencodeModel)

        fun onProviderChanged() {
            val id = providers[spinnerProvider.selectedItemPosition].id
            val cur = SettingsStore.load(this)
            if (id == "opencode") {
                secKeyBased.visibility = View.GONE
                secOpencode.visibility = View.VISIBLE
                hint.text = "Вход через аккаунт OpenCode (Google sign-in). API-ключ вводить не нужно — токен придёт автоматически."
                val connected = cur.opencodeAccessToken.isNotBlank()
                tvLoginStatus.text = if (connected) "✅ Аккаунт подключён (модель: ${cur.opencodeModel})" else "Не выполнен вход"
                loggedIn = connected
            } else {
                secKeyBased.visibility = View.VISIBLE
                secOpencode.visibility = View.GONE
                if (id == "gemini") {
                    etKey.setText(cur.geminiApiKey)
                    etUrl.setText("https://generativelanguage.googleapis.com")
                    etUrl.isEnabled = false
                    etModel.setText(cur.geminiModel)
                    hint.text = "Вставь API-ключ Google Gemini (кнопка ниже откроет AI Studio, вход через Google-аккаунт)."
                } else {
                    etKey.setText(cur.openaiApiKey)
                    etUrl.setText(cur.openaiBaseUrl)
                    etUrl.isEnabled = true
                    etModel.setText(cur.openaiModel)
                    hint.text = "Любой OpenAI-совместимый API: base URL, API key и модель."
                }
            }
        }

        onProviderChanged()
        spinnerProvider.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>, v: View?, pos: Int, id: Long) = onProviderChanged()
            override fun onNothingSelected(p: android.widget.AdapterView<*>) {}
        }

        btnGoogleLogin.setOnClickListener { startOpenCodeLogin() }
        btnOpenLogin.setOnClickListener {
            verificationUrl?.let { url ->
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                } catch (_: Exception) {
                    Toast.makeText(this, "Нет браузера", Toast.LENGTH_SHORT).show()
                }
            }
        }

        findViewById<Button>(R.id.btnGetKey).setOnClickListener {
            val id = providers[spinnerProvider.selectedItemPosition].id
            val url = when (id) {
                "gemini" -> "https://aistudio.google.com/apikey"
                "opencode" -> "https://opencode.ai/console"
                else -> "https://platform.openai.com/api-keys"
            }
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            } catch (_: Exception) {
                Toast.makeText(this, "Нет браузера", Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<Button>(R.id.btnSaveAccount).setOnClickListener { save() }
    }

    private fun startOpenCodeLogin() {
        if (loginJob?.isActive == true) return
        btnGoogleLogin.isEnabled = false
        tvLoginStatus.text = "Получаю код устройства…"
        lifecycleScope.launch {
            try {
                val start = OpenCodeOAuth.startDeviceFlow()
                verificationUrl = start.verificationUrl
                btnOpenLogin.text = if (start.userCode.isBlank()) "Открыть страницу входа"
                else "Войти: код ${start.userCode}"
                btnOpenLogin.visibility = View.VISIBLE
                tvLoginStatus.text = "Открой страницу входа и войди через Google — жду подтверждение…"

                var interval = start.intervalSec * 1000L
                val deadline = System.currentTimeMillis() + start.expiresSec * 1000L
                while (System.currentTimeMillis() < deadline && isActive) {
                    delay(interval)
                    when (val poll = OpenCodeOAuth.pollDeviceCode(start.deviceCode)) {
                        is OpenCodeOAuth.PollResult.Pending -> {}
                        is OpenCodeOAuth.PollResult.SlowDown -> interval += 5000
                        is OpenCodeOAuth.PollResult.Success -> {
                            SettingsStore.saveOpencodeTokens(applicationContext, poll.cred)
                            loggedIn = true
                            tvLoginStatus.text = "✅ Вход выполнен. Загружаю воркспейсы…"
                            loadWorkspacesAndModels(poll.cred)
                            tvLoginStatus.text = "✅ Аккаунт OpenCode подключён!\nТеперь нажми «Подключить»."
                            btnGoogleLogin.isEnabled = true
                            break
                        }
                        is OpenCodeOAuth.PollResult.Failed -> {
                            tvLoginStatus.text = "❌ Ошибка входа: ${poll.message}"
                            btnGoogleLogin.isEnabled = true
                            break
                        }
                    }
                }
            } catch (e: Exception) {
                tvLoginStatus.text = "❌ Ошибка: ${e.message ?: e}"
                btnGoogleLogin.isEnabled = true
            }
        }
    }

    private suspend fun loadWorkspacesAndModels(cred: OpenCodeOAuth.Credential) {
        try {
            orgs = OpenCodeOAuth.orgs(cred.access)
            if (orgs.isEmpty()) return
            if (orgs.size == 1) {
                SettingsStore.save(this, SettingsStore.load(this).copy(opencodeOrgId = orgs[0].id))
                spinnerOrg.visibility = View.GONE
            } else {
                spinnerOrg.visibility = View.VISIBLE
                spinnerOrg.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, orgs.map { it.name })
                val saved = SettingsStore.load(this).opencodeOrgId
                val idx = orgs.indexOfFirst { it.id == saved }.coerceAtLeast(0)
                spinnerOrg.setSelection(idx)
            }
            val activeOrg = orgs[spinnerOrg.selectedItemPosition.coerceAtLeast(0)].id
            models = OpenCodeOAuth.models(cred.access, activeOrg)
            if (models.isNotEmpty()) {
                spinnerModel.visibility = View.VISIBLE
                spinnerModel.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, models)
                val savedModel = SettingsStore.load(this).opencodeModel
                val idx = models.indexOf(savedModel).coerceAtLeast(0)
                spinnerModel.setSelection(idx)
            }
        } catch (_: Exception) {
        }
    }

    private fun save() {
        val id = providers[spinnerProvider.selectedItemPosition].id
        val old = SettingsStore.load(this)
        val updated = when (id) {
            "gemini" -> old.copy(
                provider = "gemini",
                geminiApiKey = etKey.text.toString().trim(),
                geminiModel = etModel.text.toString().trim().ifBlank { "gemini-2.0-flash" }
            )
            "opencode" -> {
                var s = old.copy(provider = "opencode")
                if (spinnerOrg.visibility == View.VISIBLE && orgs.isNotEmpty()) {
                    s = s.copy(opencodeOrgId = orgs[spinnerOrg.selectedItemPosition].id)
                }
                if (spinnerModel.visibility == View.VISIBLE && models.isNotEmpty()) {
                    s = s.copy(opencodeModel = models[spinnerModel.selectedItemPosition])
                }
                s.copy(opencodeBaseUrl = OpenCodeOAuth.INFERENCE_URL)
            }
            else -> old.copy(
                provider = "openai",
                openaiApiKey = etKey.text.toString().trim(),
                openaiBaseUrl = etUrl.text.toString().trim().ifBlank { "https://api.openai.com/v1" },
                openaiModel = etModel.text.toString().trim().ifBlank { "gpt-4o-mini" }
            )
        }
        SettingsStore.save(this, updated)
        Toast.makeText(this, "Готово!", Toast.LENGTH_SHORT).show()
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        loginJob?.cancel()
    }
}
