package com.iskezis.myst.ui.pairing

import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.iskezis.myst.MystApp
import com.iskezis.myst.R
import com.iskezis.myst.data.pairing.PairRequest
import com.iskezis.myst.data.pairing.PairingCrypto
import com.iskezis.myst.data.pairing.PairingHttp
import com.iskezis.myst.data.pairing.PairingNsd
import com.iskezis.myst.data.pairing.PairingSync
import com.iskezis.myst.data.pairing.PhonePairingSession
import com.iskezis.myst.databinding.ActivityPhonePairingBinding
import com.iskezis.myst.databinding.ItemTvTargetBinding
import com.iskezis.myst.ui.common.BaseActivity
import com.iskezis.myst.util.AppLog
import com.iskezis.myst.util.MystTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Celular: descobre a TV via NSD, pede para a TV abrir a sessão e mostra o
 * código de 5 dígitos. O payload só é liberado para quem provar o código.
 *
 * A sessão em si (servidor `/confirm`, código e expiração) vive na
 * Application, não nesta Activity: fechar a tela, voltar para a TV ou remover
 * o app dos recentes não pode interromper o pareamento.
 */
class PhonePairingActivity : BaseActivity() {

    private lateinit var binding: ActivityPhonePairingBinding
    private val app by lazy { application as MystApp }
    private val json = PairingSync.json
    private val targets = linkedMapOf<String, PairingNsd.Target>()
    private val session by lazy { app.phonePairing }

