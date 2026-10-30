package com.iskezis.myst.ui.stats

import android.content.Context
import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import coil.load
import com.google.android.material.color.MaterialColors
import com.google.android.material.imageview.ShapeableImageView
import com.google.android.material.shape.CornerFamily
import com.google.android.material.shape.ShapeAppearanceModel
import com.iskezis.myst.MystApp
import com.iskezis.myst.R
import com.iskezis.myst.data.stats.StatsAlbum
import com.iskezis.myst.data.stats.StatsArtist
import com.iskezis.myst.data.stats.StatsData
import com.iskezis.myst.data.stats.StatsPeriod
import com.iskezis.myst.data.stats.StatsTrack
import com.iskezis.myst.data.youtube.Track
import com.iskezis.myst.databinding.FragmentStatsBinding
import com.iskezis.myst.databinding.ItemStatsRowBinding
import com.iskezis.myst.ui.browse.BrowseNavigation
import com.iskezis.myst.ui.common.LoadState
import com.iskezis.myst.util.AppLog
import com.iskezis.myst.util.UiAnimations.applyFadeThrough
import com.iskezis.myst.util.UiAnimations.staggerIn
import kotlinx.coroutines.launch
import java.util.Locale

class StatsFragment : Fragment() {

    private var _binding: FragmentStatsBinding? = null
    private val binding get() = checkNotNull(_binding)
    private val viewModel: StatsViewModel by viewModels {
        StatsViewModel.factory(requireActivity().application)
    }
    private var firstResume = true
    private var animatedPeriod: StatsPeriod? = null
    private var currentData: StatsData? = null
    private val artistRows = HashMap<String, ItemStatsRowBinding>()
    private val resolvingArtists = mutableSetOf<String>()
    private val resolvingAlbums = mutableSetOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyFadeThrough()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentStatsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.statsBack.setOnClickListener {
            runCatching { findNavController().navigateUp() }
        }
        binding.statsPeriods.setOnCheckedStateChangeListener { _, checkedIds ->
            val period = when (checkedIds.firstOrNull()) {
                R.id.statsChipMonth -> StatsPeriod.MONTH
                R.id.statsChipYear -> StatsPeriod.YEAR
                R.id.statsChipAll -> StatsPeriod.ALL
                else -> StatsPeriod.WEEK
            }
            viewModel.selectPeriod(period)
        }
        binding.statsState.stateRetry.setOnClickListener { viewModel.retry() }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.state.collect(::render)
                }
                launch {
                    viewModel.artistVisuals.collect { updateArtistCovers() }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (firstResume) {
            firstResume = false
        } else {
            viewModel.refresh()
        }
    }

    override fun onDestroyView() {
        _binding?.statsShimmer?.stopShimmer()
        currentData = null
        artistRows.clear()
        resolvingArtists.clear()
        resolvingAlbums.clear()
        _binding = null
        super.onDestroyView()
    }

    private fun render(ui: StatsUiState) {
        when (val state = ui.state) {
            is LoadState.Loading -> showLoading()
            is LoadState.Empty -> showEmpty()
            is LoadState.Error -> showError(state.messageRes)
            is LoadState.Data -> showData(state.value)
        }
    }

    private fun showLoading() {
        binding.statsShimmer.isVisible = true
        binding.statsShimmer.startShimmer()
        binding.statsScroll.isVisible = false
        binding.statsState.stateRoot.isVisible = false
    }

    private fun stopLoading() {
        binding.statsShimmer.stopShimmer()
        binding.statsShimmer.isVisible = false
    }

    private fun showEmpty() {
        stopLoading()
        currentData = null
        binding.statsScroll.isVisible = false
        binding.statsState.stateRoot.isVisible = true
        binding.statsState.stateIcon.setImageResource(R.drawable.ic_music)
        binding.statsState.stateTitle.setText(R.string.stats_empty_title)
        binding.statsState.stateSubtitle.isVisible = true
        binding.statsState.stateSubtitle.setText(R.string.stats_empty_subtitle)
        binding.statsState.stateRetry.isVisible = false
    }

    private fun showError(messageRes: Int) {
        stopLoading()
        currentData = null
        binding.statsScroll.isVisible = false
        binding.statsState.stateRoot.isVisible = true
        binding.statsState.stateIcon.setImageResource(R.drawable.ic_cloud_off)
        binding.statsState.stateTitle.setText(R.string.state_error)
        binding.statsState.stateSubtitle.isVisible = true
        binding.statsState.stateSubtitle.setText(messageRes)
        binding.statsState.stateRetry.isVisible = true
    }

    private fun showData(data: StatsData) {
        stopLoading()
        currentData = data
        binding.statsState.stateRoot.isVisible = false
        binding.statsScroll.isVisible = true
        binding.statsTimeValue.text = formatListening(data.totalPlayedMs)
        binding.statsTimeCompare.text = comparisonText(data)
        binding.statsPlaysValue.text = data.playCount.toString()
        viewModel.ensureArtistVisuals(data.topArtists)
        bindTracks(data.topTracks)
        bindArtists(data.topArtists)
        bindAlbums(data.topAlbums)
        bindHourChart(data)
        bindActivityChart(data)
        binding.statsStreakCurrent.text = daysText(data.currentStreak)
        binding.statsStreakRecord.text = daysText(data.longestStreak)
        if (animatedPeriod != data.period) {
            animatedPeriod = data.period
            for (index in 0 until binding.statsContent.childCount) {
                binding.statsContent.getChildAt(index).staggerIn(index)
            }
        }
    }

    private fun bindTracks(items: List<StatsTrack>) {
        bindRows(
            container = binding.statsTopTracks,
            emptyText = getString(R.string.stats_empty_songs),
            rows = items.mapIndexed { index, item ->
                val playable = item.videoId.isNotBlank()
                Row(
                    key = item.videoId,
                    rank = index + 1,
                    title = item.title.ifBlank { getString(R.string.stats_unknown_track) },
                    subtitle = item.artist.ifBlank { getString(R.string.stats_unknown_artist) },
                    thumbnailUrl = item.thumbnailUrl,
                    count = playsText(item.plays),
                    actionIcon = if (playable) R.drawable.ic_play else null,
                    actionLabel = if (playable) getString(R.string.stats_action_play) else null,
                    onClick = if (playable) {
                        { _: ItemStatsRowBinding -> playTrack(index) }
                    } else {
                        null
                    },
                )
            },
        )
    }

    private fun bindArtists(items: List<StatsArtist>) {
        artistRows.clear()
        val bindings = bindRows(
            container = binding.statsTopArtists,
            emptyText = getString(R.string.stats_empty_artists),
            rows = items.mapIndexed { index, item ->
                val name = item.name.trim()
                val clickable = name.isNotBlank()
                Row(
                    key = name,
                    rank = index + 1,
                    title = name.ifBlank { getString(R.string.stats_unknown_artist) },
                    subtitle = formatListening(item.playedMs),
                    thumbnailUrl = viewModel.artistVisual(name)?.thumbnailUrl
                        ?: item.thumbnailUrl,
                    count = playsText(item.plays),
                    isArtist = true,
                    actionIcon = if (clickable) R.drawable.ic_chevron_right else null,
                    actionLabel = if (clickable) {
                        getString(R.string.stats_action_open_artist)
                    } else {
                        null
                    },
                    onClick = if (clickable) {
                        { row: ItemStatsRowBinding -> openArtist(item, row) }
                    } else {
                        null
                    },
                )
            },
        )
        bindings.forEach { (row, item) ->
            if (row.key.isNotBlank()) artistRows[row.key] = item
        }
    }

    private fun bindAlbums(items: List<StatsAlbum>) {
        bindRows(
            container = binding.statsTopAlbums,
            emptyText = getString(R.string.stats_empty_albums),
            rows = items.mapIndexed { index, item ->
                val name = item.name.trim()
                val clickable = name.isNotBlank()
                Row(
                    key = "${item.name}\u0000${item.artist}",
                    rank = index + 1,
                    title = name.ifBlank { getString(R.string.stats_unknown_album) },
                    subtitle = item.artist.ifBlank { getString(R.string.stats_unknown_artist) },
                    thumbnailUrl = viewModel.albumVisual(name, item.artist)?.thumbnailUrl
                        ?: item.thumbnailUrl,
                    count = playsText(item.plays),
                    actionIcon = if (clickable) R.drawable.ic_chevron_right else null,
                    actionLabel = if (clickable) {
                        getString(R.string.stats_action_open_album)
                    } else {
                        null
                    },
                    onClick = if (clickable) {
                        { row: ItemStatsRowBinding -> openAlbum(item, row) }
                    } else {
                        null
                    },
                )
            },
        )
    }

    private fun bindRows(
        container: LinearLayout,
        emptyText: String,
        rows: List<Row>,
    ): List<Pair<Row, ItemStatsRowBinding>> {
        container.removeAllViews()
        if (rows.isEmpty()) {
            container.addView(emptyLabel(emptyText))
            return emptyList()
        }
        val bindings = ArrayList<Pair<Row, ItemStatsRowBinding>>(rows.size)
        rows.forEach { row ->
            val item = ItemStatsRowBinding.inflate(layoutInflater, container, false)
            item.statsRowRank.text = row.rank.toString()
            item.statsRowTitle.text = row.title
            item.statsRowSubtitle.text = row.subtitle
            item.statsRowCount.text = row.count
            if (row.isArtist) {
                item.statsRowCover.shapeAppearanceModel = ShapeAppearanceModel.builder()
                    .setAllCorners(
                        CornerFamily.ROUNDED,
                        resources.getDimension(R.dimen.track_thumb) / 2f,
                    )
                    .build()
            }
            bindCover(item.statsRowCover, row.thumbnailUrl)
            item.statsRowProgress.isVisible = false
            val actionIcon = row.actionIcon
            item.statsRowAction.isVisible = actionIcon != null
            if (actionIcon != null) {
                item.statsRowAction.setImageResource(actionIcon)
            }
            val actionLabel = row.actionLabel
            item.root.contentDescription = if (actionLabel != null) {
                getString(
                    R.string.stats_row_action_description,
                    row.rank,
                    row.title,
                    row.count,
                    actionLabel,
                )
            } else {
                getString(
                    R.string.stats_row_description,
                    row.rank,
                    row.title,
                    row.count,
                )
            }
            val onClick = row.onClick
            if (onClick != null) {
                item.root.isClickable = true
                item.root.isFocusable = true
                item.root.setOnClickListener { onClick(item) }
            } else {
                item.root.isClickable = false
                item.root.isFocusable = false
                item.root.setOnClickListener(null)
            }
            container.addView(item.root)
            bindings += row to item
        }
        return bindings
    }

    private fun bindCover(image: ShapeableImageView, url: String?) {
        val target = url?.takeIf { it.isNotBlank() }
        if (target == null) {
            image.tag = null
            image.setImageResource(R.drawable.bg_cover_placeholder)
            return
        }
        if (image.tag == target) return
        image.tag = target
        image.load(target) {
            crossfade(true)
            placeholder(R.drawable.bg_cover_placeholder)
            error(R.drawable.bg_cover_placeholder)
            listener(
                onError = { _, result ->
                    AppLog.w(
                        AppLog.TAG_UI,
                        "Falha ao carregar imagem das estatísticas: " +
                            AppLog.describe(result.throwable),
                    )
                },
            )
        }
    }

    private fun updateArtistCovers() {
        artistRows.forEach { (name, item) ->
            val visual = viewModel.artistVisual(name) ?: return@forEach
            if (!visual.thumbnailUrl.isNullOrBlank()) {
                bindCover(item.statsRowCover, visual.thumbnailUrl)
            }
        }
    }

    private fun playTrack(index: Int) {
        val data = currentData ?: return
        if (index !in data.topTracks.indices) return
        val tracks = data.topTracks.map { it.toTrack() }
        if (tracks.isEmpty()) return
        val app = requireActivity().application as? MystApp ?: return
        try {
            app.playerController.play(tracks, index)
        } catch (t: Throwable) {
            AppLog.failure(AppLog.TAG_PLAYER, "Falha ao tocar música das estatísticas", t)
            Toast.makeText(requireContext(), R.string.error_generic, Toast.LENGTH_LONG).show()
        }
    }

    private fun openArtist(artist: StatsArtist, row: ItemStatsRowBinding) {
        val context = context ?: return
        val name = artist.name.trim()
        if (name.isBlank()) return
        val known = viewModel.artistVisual(name)
        if (known != null) {
            openArtistScreen(context, known, name)
            return
        }
        val key = name.lowercase(Locale.ROOT)
        if (!resolvingArtists.add(key)) return
        row.statsRowProgress.isVisible = true
        row.statsRowAction.isVisible = false
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val visual = viewModel.resolveArtist(name)
                if (!isAdded) return@launch
                if (visual != null) {
                    openArtistScreen(context, visual, name)
                } else {
                    Toast.makeText(
                        context,
                        R.string.stats_artist_fallback,
                        Toast.LENGTH_SHORT,
                    ).show()
                    runCatching { BrowseNavigation.openArtistOrSearch(context, null, name) }
                        .onFailure { t ->
                            AppLog.failure(AppLog.TAG_UI, "Falha abrindo busca do artista", t)
                            Toast.makeText(
                                context,
                                R.string.stats_open_error,
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                }
            } finally {
                resolvingArtists.remove(key)
                if (isAdded) {
                    row.statsRowProgress.isVisible = false
                    row.statsRowAction.isVisible = true
                }
            }
        }
    }

    private fun openAlbum(album: StatsAlbum, row: ItemStatsRowBinding) {
        val context = context ?: return
        val name = album.name.trim()
        if (name.isBlank()) return
        val known = viewModel.albumVisual(name, album.artist)
        if (known != null) {
            openAlbumScreen(context, known, album)
            return
        }
        val key = "${name.lowercase(Locale.ROOT)}|${album.artist.lowercase(Locale.ROOT)}"
        if (!resolvingAlbums.add(key)) return
        row.statsRowProgress.isVisible = true
        row.statsRowAction.isVisible = false
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val visual = viewModel.resolveAlbum(name, album.artist)
                if (!isAdded) return@launch
                if (visual != null) {
                    openAlbumScreen(context, visual, album)
                } else {
                    Toast.makeText(
                        context,
                        R.string.stats_album_fallback,
                        Toast.LENGTH_SHORT,
                    ).show()
                    runCatching { BrowseNavigation.openAlbumOrSearch(context, null, name) }
                        .onFailure { t ->
                            AppLog.failure(AppLog.TAG_UI, "Falha abrindo busca do álbum", t)
                            Toast.makeText(
                                context,
                                R.string.stats_open_error,
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                }
            } finally {
                resolvingAlbums.remove(key)
                if (isAdded) {
                    row.statsRowProgress.isVisible = false
                    row.statsRowAction.isVisible = true
                }
            }
        }
    }

    private fun openArtistScreen(
        context: Context,
        visual: StatsBrowseVisual,
        name: String,
    ) {
        try {
            BrowseNavigation.openArtist(
                context,
                visual.browseId,
                name,
                visual.thumbnailUrl.orEmpty(),
            )
        } catch (t: Throwable) {
            AppLog.failure(AppLog.TAG_UI, "Falha abrindo artista das estatísticas", t)
            Toast.makeText(context, R.string.stats_open_error, Toast.LENGTH_LONG).show()
        }
    }

    private fun openAlbumScreen(
        context: Context,
        visual: StatsBrowseVisual,
        album: StatsAlbum,
    ) {
        try {
            BrowseNavigation.openAlbum(
                context,
                visual.browseId,
                album.name,
                album.artist,
                visual.thumbnailUrl.orEmpty(),
            )
        } catch (t: Throwable) {
            AppLog.failure(AppLog.TAG_UI, "Falha abrindo álbum das estatísticas", t)
            Toast.makeText(context, R.string.stats_open_error, Toast.LENGTH_LONG).show()
        }
    }

    private fun StatsTrack.toTrack(): Track = Track(
        videoId = videoId,
        title = title,
        artist = artist,
        thumbnailUrl = thumbnailUrl,
        album = album,
        artistId = viewModel.artistVisual(artist)?.browseId,
        albumId = if (album.isBlank()) {
            null
        } else {
            viewModel.albumVisual(album, artist)?.browseId
        },
    )

    private fun emptyLabel(text: String): TextView = TextView(requireContext()).apply {
        this.text = text
        setTextColor(
            MaterialColors.getColor(
                binding.statsTopTracks,
                R.attr.mystOnSurfaceDim,
            ),
        )
        setTextSize(
            TypedValue.COMPLEX_UNIT_PX,
            resources.getDimension(R.dimen.text_small),
        )
        setPadding(0, resources.getDimensionPixelSize(R.dimen.space_s), 0, 0)
    }

    private fun bindHourChart(data: StatsData) {
        val values = data.hourly.map { it.toFloat() }
        val labels = List(24) { index -> if (index % 6 == 0) "${index}h" else "" }
        binding.statsHourChart.setData(values, labels, labelEvery = 1)
        val peak = data.hourly.indexOf(data.hourly.maxOrNull() ?: 0L)
        binding.statsHourChart.contentDescription = if ((data.hourly.maxOrNull() ?: 0L) > 0L) {
            getString(
                R.string.stats_chart_hour_description,
                peak,
                formatListening(data.hourly[peak]),
            )
        } else {
            getString(R.string.stats_chart_empty)
        }
    }

    private fun bindActivityChart(data: StatsData) {
        val values = data.daily.map { it.playedMs.toFloat() }
        val labels = data.daily.map { it.label }
        val labelEvery = if (data.daily.size <= 7) 1 else 5
        binding.statsActivityChart.setData(values, labels, labelEvery)
        binding.statsActivitySubtitle.setText(
            if (data.period == StatsPeriod.WEEK) {
                R.string.stats_activity_week
            } else {
                R.string.stats_activity_month
            },
        )
        val peakIndex = data.daily.indices.maxByOrNull { data.daily[it].playedMs } ?: 0
        val peak = data.daily.getOrNull(peakIndex)
        binding.statsActivityChart.contentDescription = if (peak != null && peak.playedMs > 0L) {
            getString(
                R.string.stats_chart_day_description,
                data.daily.size,
                peak.label,
                formatListening(peak.playedMs),
            )
        } else {
            getString(R.string.stats_chart_empty)
        }
    }

    private fun comparisonText(data: StatsData): String {
        if (data.period == StatsPeriod.ALL) {
            return getString(R.string.stats_compare_all)
        }
        val previous = data.previousPlayedMs ?: return getString(R.string.stats_compare_none)
        if (previous <= 0L) {
            return if (data.totalPlayedMs > 0L) {
                getString(R.string.stats_compare_new)
            } else {
                getString(R.string.stats_compare_none)
            }
        }
        val percent = ((data.totalPlayedMs - previous) * 100.0 / previous)
            .toInt()
        return when {
            percent > 0 -> getString(R.string.stats_compare_up, percent)
            percent < 0 -> getString(R.string.stats_compare_down, -percent)
            else -> getString(R.string.stats_compare_same)
        }
    }

    private fun formatListening(millis: Long): String {
        val totalMinutes = (millis.coerceAtLeast(0L) / 60_000L)
        return when {
            totalMinutes < 1L -> getString(R.string.stats_less_minute)
            totalMinutes < 60L -> getString(R.string.stats_minutes, totalMinutes)
            else -> getString(
                R.string.stats_hours_minutes,
                totalMinutes / 60L,
                totalMinutes % 60L,
            )
        }
    }

    private fun playsText(plays: Int): String =
        resources.getQuantityString(R.plurals.stats_plays, plays, plays)

    private fun daysText(days: Int): String =
        resources.getQuantityString(R.plurals.stats_days, days, days)

    private data class Row(
        val key: String = "",
        val rank: Int,
        val title: String,
        val subtitle: String,
        val thumbnailUrl: String?,
        val count: String,
        val isArtist: Boolean = false,
        val actionIcon: Int? = null,
        val actionLabel: String? = null,
        val onClick: ((ItemStatsRowBinding) -> Unit)? = null,
    )
}
