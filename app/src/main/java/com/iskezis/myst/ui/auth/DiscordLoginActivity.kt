package com.iskezis.myst.ui.auth

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import com.iskezis.myst.R
import com.iskezis.myst.databinding.ActivityDiscordLoginBinding
import org.json.JSONTokener

class DiscordLoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDiscordLoginBinding
    private val handler = Handler(Looper.getMainLooper())
    private var finished = false

    private val tokenPoll = object : Runnable {
        override fun run() {
            checkToken()
            if (!finished) handler.postDelayed(this, 1500)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDiscordLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }

        binding.discordBack.setOnClickListener { finish() }
        binding.discordRetry.setOnClickListener {
            binding.discordErrorRoot.isVisible = false
            binding.discordWebView.isVisible = true
            binding.discordProgress.isVisible = true
            binding.discordWebView.reload()
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.discordWebView.canGoBack()) {
                    binding.discordWebView.goBack()
                } else {
                    finish()
                }
            }
        })

        setupWebView()
        handler.postDelayed(tokenPoll, 1500)
    }

    private fun setupWebView() {
        val webView = binding.discordWebView
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            setSupportMultipleWindows(false)
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            userAgentString =
                "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                binding.discordProgress.isVisible = true
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                binding.discordProgress.isVisible = false
                checkToken()
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?,
            ) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) showError()
            }

            @Deprecated("Compatibilidade com API 21/22")
            override fun onReceivedError(
                view: WebView?,
                errorCode: Int,
                description: String?,
                failingUrl: String?,
            ) {
                super.onReceivedError(view, errorCode, description, failingUrl)
                showError()
            }
        }
        webView.loadUrl(START_URL)
    }

    private fun checkToken() {
        if (finished) return
        val url = binding.discordWebView.url ?: return
        if (!url.contains("discord.com/channels") && !url.contains("discord.com/app")) return
        binding.discordWebView.evaluateJavascript(TOKEN_JS) { raw ->
            val token = parseToken(raw) ?: return@evaluateJavascript
            finished = true
            handler.removeCallbacks(tokenPoll)
            CookieManager.getInstance().flush()
            setResult(RESULT_OK, Intent().putExtra(EXTRA_TOKEN, token))
            finish()
        }
    }

    private fun parseToken(raw: String?): String? {
        if (raw == null) return null
        val value = try {
            JSONTokener(raw).nextValue() as? String
        } catch (t: Throwable) {
            null
        } ?: return null
        val token = value.trim()
        if (token.length < 40) return null
        if (token.count { it == '.' } != 2) return null
        return token
    }

    private fun showError() {
        binding.discordProgress.isVisible = false
        binding.discordWebView.isVisible = false
        binding.discordErrorRoot.isVisible = true
    }

    override fun onDestroy() {
        finished = true
        handler.removeCallbacks(tokenPoll)
        binding.discordWebView.apply {
            loadUrl("about:blank")
            clearHistory()
            destroy()
        }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_TOKEN = "extra_discord_token"
        private const val START_URL = "https://discord.com/login"
        private const val TOKEN_JS =
            "(function(){try{var t=window.localStorage.getItem('token');return t||'';}catch(e){return '';}})()"
    }
}
