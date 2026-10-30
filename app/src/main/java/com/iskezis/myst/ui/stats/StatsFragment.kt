package com.iskezis.myst.ui.stats

import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import coil.load
import com.google.android.material.color.MaterialColors
import com.iskezis.myst.R
import com.iskezis.myst.data.stats.StatsAlbum
import com.iskezis.myst.data.stats.StatsArtist
import com.iskezis.myst.data.stats.StatsData
import com.iskezis.myst.data.stats.StatsPeriod
import com.iskezis.myst.data.stats.StatsTrack
import com.iskezis.myst.databinding.FragmentStatsBinding
import com.iskezis.myst.databinding.ItemStatsRowBinding
import com.iskezis.myst.ui.common.LoadState
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
                viewModel.state.collect(::render)
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
        binding.statsState.stateRoot.isVisible = false
        binding.statsScroll.isVisible = true
        binding.statsTimeValue.text = formatListening(data.totalPlayedMs)
        binding.statsTimeCompare.text = comparisonText(data)
        binding.statsPlaysValue.text = data.playCount.toString()
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
                Row(
                    rank = index + 1,
                    title = item.title.ifBlank { getString(R.string.stats_unknown_track) },
                    subtitle = item.artist.ifBlank { getString(R.string.stats_unknown_artist) },
                    thumbnailUrl = item.thumbnailUrl,
                    count = playsText(item.plays),
                )
            },
        )
    }

    private fun bindArtists(items: List<StatsArtist>) {
        bindRows(
            container = binding.statsTopArtists,
            emptyText = getString(R.string.stats_empty_artists),
            rows = items.mapIndexed { index, item ->
                Row(
                    rank = index + 1,
                    title = item.name.ifBlank { getString(R.string.stats_unknown_artist) },
                    subtitle = formatListening(item.playedMs),
                    thumbnailUrl = item.thumbnailUrl,
                    count = playsText(item.plays),
                )
            },
        )
    }

    private fun bindAlbums(items: List<StatsAlbum>) {
        bindRows(
            container = binding.statsTopAlbums,
            emptyText = getString(R.string.stats_empty_albums),
            rows = items.mapIndexed { index, item ->
                Row(
                    rank = index + 1,
                    title = item.name.ifBlank { getString(R.string.stats_unknown_album) },
                    subtitle = item.artist.ifBlank { getString(R.string.stats_unknown_artist) },
                    thumbnailUrl = item.thumbnailUrl,
                    count = playsText(item.plays),
                )
            },
        )
    }

    private fun bindRows(container: LinearLayout, emptyText: String, rows: List<Row>) {
        container.removeAllViews()
        if (rows.isEmpty()) {
            container.addView(emptyLabel(emptyText))
            return
        }
        rows.forEach { row ->
            val item = ItemStatsRowBinding.inflate(layoutInflater, container, false)
            item.statsRowRank.text = row.rank.toString()
            item.statsRowTitle.text = row.title
            item.statsRowSubtitle.text = row.subtitle
            item.statsRowCount.text = row.count
            if (!row.thumbnailUrl.isNullOrBlank()) {
                item.statsRowCover.load(row.thumbnailUrl) {
                    crossfade(true)
                    placeholder(R.drawable.bg_cover_placeholder)
                    error(R.drawable.bg_cover_placeholder)
                }
            } else {
                item.statsRowCover.setImageResource(R.drawable.bg_cover_placeholder)
            }
            item.root.contentDescription = getString(
                R.string.stats_row_description,
                row.rank,
                row.title,
                row.count,
            )
            container.addView(item.root)
        }
    }

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
        val rank: Int,
        val title: String,
        val subtitle: String,
        val thumbnailUrl: String?,
        val count: String,
    )
}
