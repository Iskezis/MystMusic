package com.iskezis.myst.ui.auth

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.JsResult
import android.webkit.WebChromeClient
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
import com.iskezis.myst.util.AppLog

/**
 * Login do Discord para o Rich Presence.
 *
 * O token fica em `localStorage.token` como uma string JSON, entre aspas. O
 * `evaluateJavascript` devolve essa string com as aspas; enviá-las no header
 * `Authorization` faz o Discord responder HTTP 401. O código abaixo remove as
 * aspas antes de salvar. Além disso, o cliente do Discord remove o acesso ao
 * `localStorage` da janela principal depois que o app web inicia, então a
 * leitura é feita dentro de um iframe same-origin novo, que ainda enxerga o
 * armazenamento real; o valor chega por `alert` — o mesmo método usado pelo
 * KizzyRPC/BitChord. O token nunca é gravado no Logcat.
 */
class DiscordLoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDiscordLoginBinding
    private val handler = Handler(Looper.getMainLooper())
    private var finished = false
    private var captured = false
    private var awaitingToken = false
    private var attempts = 0

    private val harvestTimeout = Runnable {
        if (finished || captured || !awaitingToken) return@Runnable
        awaitingToken = false
        if (attempts < MAX_TOKEN_ATTEMPTS) {
            handler.postDelayed({ harvestToken() }, TOKEN_RETRY_MS)
        } else {
            failTokenExtraction()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLog.i(AppLog.TAG_AUTH, "DiscordLoginActivity.onCreate")
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
            attempts = 0
            captured = false
            awaitingToken = false
            binding.discordWebView.reload()
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!binding.discordErrorRoot.isVisible && binding.discordWebView.canGoBack()) {
                    binding.discordWebView.goBack()
                } else {
                    finish()
                }
            }
        })

        setupWebView()
    }

    private fun setupWebView() {
        val webView = binding.discordWebView
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            setSupportMultipleWindows(false)
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            userAgentString = browserUserAgent(WebSettings.getDefaultUserAgent(this@DiscordLoginActivity))
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                binding.discordProgress.isVisible = true
                binding.discordWebView.isVisible = true
                binding.discordErrorRoot.isVisible = false
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                binding.discordProgress.isVisible = false
                if (isSignedIn(url)) harvestToken()
            }

            /**
             * O cliente do Discord troca de tela com `pushState`, sem uma
             * navegação real. `shouldOverrideUrlLoading` e `onPageFinished`
             * sozinhos podem perder o momento em que o token aparece.
             */
            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                super.doUpdateVisitedHistory(view, url, isReload)
                if (isSignedIn(url)) harvestToken()
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?,
            ): Boolean {
                if (isSignedIn(request?.url?.toString())) harvestToken()
                return false
            }

            @Deprecated("Compatibilidade com API 21/22")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                if (isSignedIn(url)) harvestToken()
                return false
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?,
            ) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
                    showError(R.string.discord_login_loading)
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
                showError(R.string.discord_login_loading)
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onJsAlert(
                view: WebView?,
                url: String?,
                message: String?,
                result: JsResult?,
            ): Boolean {
                // Se o alerta atrasar além do timeout, ainda assim não deixe
                // um token aparecer em um diálogo do WebView.
                val lateToken = if (!awaitingToken) parseToken(message) else null
                if (lateToken != null) {
                    result?.confirm()
                    if (!finished && !captured) {
                        captured = true
                        finishWithToken(lateToken)
                    }
                    return true
                }
                // Não engole alertas legítimos do Discord: só intercepta a
                // resposta da leitura que nós mesmos disparamos.
                if (!awaitingToken) return super.onJsAlert(view, url, message, result)
                awaitingToken = false
                handler.removeCallbacks(harvestTimeout)
                result?.confirm()
                if (finished || captured) return true

                val token = parseToken(message)
                if (token == null) {
                    if (attempts < MAX_TOKEN_ATTEMPTS) {
                        handler.postDelayed({ harvestToken() }, TOKEN_RETRY_MS)
                    } else {
                        failTokenExtraction()
                    }
                    return true
                }
                captured = true
                finishWithToken(token)
                return true
            }
        }

        webView.loadUrl(START_URL)
    }

    private fun harvestToken() {
        if (finished || captured || awaitingToken) return
        if (!isSignedIn(binding.discordWebView.url)) return
        if (attempts >= MAX_TOKEN_ATTEMPTS) {
            failTokenExtraction()
            return
        }
        attempts++
        AppLog.d(
            AppLog.TAG_AUTH,
            "Discord: leitura do token, tentativa $attempts/$MAX_TOKEN_ATTEMPTS",
        )
        awaitingToken = true
        handler.removeCallbacks(harvestTimeout)
        handler.postDelayed(harvestTimeout, TOKEN_ALERT_TIMEOUT_MS)
        binding.discordWebView.evaluateJavascript(TOKEN_SNIPPET, null)
    }

    private fun parseToken(message: String?): String? {
        var token = message?.trim().orEmpty()
        if (token.isEmpty() || token == "null" || token == "undefined") return null
        if (token.length >= 2 && token.startsWith("\"") && token.endsWith("\"")) {
            token = token.substring(1, token.length - 1)
        }
        if (token.length < 40) return null
        if (token.any { it.isWhitespace() }) return null
        // Tokens normais têm 2 pontos; tokens com 2FA começam com "mfa." e
        // têm 3. Aceitar qualquer coisa a partir de 2 evita rejeitar 2FA.
        if (token.count { it == '.' } < 2) return null
        return token
    }

    private fun finishWithToken(token: String) {
        finished = true
        handler.removeCallbacksAndMessages(null)
        CookieManager.getInstance().flush()
        AppLog.i(
            AppLog.TAG_AUTH,
            "Discord: token capturado (${token.length} caracteres, " +
                "pontos=${token.count { it == '.' }}, mfa=${token.startsWith("mfa.")})",
        )
        setResult(RESULT_OK, Intent().putExtra(EXTRA_TOKEN, token))
        finish()
    }

    private fun failTokenExtraction() {
        awaitingToken = false
        handler.removeCallbacks(harvestTimeout)
        AppLog.w(
            AppLog.TAG_AUTH,
            "Discord: token não apareceu após $attempts tentativas",
        )
        showError(R.string.discord_login_token_error)
    }

    private fun showError(messageRes: Int) {
        binding.discordProgress.isVisible = false
        binding.discordWebView.isVisible = false
        binding.discordErrorText.setText(messageRes)
        binding.discordErrorRoot.isVisible = true
    }

    override fun onDestroy() {
        finished = true
        handler.removeCallbacksAndMessages(null)
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
        private const val MAX_TOKEN_ATTEMPTS = 12
        private const val TOKEN_RETRY_MS = 500L
        private const val TOKEN_ALERT_TIMEOUT_MS = 2_000L
        private val WEBVIEW_VERSION = Regex("""Version/\d+(\.\d+)*\s*""")
        private const val FALLBACK_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; SM-S921U; Build/UP1A.231005.007) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.0.0 Mobile Safari/537.36"

        /**
         * O iframe é essencial: o Discord remove o acesso ao localStorage da
         * janela principal depois de inicializar. O `alert` entrega o valor
         * sem precisar de `addJavascriptInterface`.
         */
        private const val TOKEN_SNIPPET = """
            (function () {
              try {
                var frame = document.createElement('iframe');
                document.body.appendChild(frame);
                var raw = frame.contentWindow.localStorage.token;
                document.body.removeChild(frame);
                if (!raw) { alert(''); return; }
                if (raw.charAt(0) === '"' && raw.charAt(raw.length - 1) === '"') {
                  raw = raw.slice(1, -1);
                }
                alert(raw);
              } catch (e) {
                alert('');
              }
            })()
        """

        /**
         * Remove os marcadores de WebView que o Discord interpreta como um
         * "Android Browser 4.0" sem suporte, o que faz a página de login ficar
         * em branco em alguns aparelhos.
         */
        private fun browserUserAgent(platformUserAgent: String?): String {
            val stripped = platformUserAgent
                ?.replace("; wv", "")
                ?.replace(WEBVIEW_VERSION, "")
                ?.replace("  ", " ")
                ?.trim()
            return stripped?.takeIf { it.contains("Chrome/") } ?: FALLBACK_USER_AGENT
        }

        private fun isSignedIn(url: String?): Boolean =
            url != null &&
                url.startsWith("https://discord.com/") &&
                !url.contains("/login") &&
                !url.contains("/register")
    }
}
