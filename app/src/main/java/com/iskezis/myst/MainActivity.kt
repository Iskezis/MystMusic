package com.iskezis.myst

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavController
import androidx.navigation.NavOptions
import androidx.navigation.fragment.NavHostFragment
import coil.load
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.iskezis.myst.databinding.ActivityMainBinding
import com.iskezis.myst.player.PlayerState
import com.iskezis.myst.ui.auth.AuthActivity
import com.iskezis.myst.util.UiAnimations.animatePlayIcon
import com.iskezis.myst.util.UiAnimations.pressScale
import com.iskezis.myst.util.formatDuration
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var navController: NavController
    private var playerState = PlayerState()
    private var miniVisible = false
    private var navBarHidden = false

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* Negar não impede o uso do app. */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as MystApp
        if (!app.session.isLoggedIn) {
            startActivity(
                Intent(this, AuthActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK,
                ),
            )
            finish()
            return
        }
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, binding.root).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        val navHost = supportFragmentManager.findFragmentById(R.id.navHost) as NavHostFragment
        navController = navHost.navController

        binding.navBar.navHome.setOnClickListener { navigateTo(R.id.homeFragment) }
        binding.navBar.navPlaylists.setOnClickListener { navigateTo(R.id.playlistsFragment) }
        binding.navBar.navSettings.setOnClickListener { navigateTo(R.id.settingsFragment) }
        binding.navBar.navHome.pressScale()
        binding.navBar.navPlaylists.pressScale()
        binding.navBar.navSettings.pressScale()
        binding.navBar.navCenterCard.pressScale()
        binding.miniPlayer.root.pressScale()

        binding.miniPlayer.root.setOnClickListener {
            if (navController.currentDestination?.id != R.id.playerFragment) {
                val options = NavOptions.Builder().setLaunchSingleTop(true).build()
                navController.navigate(R.id.playerFragment, null, options)
            }
        }
        binding.miniPlayer.miniPlay.setOnClickListener {
            (application as MystApp).playerController.toggle()
        }

        navController.addOnDestinationChangedListener { _, destination, _ ->
            updateSelection(destination.id)
            renderMiniPlayer()
        }
        updateSelection(navController.currentDestination?.id ?: R.id.homeFragment)

        observePlayer()
        requestNotificationPermissionIfNeeded()
    }

    private fun observePlayer() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                (application as MystApp).playerController.state.collect { state ->
                    playerState = state
                    renderMiniPlayer()
                    (application as MystApp).discord.onPlayerState(state)
                }
            }
        }
    }

    private fun renderMiniPlayer() {
        val state = playerState
        val track = state.track
        val onPlayerScreen = navController.currentDestination?.id == R.id.playerFragment
        val shouldShow = track != null && !onPlayerScreen
        if (shouldShow) {
            if (!miniVisible) {
                miniVisible = true
                binding.miniPlayer.root.animate().cancel()
                binding.miniPlayer.root.isVisible = true
                binding.miniPlayer.root.alpha = 0f
                binding.miniPlayer.root.translationY = dp(20f)
                binding.miniPlayer.root.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(240)
                    .start()
            }
        } else {
            if (miniVisible) {
                miniVisible = false
                binding.miniPlayer.root.animate().cancel()
                binding.miniPlayer.root.animate()
                    .alpha(0f)
                    .translationY(dp(20f))
                    .setDuration(180)
                    .withEndAction {
                        if (!miniVisible) binding.miniPlayer.root.isVisible = false
                    }
                    .start()
            } else if (!onPlayerScreen) {
                binding.miniPlayer.root.isVisible = false
            }
        }
        if (track == null) return

        binding.miniPlayer.miniTitle.text = track.title.ifBlank {
            getString(R.string.player_unknown_title)
        }
        binding.miniPlayer.miniArtist.text = track.artist.ifBlank {
            getString(R.string.player_unknown_artist)
        }
        binding.miniPlayer.miniCover.contentDescription = getString(
            R.string.content_cover,
            track.title,
        )
        binding.miniPlayer.miniCover.load(track.thumbnailUrl) {
            crossfade(true)
            placeholder(R.drawable.bg_cover_placeholder)
            error(R.drawable.bg_cover_placeholder)
        }
        animatePlayIcon(
            binding.miniPlayer.miniPlay,
            state.isPlaying,
            R.drawable.ic_play,
            R.drawable.ic_pause,
        )
        binding.miniPlayer.miniPlay.contentDescription = getString(
            if (state.isPlaying) R.string.player_pause else R.string.player_play,
        )
        val progress = if (state.durationMs > 0) {
            (state.positionMs * 1000 / state.durationMs).toInt().coerceIn(0, 1000)
        } else {
            0
        }
        com.iskezis.myst.util.UiAnimations.animateProgress(
            binding.miniPlayer.miniProgress,
            progress,
        )
        binding.miniPlayer.root.contentDescription = getString(
            R.string.player_now_playing,
        ) + ": " + track.title + " — " + formatDuration(state.positionMs)
    }

    private fun navigateTo(destinationId: Int) {
        if (navController.currentDestination?.id == destinationId) return
        val options = NavOptions.Builder()
            .setLaunchSingleTop(true)
            .setRestoreState(true)
            .setPopUpTo(navController.graph.startDestinationId, false)
            .build()
        navController.navigate(destinationId, null, options)
        if (destinationId == R.id.playlistsFragment) {
            binding.navBar.navCenterCard.animate()
                .scaleX(1.12f).scaleY(1.12f).setDuration(120)
                .withEndAction {
                    binding.navBar.navCenterCard.animate()
                        .scaleX(1f).scaleY(1f).setDuration(180).start()
                }
                .start()
        }
    }

    private fun updateSelection(destinationId: Int) {
        binding.navBar.navHome.isSelected = destinationId == R.id.homeFragment
        binding.navBar.navPlaylists.isSelected = destinationId == R.id.playlistsFragment
        binding.navBar.navSettings.isSelected = destinationId == R.id.settingsFragment
        animateNavBar(visible = destinationId != R.id.playerFragment)
    }

    private fun animateNavBar(visible: Boolean) {
        if (visible == !navBarHidden) return
        navBarHidden = !visible
        binding.navBar.animate().cancel()
        if (visible) {
            binding.navBar.animate()
                .translationY(0f)
                .alpha(1f)
                .setDuration(250)
                .start()
        } else {
            val height = binding.navBar.height.takeIf { it > 0 } ?: dp(80f).toInt()
            val offset = height + resources.getDimension(R.dimen.nav_margin_bottom)
            binding.navBar.animate()
                .translationY(offset)
                .alpha(0f)
                .setDuration(250)
                .start()
        }
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) return
        val prefs = getSharedPreferences("myst_ui", MODE_PRIVATE)
        if (prefs.getBoolean("notification_asked", false)) return
        prefs.edit().putBoolean("notification_asked", true).apply()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.notifications_title)
            .setMessage(R.string.notifications_message)
            .setNegativeButton(R.string.notifications_later, null)
            .setPositiveButton(R.string.notifications_allow) { _, _ ->
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            .show()
    }
}