    private var nsd: PairingNsd? = null
    private var selected: PairingNsd.Target? = null
    private var discoveryTimeoutJob: Job? = null
    private var successJob: Job? = null
    private var requestInFlight = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLog.i(AppLog.TAG_PAIRING, "PhonePairingActivity.onCreate")
        binding = ActivityPhonePairingBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.phonePairBack.setOnClickListener { cancelAndFinish() }
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() = cancelAndFinish()
            },
        )
        binding.phonePairRetry.setOnClickListener { resetFlow() }
        binding.phonePairProgress.isVisible = true

        if (!app.session.isLoggedIn) {
            session.cancel()
            showError(getString(R.string.pairing_login_required))
            return
        }
        val initial = session.state.value
        if (initial is PhonePairingSession.State.Paired) {
            renderPaired(initial.tvName)
            return
        }
        nsd = PairingNsd(this)
        if (!session.ensureServer()) {
            showError(getString(R.string.pairing_error))
            return
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                session.state.collect { renderSession(it) }
            }
        }
        renderSession(initial)
        if (initial is PhonePairingSession.State.Idle) {
            startDiscovery()
        }
    }

    private fun renderSession(state: PhonePairingSession.State) {
        when (state) {
            is PhonePairingSession.State.Idle -> Unit
            is PhonePairingSession.State.Waiting -> {
                if (requestInFlight) {
                    binding.phonePairTvList.isVisible = false
                    binding.phonePairNone.isVisible = false
                    binding.phonePairCodeRoot.isVisible = false
                    binding.phonePairProgress.isVisible = true
                    binding.phonePairRetry.isVisible = false
                    setStatus(getString(R.string.pairing_connecting_tv, state.challenge.tvName), R.attr.mystOnSurfaceDim)
                } else {
                    renderWaiting(state.challenge)
                }
            }
            is PhonePairingSession.State.Paired -> renderPaired(state.tvName)
            is PhonePairingSession.State.Expired -> renderExpired()
        }
    }

    private fun renderWaiting(challenge: PhonePairingSession.Challenge) {
        binding.phonePairTvList.isVisible = false
        binding.phonePairNone.isVisible = false
        binding.phonePairProgress.isVisible = false
        binding.phonePairCode.text = challenge.code
        binding.phonePairCodeRoot.isVisible = true
        binding.phonePairRetry.isVisible = false
        binding.phonePairStatus.setText(R.string.pairing_waiting)
        binding.phonePairStatus.setTextColor(MystTheme.color(this, R.attr.mystAccentText))
    }

    private fun renderPaired(tvName: String) {
        if (successJob?.isActive == true) return
        binding.phonePairProgress.isVisible = false
        binding.phonePairCodeRoot.isVisible = false
        binding.phonePairTvList.isVisible = false
        binding.phonePairNone.isVisible = false
        binding.phonePairRetry.isVisible = false
        binding.phonePairStatus.setTextColor(MystTheme.color(this, R.attr.mystAccentText))
        binding.phonePairStatus.setText(R.string.pairing_success)
        Toast.makeText(this, R.string.pairing_success, Toast.LENGTH_SHORT).show()
        AppLog.i(AppLog.TAG_PAIRING, "Pareamento concluído com $tvName")
        successJob = lifecycleScope.launch {
            delay(1_200)
            if (!isFinishing && !isDestroyed) {
                setResult(RESULT_OK)
                finish()
            }
        }
    }

    private fun renderExpired() {
        binding.phonePairProgress.isVisible = false
        binding.phonePairCodeRoot.isVisible = false
        binding.phonePairTvList.isVisible = false
        binding.phonePairNone.isVisible = false
        binding.phonePairRetry.isVisible = true
        setStatus(getString(R.string.pairing_expired), R.attr.mystError)
    }

    private fun startDiscovery() {
        val helper = nsd ?: return
        discoveryTimeoutJob?.cancel()
        discoveryTimeoutJob = null
        requestInFlight = false
        selected = null
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
                    if (isFinishing || isDestroyed || !isIdle()) return@runOnUiThread
                    discoveryTimeoutJob?.cancel()
                    discoveryTimeoutJob = null
                    addTarget(target)
                }
            },
            onLost = { name ->
                runOnUiThread {
                    if (!isIdle()) return@runOnUiThread
                    targets.remove(name)
                    renderTargets()
                }
            },
            onError = { error ->
                runOnUiThread {
                    if (isFinishing || isDestroyed || !isIdle()) return@runOnUiThread
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
            if (isFinishing || isDestroyed || !isIdle()) return@launch
            if (targets.isEmpty() && !requestInFlight) {
                binding.phonePairProgress.isVisible = false
                binding.phonePairNone.isVisible = true
                binding.phonePairRetry.isVisible = true
                setStatus(getString(R.string.pairing_none), R.attr.mystOnSurfaceDim)
            }
        }
    }

    private fun isIdle(): Boolean = session.state.value is PhonePairingSession.State.Idle

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
        if (requestInFlight || session.state.value !is PhonePairingSession.State.Idle) return
        if (!session.ensureServer()) {
            showError(getString(R.string.pairing_error))
            return
        }
        discoveryTimeoutJob?.cancel()
        discoveryTimeoutJob = null
        nsd?.stopDiscovery()
        selected = target
        requestInFlight = true
        val challenge = session.begin(target.name)
        if (challenge == null) {
            requestInFlight = false
            showError(getString(R.string.pairing_error))
            return
        }
        binding.phonePairTvList.isVisible = false
        binding.phonePairNone.isVisible = false
        binding.phonePairProgress.isVisible = true
        binding.phonePairRetry.isVisible = false
        binding.phonePairCodeRoot.isVisible = false
        setStatus(getString(R.string.pairing_connecting_tv, target.name), R.attr.mystOnSurfaceDim)
        lifecycleScope.launch {
            try {
                val request = PairRequest(
                    codeHash = PairingCrypto.hashCode(challenge.code, challenge.nonce),
                    nonce = challenge.nonce,
                    phonePort = session.port,
                    deviceName = Build.MODEL.orEmpty().ifBlank { "Celular" },
                )
                PairingHttp.postJson(
                    PairingHttp.url(target.host, target.port, "/request"),
                    json.encodeToString(PairRequest.serializer(), request),
                )
                requestInFlight = false
                if (isFinishing || isDestroyed) return@launch
                if (session.state.value is PhonePairingSession.State.Waiting) {
                    renderWaiting(challenge)
                }
            } catch (t: Throwable) {
                AppLog.failure(AppLog.TAG_PAIRING, "TV não aceitou a conexão", t)
                requestInFlight = false
                session.cancel()
                if (isFinishing || isDestroyed) return@launch
                binding.phonePairTvList.isVisible = true
                binding.phonePairProgress.isVisible = false
                binding.phonePairCodeRoot.isVisible = false
                binding.phonePairRetry.isVisible = true
                renderTargets()
                setStatus(getString(R.string.pairing_error), R.attr.mystError)
            }
        }
    }

    private fun resetFlow() {
        if (session.state.value is PhonePairingSession.State.Paired) return
        successJob?.cancel()
        successJob = null
        session.cancel()
        if (!session.ensureServer()) {
            showError(getString(R.string.pairing_error))
            return
        }
        binding.phonePairTvList.isVisible = true
        startDiscovery()
    }

    private fun cancelAndFinish() {
        if (session.state.value !is PhonePairingSession.State.Paired) {
            session.cancel()
        }
        finish()
    }

    private fun setStatus(message: String, colorAttr: Int) {
        binding.phonePairStatus.text = message
        binding.phonePairStatus.setTextColor(MystTheme.color(this, colorAttr))
    }

    private fun showError(message: String) {
        binding.phonePairProgress.isVisible = false
        binding.phonePairCodeRoot.isVisible = false
        binding.phonePairRetry.isVisible = true
        setStatus(message, R.attr.mystError)
    }

    override fun onDestroy() {
        AppLog.i(AppLog.TAG_PAIRING, "PhonePairingActivity.onDestroy")
        discoveryTimeoutJob?.cancel()
        discoveryTimeoutJob = null
        successJob?.cancel()
        successJob = null
        runCatching { nsd?.close() }
        nsd = null
        // A sessão e o servidor de confirmação continuam na Application. Só
        // um cancelamento explícito, o sucesso ou a expiração os encerram.
        super.onDestroy()
    }

    companion object {
        private const val DISCOVERY_TIMEOUT_MS = 12_000L
    }
}
