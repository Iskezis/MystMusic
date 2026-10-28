package com.iskezis.myst.ui.settings

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.iskezis.myst.BuildConfig
import com.iskezis.myst.R
import com.iskezis.myst.data.account.SessionManager
import com.iskezis.myst.data.discord.DiscordConnection
import com.iskezis.myst.data.discord.DiscordState
import com.iskezis.myst.databinding.FragmentSettingsBinding
import com.iskezis.myst.ui.auth.AuthActivity
import com.iskezis.myst.ui.auth.DiscordLoginActivity
import com.iskezis.myst.ui.logs.LogsActivity
import com.iskezis.myst.ui.pairing.PhonePairingActivity
import com.iskezis.myst.ui.pairing.TvPairingActivity
import com.iskezis.myst.util.AppLog
import com.iskezis.myst.util.UiAnimations.applyFadeThrough
import com.iskezis.myst.util.UiAnimations.pressScale
import kotlinx.coroutines.launch

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

        binding.settingsLogout.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.settings_logout)
                .setMessage(
                    getString(
                        R.string.settings_logout_confirm,
                        viewModel.state.value.name,
                    ),
                )
                .setNegativeButton(R.string.common_cancel, null)
                .setPositiveButton(R.string.settings_logout) { _, _ -> viewModel.logout() }
                .show()
        }

        binding.settingsDiscordAction.setOnClickListener { onDiscordAction() }
        binding.settingsDiscordCard.setOnClickListener { onDiscordAction() }
        binding.settingsTvCard.isVisible = BuildConfig.FLAVOR != "tv"
        binding.settingsTvCard.setOnClickListener {
            runCatching {
                startActivity(Intent(requireContext(), PhonePairingActivity::class.java))
            }.onFailure {
                AppLog.failure(AppLog.TAG_PAIRING, "Falha abrindo pareamento", it)
            }
        }
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
                            "Settings render: conta=${state.accountType}",
                        )
                        binding.settingsAccountName.text = state.name
                        binding.settingsAccountType.setText(
                            if (state.accountType == SessionManager.TYPE_YOUTUBE) {
                                R.string.settings_account_youtube
                            } else {
                                R.string.settings_account_myst
                            },
                        )
                        binding.settingsInitial.text = state.initial
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

    override fun onDestroyView() {
        AppLog.d(AppLog.TAG_UI, "SettingsFragment.onDestroyView")
        super.onDestroyView()
        _binding = null
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
                R.color.sky_400,
                R.string.discord_disconnect,
                R.drawable.ic_close,
            )
            DiscordConnection.CONNECTING -> Quad(
                R.string.settings_discord_connecting,
                R.color.text_dim,
                R.string.discord_disconnect,
                R.drawable.ic_close,
            )
            DiscordConnection.ERROR -> Quad(
                R.string.settings_discord_error,
                R.color.error,
                R.string.settings_discord_retry,
                R.drawable.ic_sync,
            )
            DiscordConnection.DISCONNECTED -> Quad(
                R.string.settings_discord_off,
                R.color.text_dim,
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
        binding.settingsDiscordStatus.setTextColor(ContextCompat.getColor(context, colorRes))
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
