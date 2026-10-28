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
import com.iskezis.myst.data.account.AccountRepository
import com.iskezis.myst.databinding.ActivityYoutubeLoginBinding

class YouTubeLoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityYoutubeLoginBinding
    private val handler = Handler(Looper.getMainLooper())
    private var finished = false

    private val cookiePoll = object : Runnable {
        override fun run() {
            checkCookies()
            if (!finished) handler.postDelayed(this, 1500)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityYoutubeLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }

        binding.loginBack.setOnClickListener { finish() }
        binding.loginRetry.setOnClickListener {
            binding.loginErrorRoot.isVisible = false
            binding.loginWebView.isVisible = true
            binding.loginProgress.isVisible = true
            binding.loginWebView.reload()
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.loginWebView.canGoBack()) {
                    binding.loginWebView.goBack()
                } else {
                    finish()
                }
            }
        })

        setupWebView()
        handler.postDelayed(cookiePoll, 1500)
    }

    private fun setupWebView() {
        val webView = binding.loginWebView
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
                binding.loginProgress.isVisible = true
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                binding.loginProgress.isVisible = false
                checkCookies()
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?,
            ) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
                    showError()
                }
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

    private fun checkCookies() {
        if (finished) return
        val currentUrl = binding.loginWebView.url ?: return
        if (!currentUrl.contains("music.youtube.com")) return
        val cookies = CookieManager.getInstance().getCookie("https://music.youtube.com")
        if (cookies.isNullOrBlank()) return
        if (AccountRepository.extractSapisid(cookies) == null) return
        finished = true
        handler.removeCallbacks(cookiePoll)
        CookieManager.getInstance().flush()
        setResult(
            RESULT_OK,
            Intent().putExtra(EXTRA_COOKIES, cookies),
        )
        finish()
    }

    private fun showError() {
        binding.loginProgress.isVisible = false
        binding.loginWebView.isVisible = false
        binding.loginErrorRoot.isVisible = true
    }

    override fun onDestroy() {
        finished = true
        handler.removeCallbacks(cookiePoll)
        binding.loginWebView.apply {
            loadUrl("about:blank")
            clearHistory()
            destroy()
        }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_COOKIES = "extra_youtube_cookies"
        private const val START_URL =
            "https://accounts.google.com/ServiceLogin?continue=https%3A%2F%2Fmusic.youtube.com%2F"
    }
}
