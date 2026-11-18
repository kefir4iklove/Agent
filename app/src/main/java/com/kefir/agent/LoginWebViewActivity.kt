package com.kefir.agent

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * In-app fallback browser: used when the device has no browser app that can
 * open ACTION_VIEW links ("нет поддерживаемых приложений").
 */
class LoginWebViewActivity : AppCompatActivity() {

    private lateinit var web: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login_web)

        val url = intent.getStringExtra("url") ?: "https://opencode.ai/console"
        web = findViewById(R.id.loginWeb)
        val progress = findViewById<TextView>(R.id.loginProgress)
        val btnCopy = findViewById<ImageButton>(R.id.btnLoginCopy)
        val btnExternal = findViewById<ImageButton>(R.id.btnLoginExternal)

        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                return false // keep auth inside this WebView
            }
            override fun onPageFinished(view: WebView, urlString: String) {
                progress.visibility = View.GONE
            }
        }
        progress.visibility = View.VISIBLE
        if (savedInstanceState == null) web.loadUrl(url)

        findViewById<android.widget.Button>(R.id.btnLoginDone).setOnClickListener { finish() }

        btnCopy.setOnClickListener {
            val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("url", url))
            Toast.makeText(this, "Ссылка скопирована", Toast.LENGTH_SHORT).show()
        }
        btnExternal.setOnClickListener {
            try {
                startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)))
            } catch (_: Exception) {
                Toast.makeText(this, "Браузера нет — входи здесь, внутри окна", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }
}
