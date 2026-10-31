package com.iskezis.myst.ui.home

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.iskezis.myst.R
import com.iskezis.myst.data.account.AvatarStore
import com.iskezis.myst.data.youtube.Track
import com.iskezis.myst.databinding.ItemCarouselBinding
import com.iskezis.myst.databinding.ItemHomeHeaderBinding
import com.iskezis.myst.databinding.ItemHomeOfflineBinding
import com.iskezis.myst.databinding.ItemHomeSearchBinding
import com.iskezis.myst.databinding.ItemSectionHeaderBinding
import com.iskezis.myst.ui.common.LoadState
import com.iskezis.myst.ui.common.TrackCardAdapter
import java.io.File

enum class HomeSection { RECENT, TRENDING, RECOMMENDED, OFFLINE }

sealed interface HomeItem {
    data class Greeting(val name: String, val avatarPath: String?) : HomeItem
    object Search : HomeItem
    object Offline : HomeItem
    data class Header(val section: HomeSection) : HomeItem
    data class Carousel(val section: HomeSection, val state: LoadState<List<Track>>) : HomeItem
}

class HomeAdapter(
    private val onSearch: () -> Unit,
    private val onAvatar: () -> Unit,
    private val onStats: () -> Unit,
    private val onPlay: (List<Track>, Int) -> Unit,
    private val onMore: (Track) -> Unit,
    private val onRetryRecent: () -> Unit,
    private val onRetryTrending: () -> Unit,
    private val onRetryRecommended: () -> Unit,
    private val onRetryOffline: () -> Unit,
) : ListAdapter<HomeItem, RecyclerView.ViewHolder>(Diff) {

    private val cardPool = RecyclerView.RecycledViewPool()

    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        is HomeItem.Greeting -> TYPE_GREETING
        is HomeItem.Search -> TYPE_SEARCH
        is HomeItem.Offline -> TYPE_OFFLINE
        is HomeItem.Header -> TYPE_HEADER
        is HomeItem.Carousel -> TYPE_CAROUSEL
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_GREETING -> GreetingHolder(ItemHomeHeaderBinding.inflate(inflater, parent, false))
            TYPE_SEARCH -> SearchHolder(ItemHomeSearchBinding.inflate(inflater, parent, false))
            TYPE_OFFLINE -> OfflineHolder(
                ItemHomeOfflineBinding.inflate(inflater, parent, false),
                onRetryOffline,
            )
            TYPE_HEADER -> HeaderHolder(ItemSectionHeaderBinding.inflate(inflater, parent, false))
            else -> CarouselHolder(
                ItemCarouselBinding.inflate(inflater, parent, false),
                onPlay,
                onMore,
                cardPool,
                onRetryRecent,
                onRetryTrending,
                onRetryRecommended,
                onRetryOffline,
            )
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is HomeItem.Greeting -> (holder as GreetingHolder).bind(item, onAvatar, onStats)
            is HomeItem.Search -> (holder as SearchHolder).bind(onSearch)
            is HomeItem.Offline -> (holder as OfflineHolder).bind()
            is HomeItem.Header -> (holder as HeaderHolder).bind(item)
            is HomeItem.Carousel -> (holder as CarouselHolder).bind(item)
        }
    }

    private class GreetingHolder(private val binding: ItemHomeHeaderBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: HomeItem.Greeting, onAvatar: () -> Unit, onStats: () -> Unit) {
            val context = binding.root.context
            val name = item.name.trim()
            binding.homeGreeting.text = if (name.isBlank()) {
                context.getString(R.string.home_greeting_fallback)
            } else {
                context.getString(R.string.home_greeting, name)
            }
            binding.homeHeaderInitial.text = name.take(1).uppercase()
            binding.homeHeaderAvatarFrame.contentDescription =
                context.getString(R.string.nav_settings)

            val path = item.avatarPath
            val key = AvatarStore.cacheKey(path)
            if (path != null && key != null) {
                binding.homeHeaderAvatarImage.isVisible = true
                binding.homeHeaderInitial.isVisible = false
                binding.homeHeaderAvatarImage.load(File(path)) {
                    memoryCacheKey(key)
                    diskCacheKey(key)
                    crossfade(true)
                    placeholder(R.drawable.bg_avatar)
                    error(R.drawable.bg_avatar)
                }
            } else {
                binding.homeHeaderAvatarImage.isVisible = false
                binding.homeHeaderInitial.isVisible = true
            }
            binding.homeHeaderAvatarFrame.setOnClickListener { onAvatar() }
            binding.homeStatsButton.setOnClickListener { onStats() }
        }
    }

    private class SearchHolder(private val binding: ItemHomeSearchBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(onSearch: () -> Unit) {
            binding.homeSearchCard.setOnClickListener { onSearch() }
        }
    }

    private class OfflineHolder(
        private val binding: ItemHomeOfflineBinding,
        private val onRetry: () -> Unit,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind() {
            binding.homeOfflineRetry.setOnClickListener { onRetry() }
        }
    }

    private class HeaderHolder(private val binding: ItemSectionHeaderBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: HomeItem.Header) {
            val context = binding.root.context
            binding.sectionTitle.setText(sectionTitleRes(item.section))
            val subtitle = when (item.section) {
                HomeSection.RECENT -> R.string.section_recent_subtitle
                HomeSection.OFFLINE -> R.string.section_downloads_subtitle
                else -> null
            }
            if (subtitle != null) {
                binding.sectionSubtitle.setText(subtitle)
                binding.sectionSubtitle.visibility = View.VISIBLE
            } else {
                binding.sectionSubtitle.visibility = View.GONE
            }
            binding.root.contentDescription = context.getString(sectionTitleRes(item.section))
        }
    }

    private class CarouselHolder(
        private val binding: ItemCarouselBinding,
        onPlay: (List<Track>, Int) -> Unit,
        onMore: (Track) -> Unit,
        pool: RecyclerView.RecycledViewPool,
        private val onRetryRecent: () -> Unit,
        private val onRetryTrending: () -> Unit,
        private val onRetryRecommended: () -> Unit,
        private val onRetryOffline: () -> Unit,
    ) : RecyclerView.ViewHolder(binding.root) {

        private val cards = TrackCardAdapter(onPlay, onMore)

        init {
            binding.carouselList.layoutManager =
                LinearLayoutManager(binding.root.context, LinearLayoutManager.HORIZONTAL, false)
            binding.carouselList.setHasFixedSize(true)
            binding.carouselList.adapter = cards
            binding.carouselList.setRecycledViewPool(pool)
        }

        fun bind(item: HomeItem.Carousel) {
            val context = binding.root.context
            when (val state = item.state) {
                is LoadState.Loading -> {
                    binding.carouselShimmer.visibility = View.VISIBLE
                    binding.carouselShimmer.startShimmer()
                    binding.carouselList.visibility = View.GONE
                    binding.carouselState.visibility = View.GONE
                }
                is LoadState.Data -> {
                    binding.carouselShimmer.stopShimmer()
                    binding.carouselShimmer.visibility = View.GONE
                    binding.carouselList.visibility = View.VISIBLE
                    binding.carouselState.visibility = View.GONE
                    cards.submitList(state.value)
                }
                is LoadState.Empty -> {
                    binding.carouselShimmer.stopShimmer()
                    binding.carouselShimmer.visibility = View.GONE
                    binding.carouselList.visibility = View.GONE
                    binding.carouselState.visibility = View.VISIBLE
                    binding.carouselStateIcon.setImageResource(
                        if (item.section == HomeSection.OFFLINE) {
                            R.drawable.ic_arrow_down
                        } else {
                            R.drawable.ic_music
                        },
                    )
                    binding.carouselStateTitle.text = context.getString(
                        when (item.section) {
                            HomeSection.RECENT -> R.string.section_personalize_hint
                            HomeSection.OFFLINE -> R.string.home_offline_empty_title
                            else -> R.string.state_empty
                        },
                    )
                    binding.carouselRetry.visibility = View.GONE
                }
                is LoadState.Error -> {
                    binding.carouselShimmer.stopShimmer()
                    binding.carouselShimmer.visibility = View.GONE
                    binding.carouselList.visibility = View.GONE
                    binding.carouselState.visibility = View.VISIBLE
                    binding.carouselStateIcon.setImageResource(R.drawable.ic_cloud_off)
                    binding.carouselStateTitle.text = context.getString(state.messageRes)
                    binding.carouselRetry.visibility = View.VISIBLE
                    binding.carouselRetry.setOnClickListener {
                        when (item.section) {
                            HomeSection.RECENT -> onRetryRecent()
                            HomeSection.TRENDING -> onRetryTrending()
                            HomeSection.RECOMMENDED -> onRetryRecommended()
                            HomeSection.OFFLINE -> onRetryOffline()
                        }
                    }
                }
            }
        }
    }

    private object Diff : DiffUtil.ItemCallback<HomeItem>() {
        override fun areItemsTheSame(oldItem: HomeItem, newItem: HomeItem): Boolean =
            when {
                oldItem is HomeItem.Greeting && newItem is HomeItem.Greeting -> true
                oldItem === HomeItem.Search && newItem === HomeItem.Search -> true
                oldItem === HomeItem.Offline && newItem === HomeItem.Offline -> true
                oldItem is HomeItem.Header && newItem is HomeItem.Header ->
                    oldItem.section == newItem.section
                oldItem is HomeItem.Carousel && newItem is HomeItem.Carousel ->
                    oldItem.section == newItem.section
                else -> false
            }

        override fun areContentsTheSame(oldItem: HomeItem, newItem: HomeItem): Boolean =
            oldItem == newItem
    }

    companion object {
        private const val TYPE_GREETING = 0
        private const val TYPE_SEARCH = 1
        private const val TYPE_HEADER = 2
        private const val TYPE_CAROUSEL = 3
        private const val TYPE_OFFLINE = 4
    }
}
