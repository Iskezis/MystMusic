package com.iskezis.myst.ui.auth

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.transition.TransitionManager
import coil.load
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.transition.MaterialSharedAxis
import com.iskezis.myst.MainActivity
import com.iskezis.myst.R
import com.iskezis.myst.data.account.AvatarStore
import com.iskezis.myst.data.account.MystAccountFileRepository
import com.iskezis.myst.databinding.ActivityAuthBinding
import com.iskezis.myst.ui.common.BaseActivity
import com.iskezis.myst.util.AppLog
import com.iskezis.myst.util.MystTheme
import com.iskezis.myst.util.UiAnimations
import com.iskezis.myst.util.UiAnimations.pressScale
import kotlinx.coroutines.launch
import java.io.File

class AuthActivity : BaseActivity() {

    private lateinit var binding: ActivityAuthBinding
    private val viewModel: AuthViewModel by viewModels { AuthViewModel.factory(application) }

    private var registerMode = false
    private var navigated = false
    private var autoYouTubeLaunched = false
    private var autoMystShown = false
    private var pendingAvatarPath: String? = null
    private var conflictDialogShowing = false
    private var legacyDialogShowing = false
    private var accountHintApplied = false
    private var accountFilePromptShown = false
    private var accountFilePickerLaunched = false

