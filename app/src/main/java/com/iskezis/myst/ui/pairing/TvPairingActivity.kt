package com.iskezis.myst.ui.pairing

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.widget.Toast
import com.iskezis.myst.ui.common.BaseActivity
import com.iskezis.myst.util.MystTheme
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.iskezis.myst.MainActivity
import com.iskezis.myst.MystApp
import com.iskezis.myst.R
import com.iskezis.myst.data.pairing.LocalPairingServer
import com.iskezis.myst.data.pairing.PairAck
import com.iskezis.myst.data.pairing.PairConfirm
import com.iskezis.myst.data.pairing.PairPayload
import com.iskezis.myst.data.pairing.PairRequest
import com.iskezis.myst.data.pairing.PairingCrypto
import com.iskezis.myst.data.pairing.PairingHttp
import com.iskezis.myst.data.pairing.PairingNsd
import com.iskezis.myst.data.pairing.PairingSync
import com.iskezis.myst.databinding.ActivityTvPairingBinding
import com.iskezis.myst.util.AppLog
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * TV: anuncia o serviço na rede, recebe a solicitação do celular e exige o
 * código de 5 dígitos antes de baixar a conta/playlists cifradas.
 */
class TvPairingActivity : BaseActivity() {

    private lateinit var binding: ActivityTvPairingBinding
    private val app by lazy { application as MystApp }
    private val json = PairingSync.json

    private var server: LocalPairingServer? = null
    private var nsd: PairingNsd? = null
    private var expiryJob: Job? = null
    private var entered = ""
    private var attempts = 0
    private var blockedUntil = 0L
    private var busy = false
    private var success = false

    @Volatile
    private var pending: PairRequest? = null

