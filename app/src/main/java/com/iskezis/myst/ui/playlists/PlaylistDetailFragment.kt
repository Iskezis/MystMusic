package com.iskezis.myst.ui.playlists

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.iskezis.myst.MystApp
import com.iskezis.myst.R
import com.iskezis.myst.data.youtube.Track
import com.iskezis.myst.databinding.FragmentPlaylistDetailBinding
import com.iskezis.myst.ui.common.LoadState
import com.iskezis.myst.util.AppLog
import com.iskezis.myst.util.UiAnimations.applyFadeThrough
import com.iskezis.myst.util.UiAnimations.pressScale
import com.iskezis.myst.util.UiAnimations.staggerVisibleChildren
import kotlinx.coroutines.launch

class PlaylistDetailFragment : Fragment() {

    private var _binding: FragmentPlaylistDetailBinding? = null
    private val binding get() = checkNotNull(_binding)
    private val playlistId: Long by lazy {
        requireArguments().getLong(PlaylistsFragment.ARG_PLAYLIST_ID)
    }
    private val viewModel: PlaylistDetailViewModel by viewModels {
        PlaylistDetailViewModel.factory(requireActivity().application, playlistId)
    }
    private lateinit var adapter: PlaylistTrackAdapter
    private lateinit var touchHelper: ItemTouchHelper
    private var currentState = PlaylistDetailUiState()
    private var dragging = false
    private var orderDirty = false
    private var navigatedAway = false
    private var animatedOnce = false

