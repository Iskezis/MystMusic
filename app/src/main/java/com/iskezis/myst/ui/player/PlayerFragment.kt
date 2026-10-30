package com.iskezis.myst.ui.player

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.Player
import androidx.navigation.fragment.findNavController
import coil.load
import com.iskezis.myst.MystApp
import com.iskezis.myst.R
import com.iskezis.myst.data.youtube.Track
import com.iskezis.myst.databinding.FragmentPlayerBinding
import com.iskezis.myst.player.PlayerController
import com.iskezis.myst.player.PlayerState
import com.iskezis.myst.ui.auth.YouTubeLoginActivity
import com.iskezis.myst.ui.browse.BrowseNavigation
import com.iskezis.myst.ui.playlists.AddToPlaylistSheet
import com.iskezis.myst.util.AppLog
import com.iskezis.myst.util.UiAnimations.animatePlayIcon
import com.iskezis.myst.util.UiAnimations.pressScale
import com.iskezis.myst.util.formatDuration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class PlayerFragment : Fragment() {

    private var _binding: FragmentPlayerBinding? = null
    private val binding get() = checkNotNull(_binding)
    private val controller: PlayerController?
        get() = (activity?.application as? MystApp)?.playerController
    private var latest: PlayerState = PlayerState()
    private var seeking = false
    private var loadedArtworkFor: String? = null
    private var lastLoggedTrack: String? = null
    private var lastLoggedPlaying: Boolean? = null

    private val youtubeLogin = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) {
            AppLog.i(AppLog.TAG_AUTH, "Login YouTube cancelado no player")
            return@registerForActivityResult
        }
        val cookies = result.data?.getStringExtra(YouTubeLoginActivity.EXTRA_COOKIES).orEmpty()
        val host = activity ?: return@registerForActivityResult
        val app = host.application as? MystApp
        if (cookies.isBlank() || app == null) {
            AppLog.w(AppLog.TAG_AUTH, "Login YouTube no player sem cookies válidos")
            Toast.makeText(host, R.string.player_login_failed, Toast.LENGTH_LONG).show()
            return@registerForActivityResult
        }
        AppLog.i(AppLog.TAG_AUTH, "Cookies do YouTube recebidos no player: ${cookies.length} bytes")
        host.lifecycleScope.launch {
            try {
                val name = app.accounts.linkYouTube(
                    cookies,
                    host.getString(R.string.youtube_account_fallback),
                )
                AppLog.i(AppLog.TAG_AUTH, "YouTube vinculado no player: $name")
                Toast.makeText(
                    host,
                    R.string.player_login_success,
                    Toast.LENGTH_SHORT,
                ).show()
                app.playerController.retry()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                AppLog.failure(AppLog.TAG_AUTH, "Falha vinculando YouTube no player", t)
                Toast.makeText(
                    host,
                    R.string.player_login_failed,
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        AppLog.d(AppLog.TAG_UI, "PlayerFragment.onCreateView")
        _binding = FragmentPlayerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        AppLog.i(AppLog.TAG_UI, "PlayerFragment.onViewCreated")
        binding.playerBack.setOnClickListener { closePlayer() }
        binding.playerPlay.setOnClickListener { controller?.toggle() }
        binding.playerNext.setOnClickListener { controller?.next() }
        binding.playerPrevious.setOnClickListener { controller?.previous() }
        binding.playerShuffle.setOnClickListener { controller?.toggleShuffle() }
        binding.playerRepeat.setOnClickListener { controller?.cycleRepeat() }
        binding.playerRetry.setOnClickListener { retry() }
        binding.playerYouTubeLogin.setOnClickListener { openYouTubeLogin() }
        binding.playerCopyLogs.setOnClickListener { copyDiagnostics() }
        binding.playerMore.setOnClickListener {
            latest.track?.let { track ->
                AddToPlaylistSheet.newInstance(track)
                    .show(childFragmentManager, AddToPlaylistSheet.TAG)
            }
        }
        listOf(
            binding.playerBack,
            binding.playerMore,
            binding.playerPlay,
            binding.playerNext,
            binding.playerPrevious,
            binding.playerShuffle,
            binding.playerRepeat,
            binding.playerYouTubeLogin,
            binding.playerCopyLogs,
        ).forEach { it.pressScale() }
        animateCoverEntrance()
        binding.playerSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && latest.durationMs > 0) {
                    binding.playerPosition.text = formatDuration(latest.durationMs * progress / 1000)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                seeking = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val progress = seekBar?.progress ?: 0
                if (latest.durationMs > 0) {
                    controller?.seekTo(latest.durationMs * progress / 1000)
                }
                seeking = false
            }
        })

        val playerController = controller
        if (playerController == null) {
            AppLog.e(AppLog.TAG_UI, "PlayerFragment sem PlayerController")
            showConnectionError()
            return
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                try {
                    playerController.state.collect { state ->
                        latest = state
                        render(state)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (t: Throwable) {
                    AppLog.failure(AppLog.TAG_UI, "Falha ao observar o player", t)
                    showConnectionError()
                }
            }
        }
    }

    private fun render(state: PlayerState) {
        val binding = _binding ?: return
        if (!isAdded) return
        val track = state.track
        val title = track?.title?.ifBlank { getString(R.string.player_unknown_title) }
        val artist = track?.artist?.ifBlank { getString(R.string.player_unknown_artist) }
        binding.playerTitle.text = title ?: getString(R.string.player_unknown_title)
        binding.playerArtist.text = artist ?: getString(R.string.player_unknown_artist)
        val album = track?.album.orEmpty()
        binding.playerAlbum.text = album
        binding.playerAlbum.isVisible = album.isNotBlank()
        val currentTrack = track
        val artistClickable = currentTrack != null && currentTrack.artist.isNotBlank()
        binding.playerArtist.isClickable = artistClickable
        binding.playerArtist.isFocusable = artistClickable
        binding.playerArtist.setOnClickListener(
            if (artistClickable && currentTrack != null) {
                View.OnClickListener { openArtist(currentTrack) }
            } else {
                null
            },
        )
        val albumClickable = currentTrack != null && album.isNotBlank()
        binding.playerAlbum.isClickable = albumClickable
        binding.playerAlbum.isFocusable = albumClickable
        binding.playerAlbum.setOnClickListener(
            if (albumClickable && currentTrack != null) {
                View.OnClickListener { openAlbum(currentTrack) }
            } else {
                null
            },
        )
        binding.playerCover.contentDescription = getString(
            R.string.content_cover,
            title ?: getString(R.string.player_unknown_title),
        )

        val artwork = track?.artworkUrl()
        if (artwork.isNullOrBlank()) {
            loadedArtworkFor = null
            binding.playerCover.setImageResource(R.drawable.bg_cover_placeholder)
            binding.playerBackdrop.setImageResource(R.drawable.bg_cover_placeholder)
        } else if (loadedArtworkFor != artwork) {
            loadedArtworkFor = artwork
            binding.playerCover.load(artwork) {
                crossfade(true)
                placeholder(R.drawable.bg_cover_placeholder)
                error(R.drawable.bg_cover_placeholder)
                listener(
                    onError = { _, result ->
                        AppLog.w(
                            AppLog.TAG_UI,
                            "Falha ao carregar capa do player: ${AppLog.describe(result.throwable)}",
                        )
                    },
                )
            }
            binding.playerBackdrop.load(artwork) {
                crossfade(true)
                placeholder(R.drawable.bg_cover_placeholder)
                error(R.drawable.bg_cover_placeholder)
            }
        }

        binding.playerPlay.isEnabled = track != null
        animatePlayIcon(binding.playerPlay, state.isPlaying, R.drawable.ic_play, R.drawable.ic_pause)
        binding.playerPlay.contentDescription = getString(
            if (state.isPlaying) R.string.player_pause else R.string.player_play,
        )
        binding.playerNext.isEnabled = state.hasNext || track != null
        binding.playerPrevious.isEnabled = track != null
        binding.playerShuffle.isSelected = state.shuffle
        binding.playerRepeat.isSelected = state.repeatMode != Player.REPEAT_MODE_OFF

        val duration = state.durationMs
        binding.playerDuration.text = formatDuration(duration)
        binding.playerPosition.text = formatDuration(state.positionMs)
        binding.playerSeek.isEnabled = duration > 0
        if (!seeking) {
            val progress = if (duration > 0) {
                (state.positionMs * 1000 / duration).toInt().coerceIn(0, 1000)
            } else {
                0
            }
            binding.playerSeek.progress = progress
        }

        binding.playerError.isVisible = state.error
        binding.playerError.text = state.errorMessage ?: getString(R.string.player_error_stream)
        binding.playerRetry.isVisible = state.error && !state.loginRequired
        binding.playerYouTubeLogin.isVisible = state.loginRequired
        binding.playerCopyLogs.isVisible = state.error

        if (state.track?.videoId != lastLoggedTrack) {
            lastLoggedTrack = state.track?.videoId
            AppLog.i(
                AppLog.TAG_PLAYER,
                "Player exibindo: ${state.track?.title ?: "<nenhuma>"} " +
                    "(${state.track?.videoId ?: "-"})",
            )
        }
        if (state.isPlaying != lastLoggedPlaying) {
            lastLoggedPlaying = state.isPlaying
            AppLog.i(AppLog.TAG_PLAYER, "Player isPlaying=${state.isPlaying}")
        }
    }

    private fun openArtist(track: Track) {
        val context = context ?: return
        val opened = BrowseNavigation.openArtistOrSearch(
            context,
            track.artistId,
            track.artist,
        )
        if (!opened) {
            Toast.makeText(context, R.string.track_menu_artist_unavailable, Toast.LENGTH_SHORT)
                .show()
        }
    }

    private fun openAlbum(track: Track) {
        val context = context ?: return
        val opened = BrowseNavigation.openAlbumOrSearch(
            context,
            track.albumId,
            track.album,
        )
        if (!opened) {
            Toast.makeText(context, R.string.track_menu_album_unavailable, Toast.LENGTH_SHORT)
                .show()
        }
    }

    private fun showConnectionError() {
        val binding = _binding ?: return
        binding.playerError.isVisible = true
        binding.playerError.text = getString(R.string.player_connection_error)
        binding.playerRetry.isVisible = true
        binding.playerYouTubeLogin.isVisible = false
        binding.playerCopyLogs.isVisible = true
    }

    private fun retry() {
        val playerController = controller
        if (playerController == null) {
            AppLog.e(AppLog.TAG_PLAYER, "Tentar novamente sem PlayerController")
            Toast.makeText(requireContext(), R.string.player_connection_error, Toast.LENGTH_LONG)
                .show()
            return
        }
        AppLog.i(AppLog.TAG_PLAYER, "Tentar novamente solicitado pelo player")
        playerController.retry()
    }

    private fun openYouTubeLogin() {
        AppLog.i(AppLog.TAG_AUTH, "Abrindo login do YouTube a partir do player")
        try {
            youtubeLogin.launch(Intent(requireContext(), YouTubeLoginActivity::class.java))
        } catch (t: Throwable) {
            AppLog.failure(AppLog.TAG_AUTH, "Falha abrindo login do YouTube no player", t)
            Toast.makeText(requireContext(), R.string.player_login_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun copyDiagnostics() {
        try {
            val clipboard = requireContext()
                .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(
                ClipData.newPlainText("MystMusic", AppLog.diagnostics()),
            )
            Toast.makeText(
                requireContext(),
                R.string.player_logs_copied,
                Toast.LENGTH_LONG,
            ).show()
            AppLog.i(AppLog.TAG_PLAYER, "Diagnóstico copiado para a área de transferência")
        } catch (t: Throwable) {
            AppLog.failure(AppLog.TAG_PLAYER, "Falha copiando diagnóstico", t)
            Toast.makeText(requireContext(), R.string.error_generic, Toast.LENGTH_LONG).show()
        }
    }

    private fun closePlayer() {
        AppLog.d(AppLog.TAG_UI, "PlayerFragment: fechar")
        val host = activity ?: return
        if (host is PlayerActivity) {
            host.finish()
            return
        }
        val nav = runCatching { findNavController() }.getOrNull()
        if (nav == null) {
            AppLog.w(AppLog.TAG_UI, "Player sem NavController e sem PlayerActivity; não fecha a tela principal")
            return
        }
        val moved = runCatching { nav.navigateUp() }
            .onFailure {
                AppLog.failure(AppLog.TAG_UI, "navigateUp falhou no player", it)
            }
            .getOrDefault(false)
        if (!moved) {
            AppLog.w(AppLog.TAG_UI, "navigateUp não moveu a navegação do player")
        }
    }

    override fun onDestroyView() {
        AppLog.d(AppLog.TAG_UI, "PlayerFragment.onDestroyView")
        super.onDestroyView()
        _binding = null
    }

    private fun animateCoverEntrance() {
        val reduced = !com.iskezis.myst.util.UiAnimations.enabled(requireContext())
        if (reduced) {
            binding.playerCover.alpha = 1f
            binding.playerCover.scaleX = 1f
            binding.playerCover.scaleY = 1f
            return
        }
        binding.playerCover.alpha = 0f
        binding.playerCover.scaleX = 0.92f
        binding.playerCover.scaleY = 0.92f
        binding.playerCover.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(350)
            .setInterpolator(androidx.interpolator.view.animation.FastOutSlowInInterpolator())
            .start()
    }
}
