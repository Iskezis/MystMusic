package com.iskezis.myst.ui.pairing

import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.Toast
import com.iskezis.myst.ui.common.BaseActivity
import com.iskezis.myst.util.MystTheme
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
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
import com.iskezis.myst.databinding.ActivityPhonePairingBinding
import com.iskezis.myst.databinding.ItemTvTargetBinding
import com.iskezis.myst.util.AppLog
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Celular: descobre a TV via NSD, pede para a TV abrir a sessão e mostra o
 * código de 5 dígitos. O payload só é liberado para quem provar o código.
 */
class PhonePairingActivity : BaseActivity() {

    private lateinit var binding: ActivityPhonePairingBinding
    private val app by lazy { application as MystApp }
    private val json = PairingSync.json
    private val targets = linkedMapOf<String, PairingNsd.Target>()

    private var server: LocalPairingServer? = null
    private var nsd: PairingNsd? = null
    private var serverPort = 0
    private var selected: PairingNsd.Target? = null
    @Volatile
    private var code: String? = null
    private var nonce: String = PairingCrypto.newNonce()
    private var expiryJob: Job? = null
    private var discoveryTimeoutJob: Job? = null
    private var requestInFlight = false

    @Volatile
    private var paired = false

    @Volatile
    private var expired = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLog.i(AppLog.TAG_PAIRING, "PhonePairingActivity.onCreate")
        binding = ActivityPhonePairingBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.phonePairBack.setOnClickListener { finish() }
        binding.phonePairRetry.setOnClickListener { resetFlow() }
        binding.phonePairProgress.isVisible = true

