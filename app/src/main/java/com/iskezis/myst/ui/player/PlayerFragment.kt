package com.iskezis.myst.ui.player

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
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
import com.iskezis.myst.databinding.FragmentPlayerBinding
import com.iskezis.myst.player.PlayerState
import com.iskezis.myst.ui.playlists.AddToPlaylistSheet
import com.iskezis.myst.util.formatDuration
import kotlinx.coroutines.launch

class PlayerFragment : Fragment() {

    private var _binding: FragmentPlayerBinding? = null
    private val binding get() = checkNotNull(_binding)
    private val controller get() = (requireActivity().application as MystApp).playerController
    private var latest: PlayerState = PlayerState()
    private var seeking = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentPlayerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.playerBack.setOnClickListener { findNavController().navigateUp() }
        binding.playerPlay.setOnClickListener { controller.toggle() }
        binding.playerNext.setOnClickListener { controller.next() }
        binding.playerPrevious.setOnClickListener { controller.previous() }
        binding.playerShuffle.setOnClickListener { controller.toggleShuffle() }
        binding.playerRepeat.setOnClickListener { controller.cycleRepeat() }
        binding.playerRetry.setOnClickListener { controller.retry() }
        binding.playerMore.setOnClickListener {
            latest.track?.let { track ->
                AddToPlaylistSheet.newInstance(track)
                    .show(childFragmentManager, AddToPlaylistSheet.TAG)
            }
        }
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
                    controller.seekTo(latest.durationMs * progress / 1000)
                }
                seeking = false
            }
        })

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                controller.state.collect { state ->
                    latest = state
                    render(state)
                }
            }
        }
    }

    private fun render(state: PlayerState) {
        val track = state.track
        val title = track?.title?.ifBlank { getString(R.string.player_unknown_title) }
        val artist = track?.artist?.ifBlank { getString(R.string.player_unknown_artist) }
        binding.playerTitle.text = title ?: getString(R.string.player_unknown_title)
        binding.playerArtist.text = artist ?: getString(R.string.player_unknown_artist)
        binding.playerCover.contentDescription = getString(
            R.string.content_cover,
            title ?: getString(R.string.player_unknown_title),
        )

        val context = requireContext()
        if (track?.thumbnailUrl.isNullOrBlank()) {
            binding.playerCover.setImageResource(R.drawable.bg_cover_placeholder)
            binding.playerBackdrop.setImageResource(R.drawable.bg_cover_placeholder)
        } else {
            binding.playerCover.load(track?.thumbnailUrl) {
                crossfade(true)
                placeholder(R.drawable.bg_cover_placeholder)
                error(R.drawable.bg_cover_placeholder)
            }
            binding.playerBackdrop.load(track?.thumbnailUrl) {
                crossfade(true)
                placeholder(R.drawable.bg_cover_placeholder)
                error(R.drawable.bg_cover_placeholder)
            }
        }

        binding.playerPlay.isEnabled = track != null
        binding.playerPlay.setImageResource(
            if (state.isPlaying) R.drawable.ic_pause else R.drawable.ic_play,
        )
        binding.playerPlay.contentDescription = context.getString(
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
        binding.playerRetry.isVisible = state.error
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
