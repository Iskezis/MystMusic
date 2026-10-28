package com.iskezis.myst.data.spotify

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.util.Log
import com.iskezis.myst.BuildConfig
import com.iskezis.myst.data.db.PlaylistRepository
import com.iskezis.myst.data.youtube.Track
import com.iskezis.myst.data.youtube.YouTubeRepository
import com.iskezis.myst.util.similarity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.abs

class SpotifyLinkException : Exception("Link de playlist inválido")

class SpotifyUnavailableException : Exception("Playlist indisponível")

class SpotifyImportException(cause: Throwable? = null) :
    Exception("Não foi possível importar a playlist", cause)

data class SpotifyTrack(
    val title: String,
    val artist: String,
    val durationMs: Long,
)

data class SpotifyPlaylist(
    val name: String,
    val coverUrl: String?,
    val tracks: List<SpotifyTrack>,
)

data class SpotifyImportSummary(
    val playlistId: Long,
    val name: String,
    val total: Int,
    val found: Int,
    val notFound: Int,
)

/**
 * Importa playlists públicas do Spotify. Tenta a Web API oficial com Client
 * Credentials quando as chaves existem; sem chaves (ou se a API recusar), lê o
 * JSON embutido na página pública de embed. As duas vias são não confiáveis a
 * longo prazo, por isso todo o parser é defensivo e há mensagem visível de erro.
 */
