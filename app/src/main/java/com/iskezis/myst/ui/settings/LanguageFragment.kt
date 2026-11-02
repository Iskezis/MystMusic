package com.iskezis.myst.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.iskezis.myst.BuildConfig
import com.iskezis.myst.MystApp
import com.iskezis.myst.data.settings.AppLanguage
import com.iskezis.myst.databinding.FragmentLanguageBinding
import com.iskezis.myst.util.AppLog
import com.iskezis.myst.util.UiAnimations.applyFadeThrough

/**
 * Configurações → Idioma.
 *
 * A escolha é aplicada imediatamente via AppCompatDelegate; o AppCompat recria
 * a Activity e o Navigation restaura esta mesma tela. Na TV pareada as opções
 * ficam desabilitadas porque o idioma é o do celular até a desconexão.
 */
class LanguageFragment : Fragment() {

    private var _binding: FragmentLanguageBinding? = null
    private val binding get() = checkNotNull(_binding)
    private val app get() = requireActivity().application as MystApp

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyFadeThrough()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentLanguageBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        AppLog.d(AppLog.TAG_UI, "LanguageFragment.onViewCreated")

        binding.languageBack.setOnClickListener {
            runCatching { findNavController().navigateUp() }
                .onFailure { AppLog.failure(AppLog.TAG_UI, "Falha voltando de Idioma", it) }
        }
        binding.languageCardSystem.setOnClickListener { select(AppLanguage.SYSTEM) }
        binding.languageCardPortuguese.setOnClickListener { select(AppLanguage.PORTUGUESE) }
        binding.languageCardEnglish.setOnClickListener { select(AppLanguage.ENGLISH) }
        binding.languageCardSpanish.setOnClickListener { select(AppLanguage.SPANISH) }

        render()
    }

    private fun select(language: AppLanguage) {
        if (isManagedByPhone()) return
        app.language.setLanguage(language)
    }

    private fun render() {
        val managedByPhone = isManagedByPhone()
        val shown = if (managedByPhone) app.language.effectiveLanguage() else app.language.language

        binding.languageRadioSystem.isChecked = shown == AppLanguage.SYSTEM
        binding.languageRadioPortuguese.isChecked = shown == AppLanguage.PORTUGUESE
        binding.languageRadioEnglish.isChecked = shown == AppLanguage.ENGLISH
        binding.languageRadioSpanish.isChecked = shown == AppLanguage.SPANISH

        binding.languageCardSystem.isSelected = shown == AppLanguage.SYSTEM
        binding.languageCardPortuguese.isSelected = shown == AppLanguage.PORTUGUESE
        binding.languageCardEnglish.isSelected = shown == AppLanguage.ENGLISH
        binding.languageCardSpanish.isSelected = shown == AppLanguage.SPANISH

        binding.languagePairedNote.isVisible = managedByPhone
        val cards = listOf(
            binding.languageCardSystem,
            binding.languageCardPortuguese,
            binding.languageCardEnglish,
            binding.languageCardSpanish,
        )
        cards.forEach { card ->
            card.isEnabled = !managedByPhone
            card.isFocusable = !managedByPhone
            card.alpha = if (managedByPhone) 0.55f else 1f
        }

        if (BuildConfig.FLAVOR == "tv") {
            val target = if (managedByPhone) {
                binding.languageBack
            } else {
                when (shown) {
                    AppLanguage.SYSTEM -> binding.languageCardSystem
                    AppLanguage.PORTUGUESE -> binding.languageCardPortuguese
                    AppLanguage.ENGLISH -> binding.languageCardEnglish
                    AppLanguage.SPANISH -> binding.languageCardSpanish
                }
            }
            target.post {
                if (isAdded && target.isShown && !target.hasFocus()) target.requestFocus()
            }
        }
    }

    private fun isManagedByPhone(): Boolean =
        BuildConfig.FLAVOR == "tv" && app.language.pairedLanguage != null

    override fun onDestroyView() {
        AppLog.d(AppLog.TAG_UI, "LanguageFragment.onDestroyView")
        super.onDestroyView()
        _binding = null
    }
}
