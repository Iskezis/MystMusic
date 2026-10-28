package com.iskezis.myst.ui.settings

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.iskezis.myst.BuildConfig
import com.iskezis.myst.R
import com.iskezis.myst.data.account.SessionManager
import com.iskezis.myst.databinding.FragmentSettingsBinding
import com.iskezis.myst.ui.auth.AuthActivity
import kotlinx.coroutines.launch

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = checkNotNull(_binding)
    private val viewModel: SettingsViewModel by viewModels {
        SettingsViewModel.factory(requireActivity().application)
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

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.state.collect { state ->
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
                            val intent = Intent(requireContext(), AuthActivity::class.java).addFlags(
                                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK,
                            )
                            startActivity(intent)
                            requireActivity().finish()
                        }
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
