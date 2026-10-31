package com.iskezis.myst.data.youtube

import com.iskezis.myst.util.Thumbnails

data class Track(
    val videoId: String,
    val title: String,
    val artist: String,
    val thumbnailUrl: String? = null,
    val durationMs: Long = 0L,
    val album: String = "",
    /** Canal do artista no YouTube Music, quando a resposta informa. */
    val artistId: String? = null,
    /** Álbum do YouTube Music, quando a resposta informa. */
    val albumId: String? = null,
) {
    /** Melhor arte disponível, mesmo para faixas antigas salvas com URL pequena. */
    fun artworkUrl(): String? = Thumbnails.best(thumbnailUrl, videoId)
}

/** Resultado resumido de artista na busca e nos carrosséis. */
data class ArtistSummary(
    val browseId: String,
    val name: String,
    val thumbnailUrl: String? = null,
    val subtitle: String = "",
)

/** Resultado resumido de álbum na busca e nos carrosséis. */
data class AlbumSummary(
    val browseId: String,
    val title: String,
    val artist: String = "",
    val artistId: String? = null,
    val year: String = "",
    val thumbnailUrl: String? = null,
)

/** Resultado resumido de playlist pública ou da comunidade. */
data class PlaylistSummary(
    val browseId: String,
    val title: String,
    val author: String = "",
    val trackCount: Int = 0,
    val thumbnailUrl: String? = null,
)

enum class SearchFilter {
    ALL,
    SONGS,
    ARTISTS,
    ALBUMS,
    PLAYLISTS,
}

/** Chip devolvido pelo YouTube; os params viram o filtro da próxima busca. */
data class SearchChip(
    val filter: SearchFilter,
    val label: String,
    val params: String?,
)

data class SearchPage(
    val tracks: List<Track> = emptyList(),
    val artists: List<ArtistSummary> = emptyList(),
    val albums: List<AlbumSummary> = emptyList(),
    val playlists: List<PlaylistSummary> = emptyList(),
    val chips: List<SearchChip> = emptyList(),
    val continuation: String? = null,
)

/** Endereço de uma prateleira "Ver todas" do YouTube Music. */
data class BrowseTarget(
    val browseId: String,
    val params: String? = null,
)

data class ArtistPage(
    val browseId: String,
    val name: String,
    val thumbnailUrl: String? = null,
    val description: String = "",
    val monthlyListeners: String = "",
    val topTracks: List<Track> = emptyList(),
    val songsContinuation: String? = null,
    /** Playlist "Músicas em alta" que o YouTube abre pelo botão "Ver todas". */
    val topSongsBrowseId: String? = null,
    /** Discografia completa (além dos 10 itens do carrossel da página). */
    val albumsBrowse: BrowseTarget? = null,
    val singlesBrowse: BrowseTarget? = null,
    val albums: List<AlbumSummary> = emptyList(),
    val singles: List<AlbumSummary> = emptyList(),
    val playlists: List<PlaylistSummary> = emptyList(),
    val relatedArtists: List<ArtistSummary> = emptyList(),
)

data class AlbumPage(
    val browseId: String,
    val title: String,
    val artist: String = "",
    val artistId: String? = null,
    val year: String = "",
    val description: String = "",
    val thumbnailUrl: String? = null,
    val tracks: List<Track> = emptyList(),
    val continuation: String? = null,
)

data class PlaylistPage(
    val browseId: String,
    val title: String,
    val author: String = "",
    val description: String = "",
    val thumbnailUrl: String? = null,
    val trackCount: Int = 0,
    val tracks: List<Track> = emptyList(),
    val continuation: String? = null,
)

/** Página de faixas carregada por continuação (álbum, playlist ou artista). */
data class TrackPage(
    val tracks: List<Track> = emptyList(),
    val continuation: String? = null,
)

