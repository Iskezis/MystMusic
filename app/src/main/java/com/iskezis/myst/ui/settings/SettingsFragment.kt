package com.iskezis.myst.ui.settings

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import coil.load
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.iskezis.myst.BuildConfig
import com.iskezis.myst.R
import com.iskezis.myst.data.account.AvatarStore
import com.iskezis.myst.data.account.MystAccountFileRepository
import com.iskezis.myst.data.discord.DiscordConnection
import com.iskezis.myst.data.discord.DiscordState
import com.iskezis.myst.databinding.FragmentSettingsBinding
import com.iskezis.myst.ui.auth.AuthActivity
import com.iskezis.myst.ui.auth.AvatarCropActivity
import com.iskezis.myst.ui.auth.DiscordLoginActivity
import com.iskezis.myst.ui.logs.LogsActivity
import com.iskezis.myst.ui.pairing.PhonePairingActivity
import com.iskezis.myst.ui.pairing.TvPairingActivity
import com.iskezis.myst.util.AppLog
import com.iskezis.myst.util.MystTheme
import com.iskezis.myst.util.UiAnimations.applyFadeThrough
import com.iskezis.myst.util.UiAnimations.pressScale
import kotlinx.coroutines.launch
import java.io.File

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = checkNotNull(_binding)
    private val viewModel: SettingsViewModel by viewModels {
        SettingsViewModel.factory(requireActivity().application)
    }

    private val discordLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val token = result.data
                ?.getStringExtra(DiscordLoginActivity.EXTRA_TOKEN)
                .orEmpty()
            if (token.isNotBlank()) viewModel.connectDiscord(token)
        }
    }

    private val avatarPicker = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        avatarCropLauncher.launch(
            Intent(requireContext(), AvatarCropActivity::class.java)
                .putExtra(AvatarCropActivity.EXTRA_SOURCE_URI, uri.toString()),
        )
    }

    private val avatarCropLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val path = result.data?.getStringExtra(AvatarCropActivity.EXTRA_OUTPUT_PATH)
        if (path.isNullOrBlank()) {
            Toast.makeText(requireContext(), R.string.settings_avatar_error, Toast.LENGTH_LONG).show()
            return@registerForActivityResult
        }
        viewModel.changeAvatar(path)
    }

    private val accountFileCreator = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri != null) viewModel.saveAccountFile(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyFadeThrough()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        AppLog.d(AppLog.TAG_UI, "SettingsFragment.onViewCreated")
        binding.settingsVersion.text = getString(R.string.settings_version, BuildConfig.VERSION_NAME)

        binding.settingsLogout.setOnClickListener { showLogoutDialog() }
        binding.settingsAccountFileAction.setOnClickListener { saveAccountFile() }
        binding.settingsAccountFileAction.pressScale()
        // A TV recebe a conta pelo pareamento e não tem um seletor de arquivos
        // confortável no controle remoto.
        binding.settingsAccountFileSection.isVisible = BuildConfig.FLAVOR != "tv"
        if (BuildConfig.FLAVOR != "tv") {
            binding.settingsAvatarFrame.setOnClickListener { pickAvatar() }
            binding.settingsAvatarFrame.pressScale()
        } else {
            // A TV recebe a foto pelo pareamento e não tem um seletor de
            // imagens confortável no controle remoto.
            binding.settingsAvatarFrame.isClickable = false
            binding.settingsAvatarFrame.isFocusable = false
        }

        binding.settingsDiscordAction.setOnClickListener { onDiscordAction() }
        binding.settingsDiscordCard.setOnClickListener { onDiscordAction() }
        binding.settingsAppearanceCard.setOnClickListener {
            runCatching { findNavController().navigate(R.id.appearanceFragment) }
                .onFailure {
                    AppLog.failure(AppLog.TAG_UI, "Falha abrindo Aparência", it)
                }
        }
        binding.settingsAppearanceCard.pressScale()
        binding.settingsTvCard.isVisible = BuildConfig.FLAVOR != "tv"
        binding.settingsTvCard.setOnClickListener {
            if (viewModel.state.value.pairedTv) return@setOnClickListener
            runCatching {
                startActivity(Intent(requireContext(), PhonePairingActivity::class.java))
            }.onFailure {
                AppLog.failure(AppLog.TAG_PAIRING, "Falha abrindo pareamento", it)
            }
        }
        binding.settingsTvDisconnect.setOnClickListener { showTvDisconnectDialog() }
        binding.settingsTvDisconnect.pressScale()
        binding.settingsLogout.pressScale()
        binding.settingsDiscordAction.pressScale()
        binding.settingsLogsCard.setOnClickListener {
            runCatching {
                startActivity(Intent(requireContext(), LogsActivity::class.java))
            }.onFailure {
                AppLog.failure(AppLog.TAG_UI, "Falha abrindo tela de logs", it)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.state.collect { state ->
                        AppLog.d(
                            AppLog.TAG_UI,
                            "Settings render: youtube=${state.youtubeConnected}",
                        )
                        binding.settingsAccountName.text = state.name
                        binding.settingsAccountType.text = if (state.youtubeConnected) {
                            getString(
                                R.string.settings_youtube_connected,
                                state.youtubeName.ifBlank {
                                    getString(R.string.youtube_account_fallback)
                                },
                            )
                        } else {
                            getString(R.string.settings_youtube_disconnected)
                        }
                        binding.settingsInitial.text = state.initial
                        renderAvatar(state)
                        renderAccountFile(state)
                        renderTv(state)
                    }
                }
                launch {
                    viewModel.error.collect { errorRes ->
                        if (errorRes != null) {
                            Toast.makeText(
                                requireContext(),
                                errorRes,
                                Toast.LENGTH_LONG,
                            ).show()
                            viewModel.consumeError()
                        }
                    }
                }
                launch {
                    viewModel.message.collect { messageRes ->
                        if (messageRes != null) {
                            Toast.makeText(
                                requireContext(),
                                messageRes,
                                Toast.LENGTH_LONG,
                            ).show()
                            viewModel.consumeMessage()
                        }
                    }
                }
                launch {
                    viewModel.loggedOut.collect { done ->
                        if (done) {
                            // Na TV o login por teclado/WebView não é viável com
                            // controle remoto; deslogar volta para o pareamento.
                            val target = if (BuildConfig.FLAVOR == "tv") {
                                TvPairingActivity::class.java
                            } else {
                                AuthActivity::class.java
                            }
                            val intent = Intent(requireContext(), target).addFlags(
                                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK,
                            )
                            startActivity(intent)
                            requireActivity().finish()
                        }
                    }
                }
                launch {
                    viewModel.discord.collect { discord -> renderDiscord(discord) }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Ao voltar do pareamento (ou de outra Activity) o ViewModel pode ter
        // sido criado antes de o segredo ser salvo.
        viewModel.refreshPairing()
    }

    override fun onDestroyView() {
        AppLog.d(AppLog.TAG_UI, "SettingsFragment.onDestroyView")
        super.onDestroyView()
        _binding = null
    }

    private fun pickAvatar() {
        runCatching {
            avatarPicker.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
            )
        }.onFailure { t ->
            AppLog.failure(AppLog.TAG_UI, "Seletor de fotos indisponível", t)
            Toast.makeText(requireContext(), R.string.settings_avatar_error, Toast.LENGTH_LONG)
                .show()
        }
    }

    private fun saveAccountFile() {
        if (viewModel.state.value.accountFileSaving) return
        runCatching {
            accountFileCreator.launch(MystAccountFileRepository.DEFAULT_FILE_NAME)
        }.onFailure { t ->
            AppLog.failure(AppLog.TAG_AUTH, "Seletor de arquivos indisponível", t)
            Toast.makeText(requireContext(), R.string.account_file_error, Toast.LENGTH_LONG).show()
        }
    }

    private fun renderAccountFile(state: SettingsState) {
        val status = state.accountFileStatus
        val statusRes = when {
            state.accountFileSaving -> R.string.settings_account_file_working
            status.error -> R.string.settings_account_file_status_error
            status.externalSaved && status.externalAutoSync ->
                R.string.settings_account_file_status_synced
            status.externalSaved -> R.string.settings_account_file_status_manual
            status.lastSyncAt > 0L -> R.string.settings_account_file_status_internal
            else -> R.string.settings_account_file_status_missing
        }
        binding.settingsAccountFileStatus.setText(statusRes)
        binding.settingsAccountFileStatus.setTextColor(
            MystTheme.color(
                requireContext(),
                if (status.error) R.attr.mystError else R.attr.mystOnSurfaceDim,
            ),
        )
        binding.settingsAccountFileAction.isEnabled = !state.accountFileSaving
        binding.settingsAccountFileAction.setText(
            if (state.accountFileSaving) {
                R.string.settings_account_file_saving
            } else {
                R.string.settings_account_file_save
            },
        )
    }

    private fun renderTv(state: SettingsState) {
        val connected = state.pairedTv && BuildConfig.FLAVOR != "tv"
        binding.settingsTvStatus.isVisible = connected
        binding.settingsTvDisconnect.isVisible = connected
        binding.settingsTvCard.isClickable = !connected
        binding.settingsTvCard.isFocusable = !connected
        if (connected) {
            binding.settingsTvStatus.text = getString(
                R.string.settings_tv_connected,
                state.pairedTvName.ifBlank { getString(R.string.settings_tv_default_name) },
            )
            binding.settingsTvDisconnect.isEnabled = !state.tvDisconnecting
            binding.settingsTvDisconnect.setText(
                if (state.tvDisconnecting) {
                    R.string.settings_tv_disconnecting
                } else {
                    R.string.settings_tv_disconnect
                },
            )
        }
    }

    private fun showTvDisconnectDialog() {
        if (viewModel.state.value.tvDisconnecting) return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.settings_tv_disconnect_confirm_title)
            .setMessage(R.string.settings_tv_disconnect_confirm_message)
            .setNegativeButton(R.string.common_cancel, null)
            .setPositiveButton(R.string.settings_tv_disconnect) { _, _ ->
                viewModel.disconnectTv()
            }
            .show()
    }

    private fun renderAvatar(state: SettingsState) {
        binding.settingsAvatarFrame.contentDescription = if (state.name.isBlank()) {
            getString(R.string.settings_avatar_change)
        } else {
            getString(R.string.settings_avatar_of, state.name)
        }
        val path = state.avatarPath
        val key = AvatarStore.cacheKey(path)
        if (path != null && key != null) {
            binding.settingsAvatarImage.isVisible = true
            binding.settingsInitial.isVisible = false
            binding.settingsAvatarImage.load(File(path)) {
                memoryCacheKey(key)
                diskCacheKey(key)
                crossfade(true)
                placeholder(R.drawable.bg_avatar)
                error(R.drawable.bg_avatar)
            }
        } else {
            binding.settingsAvatarImage.isVisible = false
            binding.settingsInitial.isVisible = true
        }
    }

    private fun showLogoutDialog() {
        val options = arrayOf(
            getString(R.string.settings_logout_myst),
            getString(R.string.settings_logout_youtube),
            getString(R.string.settings_logout_both),
        )
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.settings_logout_title)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> viewModel.logoutMyst()
                    1 -> viewModel.logoutYouTube()
                    2 -> viewModel.logoutAll()
                }
            }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }

    private fun onDiscordAction() {
        val state = viewModel.discord.value
        AppLog.d(AppLog.TAG_UI, "Ação Discord: ${state.connection}")
        when {
            state.connection == DiscordConnection.CONNECTED ||
                state.connection == DiscordConnection.CONNECTING -> viewModel.disconnectDiscord()

            viewModel.hasDiscordToken() -> viewModel.retryDiscord()

            else -> showDiscordWarning()
        }
    }

    private fun showDiscordWarning() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.discord_warning_title)
            .setMessage(R.string.discord_warning)
            .setNegativeButton(R.string.common_cancel, null)
            .setPositiveButton(R.string.discord_accept) { _, _ ->
                discordLauncher.launch(Intent(requireContext(), DiscordLoginActivity::class.java))
            }
            .show()
    }

    private fun renderDiscord(state: DiscordState) {
        AppLog.d(AppLog.TAG_UI, "Discord: ${state.connection} usuário=${state.userName ?: "-"}")
        val context = requireContext()
        val (statusRes, colorRes, buttonRes, iconRes) = when (state.connection) {
            DiscordConnection.CONNECTED -> Quad(
                R.string.settings_discord_on,
                R.attr.mystAccentText,
                R.string.discord_disconnect,
                R.drawable.ic_close,
            )
            DiscordConnection.CONNECTING -> Quad(
                R.string.settings_discord_connecting,
                R.attr.mystOnSurfaceDim,
                R.string.discord_disconnect,
                R.drawable.ic_close,
            )
            DiscordConnection.ERROR -> Quad(
                R.string.settings_discord_error,
                R.attr.mystError,
                R.string.settings_discord_retry,
                R.drawable.ic_sync,
            )
            DiscordConnection.DISCONNECTED -> Quad(
                R.string.settings_discord_off,
                R.attr.mystOnSurfaceDim,
                R.string.settings_discord_connect,
                R.drawable.ic_discord,
            )
        }
        val status = when {
            state.connection == DiscordConnection.CONNECTED && !state.userName.isNullOrBlank() ->
                getString(R.string.settings_discord_connected, state.userName)
            else -> getString(statusRes)
        }
        binding.settingsDiscordStatus.text = status
        binding.settingsDiscordStatus.setTextColor(MystTheme.color(context, colorRes))
        binding.settingsDiscordAction.setText(buttonRes)
        binding.settingsDiscordAction.setIconResource(iconRes)
    }

    private data class Quad(
        val status: Int,
        val color: Int,
        val button: Int,
        val icon: Int,
    )
}