class SpotifyImporter(
    private val context: Context,
    private val youtube: YouTubeRepository,
    private val playlists: PlaylistRepository,
) {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .callTimeout(40, TimeUnit.SECONDS)
        .build()

    suspend fun importPlaylist(
        link: String,
        userId: Long,
        onProgress: (done: Int, total: Int) -> Unit,
    ): SpotifyImportSummary {
        val spotifyId = parsePlaylistId(link) ?: throw SpotifyLinkException()
        val playlist = readPlaylist(spotifyId)
        if (playlist.tracks.isEmpty()) throw SpotifyUnavailableException()

        val limited = playlist.tracks.take(MAX_TRACKS)
        val mapped = ArrayList<Track>(limited.size)
        var failures = 0
        var processed = 0
        onProgress(0, limited.size)

        limited.chunked(MAPPING_CONCURRENCY).forEach { chunk ->
            currentCoroutineContext().ensureActive()
            val results = coroutineScope {
                chunk.map { spotifyTrack ->
                    async(Dispatchers.IO) { mapTrack(spotifyTrack) }
                }.awaitAll()
            }
            results.forEach { result ->
                if (result.failed) failures++
                if (result.track != null) mapped.add(result.track)
            }
            processed += chunk.size
            onProgress(processed, limited.size)
        }

        if (mapped.isEmpty()) {
            if (failures > 0) throw SpotifyImportException()
            throw SpotifyUnavailableException()
        }

        currentCoroutineContext().ensureActive()
        val name = playlist.name.ifBlank { DEFAULT_NAME }
        val playlistId = playlists.create(userId, name)
        playlists.addTracks(playlistId, mapped)
        playlist.coverUrl?.let { url ->
            downloadCover(url, playlistId)?.let { uri -> playlists.setCover(playlistId, uri) }
        }
        return SpotifyImportSummary(
            playlistId = playlistId,
            name = name,
            total = limited.size,
            found = mapped.size,
            notFound = (limited.size - mapped.size).coerceAtLeast(0),
        )
    }

    suspend fun readPlaylist(spotifyId: String): SpotifyPlaylist = withContext(Dispatchers.IO) {
        val clientId = BuildConfig.SPOTIFY_CLIENT_ID
        val clientSecret = BuildConfig.SPOTIFY_CLIENT_SECRET
        if (clientId.isNotBlank() && clientSecret.isNotBlank()) {
            try {
                val fromApi = readFromApi(spotifyId, clientId, clientSecret)
                if (fromApi.tracks.isNotEmpty()) {
                    Log.i(TAG, "Playlist lida pela Web API")
                    return@withContext fromApi
                }
                Log.w(TAG, "Web API retornou playlist vazia; usando embed")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                Log.w(TAG, "Web API falhou; usando embed", t)
            }
        }
        val fromEmbed = readFromEmbed(spotifyId)
        Log.i(TAG, "Playlist lida pelo JSON do embed")
        fromEmbed
    }

    private fun readFromApi(
        spotifyId: String,
        clientId: String,
        clientSecret: String,
    ): SpotifyPlaylist {
        val token = requestToken(clientId, clientSecret)
        val request = Request.Builder()
            .url("$API_BASE/playlists/$spotifyId")
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .build()
        val root = executeJson(request) ?: throw SpotifyImportException()
        val name = (root["name"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val cover = ((root["images"] as? JsonArray)?.firstOrNull() as? JsonObject)
            ?.get("url")
            ?.let { (it as? JsonPrimitive)?.contentOrNull }

        val tracks = mutableListOf<SpotifyTrack>()
        val tracksObject = root["tracks"] as? JsonObject
        parseApiTracks(tracksObject, tracks)

        var offset = 100
        var guard = 0
        while (offset < MAX_TRACKS && guard < 20) {
            guard++
            val pageRequest = Request.Builder()
                .url("$API_BASE/playlists/$spotifyId/tracks?limit=100&offset=$offset")
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/json")
                .build()
            val page = executeJson(pageRequest) ?: break
            val items = page["items"] as? JsonArray ?: break
            val before = tracks.size
            parseApiTracks(page, tracks)
            if (items.isEmpty() || tracks.size == before) break
            offset += items.size
        }
        return SpotifyPlaylist(name, cover, tracks)
    }

    private fun parseApiTracks(container: JsonObject?, output: MutableList<SpotifyTrack>) {
        val items = container?.get("items") as? JsonArray ?: return
        items.forEach { element ->
            val item = element as? JsonObject ?: return@forEach
            val track = item["track"] as? JsonObject ?: return@forEach
            val isLocal = (track["is_local"] as? JsonPrimitive)?.contentOrNull == "true"
            if (isLocal) return@forEach
            val title = (track["name"] as? JsonPrimitive)?.contentOrNull.orEmpty().trim()
            if (title.isBlank() || title == AD_TITLE) return@forEach
            val artists = (track["artists"] as? JsonArray)?.mapNotNull { artist ->
                ((artist as? JsonObject)?.get("name") as? JsonPrimitive)?.contentOrNull
            }.orEmpty().joinToString(", ")
            val duration = (track["duration_ms"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: 0L
            output.add(SpotifyTrack(title, artists, duration))
        }
    }

    private fun requestToken(clientId: String, clientSecret: String): String {
        val credentials = Base64.encodeToString(
            "$clientId:$clientSecret".toByteArray(Charsets.UTF_8),
            Base64.NO_WRAP,
        )
        val body = FormBody.Builder().add("grant_type", "client_credentials").build()
        val request = Request.Builder()
            .url("https://accounts.spotify.com/api/token")
            .header("Authorization", "Basic $credentials")
            .post(body)
            .build()
        val root = executeJson(request) ?: throw SpotifyImportException()
        return (root["access_token"] as? JsonPrimitive)?.contentOrNull
            ?: throw SpotifyImportException()
    }

    private fun readFromEmbed(spotifyId: String): SpotifyPlaylist {
        val request = Request.Builder()
            .url("https://open.spotify.com/embed/playlist/$spotifyId")
            .header("User-Agent", BROWSER_UA)
            .header("Accept-Language", "pt-BR,pt;q=0.9,en;q=0.8")
            .build()
        val html = executeText(request) ?: throw SpotifyImportException()
        val match = NEXT_DATA.find(html) ?: throw SpotifyImportException()
        val root = try {
            json.parseToJsonElement(match.groupValues[1])
        } catch (t: Throwable) {
            throw SpotifyImportException(t)
        }
        val entity = findPlaylistEntity(root) ?: throw SpotifyUnavailableException()
        val name = (entity["name"] as? JsonPrimitive)?.contentOrNull
            ?: (entity["title"] as? JsonPrimitive)?.contentOrNull
            ?: ""
        val cover = findCover(entity)
        val tracks = mutableListOf<SpotifyTrack>()
        val trackList = entity["trackList"] as? JsonArray ?: JsonArray(emptyList())
        trackList.forEach { element ->
            val item = element as? JsonObject ?: return@forEach
            val title = (item["title"] as? JsonPrimitive)?.contentOrNull
                ?: (item["name"] as? JsonPrimitive)?.contentOrNull
                ?: return@forEach
            if (title.isBlank() || title == AD_TITLE) return@forEach
            val subtitle = (item["subtitle"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            var duration = (item["duration"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: 0L
            if (duration in 1..9999) duration *= 1000
            tracks.add(SpotifyTrack(title.trim(), subtitle.trim(), duration))
        }
        return SpotifyPlaylist(name.trim(), cover, tracks)
    }

    private fun findPlaylistEntity(element: JsonElement): JsonObject? {
        when (element) {
            is JsonObject -> {
                if (element["trackList"] is JsonArray) return element
                element.values.forEach { value ->
                    findPlaylistEntity(value)?.let { return it }
                }
            }
            is JsonArray -> element.forEach { value ->
                findPlaylistEntity(value)?.let { return it }
            }
            else -> Unit
        }
        return null
    }

    private fun findCover(entity: JsonObject): String? {
        val coverArt = entity["coverArt"] as? JsonObject
        val sources = coverArt?.get("sources") as? JsonArray
        sources?.forEach { source ->
            val url = ((source as? JsonObject)?.get("url") as? JsonPrimitive)?.contentOrNull
            if (!url.isNullOrBlank()) return url
        }
        (entity["images"] as? JsonArray)?.forEach { image ->
            val url = ((image as? JsonObject)?.get("url") as? JsonPrimitive)?.contentOrNull
            if (!url.isNullOrBlank()) return url
        }
        return null
    }

    private suspend fun mapTrack(spotifyTrack: SpotifyTrack): MappedTrack {
        val query = listOf(spotifyTrack.title, spotifyTrack.artist)
            .filter { it.isNotBlank() }
            .joinToString(" ")
        val candidates = try {
            youtube.search(query, 8)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            return MappedTrack(null, failed = true)
        }
        if (candidates.isEmpty()) return MappedTrack(null, failed = false)

        val best = candidates.maxByOrNull { candidate ->
            val titleScore = similarity(spotifyTrack.title, candidate.title)
            val artistScore = candidate.artist.split(",")
                .maxOfOrNull { similarity(spotifyTrack.artist, it.trim()) } ?: 0.0
            val durationScore = if (spotifyTrack.durationMs > 0 && candidate.durationMs > 0) {
                (1.0 - abs(spotifyTrack.durationMs - candidate.durationMs) / 20_000.0)
                    .coerceIn(0.0, 1.0)
            } else {
                0.5
            }
            0.6 * titleScore + 0.3 * artistScore + 0.1 * durationScore
        } ?: return MappedTrack(null, failed = false)
        val score = run {
            val titleScore = similarity(spotifyTrack.title, best.title)
            val artistScore = best.artist.split(",")
                .maxOfOrNull { similarity(spotifyTrack.artist, it.trim()) } ?: 0.0
            0.6 * titleScore + 0.4 * artistScore
        }
        return if (score >= MATCH_THRESHOLD) MappedTrack(best, failed = false)
        else MappedTrack(null, failed = false)
    }

    private suspend fun downloadCover(url: String, playlistId: Long): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder().url(url).build()
                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    val bytes = response.body?.bytes() ?: return@use null
                    if (bytes.size > 6 * 1024 * 1024) return@use null
                    val dir = File(context.filesDir, "covers").apply { mkdirs() }
                    val file = File(dir, "$playlistId.jpg")
                    file.writeBytes(bytes)
                    Uri.fromFile(file).toString()
                }
            }.getOrNull()
        }

    private fun executeJson(request: Request): JsonObject? {
        val text = executeText(request) ?: return null
        return try {
            json.parseToJsonElement(text) as? JsonObject
        } catch (t: Throwable) {
            null
        }
    }

    private fun executeText(request: Request): String? {
        return try {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                response.body?.string()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            null
        }
    }

    private data class MappedTrack(val track: Track?, val failed: Boolean)

    companion object {
        private const val TAG = "SpotifyImport"
        private const val API_BASE = "https://api.spotify.com/v1"
        private const val DEFAULT_NAME = "Playlist do Spotify"
        private const val AD_TITLE = "Advertisement"
        private const val MAX_TRACKS = 500
        private const val MAPPING_CONCURRENCY = 4
        private const val MATCH_THRESHOLD = 0.42
        private const val BROWSER_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        private val NEXT_DATA = Regex(
            """<script id="__NEXT_DATA__" type="application/json">(.*?)</script>""",
            RegexOption.DOT_MATCHES_ALL,
        )
        private val WEB_LINK = Regex(
            """(?:https?://)?open\.spotify\.com/(?:intl-[a-z]{2}/)?playlist/([A-Za-z0-9]{22})""",
            RegexOption.IGNORE_CASE,
        )
        private val URI_LINK = Regex("""spotify:playlist:([A-Za-z0-9]{22})""")

        fun parsePlaylistId(link: String): String? {
            val trimmed = link.trim()
            val match = WEB_LINK.find(trimmed) ?: URI_LINK.find(trimmed)
            return match?.groupValues?.getOrNull(1)
        }
    }
}