    private val coverPicker = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) saveCover(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyFadeThrough()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentPlaylistDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        AppLog.d(AppLog.TAG_UI, "PlaylistDetailFragment.onViewCreated id=$playlistId")
        adapter = PlaylistTrackAdapter(
            onPlay = { tracks, index ->
                AppLog.i(
                    AppLog.TAG_UI,
                    "Playlist $playlistId: tocar ${tracks.size} faixas no índice $index",
                )
                (requireActivity().application as MystApp).playerController.play(tracks, index)
            },
            onRemove = { track, _ -> confirmRemove(track) },
        )
        binding.detailTracks.layoutManager = LinearLayoutManager(requireContext())
        binding.detailTracks.adapter = adapter
        touchHelper = ItemTouchHelper(touchCallback)
        touchHelper.attachToRecyclerView(binding.detailTracks)
        adapter.onDragStart = { holder -> touchHelper.startDrag(holder) }

        binding.detailBack.setOnClickListener { findNavController().navigateUp() }
        binding.detailMore.setOnClickListener { showActions() }
        binding.detailCoverFrame.setOnClickListener { launchCoverPicker() }
        binding.detailCoverEdit.setOnClickListener { launchCoverPicker() }
        binding.detailPlayAll.setOnClickListener { playAll(shuffle = false) }
        binding.detailShuffle.setOnClickListener { playAll(shuffle = true) }
        binding.detailPlayAll.pressScale()
        binding.detailShuffle.pressScale()
        binding.detailBack.pressScale()
        binding.detailMore.pressScale()
        binding.detailState.stateRetry.setOnClickListener {
            if (currentState.errorRes != null) viewModel.retry() else viewModel.retryTracks()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.state.collect { state ->
                        currentState = state
                        render(state)
                    }
                }
                launch {
                    viewModel.deleted.collect { deleted ->
                        if (deleted && !navigatedAway) {
                            navigatedAway = true
                            findNavController().navigateUp()
                        }
                    }
                }
                launch {
                    viewModel.message.collect { messageRes ->
                        if (messageRes != null) {
                            Snackbar.make(binding.root, messageRes, Snackbar.LENGTH_LONG).show()
                            viewModel.consumeMessage()
                        }
                    }
                }
            }
        }
    }

    private fun render(state: PlaylistDetailUiState) {
        renderHeader(state)
        if (state.errorRes != null) {
            stopShimmer()
            binding.detailTracks.isVisible = false
            with(binding.detailState) {
                stateRoot.isVisible = true
                stateIcon.setImageResource(R.drawable.ic_cloud_off)
                stateTitle.setText(R.string.state_error)
                stateSubtitle.text = getString(state.errorRes)
                stateSubtitle.isVisible = true
                stateRetry.setText(R.string.state_retry)
                stateRetry.isVisible = true
            }
            return
        }
        when (val tracks = state.tracks) {
            is LoadState.Loading -> {
                binding.detailShimmer.isVisible = true
                binding.detailShimmer.startShimmer()
                binding.detailTracks.isVisible = false
                binding.detailState.stateRoot.isVisible = false
            }
            is LoadState.Data -> {
                stopShimmer()
                binding.detailState.stateRoot.isVisible = false
                binding.detailTracks.isVisible = true
                if (!dragging) adapter.submit(tracks.value)
                setPlayButtons(tracks.value.isNotEmpty())
                if (!animatedOnce && tracks.value.isNotEmpty()) {
                    animatedOnce = true
                    binding.detailTracks.postDelayed(
                        { binding.detailTracks.staggerVisibleChildren() },
                        120,
                    )
                }
            }
            is LoadState.Empty -> {
                stopShimmer()
                binding.detailTracks.isVisible = false
                setPlayButtons(false)
                with(binding.detailState) {
                    stateRoot.isVisible = true
                    stateIcon.setImageResource(R.drawable.ic_playlist)
                    stateTitle.setText(R.string.state_empty)
                    stateSubtitle.setText(R.string.playlist_empty)
                    stateSubtitle.isVisible = true
                    stateRetry.isVisible = false
                }
            }
            is LoadState.Error -> {
                stopShimmer()
                binding.detailTracks.isVisible = false
                setPlayButtons(false)
                with(binding.detailState) {
                    stateRoot.isVisible = true
                    stateIcon.setImageResource(R.drawable.ic_cloud_off)
                    stateTitle.setText(R.string.state_error)
                    stateSubtitle.text = getString(tracks.messageRes)
                    stateSubtitle.isVisible = true
                    stateRetry.setText(R.string.state_retry)
                    stateRetry.isVisible = true
                }
            }
        }
    }

    private fun renderHeader(state: PlaylistDetailUiState) {
        val playlist = state.playlist
        val name = playlist?.name.orEmpty()
        binding.detailBarTitle.text = name.ifBlank { getString(R.string.playlists_title) }
        binding.detailName.text = name.ifBlank { getString(R.string.playlists_title) }
        binding.detailInitial.text = name.trim().take(1).uppercase()
        if (playlist?.coverUri.isNullOrBlank()) {
            binding.detailCover.setImageDrawable(null)
            binding.detailInitial.isVisible = true
        } else {
            binding.detailInitial.isVisible = false
            binding.detailCover.load(playlist?.coverUri) {
                crossfade(true)
                placeholder(R.drawable.bg_playlist_placeholder)
                error(R.drawable.bg_playlist_placeholder)
            }
        }
        val count = when (val tracks = state.tracks) {
            is LoadState.Data -> tracks.value.size
            is LoadState.Empty -> 0
            else -> null
        }
        binding.detailSubtitle.isVisible = count != null
        if (count != null) {
            binding.detailSubtitle.text = getString(R.string.playlist_track_count, count)
        }
    }

    private fun setPlayButtons(enabled: Boolean) {
        binding.detailPlayAll.isEnabled = enabled
        binding.detailShuffle.isEnabled = enabled
    }

    private fun stopShimmer() {
        binding.detailShimmer.stopShimmer()
        binding.detailShimmer.isVisible = false
    }

    private fun playAll(shuffle: Boolean) {
        val tracks = (currentState.tracks as? LoadState.Data)?.value ?: return
        if (tracks.isEmpty()) return
        AppLog.i(
            AppLog.TAG_UI,
            "Playlist $playlistId: tocar tudo (${tracks.size} faixas, shuffle=$shuffle)",
        )
        (requireActivity().application as MystApp).playerController.play(
            tracks,
            0,
            shuffle,
        )
    }

    private fun launchCoverPicker() {
        coverPicker.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
        )
    }

    private fun saveCover(uri: android.net.Uri) {
        AppLog.d(AppLog.TAG_UI, "Salvando capa da playlist $playlistId")
        viewLifecycleOwner.lifecycleScope.launch {
            val stored = CoverStore.save(requireContext(), playlistId, uri)
            if (stored == null) {
                AppLog.w(AppLog.TAG_UI, "CoverStore falhou para playlist $playlistId")
                Snackbar.make(binding.root, R.string.error_generic, Snackbar.LENGTH_LONG).show()
            } else {
                viewModel.setCover(stored)
            }
        }
    }

    private fun showActions() {
        PopupMenu(requireContext(), binding.detailMore).apply {
            inflate(R.menu.menu_playlist_detail)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.actionRename -> {
                        showRename()
                        true
                    }
                    R.id.actionCover -> {
                        launchCoverPicker()
                        true
                    }
                    R.id.actionDelete -> {
                        confirmDelete()
                        true
                    }
                    else -> false
                }
            }
        }.show()
    }

    private fun showRename() {
        showPlaylistNameDialog(
            context = requireContext(),
            titleRes = R.string.playlist_rename,
            initialName = currentState.playlist?.name.orEmpty(),
            positiveRes = R.string.playlist_save,
        ) { name -> viewModel.rename(name) }
    }

    private fun confirmDelete() {
        val name = currentState.playlist?.name.orEmpty()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.playlist_delete)
            .setMessage(getString(R.string.playlist_delete_confirm, name))
            .setNegativeButton(R.string.common_cancel, null)
            .setPositiveButton(R.string.common_delete) { _, _ -> viewModel.delete() }
            .show()
    }

    private fun confirmRemove(track: Track) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(track.title.ifBlank { getString(R.string.player_unknown_title) })
            .setMessage(R.string.playlist_remove_track_confirm)
            .setNegativeButton(R.string.common_cancel, null)
            .setPositiveButton(R.string.common_remove) { _, _ ->
                val index = adapter.current().indexOfFirst { it.videoId == track.videoId }
                if (index >= 0) removeWithUndo(track, index)
            }
            .show()
    }

    private fun removeWithUndo(track: Track, position: Int) {
        val removed = adapter.removeAt(position) ?: return
        viewModel.removeTrack(removed)
        Snackbar.make(binding.root, R.string.playlist_track_removed, Snackbar.LENGTH_LONG)
            .setAction(R.string.playlist_undo) {
                adapter.insert(position, removed)
                viewModel.restoreTrack(removed, position)
            }
            .show()
    }

    private val touchCallback = object : ItemTouchHelper.SimpleCallback(
        ItemTouchHelper.UP or ItemTouchHelper.DOWN,
        ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT,
    ) {
        override fun isLongPressDragEnabled(): Boolean = false

        override fun onMove(
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder,
            target: RecyclerView.ViewHolder,
        ): Boolean {
            val from = viewHolder.bindingAdapterPosition
            val to = target.bindingAdapterPosition
            if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
            if (adapter.move(from, to)) {
                orderDirty = true
                return true
            }
            return false
        }

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
            val position = viewHolder.bindingAdapterPosition
            val track = adapter.itemAt(position) ?: return
            removeWithUndo(track, position)
        }

        override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
            super.onSelectedChanged(viewHolder, actionState)
            if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                dragging = true
                orderDirty = false
                viewHolder?.itemView?.apply {
                    alpha = 0.9f
                    elevation = resources.displayMetrics.density * 8f
                    animate().scaleX(1.03f).scaleY(1.03f).setDuration(150).start()
                }
            }
        }

        override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
            super.clearView(recyclerView, viewHolder)
            viewHolder.itemView.animate().scaleX(1f).scaleY(1f).setDuration(180).start()
            viewHolder.itemView.alpha = 1f
            viewHolder.itemView.elevation = 0f
            if (dragging) {
                dragging = false
                if (orderDirty) {
                    orderDirty = false
                    viewModel.reorder(adapter.current())
                }
            }
        }
    }

    override fun onDestroyView() {
        AppLog.d(AppLog.TAG_UI, "PlaylistDetailFragment.onDestroyView id=$playlistId")
        adapter.onDragStart = null
        binding.detailTracks.adapter = null
        super.onDestroyView()
        _binding = null
    }
}
