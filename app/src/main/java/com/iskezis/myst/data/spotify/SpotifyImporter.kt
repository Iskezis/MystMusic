package com.iskezis.myst.data.spotify

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.util.Log
import com.iskezis.myst.BuildConfig
import com.iskezis.myst.data.db.PlaylistRepository
import com.iskezis.myst.data.youtube.Track
import com.iskezis.myst.data.youtube.YouTubeRepository
import com.iskezis.myst.util.AppLog
import com.iskezis.myst.util.normalizeText
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
import java.io.FileOutputStream
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
        AppLog.i(AppLog.TAG_SPOTIFY, "Iniciando importação: link=${AppLog.urlForLog(link)}")
        val spotifyId = parsePlaylistId(link) ?: throw SpotifyLinkException()
        AppLog.d(AppLog.TAG_SPOTIFY, "ID do Spotify: $spotifyId")
        val playlist = readPlaylist(spotifyId)
        AppLog.i(
            AppLog.TAG_SPOTIFY,
            "Playlist Spotify lida: ${playlist.name} (${playlist.tracks.size} faixas)",
        )
        if (playlist.tracks.isEmpty()) throw SpotifyUnavailableException()

        val limited = playlist.tracks.take(MAX_TRACKS)
        AppLog.d(AppLog.TAG_SPOTIFY, "Mapeando ${limited.size} faixas para o YouTube Music")
        // Cada faixa tem um índice fixo. A ordem final é sempre a ordem do
        // Spotify, mesmo com mapeamentos paralelos e faixas não encontradas.
        val mappedSlots = arrayOfNulls<Track>(limited.size)
        var failures = 0
        var processed = 0
        var slotOffset = 0
        onProgress(0, limited.size)

        limited.chunked(MAPPING_CONCURRENCY).forEach { chunk ->
            currentCoroutineContext().ensureActive()
            val results = coroutineScope {
                chunk.map { spotifyTrack ->
                    async(Dispatchers.IO) { mapTrack(spotifyTrack) }
                }.awaitAll()
            }
            results.forEachIndexed { index, result ->
                if (result.failed) failures++
                mappedSlots[slotOffset + index] = result.track
            }
            slotOffset += chunk.size
            processed += chunk.size
            AppLog.d(
                AppLog.TAG_SPOTIFY,
                "Progresso do mapeamento: $processed/${limited.size}",
            )
            onProgress(processed, limited.size)
        }
        val mapped = mappedSlots.filterNotNull()
        AppLog.i(
            AppLog.TAG_SPOTIFY,
            "Mapeamento concluído: ${mapped.size} encontradas, " +
                "${limited.size - mapped.size} não encontradas, falhas=$failures",
        )

        if (mapped.isEmpty()) {
            if (failures > 0) throw SpotifyImportException()
            throw SpotifyUnavailableException()
        }

        currentCoroutineContext().ensureActive()
        val name = playlist.name.ifBlank { DEFAULT_NAME }
        AppLog.i(AppLog.TAG_SPOTIFY, "Criando playlist Myst $name com ${mapped.size} faixas")
        val playlistId = playlists.create(userId, name)
        playlists.addTracks(playlistId, mapped)
        playlist.coverUrl?.let { url ->
            downloadCover(url, playlistId)?.let { uri -> playlists.setCover(playlistId, uri) }
        }
        AppLog.i(AppLog.TAG_SPOTIFY, "Importação concluída: playlistId=$playlistId")
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
                    AppLog.i(AppLog.TAG_SPOTIFY, "Playlist lida pela Web API")
                    return@withContext fromApi
                }
                AppLog.w(AppLog.TAG_SPOTIFY, "Web API retornou playlist vazia; usando embed")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                AppLog.failure(AppLog.TAG_SPOTIFY, "Web API do Spotify falhou; usando embed", t)
            }
        }
        val fromEmbed = readFromEmbed(spotifyId)
        AppLog.i(AppLog.TAG_SPOTIFY, "Playlist lida pelo JSON do embed")
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
        val cover = largestImage(root["images"] as? JsonArray)

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
            if (items.isEmpty()) break
            parseApiTracks(page, tracks)
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
            val type = (track["type"] as? JsonPrimitive)?.contentOrNull
            if (type == "episode") return@forEach
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
        val entity = findPlaylistEntity(root, spotifyId) ?: throw SpotifyUnavailableException()
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
            val artist = subtitle
                .substringBefore("•")
                .substringBefore(" · ")
                .trim()
            val duration = parseSpotifyDuration(item["duration"])
            tracks.add(SpotifyTrack(title.trim(), artist, duration))
        }
        return SpotifyPlaylist(name.trim(), cover, tracks)
    }

    private fun parseSpotifyDuration(element: JsonElement?): Long {
        val raw = (element as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        if (raw.isBlank()) return 0L
        if (raw.contains(":")) {
            val parts = raw.split(":").mapNotNull { it.trim().toLongOrNull() }
            return when (parts.size) {
                3 -> (parts[0] * 3600 + parts[1] * 60 + parts[2]) * 1000
                2 -> (parts[0] * 60 + parts[1]) * 1000
                else -> 0L
            }
        }
        val value = raw.toLongOrNull() ?: return 0L
        return if (value in 1..9_999) value * 1000 else value
    }

    private fun findPlaylistEntity(element: JsonElement, spotifyId: String): JsonObject? {
        val candidates = mutableListOf<JsonObject>()
        fun collect(value: JsonElement) {
            when (value) {
                is JsonObject -> {
                    if (value["trackList"] is JsonArray) candidates.add(value)
                    value.values.forEach { collect(it) }
                }
                is JsonArray -> value.forEach { collect(it) }
                else -> Unit
            }
        }
        collect(element)
        // O embed traz entidades de playlists relacionadas. Escolher a primeira
        // poderia importar a playlist errada; priorizamos a que tem o ID pedido.
        return candidates.firstOrNull { candidate ->
            val uri = (candidate["uri"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            val id = (candidate["id"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            uri.contains(spotifyId) || id == spotifyId
        } ?: candidates.firstOrNull()
    }

    private fun findCover(entity: JsonObject): String? {
        val candidates = mutableListOf<Pair<String, Long>>()
        fun add(url: String?, width: Long?, height: Long?) {
            if (url.isNullOrBlank()) return
            candidates.add(url to ((width ?: 0L) * (height ?: 0L)))
        }
        val coverArt = entity["coverArt"] as? JsonObject
        (coverArt?.get("sources") as? JsonArray)?.forEach { source ->
            val item = source as? JsonObject ?: return@forEach
            add(
                (item["url"] as? JsonPrimitive)?.contentOrNull,
                (item["width"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull(),
                (item["height"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull(),
            )
        }
        (entity["images"] as? JsonArray)?.forEach { image ->
            val item = image as? JsonObject ?: return@forEach
            add(
                (item["url"] as? JsonPrimitive)?.contentOrNull,
                (item["width"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull(),
                (item["height"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull(),
            )
        }
        return candidates.maxByOrNull { it.second }?.first ?: candidates.firstOrNull()?.first
    }

    private fun largestImage(images: JsonArray?): String? {
        if (images == null) return null
        val candidates = images.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val url = (item["url"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            val width = (item["width"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: 0L
            val height = (item["height"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: 0L
            url to (width * height)
        }
        return candidates.maxByOrNull { it.second }?.first ?: candidates.firstOrNull()?.first
    }

    private suspend fun mapTrack(spotifyTrack: SpotifyTrack): MappedTrack {
        val query = listOf(spotifyTrack.title, spotifyTrack.artist.trim())
            .filter { it.isNotBlank() }
            .joinToString(" ")
        val candidates = try {
            youtube.search(query, 12)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            return MappedTrack(null, failed = true)
        }
        if (candidates.isEmpty()) return MappedTrack(null, failed = false)

        var bestTrack: Track? = null
        var bestScore = 0.0
        candidates.forEach { candidate ->
            val titleScore = titleScore(spotifyTrack.title, candidate.title)
            if (titleScore < MIN_TITLE_SCORE) return@forEach
            if (isWrongVariant(candidate.title, spotifyTrack.title)) return@forEach
            val artistScore = artistScore(
                spotifyTrack.artist,
                candidate.artist,
                candidate.title,
            )
            val durationScore = durationScore(
                spotifyTrack.durationMs,
                candidate.durationMs,
            ) ?: return@forEach
            val artistsKnown = splitArtists(spotifyTrack.artist).isNotEmpty() &&
                (candidate.artist.isNotBlank() || candidate.title.isNotBlank())
            if (artistsKnown && artistScore < MIN_ARTIST_SCORE) return@forEach

            val score = 0.60 * titleScore + 0.30 * artistScore + 0.10 * durationScore
            if (score > bestScore) {
                bestScore = score
                bestTrack = candidate
            }
        }
        return if (bestTrack != null && bestScore >= MATCH_THRESHOLD) {
            MappedTrack(bestTrack, failed = false)
        } else {
            MappedTrack(null, failed = false)
        }
    }

    private fun titleScore(spotifyTitle: String, candidateTitle: String): Double {
        val direct = similarity(spotifyTitle, candidateTitle)
        val spotifyCore = coreTitle(spotifyTitle)
        val candidateCore = coreTitle(candidateTitle)
        val core = similarity(spotifyCore, candidateCore)
        val shorter = minOf(spotifyCore.length, candidateCore.length)
        val longer = maxOf(spotifyCore.length, candidateCore.length)
        val contains = if (
            shorter >= 4 && longer > 0 &&
            shorter.toDouble() / longer >= 0.6 &&
            (spotifyCore.contains(candidateCore) || candidateCore.contains(spotifyCore))
        ) 0.92 else 0.0
        return maxOf(direct, core, contains).coerceIn(0.0, 1.0)
    }

    private fun coreTitle(value: String): String {
        val withoutBrackets = value.replace(Regex("""\s*[\(\[][^)\]]*[\)\]]"""), " ")
        return normalizeText(withoutBrackets)
    }

    private fun artistScore(
        spotifyArtist: String,
        candidateArtist: String,
        candidateTitle: String,
    ): Double {
        val wanted = splitArtists(spotifyArtist)
        if (wanted.isEmpty()) return 0.45
        val haystacks = buildList {
            add(normalizeText(candidateArtist))
            add(normalizeText(candidateTitle))
            addAll(splitArtists(candidateArtist))
        }.filter { it.isNotBlank() }
        if (haystacks.isEmpty()) return 0.0
        var best = 0.0
        wanted.forEach { artist ->
            haystacks.forEach { haystack ->
                val value = when {
                    haystack == artist -> 1.0
                    artist.length >= 4 && haystack.contains(artist) -> 1.0
                    haystack.length >= 4 && artist.contains(haystack) -> 0.95
                    else -> similarity(artist, haystack)
                }
                if (value > best) best = value
            }
        }
        return best.coerceIn(0.0, 1.0)
    }

    private fun splitArtists(raw: String): List<String> {
        return raw.split(",", "&", " feat. ", " featuring ", " with ", " x ")
            .map { normalizeText(it) }
            .filter { it.isNotBlank() }
    }

    private fun isWrongVariant(candidateTitle: String, spotifyTitle: String): Boolean {
        val candidate = normalizeText(candidateTitle)
        val spotify = normalizeText(spotifyTitle)
        val blocked = listOf(
            "karaoke",
            "instrumental",
            "nightcore",
            "sped up",
            "slowed",
            "8d audio",
            "bass boosted",
            "reaction",
            "tutorial",
            "made famous",
            "in the style of",
            "cover",
            "remix",
            "acoustic",
        )
        if (blocked.any { word -> containsWord(candidate, word) && !containsWord(spotify, word) }) {
            return true
        }
        val liveMarkers = listOf("live at", "live from", "live version", "live session")
        if (liveMarkers.any { marker -> containsWord(candidate, marker) && !spotify.contains("live") }) {
            return true
        }
        if (Regex("""\((live|ao vivo)\)""", RegexOption.IGNORE_CASE).containsMatchIn(candidateTitle) &&
            !spotify.contains("live")
        ) {
            return true
        }
        return false
    }

    private fun durationScore(spotifyMs: Long, candidateMs: Long): Double? {
        if (spotifyMs <= 0L || candidateMs <= 0L) return 0.5
        val diff = abs(spotifyMs - candidateMs)
        if (diff > MAX_DURATION_DIFF_MS) return null
        return (1.0 - diff.toDouble() / MAX_DURATION_DIFF_MS).coerceIn(0.0, 1.0)
    }

    private fun containsWord(text: String, word: String): Boolean {
        if (text.isBlank() || word.isBlank()) return false
        return " $text ".contains(" $word ")
    }

    private suspend fun downloadCover(url: String, playlistId: Long): String? =
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder().url(url).build()
                val bytes = http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext null
                    response.body?.bytes() ?: return@withContext null
                }
                if (bytes.size > 8 * 1024 * 1024) return@withContext null
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null
                val options = BitmapFactory.Options().apply {
                    inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight)
                }
                val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                    ?: return@withContext null
                val scaled = scaleDown(decoded, COVER_MAX_SIDE)
                val dir = File(context.filesDir, "covers").apply { mkdirs() }
                val file = File(dir, "$playlistId.jpg")
                FileOutputStream(file).use { output ->
                    scaled.compress(Bitmap.CompressFormat.JPEG, 92, output)
                }
                if (scaled !== decoded) scaled.recycle()
                decoded.recycle()
                Uri.fromFile(file).toString()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                null
            }
        }

    private fun sampleSize(width: Int, height: Int): Int {
        var sample = 1
        var largest = maxOf(width, height)
        while (largest / 2 >= COVER_MAX_SIDE) {
            largest /= 2
            sample *= 2
        }
        return sample
    }

    private fun scaleDown(bitmap: Bitmap, maxSide: Int): Bitmap {
        val largest = maxOf(bitmap.width, bitmap.height)
        if (largest <= maxSide) return bitmap
        val ratio = maxSide.toFloat() / largest
        val width = (bitmap.width * ratio).toInt().coerceAtLeast(1)
        val height = (bitmap.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, width, height, true)
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
        private const val MATCH_THRESHOLD = 0.68
        private const val MIN_TITLE_SCORE = 0.62
        private const val MIN_ARTIST_SCORE = 0.55
        private const val MAX_DURATION_DIFF_MS = 8_000L
        private const val COVER_MAX_SIDE = 1024
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
