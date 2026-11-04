package com.iskezis.myst.data.animatedcover

import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

/** Resultado cru de uma busca no catálogo do provedor. */
data class AnimatedCoverCandidate(
    val title: String?,
    val artist: String?,
    val album: String?,
    val durationMs: Long?,
    val albumId: Long?,
)

/**
 * Correspondência conservadora entre a faixa do YouTube Music e o catálogo
 * público do provedor. Título, artista, álbum e duração precisam bater; uma
 * capa errada é pior do que nenhuma capa animada.
 */
object AnimatedCoverMatcher {

    private const val MAX_DURATION_DIFF_MS = 8_000L

    private val diacritics = Regex("\\p{M}+")
    private val bracketContent = Regex("\\([^)]*\\)|\\[[^\\]]*\\]")
    private val trailingFeatured = Regex("\\b(feat|ft|featuring|with)\\b.*$")
    private val artistSeparator =
        Regex("\\s*(,|&|/|\\bfeat\\b|\\bft\\b|\\bfeaturing\\b|\\bwith\\b).*$")
    private val nonAlphanumeric = Regex("[^a-z0-9]+")

    fun bestMatch(track: TrackKey, candidates: List<AnimatedCoverCandidate>): AnimatedCoverCandidate? {
        val title = normalizeTitle(track.title)
        if (title.isBlank()) return null
        val fullTitle = normalize(track.title)
        val artist = primaryArtist(track.artist)
        val album = normalize(track.album)

        var best: AnimatedCoverCandidate? = null
        var bestScore = 0

        for (candidate in candidates) {
            val candidateTitle = normalizeTitle(candidate.title.orEmpty())
            if (candidateTitle.isBlank() || !comparable(title, candidateTitle)) continue

            val candidateArtist = primaryArtist(candidate.artist.orEmpty())
            if (artist.isNotBlank() && !comparable(artist, candidateArtist)) continue

            val candidateAlbum = normalize(candidate.album.orEmpty())
            if (album.isNotBlank()) {
                if (candidateAlbum.isBlank() || !comparable(album, candidateAlbum)) continue
            }

            val candidateDuration = candidate.durationMs ?: 0L
            val durationDiff = if (track.durationMs > 0L && candidateDuration > 0L) {
                abs(track.durationMs - candidateDuration)
            } else {
                0L
            }
            if (durationDiff > MAX_DURATION_DIFF_MS) continue

            var score = 0
            score += if (title == candidateTitle) 8 else 4
            if (fullTitle == normalize(candidate.title.orEmpty())) score += 4
            score += if (artist.isNotBlank() && artist == candidateArtist) 5 else 2
            if (album.isNotBlank()) {
                score += if (album == candidateAlbum) 3 else 1
            }
            score += when {
                durationDiff <= 2_000L -> 3
                durationDiff <= 5_000L -> 2
                durationDiff <= MAX_DURATION_DIFF_MS -> 1
                else -> 0
            }
            if (candidate.albumId != null && score > bestScore) {
                best = candidate
                bestScore = score
            }
        }
        return best
    }

    /** Chave estável por álbum (ou por faixa quando o álbum não é informado). */
    fun albumKey(track: TrackKey): String {
        val artist = primaryArtist(track.artist)
        val album = normalize(track.album)
        return if (album.isNotBlank()) {
            "album:${album}|$artist"
        } else {
            "track:${normalizeTitle(track.title)}|$artist"
        }
    }

    private fun comparable(left: String, right: String): Boolean {
        if (left == right) return true
        if (left.length < 4 || right.length < 4) return false
        return left.contains(right) || right.contains(left)
    }

    internal fun normalize(value: String): String {
        if (value.isBlank()) return ""
        return Normalizer.normalize(value, Normalizer.Form.NFD)
            .lowercase(Locale.ROOT)
            .replace(diacritics, "")
            .replace(nonAlphanumeric, " ")
            .trim()
    }

    internal fun normalizeTitle(value: String): String {
        val withoutVersions = value.replace(bracketContent, " ")
        return normalize(withoutVersions.replace(trailingFeatured, " "))
    }

    internal fun primaryArtist(value: String): String =
        normalize(value.replace(artistSeparator, " "))
}