    private val accountFileCreator = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        accountFilePickerLaunched = false
        viewModel.completeAccountFileExport(uri)
    }

    private val accountFileImporter = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) confirmAccountFileImport(uri)
    }

    private val youtubeLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val data = result.data ?: return@registerForActivityResult
            val cookies = data.getStringExtra(YouTubeLoginActivity.EXTRA_COOKIES).orEmpty()
            AppLog.d(AppLog.TAG_AUTH, "Retorno do login YouTube: cookies=${cookies.length} bytes")
            if (cookies.isNotBlank()) {
                viewModel.loginYouTube(cookies, getString(R.string.youtube_account_fallback))
            }
        } else {
            AppLog.d(AppLog.TAG_AUTH, "Login YouTube cancelado (resultCode=${result.resultCode})")
        }
    }

    private val avatarPicker = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        avatarCropLauncher.launch(
            Intent(this, AvatarCropActivity::class.java)
                .putExtra(AvatarCropActivity.EXTRA_SOURCE_URI, uri.toString()),
        )
    }

    private val avatarCropLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val path = result.data?.getStringExtra(AvatarCropActivity.EXTRA_OUTPUT_PATH)
        if (path.isNullOrBlank()) {
            Toast.makeText(this, R.string.avatar_crop_error, Toast.LENGTH_LONG).show()
            return@registerForActivityResult
        }
        pendingAvatarPath = path
        renderAvatar()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLog.i(AppLog.TAG_AUTH, "AuthActivity.onCreate")
        // O AuthViewModel confere se a conta Myst ainda existe antes de
        // navegar. Não há atalho só pela sessão: uma sessão órfã (backup
        // restaurado sem o banco) precisa mostrar o login para o usuário.
        binding = ActivityAuthBinding.inflate(layoutInflater)
        setContentView(binding.root)
        pendingAvatarPath = savedInstanceState?.getString(STATE_AVATAR)
        registerMode = savedInstanceState?.getBoolean(STATE_REGISTER) ?: false
        autoYouTubeLaunched = savedInstanceState?.getBoolean(STATE_YT_LAUNCHED) ?: false
        autoMystShown = savedInstanceState?.getBoolean(STATE_MYST_SHOWN) ?: false
        accountFilePickerLaunched = savedInstanceState?.getBoolean(STATE_FILE_PICKER) ?: false
        WindowCompat.setDecorFitsSystemWindows(window, false)
        MystTheme.applySystemBars(this)
        ViewCompat.setOnApplyWindowInsetsListener(binding.authRoot) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        binding.mystStepCard.setOnClickListener {
            if (!viewModel.state.value.loading && !viewModel.state.value.mystReady) {
                showMystPanel(true)
            }
        }
        binding.youtubeStepCard.setOnClickListener {
            if (!viewModel.state.value.loading && !viewModel.state.value.youtubeReady) {
                launchYouTubeLogin()
            }
        }
        binding.authBack.setOnClickListener { showMystPanel(false) }
        binding.authSwitch.setOnClickListener {
            registerMode = !registerMode
            renderMode(registerMode)
        }
        binding.authSubmit.setOnClickListener { submit() }
        binding.authContinue.setOnClickListener {
            val state = viewModel.state.value
            if (state.mystReady && state.youtubeReady &&
                state.youtubeConflict == null && !state.needsLegacyTransfer
            ) {
                goToMain()
            }
        }
        binding.authAvatarPicker.setOnClickListener {
            if (registerMode) {
                runCatching {
                    avatarPicker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                    )
                }.onFailure { t ->
                    AppLog.failure(AppLog.TAG_UI, "Seletor de fotos indisponível", t)
                    Toast.makeText(this, R.string.avatar_crop_error, Toast.LENGTH_LONG).show()
                }
            }
        }
        binding.authImportFile.setOnClickListener {
            if (!viewModel.state.value.loading) {
                // Se o usuário já tentou entrar pelo YouTube antes de escolher
                // o arquivo, o fluxo automático precisa poder rodar de novo
                // depois de restaurar a conta.
                autoYouTubeLaunched = false
                autoMystShown = false
                runCatching { accountFileImporter.launch(arrayOf("*/*")) }
                    .onFailure { t ->
                        AppLog.failure(AppLog.TAG_AUTH, "Seletor de arquivos indisponível", t)
                        Toast.makeText(
                            this,
                            R.string.account_file_read_error,
                            Toast.LENGTH_LONG,
                        ).show()
                    }
            }
        }

        binding.mystStepCard.pressScale()
        binding.youtubeStepCard.pressScale()
        binding.authSubmit.pressScale()
        binding.authBack.pressScale()
        binding.authSwitch.pressScale()
        binding.authContinue.pressScale()
        binding.authAvatarPicker.pressScale()
        binding.authImportFile.pressScale()
        binding.authConfirmInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                submit()
                true
            } else {
                false
            }
        }
        binding.authUsernameInput.doAfterTextChanged {
            binding.usernameLayout.error = null
            renderAvatar()
        }
        binding.authPasswordInput.doAfterTextChanged { binding.passwordLayout.error = null }
        binding.authConfirmInput.doAfterTextChanged { binding.confirmLayout.error = null }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.mystPanel.isVisible) {
                    showMystPanel(false)
                } else {
                    finish()
                }
            }
        })

        renderMode(registerMode)
        collectState()
        animateEntry()
    }

    private fun animateEntry() {
        if (!UiAnimations.enabled(this)) return
        val logo = binding.authLogo
        val items = listOf(
            binding.authTitle,
            binding.authSubtitle,
            binding.authSteps,
            binding.authContinue,
        )
        logo.alpha = 0f
        logo.scaleX = 0.9f
        logo.scaleY = 0.9f
        items.forEach { it.alpha = 0f }
        binding.authRoot.post {
            logo.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(400)
                .start()
            items.forEachIndexed { index, view ->
                view.translationY = 24f * resources.displayMetrics.density
                view.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setStartDelay(100L + index * 70L)
                    .setDuration(400)
                    .start()
            }
        }
    }

    private fun collectState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state ->
                    binding.authLoading.isVisible = state.loading
                    binding.authSubmit.isEnabled = !state.loading
                    binding.authSwitch.isEnabled = !state.loading
                    binding.authImportFile.isEnabled = !state.loading
                    binding.mystStepCard.isEnabled = !state.loading
                    binding.youtubeStepCard.isEnabled = !state.loading
                    binding.authContinue.isEnabled = state.mystReady &&
                        state.youtubeReady &&
                        !state.loading &&
                        state.youtubeConflict == null
                    renderSteps(state)
                    renderError(state)
                    renderAccounts(state)
                    renderMessage(state)
                    renderAccountFilePrompt(state)

                    if (state.mystReady && binding.mystPanel.isVisible) {
                        showMystPanel(false)
                    }
                    if (!state.mystReady && state.youtubeReady && !autoMystShown) {
                        autoMystShown = true
                        showMystPanel(true)
                    }
                    if (state.mystReady && !state.youtubeReady &&
                        !autoYouTubeLaunched && !state.loading &&
                        state.pendingAccountFileUserId == null
                    ) {
                        autoYouTubeLaunched = true
                        launchYouTubeLogin()
                    }

                    if (state.youtubeConflict != null) {
                        if (!state.loading && state.errorRes != null) {
                            // Falhou ao resolver: permite tentar de novo.
                            conflictDialogShowing = false
                        }
                        showConflictDialog(state.youtubeConflict)
                    } else {
                        conflictDialogShowing = false
                    }
                    if (state.needsLegacyTransfer) {
                        if (!state.loading && state.errorRes != null) {
                            legacyDialogShowing = false
                        }
                        showLegacyDialog(state)
                    } else {
                        legacyDialogShowing = false
                    }

                    if (state.navigate && !navigated &&
                        state.youtubeConflict == null && !state.needsLegacyTransfer &&
                        state.pendingAccountFileUserId == null
                    ) {
                        navigated = true
                        binding.authRoot.postDelayed({
                            if (!isFinishing && !isDestroyed) goToMain()
                        }, 350)
                    }
                }
            }
        }
    }

    private fun renderSteps(state: AuthState) {
        binding.mystStepCheck.isVisible = state.mystReady
        binding.mystStepNumber.isVisible = !state.mystReady
        binding.mystStepSubtitle.text = if (state.mystReady) {
            getString(
                R.string.auth_step_connected,
                state.mystName.ifBlank { getString(R.string.auth_myst) },
            )
        } else {
            getString(R.string.auth_myst_missing)
        }

        binding.youtubeStepCheck.isVisible = state.youtubeReady
        binding.youtubeStepNumber.isVisible = !state.youtubeReady
        binding.youtubeStepSubtitle.text = if (state.youtubeReady) {
            getString(
                R.string.auth_step_connected,
                state.youtubeName.ifBlank { getString(R.string.auth_youtube_title) },
            )
        } else {
            getString(R.string.auth_youtube_missing)
        }

        binding.authNoticeCard.isVisible =
            state.migrationNotice && !binding.mystPanel.isVisible
        binding.authStepsHint.isVisible =
            !state.migrationNotice && !binding.mystPanel.isVisible
    }

    private fun renderError(state: AuthState) {
        val error = state.errorRes
        if (error == null) {
            binding.authError.isVisible = false
            binding.authStatusError.isVisible = false
            return
        }
        if (binding.mystPanel.isVisible) {
            binding.authError.isVisible = true
            binding.authError.setText(error)
            binding.authStatusError.isVisible = false
        } else {
            binding.authError.isVisible = false
            binding.authStatusError.isVisible = true
            binding.authStatusError.setText(error)
        }
    }

    private fun showMystPanel(show: Boolean) {
        if (show && viewModel.state.value.mystReady) return
        val transition = MaterialSharedAxis(MaterialSharedAxis.X, show).apply {
            duration = 300
            addTarget(binding.authSteps)
            addTarget(binding.mystPanel)
            addTarget(binding.authContinue)
            addTarget(binding.authNoticeCard)
            addTarget(binding.authStepsHint)
            addTarget(binding.authStatusError)
        }
        TransitionManager.beginDelayedTransition(binding.authRoot, transition)
        binding.authSteps.isVisible = !show
        binding.mystPanel.isVisible = show
        binding.authContinue.isVisible = !show
        binding.authNoticeCard.isVisible =
            !show && viewModel.state.value.migrationNotice
        binding.authStepsHint.isVisible =
            !show && !viewModel.state.value.migrationNotice
        if (show) {
            binding.authStatusError.isVisible = false
            binding.authUsernameInput.requestFocus()
        } else {
            binding.authError.isVisible = false
            viewModel.clearError()
            binding.authStatusError.isVisible = false
        }
    }

    private fun renderMode(register: Boolean) {
        val transition = MaterialSharedAxis(MaterialSharedAxis.X, register).apply {
            duration = 300
            addTarget(binding.mystPanel)
        }
        TransitionManager.beginDelayedTransition(binding.authRoot, transition)
        binding.authFormTitle.setText(
            if (register) R.string.auth_register_title else R.string.auth_login_title,
        )
        binding.authSubmit.setText(if (register) R.string.auth_register else R.string.auth_login)
        binding.authSwitch.setText(
            if (register) R.string.auth_switch_login else R.string.auth_switch_register,
        )
        binding.confirmLayout.visibility = if (register) View.VISIBLE else View.GONE
        binding.authAvatarPicker.isVisible = register
        binding.authAvatarHint.isVisible = register
        binding.authImportFile.isVisible = !register
        binding.authImportHint.isVisible = !register
        if (!register) binding.authConfirmInput.text?.clear()
        renderAccounts(viewModel.state.value)
        renderAvatar()
    }

    /**
     * Mostra as contas Myst que já existem neste aparelho. Isso é essencial
     * depois de uma restauração de backup: o banco volta, mas a sessão (por
     * segurança) não, e o usuário precisa saber qual usuário usar.
     */
    private fun renderAccounts(state: AuthState) {
        val accounts = if (registerMode) emptyList() else state.localAccounts
        val show = accounts.isNotEmpty()
        binding.authAccountsTitle.isVisible = show
        binding.authAccounts.isVisible = show
        if (!show) {
            binding.authAccounts.removeAllViews()
            binding.authAccounts.tag = null
            return
        }

        val signature = accounts.joinToString("|") { it.username }
        if (binding.authAccounts.tag != signature) {
            binding.authAccounts.tag = signature
            binding.authAccounts.removeAllViews()
            accounts.forEach { account ->
                val chip = Chip(this).apply {
                    text = account.displayName.ifBlank { account.username }
                    isCheckable = false
                    contentDescription = getString(
                        R.string.auth_account_fill,
                        account.username,
                    )
                    setOnClickListener {
                        binding.authUsernameInput.setText(account.username)
                        binding.authUsernameInput.setSelection(account.username.length)
                        binding.usernameLayout.error = null
                        binding.authPasswordInput.requestFocus()
                    }
                }
                binding.authAccounts.addView(chip)
            }
        }

        if (!accountHintApplied && !registerMode && accounts.size == 1 &&
            binding.authUsernameInput.text.isNullOrBlank()
        ) {
            accountHintApplied = true
            val username = accounts.first().username
            binding.authUsernameInput.setText(username)
            binding.authUsernameInput.setSelection(username.length)
        }
    }

    private fun renderAvatar() {
        val name = binding.authUsernameInput.text?.toString()?.trim().orEmpty()
        binding.authAvatarInitial.text = name.take(1).uppercase()
        val path = pendingAvatarPath
        val key = AvatarStore.cacheKey(path)
        if (path != null && key != null) {
            binding.authAvatarImage.isVisible = true
            binding.authAvatarInitial.isVisible = false
            binding.authAvatarImage.load(File(path)) {
                memoryCacheKey(key)
                diskCacheKey(key)
                crossfade(true)
                placeholder(R.drawable.bg_avatar)
                error(R.drawable.bg_avatar)
            }
        } else {
            binding.authAvatarImage.isVisible = false
            binding.authAvatarInitial.isVisible = true
        }
    }

    private fun renderMessage(state: AuthState) {
        val message = state.messageRes ?: return
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        viewModel.consumeMessage()
    }

    /**
     * Depois do cadastro, oferece salvar o Mystaccount.json. A navegação fica
     * bloqueada enquanto o arquivo é gerado, para a pessoa não perder o aviso e
     * o arquivo não ser criado depois de o app já ter trocado de tela.
     */
    private fun renderAccountFilePrompt(state: AuthState) {
        if (state.pendingAccountFileUserId == null) {
            accountFilePromptShown = false
            return
        }
        if (state.loading || accountFilePromptShown || accountFilePickerLaunched) return
        accountFilePromptShown = true
        val name = state.pendingAccountFileUsername.ifBlank {
            state.mystName.ifBlank { getString(R.string.auth_myst) }
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.account_file_title)
            .setMessage(getString(R.string.account_file_message, name))
            .setCancelable(false)
            .setPositiveButton(R.string.account_file_save) { _, _ ->
                launchAccountFileCreator()
            }
            .setNegativeButton(R.string.account_file_later) { _, _ ->
                viewModel.completeAccountFileExport(null)
            }
            .show()
    }

    /**
     * A escolha do arquivo é o primeiro passo; a confirmação evita restaurar
     * uma conta por engano e deixa claro que o YouTube será pedido depois.
     */
    private fun confirmAccountFileImport(uri: Uri) {
        autoYouTubeLaunched = false
        autoMystShown = false
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.account_file_confirm_title)
            .setMessage(R.string.account_file_confirm_message)
            .setNegativeButton(R.string.common_cancel, null)
            .setPositiveButton(R.string.account_file_confirm_action) { _, _ ->
                viewModel.importAccount(uri)
            }
            .show()
    }

    private fun launchAccountFileCreator() {
        accountFilePickerLaunched = true
        runCatching {
            accountFileCreator.launch(MystAccountFileRepository.DEFAULT_FILE_NAME)
        }.onFailure { t ->
            accountFilePickerLaunched = false
            AppLog.failure(AppLog.TAG_AUTH, "Seletor de arquivos indisponível", t)
            Toast.makeText(this, R.string.account_file_error, Toast.LENGTH_LONG).show()
            viewModel.completeAccountFileExport(null)
        }
    }

    private fun submit() {
        binding.usernameLayout.error = null
        binding.passwordLayout.error = null
        binding.confirmLayout.error = null

        val username = binding.authUsernameInput.text?.toString()?.trim().orEmpty()
        val password = binding.authPasswordInput.text?.toString().orEmpty()
        val confirm = binding.authConfirmInput.text?.toString().orEmpty()

        var valid = true
        if (username.length !in 3..20 || !username.matches(Regex("[a-zA-Z0-9._-]+"))) {
            binding.usernameLayout.error = getString(R.string.auth_error_username)
            valid = false
        }
        if (password.length < 8) {
            binding.passwordLayout.error = getString(R.string.auth_error_password)
            valid = false
        }
        if (registerMode && password != confirm) {
            binding.confirmLayout.error = getString(R.string.auth_error_password_match)
            valid = false
        }
        if (!valid) {
            shake(binding.mystPanel)
            return
        }

        if (registerMode) {
            viewModel.register(username, password, pendingAvatarPath)
        } else {
            viewModel.login(username, password)
        }
    }

    private fun launchYouTubeLogin() {
        youtubeLauncher.launch(Intent(this, YouTubeLoginActivity::class.java))
    }

    private fun showConflictDialog(conflict: YouTubeConflictUi) {
        if (conflictDialogShowing) return
        conflictDialogShowing = true
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.auth_youtube_conflict_title)
            .setMessage(
                getString(
                    R.string.auth_youtube_conflict_message,
                    conflict.linkedName,
                    conflict.newName,
                ),
            )
            .setCancelable(false)
            .setPositiveButton(R.string.auth_youtube_conflict_replace) { _, _ ->
                // Mantém o guarda ligado enquanto o ViewModel trabalha para o
                // diálogo não reabrir no estado de carregamento.
                viewModel.confirmYouTubeConflict()
            }
            .setNegativeButton(R.string.auth_youtube_conflict_use) { _, _ ->
                viewModel.useLinkedYouTube()
            }
            .show()
    }

    private fun showLegacyDialog(state: AuthState) {
        if (legacyDialogShowing) return
        legacyDialogShowing = true
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.auth_legacy_title)
            .setMessage(getString(R.string.auth_legacy_message, state.mystName))
            .setCancelable(false)
            .setPositiveButton(R.string.auth_legacy_transfer) { _, _ ->
                viewModel.confirmLegacyTransfer()
            }
            .setNegativeButton(R.string.auth_legacy_skip) { _, _ ->
                viewModel.skipLegacyTransfer()
            }
            .show()
    }

    private fun shake(view: View) {
        view.animate()
            .translationX(8f).setDuration(50)
            .withEndAction {
                view.animate().translationX(-8f).setDuration(100).withEndAction {
                    view.animate().translationX(0f).setDuration(100).start()
                }.start()
            }
            .start()
    }

    private fun goToMain() {
        AppLog.i(AppLog.TAG_AUTH, "Abrindo MainActivity")
        startActivity(
            Intent(this, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK,
            ),
        )
        finish()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_AVATAR, pendingAvatarPath)
        outState.putBoolean(STATE_REGISTER, registerMode)
        outState.putBoolean(STATE_YT_LAUNCHED, autoYouTubeLaunched)
        outState.putBoolean(STATE_MYST_SHOWN, autoMystShown)
        outState.putBoolean(STATE_FILE_PICKER, accountFilePickerLaunched)
    }

    override fun onDestroy() {
        // Só apaga o recorte temporário quando a tela está sendo fechada de
        // verdade. Se o sistema recriar a Activity (rotação ou processo em
        // segundo plano), o caminho salvo em onSaveInstanceState precisa
        // continuar apontando para um arquivo válido.
        if (isFinishing && !isChangingConfigurations) {
            AvatarStore.cleanupTemp(this, pendingAvatarPath)
        }
        super.onDestroy()
    }

    companion object {
        private const val STATE_AVATAR = "state_avatar"
        private const val STATE_REGISTER = "state_register"
        private const val STATE_YT_LAUNCHED = "state_youtube_launched"
        private const val STATE_MYST_SHOWN = "state_myst_shown"
        private const val STATE_FILE_PICKER = "state_file_picker"
    }
}
