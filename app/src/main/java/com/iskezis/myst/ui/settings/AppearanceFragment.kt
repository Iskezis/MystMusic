package com.iskezis.myst.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.transition.AutoTransition
import androidx.transition.TransitionManager
import com.iskezis.myst.R
import com.iskezis.myst.data.settings.ThemeMode
import com.iskezis.myst.databinding.FragmentAppearanceBinding
import com.iskezis.myst.util.AppLog
import com.iskezis.myst.util.UiAnimations.applyFadeThrough
import kotlinx.coroutines.launch

class AppearanceFragment : Fragment() {

    private var _binding: FragmentAppearanceBinding? = null
    private val binding get() = checkNotNull(_binding)
    private val viewModel: AppearanceViewModel by viewModels {
        AppearanceViewModel.factory(requireActivity().application)
    }

    /**
     * Evita que o render programático dos radios/switch dispare os listeners e
     * grave uma preferência que o usuário não escolheu.
     */
    private var rendering = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyFadeThrough()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentAppearanceBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        AppLog.d(AppLog.TAG_UI, "AppearanceFragment.onViewCreated")

        binding.appearanceBack.setOnClickListener {
            findNavController().navigateUp()
        }

        binding.appearanceCardSystem.setOnClickListener {
            viewModel.setThemeMode(ThemeMode.SYSTEM)
        }
        binding.appearanceCardLight.setOnClickListener {
            viewModel.setThemeMode(ThemeMode.LIGHT)
        }
        binding.appearanceCardDark.setOnClickListener {
            viewModel.setThemeMode(ThemeMode.DARK)
        }

        binding.appearanceAmoledSwitch.setOnCheckedChangeListener { _, checked ->
            if (rendering) return@setOnCheckedChangeListener
            if (viewModel.setAmoled(checked)) {
                // O overlay AMOLED só pode ser aplicado no início da Activity.
                // Recriar mantém o mesmo destino e não reinicia o processo.
                requireActivity().recreate()
            }
        }

        viewModel.refresh()
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { render(it) }
            }
        }
    }

    private fun render(state: AppearanceState) {
        rendering = true
        binding.appearanceRadioSystem.isChecked = state.mode == ThemeMode.SYSTEM
        binding.appearanceRadioLight.isChecked = state.mode == ThemeMode.LIGHT
        binding.appearanceRadioDark.isChecked = state.mode == ThemeMode.DARK

        binding.appearanceCardSystem.isSelected = state.mode == ThemeMode.SYSTEM
        binding.appearanceCardLight.isSelected = state.mode == ThemeMode.LIGHT
        binding.appearanceCardDark.isSelected = state.mode == ThemeMode.DARK

        binding.appearanceAmoledSwitch.isChecked = state.amoled
        val showAmoled = state.darkEffective
        if (showAmoled != binding.appearanceAmoledRow.isVisible) {
            TransitionManager.beginDelayedTransition(
                binding.appearanceContent,
                AutoTransition().apply { duration = 200 },
            )
            binding.appearanceAmoledRow.isVisible = showAmoled
        }
        rendering = false
    }

    override fun onDestroyView() {
        AppLog.d(AppLog.TAG_UI, "AppearanceFragment.onDestroyView")
        super.onDestroyView()
        _binding = null
    }
}
