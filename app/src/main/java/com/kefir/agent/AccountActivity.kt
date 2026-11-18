package com.kefir.agent

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.AutoCompleteTextView
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
import java.net.URLEncoder

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
    private lateinit var etModel: AutoCompleteTextView
    private lateinit var btnRefreshModels: View
    private lateinit var spinnerOrg: Spinner
    private lateinit var spinnerModel: Spinner
    private lateinit var tvLoginStatus: TextView
    private lateinit var btnOpenLogin: Button
    private lateinit var btnGoogleLogin: Button

    private var loginJob: Job? = null
    private var modelsJob: Job? = null
    private var verificationUrl: String? = null
    private var orgs: List<OpenCodeOAuth.Org> = emptyList()
    private var models: List<String> = emptyList()
    private var keyLoadedProgrammatically = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_account)

        spinnerProvider = findViewById(R.id.spinnerProvider)
        secKeyBased = findViewById(R.id.secKeyBased)
        secOpencode = findViewById(R.id.secOpencode)
        etKey = findViewById(R.id.etAccountKey)
        etUrl = findViewById(R.id.etAccountUrl)
        etModel = findViewById(R.id.etAccountModel)
        btnRefreshModels = findViewById(R.id.btnRefreshModels)
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

        // When the API key field changes, auto-fetch the model list for the provider.
        etKey.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (keyLoadedProgrammatically) {
                    keyLoadedProgrammatically = false
                    return
                }
                scheduleModelFetch()
            }
        })
        btnRefreshModels.setOnClickListener { scheduleModelFetch(forceNetwork = true) }

        fun onProviderChanged() {
            val id = currentProviderId()
            val cur = SettingsStore.load(this)
            if (id == "opencode") {
                secKeyBased.visibility = View.GONE
                secOpencode.visibility = View.VISIBLE
                hint.text = "Вход через аккаунт OpenCode (Google sign-in). API-ключ вводить не нужно — браузер откроется автоматически."
                val connected = cur.opencodeAccessToken.isNotBlank()
                tvLoginStatus.text = if (connected) "✅ Аккаунт подключён (модель: ${cur.opencodeModel})" else "Не выполнен вход"
            } else {
                secKeyBased.visibility = View.VISIBLE
                secOpencode.visibility = View.GONE
                if (id == "gemini") {
                    keyLoadedProgrammatically = true
                    etKey.setText(cur.geminiApiKey)
                    etUrl.setText("https://generativelanguage.googleapis.com")
                    etUrl.isEnabled = false
                    etModel.setText(cur.geminiModel)
                    hint.text = "Вставь API-ключ Google Gemini (кнопка ниже откроет AI Studio, вход через Google-аккаунт). Модели подгрузятся с сервера автоматически."
                } else {
                    keyLoadedProgrammatically = true
                    etKey.setText(cur.openaiApiKey)
                    etUrl.setText(cur.openaiBaseUrl)
                    etUrl.isEnabled = true
                    etModel.setText(cur.openaiModel)
                    hint.text = "Любой OpenAI-совместимый API: base URL, API key и модель. Модели подгрузятся с сервера автоматически."
                }
                scheduleModelFetch()
            }
        }

        onProviderChanged()
        spinnerProvider.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>, v: View?, pos: Int, id: Long) = onProviderChanged()
            override fun onNothingSelected(p: android.widget.AdapterView<*>) {}
        }

        btnGoogleLogin.setOnClickListener { startOpenCodeLogin() }
        btnOpenLogin.setOnClickListener {
            verificationUrl?.let { url -> openBrowser(url) }
        }

        findViewById<Button>(R.id.btnGetKey).setOnClickListener {
            val url = when (currentProviderId()) {
                "gemini" -> "https://aistudio.google.com/apikey"
                "opencode" -> "https://opencode.ai/console"
                else -> "https://platform.openai.com/api-keys"
            }
            openBrowser(url)
        }

        findViewById<Button>(R.id.btnSaveAccount).setOnClickListener { save() }
    }

    private fun currentProviderId(): String =
        providers[spinnerProvider.selectedItemPosition.coerceAtLeast(0)].id

    /** Opens the system browser. Falls back to a chooser if needed. */
    private fun openBrowser(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
            try {
                val i = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(Intent.createChooser(i, "Открыть в браузере"))
            } catch (_: Exception) {
                Toast.makeText(this, "Браузер не найден. Открой вручную: $url", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun applyModels(list: List<String>) {
        val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, list)
        etModel.setAdapter(adapter)
        // Show the dropdown right away so the user sees choices immediately.
        if (etModel.hasFocus() && list.isNotEmpty()) etModel.showDropDown()
        // Style the selected model text as normal.
        etModel.setTextColor(0xFFE8EAED.toInt())
    }

    /**
     * Fetches the model list from the provider's server and fills the
     * dropdown. Debounced; forceNetwork=true bypasses the cache.
     */
    private fun scheduleModelFetch(forceNetwork: Boolean = false) {
        if (modelsJob?.isActive == true) {
            if (!forceNetwork) return // already fetching
            modelsJob?.cancel()
        }
        val id = currentProviderId()
        if (id == "opencode") return
        val key = etKey.text.toString().trim()
        val base = etUrl.text.toString().trim().ifBlank {
            if (id == "gemini") "https://generativelanguage.googleapis.com" else "https://api.openai.com/v1"
        }
        if (key.isBlank()) return
        modelsJob = lifecycleScope.launch {
            val res = ModelCatalog.fetch(id, key, base)
            if (!isActive) return@launch
            if (res.models.isNotEmpty()) {
                applyModels(res.models)
                if (!etModel.text.toString().trim().isBlank() && res.models.none { it == etModel.text.toString().trim() }) {
                    // keep user's manual value, dropdown just offers alternatives
                }
                Toast.makeText(this@AccountActivity, getString(R.string.models_loaded), Toast.LENGTH_SHORT).show()
            } else if (res.error != null) {
                Toast.makeText(
                    this@AccountActivity,
                    getString(R.string.models_error, res.error),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun startOpenCodeLogin() {
        if (loginJob?.isActive == true) return
        btnGoogleLogin.isEnabled = false
        tvLoginStatus.text = "Получаю код устройства…"
        lifecycleScope.launch {
            try {
                val start = OpenCodeOAuth.startDeviceFlow()
                verificationUrl = start.verificationUrl
                btnOpenLogin.text = if (start.userCode.isBlank()) getString(R.string.open_login)
                    else "Войти: код ${start.userCode}"
                btnOpenLogin.visibility = View.VISIBLE
                tvLoginStatus.text = "Открываю браузер… Если он не открылся — нажми кнопку входа ниже."

                // AUTO-OPEN the browser so the user doesn't have to tap anything.
                val target = if (start.userCode.isBlank()) start.verificationUrl
                    else {
                        // opencode console accepts ?code= so the code may be pre-filled;
                        // if not, it's shown on the page anyway.
                        start.verificationUrl + (if (start.verificationUrl.contains("?")) "&" else "?") +
                            "code=" + URLEncoder.encode(start.userCode, "UTF-8")
                    }
                openBrowser(target)

                var interval = start.intervalSec * 1000L
                val deadline = System.currentTimeMillis() + start.expiresSec * 1000L
                while (System.currentTimeMillis() < deadline && isActive) {
                    delay(interval)
                    when (val poll = OpenCodeOAuth.pollDeviceCode(start.deviceCode)) {
                        is OpenCodeOAuth.PollResult.Pending -> {}
                        is OpenCodeOAuth.PollResult.SlowDown -> interval += 5000
                        is OpenCodeOAuth.PollResult.Success -> {
                            SettingsStore.saveOpencodeTokens(applicationContext, poll.cred)
                            tvLoginStatus.text = "✅ Вход выполнен! Загружаю воркспейсы и модели…"
                            loadWorkspacesAndModels(poll.cred)
                            tvLoginStatus.text = "✅ Аккаунт OpenCode подключён!\nВыбери модель и нажми «Подключить»."
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
        val id = currentProviderId()
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
        modelsJob?.cancel()
    }
}
