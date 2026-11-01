package com.iskezis.myst.ui.settings

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.iskezis.myst.BuildConfig
import com.iskezis.myst.MystApp
import com.iskezis.myst.R
import com.iskezis.myst.data.update.UpdateChannel
import com.iskezis.myst.data.update.UpdateCheckState
import com.iskezis.myst.databinding.FragmentUpdateSettingsBinding
import com.iskezis.myst.ui.update.UpdateUi
import com.iskezis.myst.util.AppLog
import com.iskezis.myst.util.MystTheme
import com.iskezis.myst.util.UiAnimations.applyFadeThrough
import kotlinx.coroutines.launch

/**
 * Configurações → Atualizações.
 *
 * O canal fica no aparelho (não na conta Myst). Trocar a opção limpa o cache
 * condicional da API e dispara uma verificação imediata; o botão também força
 * uma consulta manual. O resultado aparece inline em três estados (verificando,
 * em dia e erro com "Tentar novamente"), enquanto o pop-up de nova versão e o
 * download continuam no [UpdateUi] compartilhado com a Navegação.
 */
class UpdateSettingsFragment : Fragment() {

    private var _binding: FragmentUpdateSettingsBinding? = null
    private val binding get() = checkNotNull(_binding)

    private val app get() = requireActivity().application as MystApp

    private var updateUi: UpdateUi? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyFadeThrough()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentUpdateSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        AppLog.d(AppLog.TAG_UI, "UpdateSettingsFragment.onViewCreated")

        binding.updateInstalledVersion.text = getString(
            R.string.update_settings_installed_value,
            BuildConfig.VERSION_NAME,
            BuildConfig.VERSION_CODE,
        )

        updateUi = UpdateUi(this, binding.root, app.updates, showSnackbars = false).also {
            it.start()
        }

        binding.updateSettingsBack.setOnClickListener {
            runCatching { findNavController().navigateUp() }
                .onFailure { AppLog.failure(AppLog.TAG_UI, "Falha voltando de Atualizações", it) }
        }
        binding.updateChannelStableCard.setOnClickListener {
            selectChannel(UpdateChannel.STABLE)
        }
        binding.updateChannelBetaCard.setOnClickListener {
            selectChannel(UpdateChannel.BETA)
        }
        binding.updateCheckNow.setOnClickListener { checkNow() }
        binding.updateStatusRetry.setOnClickListener { checkNow() }

        renderChannel(app.updates.channel)

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                app.updates.checkState.collect { renderCheck(it) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // A instalação pode ter sido liberada nas configurações do Android.
        updateUi?.onResume()
    }

    override fun onDestroyView() {
        updateUi?.stop()
        updateUi = null
        AppLog.d(AppLog.TAG_UI, "UpdateSettingsFragment.onDestroyView")
        super.onDestroyView()
        _binding = null
    }

    private fun selectChannel(channel: UpdateChannel) {
        if (app.updates.channel == channel) return
        app.updates.setChannel(channel)
        renderChannel(channel)
        // A preferência vale na hora: ignora o intervalo de 6 h e o cache ETag.
        checkNow()
    }

    private fun checkNow() {
        if (app.updates.checkState.value == UpdateCheckState.Checking) return
        updateUi?.checkManually()
    }

    private fun renderChannel(channel: UpdateChannel) {
        val stable = channel == UpdateChannel.STABLE
        binding.updateChannelStableRadio.isChecked = stable
        binding.updateChannelBetaRadio.isChecked = !stable
        binding.updateChannelStableCard.isSelected = stable
        binding.updateChannelBetaCard.isSelected = !stable
        if (BuildConfig.FLAVOR == "tv") {
            val target = if (stable) {
                binding.updateChannelStableCard
            } else {
                binding.updateChannelBetaCard
            }
            target.post { if (isAdded && !target.hasFocus()) target.requestFocus() }
        }
    }

    private fun renderCheck(state: UpdateCheckState) {
        val checking = state == UpdateCheckState.Checking
        binding.updateCheckNow.isEnabled = !checking
        binding.updateStatusRetry.isVisible = state is UpdateCheckState.Failed

        when (state) {
            UpdateCheckState.Idle -> {
                binding.updateStatusCard.isVisible = false
            }

            UpdateCheckState.Checking -> showStatus(
                iconRes = 0,
                titleRes = R.string.update_settings_checking,
                messageRes = R.string.update_settings_checking_desc,
                progress = true,
            )

            UpdateCheckState.UpToDate -> showStatus(
                iconRes = R.drawable.ic_check,
                titleRes = R.string.update_settings_up_to_date_title,
                messageRes = R.string.update_settings_up_to_date_desc,
            )

            is UpdateCheckState.Available -> showStatus(
                iconRes = R.drawable.ic_github,
                titleRes = R.string.update_available_title,
                message = state.release.versionName,
            )

            is UpdateCheckState.Failed -> showStatus(
                iconRes = R.drawable.ic_error,
                titleRes = R.string.update_settings_error_title,
                messageRes = state.messageRes,
                error = true,
            )
        }
    }

    private fun showStatus(
        iconRes: Int,
        titleRes: Int,
        messageRes: Int? = null,
        message: String? = null,
        progress: Boolean = false,
        error: Boolean = false,
    ) {
        binding.updateStatusCard.isVisible = true
        binding.updateStatusProgress.isVisible = progress
        binding.updateStatusIcon.isVisible = !progress
        binding.updateStatusTitle.setText(titleRes)
        binding.updateStatusTitle.setTextColor(
            MystTheme.color(
                requireContext(),
                if (error) R.attr.mystError else R.attr.mystOnSurface,
            ),
        )
        binding.updateStatusMessage.text = message
            ?: messageRes?.let { getString(it) }
            ?: ""
        binding.updateStatusIcon.imageTintList = ColorStateList.valueOf(
            MystTheme.color(
                requireContext(),
                if (error) R.attr.mystError else R.attr.mystAccentText,
            ),
        )
        if (iconRes != 0) binding.updateStatusIcon.setImageResource(iconRes)
    }
}
