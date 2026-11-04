package com.iskezis.myst.data.animatedcover

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AnimatedCoverMatcherTest {

    private val track = TrackKey(
        title = "Shake It Off",
        artist = "Taylor Swift",
        album = "1989 (Taylor's Version) [Deluxe]",
        durationMs = 219_000L,
    )

    @Test
    fun matchesSameSongWithAccentsAndFeaturedArtist() {
        val candidate = AnimatedCoverCandidate(
            title = "Shake It Off (feat. Someone)",
            artist = "Taylor Swift & Someone",
            album = "1989 (Taylor's Version)",
            durationMs = 220_000L,
            albumId = 1L,
        )
        assertEquals(candidate, AnimatedCoverMatcher.bestMatch(track, listOf(candidate)))
    }

    @Test
    fun prefersExactTitleWhenAlbumIsMissing() {
        val target = track.copy(album = "")
        val versioned = AnimatedCoverCandidate(
            title = "Shake It Off (Taylor's Version)",
            artist = "Taylor Swift",
            album = "1989 (Taylor's Version)",
            durationMs = 219_209L,
            albumId = 1713845538L,
        )
        val original = AnimatedCoverCandidate(
            title = "Shake It Off",
            artist = "Taylor Swift",
            album = "1989",
            durationMs = 219_209L,
            albumId = 1440935467L,
        )
        assertEquals(
            original,
            AnimatedCoverMatcher.bestMatch(target, listOf(versioned, original)),
        )
    }

    @Test
    fun rejectsDifferentDuration() {
        val candidate = AnimatedCoverCandidate(
            title = "Shake It Off",
            artist = "Taylor Swift",
            album = "1989 (Taylor's Version) [Deluxe]",
            durationMs = 260_000L,
            albumId = 1L,
        )
        assertNull(AnimatedCoverMatcher.bestMatch(track, listOf(candidate)))
    }

    @Test
    fun rejectsDifferentAlbum() {
        val candidate = AnimatedCoverCandidate(
            title = "Shake It Off",
            artist = "Taylor Swift",
            album = "Reputation",
            durationMs = 219_000L,
            albumId = 1L,
        )
        assertNull(AnimatedCoverMatcher.bestMatch(track, listOf(candidate)))
    }

    @Test
    fun albumKeyIsStableForSameAlbum() {
        val a = track.copy(album = "1989 (Taylor's Version)")
        val b = track.copy(album = "1989 (Taylor's Version)")
        assertEquals(AnimatedCoverMatcher.albumKey(a), AnimatedCoverMatcher.albumKey(b))
    }

    @Test
    fun normalizesAccentsAndPunctuation() {
        val candidate = AnimatedCoverCandidate(
            title = "Ação",
            artist = "Artista",
            album = "Álbum",
            durationMs = 0L,
            albumId = 2L,
        )
        val target = TrackKey("acao", "Artista", "Album", 0L)
        assertEquals(candidate, AnimatedCoverMatcher.bestMatch(target, listOf(candidate)))
    }
}
