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
import com.iskezis.myst.util.formatDuration
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var navController: NavController
    private var playerState = PlayerState()

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
        binding.miniPlayer.root.isVisible = track != null && !onPlayerScreen
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
        binding.miniPlayer.miniPlay.setImageResource(
            if (state.isPlaying) R.drawable.ic_pause else R.drawable.ic_play,
        )
        binding.miniPlayer.miniPlay.contentDescription = getString(
            if (state.isPlaying) R.string.player_pause else R.string.player_play,
        )
        val progress = if (state.durationMs > 0) {
            (state.positionMs * 1000 / state.durationMs).toInt().coerceIn(0, 1000)
        } else {
            0
        }
        binding.miniPlayer.miniProgress.progress = progress
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
    }

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
