package com.iskezis.myst.ui.auth

import android.content.Intent
import android.os.Bundle
import android.transition.TransitionManager
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.transition.MaterialSharedAxis
import com.iskezis.myst.MainActivity
import com.iskezis.myst.MystApp
import com.iskezis.myst.R
import com.iskezis.myst.databinding.ActivityAuthBinding
import kotlinx.coroutines.launch

class AuthActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAuthBinding
    private val viewModel: AuthViewModel by viewModels { AuthViewModel.factory(application) }
    private var registerMode = false
    private var navigated = false

    private val youtubeLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val data = result.data ?: return@registerForActivityResult
            val cookies = data.getStringExtra(YouTubeLoginActivity.EXTRA_COOKIES).orEmpty()
            if (cookies.isNotBlank()) {
                viewModel.loginYouTube(cookies, getString(R.string.youtube_account_fallback))
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if ((application as MystApp).session.isLoggedIn) {
            goToMain()
            return
        }
        binding = ActivityAuthBinding.inflate(layoutInflater)
        setContentView(binding.root)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, binding.root).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.authRoot) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        binding.youtubeButton.setOnClickListener {
            youtubeLauncher.launch(Intent(this, YouTubeLoginActivity::class.java))
        }
        binding.mystButton.setOnClickListener {
            showMystPanel(true)
        }
        binding.authBack.setOnClickListener { showMystPanel(false) }
        binding.authSwitch.setOnClickListener {
            registerMode = !registerMode
            renderMode(registerMode)
        }
        binding.authSubmit.setOnClickListener { submit() }
        binding.authConfirmInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                submit()
                true
            } else {
                false
            }
        }
        binding.authUsernameInput.doAfterTextChanged { binding.usernameLayout.error = null }
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

        collectState()
    }

    private fun collectState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state ->
                    binding.authProgress.isVisible = state.loading
                    binding.authSubmit.isEnabled = !state.loading
                    binding.authSwitch.isEnabled = !state.loading
                    binding.authError.isVisible = state.errorRes != null
                    state.errorRes?.let { binding.authError.setText(it) }
                    if (state.success && !navigated) {
                        navigated = true
                        goToMain()
                    }
                }
            }
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
            viewModel.register(username, password)
        } else {
            viewModel.login(username, password)
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
        binding.authConfirmInput.text?.clear()
    }

    private fun showMystPanel(show: Boolean) {
        val transition = MaterialSharedAxis(MaterialSharedAxis.X, show).apply {
            duration = 300
            addTarget(binding.entryPanel)
            addTarget(binding.mystPanel)
        }
        TransitionManager.beginDelayedTransition(binding.authRoot, transition)
        binding.entryPanel.visibility = if (show) View.GONE else View.VISIBLE
        binding.mystPanel.visibility = if (show) View.VISIBLE else View.GONE
        if (show) {
            binding.authUsernameInput.requestFocus()
        }
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
        startActivity(
            Intent(this, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK,
            ),
        )
        finish()
    }
}