/**
 * URLs de telemetria do player. O YouTube usa esses endereços para registrar
 * que o vídeo foi assistido; enviá-los com os cookies da conta faz a faixa
 * tocada no MystMusic aparecer nos Recentes do YouTube Music.
 */
data class PlaybackTracking(
    val playbackUrl: String? = null,
    val watchtimeUrl: String? = null,
    /** Cliente que emitiu as URLs; define User-Agent/Origin da telemetria. */
    val clientName: String = "",
)

/**
 * URL de áudio resolvida e os headers que devem acompanhar a requisição.
 * Os headers incluem o User-Agent do cliente InnerTube que emitiu a URL; alguns
 * servidores do googlevideo rejeitam a URL quando o agente muda no download.
 */
data class StreamInfo(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val tracking: PlaybackTracking? = null,
)

/** Resumo de uma playlist da biblioteca do YouTube Music. */
data class YtPlaylist(
    val id: String,
    val title: String,
    val thumbnailUrl: String? = null,
    val trackCount: Int = 0,
    val liked: Boolean = false,
)

open class MusicException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * A sessão do YouTube Music existe no aparelho, mas a conta recusou a
 * requisição (cookies expirados, revogados ou 401/403). A UI mostra o aviso
 * para entrar de novo; nada é apagado e o usuário não é deslogado sozinho.
 */
class SessionExpiredException(message: String, cause: Throwable? = null) :
    MusicException(message, cause)

/**
 * O YouTube recusou o player pedindo login/verificação. Acontece com sessões
 * anônimas em redes marcadas como bot ou quando os cookies expiraram. O player
 * usa isso para oferecer o login do YouTube em vez de um erro genérico.
 */
class LoginRequiredException(message: String, cause: Throwable? = null) : MusicException(message, cause)

interface MusicSource {
    suspend fun search(query: String, limit: Int = 30): List<Track>
    suspend fun trending(limit: Int = 30): List<Track>
    suspend fun related(videoId: String, limit: Int = 20): List<Track>
    suspend fun homeFeed(limit: Int = 30): List<Track>
    suspend fun libraryPlaylists(limit: Int = 300): List<YtPlaylist>
    suspend fun accountHistory(limit: Int = 50): List<Track>
    suspend fun personalizedHome(limit: Int = 40): List<Track>
    suspend fun playlistTracks(playlistId: String, limit: Int = 500): List<Track>
    suspend fun streamUrl(videoId: String): String
    suspend fun streamInfo(videoId: String): StreamInfo = StreamInfo(streamUrl(videoId))
    fun streamUrlBlocking(videoId: String): String
    fun streamInfoBlocking(videoId: String): StreamInfo = StreamInfo(streamUrlBlocking(videoId))
    fun invalidateStream(videoId: String)

    /**
     * Registra no YouTube que a faixa foi ouvida. É opcional: sem cookies ou
     * sem telemetria disponível o app continua funcionando normalmente.
     */
    suspend fun markWatched(videoId: String, playedMs: Long = 0L): Boolean = false

    suspend fun accountName(): String?

    /**
     * Busca completa com filtro e continuação. `params` vem dos chips do
     * YouTube; quando nulo o cliente usa o filtro conhecido.
     */
    suspend fun searchPage(
        query: String,
        filter: SearchFilter = SearchFilter.ALL,
        params: String? = null,
        continuation: String? = null,
    ): SearchPage

    suspend fun artistPage(browseId: String): ArtistPage

    /**
     * Catálogo completo de músicas do artista: prateleira principal,
     * "Músicas em alta" (com continuação) e todas as faixas dos álbuns e
     * singles/EPs da discografia. Evita duplicatas por `videoId`.
     */
    suspend fun artistSongs(page: ArtistPage): List<Track>

    suspend fun albumPage(browseId: String): AlbumPage

    suspend fun publicPlaylistPage(browseId: String): PlaylistPage

    /** Carrega mais faixas de uma continuação do browse (artista/playlist). */
    suspend fun browseContinuation(continuation: String): TrackPage
}