        if (!app.session.isLoggedIn) {
            showError(getString(R.string.pairing_login_required))
            return
        }
        nsd = PairingNsd(this)
        if (startServer()) {
            startDiscovery()
        } else {
            showError(getString(R.string.pairing_error))
        }
    }

    private fun startServer(): Boolean {
        val pairingServer = LocalPairingServer { path, body, _ ->
            if (path != "/confirm") {
                LocalPairingServer.HttpResult(404, json.encodeToString(PairAck.serializer(), PairAck(false, "not_found")))
            } else {
                handleConfirm(body)
            }
        }
        return runCatching {
            serverPort = pairingServer.start()
            server = pairingServer
            AppLog.i(AppLog.TAG_PAIRING, "Celular ouvindo confirmação na porta $serverPort")
            true
        }.getOrElse {
            AppLog.failure(AppLog.TAG_PAIRING, "Falha abrindo servidor do celular", it)
            runCatching { pairingServer.close() }
            serverPort = 0
            server = null
            false
        }
    }

    private suspend fun handleConfirm(body: String): LocalPairingServer.HttpResult {
        val activeCode = code
        if (activeCode == null || paired || expired) {
            return LocalPairingServer.HttpResult(
                403,
                json.encodeToString(PairAck.serializer(), PairAck(false, "expired")),
            )
        }
        val confirm = runCatching {
            json.decodeFromString(PairConfirm.serializer(), body)
        }.getOrNull() ?: return LocalPairingServer.HttpResult(
            400,
            json.encodeToString(PairAck.serializer(), PairAck(false, "invalid")),
        )
        if (confirm.code != activeCode) {
            AppLog.w(AppLog.TAG_PAIRING, "TV enviou código incorreto")
            return LocalPairingServer.HttpResult(
                403,
                json.encodeToString(PairAck.serializer(), PairAck(false, "invalid_code")),
            )
        }
        val bundle = runCatching { PairingSync.export(app) }.getOrElse { throwable ->
            AppLog.failure(AppLog.TAG_PAIRING, "Falha preparando payload", throwable)
            return LocalPairingServer.HttpResult(
                500,
                json.encodeToString(PairAck.serializer(), PairAck(false, "export_failed")),
            )
        }
        val plaintext = json.encodeToString(
            com.iskezis.myst.data.pairing.PairBundle.serializer(),
            bundle,
        )
        val encrypted = PairingCrypto.encrypt(activeCode, plaintext)
        paired = true
        lifecycleScope.launch {
            if (isFinishing || isDestroyed) return@launch
            binding.phonePairProgress.isVisible = false
            binding.phonePairCodeRoot.isVisible = false
            binding.phonePairTvList.isVisible = false
            binding.phonePairNone.isVisible = false
            binding.phonePairStatus.setTextColor(
                MystTheme.color(this@PhonePairingActivity, R.attr.mystAccentText),
            )
            binding.phonePairStatus.setText(R.string.pairing_success)
            binding.phonePairRetry.isVisible = false
            Toast.makeText(this@PhonePairingActivity, R.string.pairing_success, Toast.LENGTH_SHORT).show()
            delay(1_200)
            if (!isFinishing && !isDestroyed) {
                setResult(RESULT_OK)
                finish()
            }
        }
        return LocalPairingServer.HttpResult(
            200,
            json.encodeToString(PairPayload.serializer(), PairPayload(encrypted)),
        )
    }

    private fun startDiscovery() {
        val helper = nsd ?: return
        discoveryTimeoutJob?.cancel()
        discoveryTimeoutJob = null
        requestInFlight = false
        selected = null
        code = null
        expired = false
        nonce = PairingCrypto.newNonce()
        targets.clear()
        binding.phonePairTvList.removeAllViews()
        binding.phonePairCodeRoot.isVisible = false
        binding.phonePairRetry.isVisible = false
        binding.phonePairNone.isVisible = false
        binding.phonePairProgress.isVisible = true
        setStatus(getString(R.string.pairing_searching), R.attr.mystOnSurfaceDim)
        helper.discover(
            onFound = { target ->
                runOnUiThread {
                    if (isFinishing || isDestroyed || paired) return@runOnUiThread
                    discoveryTimeoutJob?.cancel()
                    discoveryTimeoutJob = null
                    addTarget(target)
                }
            },
            onLost = { name ->
                runOnUiThread {
                    targets.remove(name)
                    renderTargets()
                }
            },
            onError = { error ->
                runOnUiThread {
                    if (isFinishing || isDestroyed || paired) return@runOnUiThread
                    AppLog.w(AppLog.TAG_PAIRING, "Descoberta indisponível: $error")
                    discoveryTimeoutJob?.cancel()
                    discoveryTimeoutJob = null
                    binding.phonePairProgress.isVisible = false
                    binding.phonePairRetry.isVisible = true
                    setStatus(getString(R.string.pairing_error), R.attr.mystError)
                }
            },
        )
        // Estado vazio: sem nenhuma TV após alguns segundos, deixa de mostrar
        // apenas o spinner e oferece uma nova busca.
        discoveryTimeoutJob = lifecycleScope.launch {
            delay(DISCOVERY_TIMEOUT_MS)
            if (paired || isFinishing || isDestroyed) return@launch
            if (targets.isEmpty() && !requestInFlight) {
                binding.phonePairProgress.isVisible = false
                binding.phonePairNone.isVisible = true
                binding.phonePairRetry.isVisible = true
                setStatus(getString(R.string.pairing_none), R.attr.mystOnSurfaceDim)
            }
        }
    }

    private fun addTarget(target: PairingNsd.Target) {
        targets[target.name] = target
        renderTargets()
    }

    private fun renderTargets() {
        binding.phonePairTvList.removeAllViews()
        val values = targets.values.toList()
        binding.phonePairNone.isVisible = values.isEmpty() && !requestInFlight
        binding.phonePairProgress.isVisible = values.isEmpty() && !requestInFlight
        if (values.isEmpty()) {
            binding.phonePairStatus.setText(R.string.pairing_searching)
            binding.phonePairStatus.setTextColor(MystTheme.color(this, R.attr.mystOnSurfaceDim))
        } else {
            binding.phonePairProgress.isVisible = false
            setStatus(getString(R.string.pairing_choose_tv), R.attr.mystOnSurfaceDim)
        }
        values.forEach { target ->
            val item = ItemTvTargetBinding.inflate(LayoutInflater.from(this), binding.phonePairTvList, false)
            item.tvTargetName.text = target.name
            item.root.setOnClickListener { selectTarget(target) }
            binding.phonePairTvList.addView(item.root)
        }
    }

    private fun selectTarget(target: PairingNsd.Target) {
        if (requestInFlight || paired) return
        if (serverPort <= 0 && !startServer()) {
            showError(getString(R.string.pairing_error))
            return
        }
        discoveryTimeoutJob?.cancel()
        discoveryTimeoutJob = null
        nsd?.stopDiscovery()
        selected = target
        val newCode = PairingCrypto.newCode()
        val newNonce = PairingCrypto.newNonce()
        code = newCode
        nonce = newNonce
        requestInFlight = true
        binding.phonePairTvList.isVisible = false
        binding.phonePairNone.isVisible = false
        binding.phonePairProgress.isVisible = true
        binding.phonePairRetry.isVisible = false
        setStatus(getString(R.string.pairing_connecting_tv, target.name), R.attr.mystOnSurfaceDim)
        lifecycleScope.launch {
            try {
                val request = PairRequest(
                    codeHash = PairingCrypto.hashCode(newCode, newNonce),
                    nonce = newNonce,
                    phonePort = serverPort,
                    deviceName = Build.MODEL.orEmpty().ifBlank { "Celular" },
                )
                PairingHttp.postJson(
                    PairingHttp.url(target.host, target.port, "/request"),
                    json.encodeToString(PairRequest.serializer(), request),
                )
                requestInFlight = false
                if (isFinishing || isDestroyed || paired) return@launch
                binding.phonePairProgress.isVisible = false
                binding.phonePairCode.text = newCode
                binding.phonePairCodeRoot.isVisible = true
                binding.phonePairStatus.setText(R.string.pairing_waiting)
                binding.phonePairStatus.setTextColor(
                    MystTheme.color(this@PhonePairingActivity, R.attr.mystAccentText),
                )
                scheduleExpiry()
            } catch (t: Throwable) {
                AppLog.failure(AppLog.TAG_PAIRING, "TV não aceitou a conexão", t)
                requestInFlight = false
                code = null
                expired = false
                binding.phonePairTvList.isVisible = true
                binding.phonePairRetry.isVisible = true
                binding.phonePairProgress.isVisible = false
                setStatus(getString(R.string.pairing_error), R.attr.mystError)
            }
        }
    }

    private fun scheduleExpiry() {
        expiryJob?.cancel()
        expiryJob = lifecycleScope.launch {
            delay(CODE_TTL_MS)
            if (paired || isFinishing || isDestroyed) return@launch
            expired = true
            code = null
            binding.phonePairCodeRoot.isVisible = false
            binding.phonePairRetry.isVisible = true
            setStatus(getString(R.string.pairing_expired), R.attr.mystError)
        }
    }

    private fun resetFlow() {
        if (paired) return
        expiryJob?.cancel()
        if (serverPort <= 0 && !startServer()) {
            showError(getString(R.string.pairing_error))
            return
        }
        startDiscovery()
        binding.phonePairTvList.isVisible = true
    }

    private fun setStatus(message: String, colorAttr: Int) {
        binding.phonePairStatus.text = message
        binding.phonePairStatus.setTextColor(MystTheme.color(this, colorAttr))
    }

    private fun showError(message: String) {
        binding.phonePairProgress.isVisible = false
        binding.phonePairRetry.isVisible = true
        setStatus(message, R.attr.mystError)
    }

    override fun onDestroy() {
        AppLog.i(AppLog.TAG_PAIRING, "PhonePairingActivity.onDestroy")
        expiryJob?.cancel()
        discoveryTimeoutJob?.cancel()
        discoveryTimeoutJob = null
        runCatching { server?.close() }
        server = null
        runCatching { nsd?.close() }
        nsd = null
        super.onDestroy()
    }

    companion object {
        private const val CODE_TTL_MS = 2 * 60 * 1000L
        private const val DISCOVERY_TIMEOUT_MS = 12_000L
    }
}
