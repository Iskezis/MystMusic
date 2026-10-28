package com.iskezis.myst

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.OnBackPressedCallback
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
import com.iskezis.myst.ui.pairing.TvPairingActivity
import com.iskezis.myst.ui.player.PlayerActivity
import com.iskezis.myst.util.AppLog
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
    private var lastLoggedVideoId: String? = null
    private var lastLoggedPlaying: Boolean? = null
    private var lastLoggedError: String? = null

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* Negar não impede o uso do app. */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLog.i(AppLog.TAG_UI, "MainActivity.onCreate savedInstanceState=${savedInstanceState != null}")
        val app = application as MystApp
        if (BuildConfig.FLAVOR == "tv" && (!app.session.tvPaired || !app.session.isLoggedIn)) {
            // Na TV não há teclado físico confortável: conta e playlists chegam
            // sempre pelo pareamento com o celular. Isso também cobre o caso de
            // sessão apagada com a flag de pareamento ainda ligada.
            AppLog.i(AppLog.TAG_UI, "TV precisa de pareamento; abrindo TvPairingActivity")
            startActivity(
                Intent(this, TvPairingActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK,
                ),
            )
            finish()
            return
        }
        if (!app.session.isLoggedIn) {
            AppLog.i(AppLog.TAG_UI, "Sem sessão; abrindo AuthActivity")
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
                AppLog.d(AppLog.TAG_UI, "Mini player tocado; abrindo PlayerActivity")
                try {
                    startActivity(Intent(this, PlayerActivity::class.java))
                    overridePendingTransition(R.anim.screen_fade_in, R.anim.screen_fade_out)
                } catch (t: Throwable) {
                    AppLog.failure(AppLog.TAG_UI, "Falha ao abrir PlayerActivity", t)
                    android.widget.Toast.makeText(
                        this,
                        R.string.player_open_error,
                        android.widget.Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
        binding.miniPlayer.miniPlay.setOnClickListener {
            AppLog.d(AppLog.TAG_UI, "Botão play/pause do mini player")
            (application as MystApp).playerController.toggle()
        }

        navController.addOnDestinationChangedListener { _, destination, _ ->
            AppLog.d(
                AppLog.TAG_UI,
                "Destino: ${destination.label ?: destination.id} (${destination.id})",
            )
            updateSelection(destination.id)
            renderMiniPlayer()
        }
        updateSelection(navController.currentDestination?.id ?: R.id.homeFragment)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (navController.currentDestination?.id != navController.graph.startDestinationId) {
                    val moved = runCatching { navController.popBackStack() }.getOrDefault(false)
                    if (!moved) navigateTo(navController.graph.startDestinationId)
                    return
                }
                if (playerState.track != null) {
                    // Voltar na home não encerra o app com música tocando; a
                    // reprodução continua no PlaybackService e o usuário pode
                    // retornar sem perder a fila.
                    AppLog.d(AppLog.TAG_UI, "Back na home com música; indo para segundo plano")
                    moveTaskToBack(true)
                    return
                }
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
            }
        })

        observePlayer()
        requestNotificationPermissionIfNeeded()
    }

    private fun observePlayer() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                (application as MystApp).playerController.state.collect { state ->
                    playerState = state
                    if (state.track?.videoId != lastLoggedVideoId) {
                        lastLoggedVideoId = state.track?.videoId
                        AppLog.i(
                            AppLog.TAG_PLAYER,
                            "Faixa atual: ${state.track?.title ?: "<nenhuma>"} " +
                                "(${state.track?.videoId ?: "-"})",
                        )
                    }
                    if (state.isPlaying != lastLoggedPlaying) {
                        lastLoggedPlaying = state.isPlaying
                        AppLog.i(
                            AppLog.TAG_PLAYER,
                            "isPlaying=${
                                state.isPlaying
                            } posição=${AppLog.durationForLog(state.positionMs)} " +
                                "duração=${AppLog.durationForLog(state.durationMs)}",
                        )
                    }
                    if (state.error && state.errorMessage != lastLoggedError) {
                        lastLoggedError = state.errorMessage
                        AppLog.e(
                            AppLog.TAG_PLAYER,
                            "Erro visível no mini player: ${state.errorMessage}",
                        )
                    } else if (!state.error) {
                        lastLoggedError = null
                    }
                    renderMiniPlayer()
                    runCatching {
                        (application as MystApp).discord.onPlayerState(state)
                    }.onFailure {
                        AppLog.failure(
                            AppLog.TAG_UI,
                            "Falha atualizando Discord; player continua",
                            it,
                        )
                    }
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
        binding.miniPlayer.miniCover.load(track.artworkUrl()) {
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
        AppLog.d(AppLog.TAG_UI, "Navegando para destino $destinationId")
        val options = NavOptions.Builder()
            .setLaunchSingleTop(true)
            .setRestoreState(true)
            .setPopUpTo(navController.graph.startDestinationId, false)
            .build()
        runCatching { navController.navigate(destinationId, null, options) }
            .onFailure {
                AppLog.failure(
                    AppLog.TAG_UI,
                    "Falha navegando para o destino $destinationId",
                    it,
                )
            }
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
        val bar = binding.navBar.root
        bar.animate().cancel()
        if (visible) {
            bar.animate()
                .translationY(0f)
                .alpha(1f)
                .setDuration(250)
                .start()
        } else {
            val height = bar.height.takeIf { it > 0 } ?: dp(80f).toInt()
            val offset = height + resources.getDimension(R.dimen.nav_margin_bottom)
            bar.animate()
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