    @Volatile
    private var pendingHost: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLog.i(AppLog.TAG_PAIRING, "TvPairingActivity.onCreate")
        binding = ActivityTvPairingBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupKeypad()
        binding.tvPairRetry.setOnClickListener { resetForRetry() }
        startServer()
        updateCode()
        binding.tvPairConfirm.isEnabled = false
        binding.tvKey1.requestFocus()
    }

    private fun setupKeypad() {
        val keys = mapOf(
            '1' to binding.tvKey1,
            '2' to binding.tvKey2,
            '3' to binding.tvKey3,
            '4' to binding.tvKey4,
            '5' to binding.tvKey5,
            '6' to binding.tvKey6,
            '7' to binding.tvKey7,
            '8' to binding.tvKey8,
            '9' to binding.tvKey9,
            '0' to binding.tvKey0,
        )
        keys.forEach { (digit, button) ->
            button.setOnClickListener { appendDigit(digit) }
        }
        binding.tvKeyBack.setOnClickListener { backspace() }
        binding.tvPairConfirm.setOnClickListener { confirmCode() }
    }

    private fun startServer() {
        val pairingServer = LocalPairingServer { path, body, remote ->
            if (path != "/request") {
                LocalPairingServer.HttpResult(
                    404,
                    json.encodeToString(PairAck.serializer(), PairAck(false, "not_found")),
                )
            } else {
                handleRequest(body, remote)
            }
        }
        runCatching {
            val port = pairingServer.start()
            server = pairingServer
            val serviceName = "MystTV-${Build.MODEL.orEmpty().filter { it.isLetterOrDigit() }.take(12).ifBlank { "Android" }}"
                .take(60)
            nsd = PairingNsd(this)
            nsd?.register(
                serviceName = serviceName,
                port = port,
                onRegistered = { name ->
                    binding.tvPairService.text = getString(R.string.pairing_tv_service) + " • " + name
                },
                onError = { error ->
                    AppLog.w(AppLog.TAG_PAIRING, "NSD da TV falhou: $error")
                    binding.tvPairService.setText(R.string.pairing_tv_network_error)
                },
            )
            binding.tvPairStatus.setText(R.string.pairing_tv_no_request)
        }.onFailure {
            AppLog.failure(AppLog.TAG_PAIRING, "Falha iniciando servidor da TV", it)
            binding.tvPairStatus.setText(R.string.pairing_tv_error)
            binding.tvPairRetry.isVisible = true
        }
    }

    private fun handleRequest(body: String, remoteHost: String): LocalPairingServer.HttpResult {
        val request = runCatching {
            json.decodeFromString(PairRequest.serializer(), body)
        }.getOrNull()
        if (request == null ||
            request.codeHash.length != 64 ||
            request.phonePort !in 1024..65535 ||
            remoteHost.isBlank()
        ) {
            return LocalPairingServer.HttpResult(
                400,
                json.encodeToString(PairAck.serializer(), PairAck(false, "invalid_request")),
            )
        }
        pending = request
        pendingHost = remoteHost
        attempts = 0
        blockedUntil = 0L
        entered = ""
        busy = false
        success = false
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            binding.tvPairStatus.text = getString(
                R.string.pairing_tv_request_received,
                request.deviceName.ifBlank { getString(R.string.pairing_tv_service) },
            )
            binding.tvPairStatus.setTextColor(MystTheme.color(this, R.attr.mystAccentText))
            binding.tvPairRetry.isVisible = false
            binding.tvPairConfirm.isEnabled = false
            binding.tvKey1.requestFocus()
            updateCode()
        }
        scheduleExpiry()
        return LocalPairingServer.HttpResult(
            200,
            json.encodeToString(PairAck.serializer(), PairAck(true)),
        )
    }

    private fun scheduleExpiry() {
        expiryJob?.cancel()
        expiryJob = lifecycleScope.launch {
            delay(CODE_TTL_MS)
            if (success || pending == null || isFinishing || isDestroyed) return@launch
            pending = null
            entered = ""
            updateCode()
            binding.tvPairRetry.isVisible = true
            binding.tvPairConfirm.isEnabled = false
            binding.tvPairStatus.setText(R.string.pairing_expired)
            binding.tvPairStatus.setTextColor(MystTheme.color(this, R.attr.mystError))
        }
    }

    private fun appendDigit(digit: Char) {
        if (pending == null || busy || success) return
        if (SystemClock.elapsedRealtime() < blockedUntil) {
            binding.tvPairStatus.setText(R.string.pairing_tv_blocked)
            return
        }
        if (entered.length >= 5) return
        entered += digit
        updateCode()
    }

    private fun backspace() {
        if (busy || success || entered.isEmpty()) return
        entered = entered.dropLast(1)
        updateCode()
    }

    private fun updateCode() {
        binding.tvPairCode.text = entered.padEnd(5, '_')
        binding.tvPairConfirm.isEnabled = pending != null && entered.length == 5 && !busy
    }

    private fun confirmCode() {
        val request = pending ?: run {
            binding.tvPairStatus.setText(R.string.pairing_tv_no_request)
            return
        }
        if (busy || success) return
        val now = SystemClock.elapsedRealtime()
        if (now < blockedUntil) {
            binding.tvPairStatus.setText(R.string.pairing_tv_blocked)
            return
        }
        if (entered.length != 5) {
            binding.tvPairStatus.setText(R.string.pairing_tv_enter_code)
            return
        }
        if (!PairingCrypto.matchesHash(entered, request.nonce, request.codeHash)) {
            attempts++
            val remaining = (MAX_ATTEMPTS - attempts).coerceAtLeast(0)
            entered = ""
            updateCode()
            if (remaining <= 0) {
                blockedUntil = now + BLOCK_MS
                binding.tvPairStatus.setText(R.string.pairing_tv_blocked)
            } else {
                binding.tvPairStatus.text = getString(R.string.pairing_tv_wrong, remaining)
                binding.tvPairStatus.setTextColor(MystTheme.color(this, R.attr.mystError))
            }
            return
        }
        busy = true
        binding.tvPairConfirm.isEnabled = false
        binding.tvPairStatus.setText(R.string.pairing_tv_connecting_phone)
        binding.tvPairStatus.setTextColor(MystTheme.color(this, R.attr.mystAccentText))
        val code = entered
        val host = pendingHost
        lifecycleScope.launch {
            try {
                val payload = PairingHttp.postJson(
                    PairingHttp.url(host, request.phonePort, "/confirm"),
                    json.encodeToString(
                        PairConfirm.serializer(),
                        PairConfirm(code, Build.MODEL.orEmpty().ifBlank { "TV" }),
                    ),
                )
                val wrapper = json.decodeFromString(PairPayload.serializer(), payload)
                val plaintext = PairingCrypto.decrypt(code, wrapper.payload)
                val bundle = json.decodeFromString(
                    com.iskezis.myst.data.pairing.PairBundle.serializer(),
                    plaintext,
                )
                PairingSync.apply(app, bundle)
                success = true
                busy = false
                binding.tvPairStatus.setText(R.string.pairing_tv_success)
                binding.tvPairStatus.setTextColor(MystTheme.color(this, R.attr.mystAccentText))
                binding.tvPairRetry.isVisible = false
                binding.tvPairConfirm.isEnabled = false
                Toast.makeText(this@TvPairingActivity, R.string.pairing_success, Toast.LENGTH_SHORT).show()
                delay(900)
                if (!isFinishing && !isDestroyed) {
                    startActivity(
                        Intent(this@TvPairingActivity, MainActivity::class.java).addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK,
                        ),
                    )
                    finish()
                }
            } catch (t: Throwable) {
                AppLog.failure(AppLog.TAG_PAIRING, "Falha confirmando código na TV", t)
                busy = false
                binding.tvPairConfirm.isEnabled = true
                binding.tvPairRetry.isVisible = true
                binding.tvPairStatus.setText(R.string.pairing_tv_error)
                binding.tvPairStatus.setTextColor(MystTheme.color(this, R.attr.mystError))
            }
        }
    }

    private fun resetForRetry() {
        if (success) return
        expiryJob?.cancel()
        pending = null
        pendingHost = ""
        entered = ""
        busy = false
        attempts = 0
        blockedUntil = 0L
        binding.tvPairRetry.isVisible = false
        binding.tvPairStatus.setText(R.string.pairing_tv_no_request)
        binding.tvPairStatus.setTextColor(MystTheme.color(this, R.attr.mystOnSurfaceDim))
        updateCode()
    }

    override fun onDestroy() {
        AppLog.i(AppLog.TAG_PAIRING, "TvPairingActivity.onDestroy")
        expiryJob?.cancel()
        runCatching { nsd?.close() }
        nsd = null
        runCatching { server?.close() }
        server = null
        super.onDestroy()
    }

    companion object {
        private const val CODE_TTL_MS = 2 * 60 * 1000L
        private const val BLOCK_MS = 60 * 1000L
        private const val MAX_ATTEMPTS = 5
    }
}
