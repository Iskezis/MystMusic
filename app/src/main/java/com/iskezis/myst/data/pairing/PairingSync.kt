package com.iskezis.myst.data.pairing

import android.net.Uri
import android.util.Base64
import androidx.room.withTransaction
import com.iskezis.myst.MystApp
import com.iskezis.myst.data.account.AvatarStore
import com.iskezis.myst.data.account.SessionManager
import com.iskezis.myst.data.account.YouTubeAccount
import com.iskezis.myst.data.db.ArtistAffinityEntity
import com.iskezis.myst.data.db.HistoryEntity
import com.iskezis.myst.data.db.PairTombstoneEntity
import com.iskezis.myst.data.db.PlaylistEntity
import com.iskezis.myst.data.db.PlaylistRepository
import com.iskezis.myst.data.db.PlaylistTrackEntity
import com.iskezis.myst.data.db.UserEntity
import com.iskezis.myst.data.youtube.Track
import com.iskezis.myst.util.AppLog
import com.iskezis.myst.util.sha256Hex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Serializa e mescla os dados da conta Myst para o pareamento e para a
 * sincronização contínua celular↔TV.
 *
 * A mesclagem é monotônica: playlists e faixas são unidas por nome/videoId e
 * nada é apagado sem uma lápide explícita (`pair_tombstones`). Assim, criar uma
 * playlist na TV e uma diferente no celular mantém as duas; apagar ou renomear
 * em um dos lados propaga a remoção em vez de ressuscitar a playlist.
 */
object PairingSync {

    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }

    suspend fun export(
        app: MystApp,
        pairId: String? = null,
        pairSecret: String? = null,
        phoneName: String = "",
    ): PairBundle = withContext(Dispatchers.IO) {
        if (!app.session.isLoggedIn) {
            throw IllegalStateException("Conecte as duas contas antes de parear a TV")
        }
        val session = app.session.session
            ?: throw IllegalStateException("Conta Myst ausente")
        val user = app.accounts.currentUser()
        val avatarBase64 = AvatarStore.readBase64(user?.avatarPath)
        val account = PairAccount(
            type = session.type,
            username = user?.username ?: "paired_${sha256Hex(session.displayName).take(12)}",
            displayName = session.displayName.ifBlank {
                user?.displayName ?: user?.username ?: "MystMusic"
            },
            avatarBase64 = avatarBase64,
            youtubeAccountId = app.session.youtubeAccountId,
            youtubeDisplayName = app.session.youtubeDisplayName,
        )
        val snapshot = exportSnapshot(app, session.userId)
        AppLog.i(
            AppLog.TAG_PAIRING,
            "Pareamento exportado: conta=${account.type} " +
                "youtube=${!account.youtubeAccountId.isNullOrBlank()} " +
                "avatar=${avatarBase64 != null} playlists=${snapshot.playlists.size} " +
                "histórico=${snapshot.history.size} lápides=${snapshot.tombstones.size}",
        )
        PairBundle(
            account = account,
            youtubeCookies = app.session.youtubeCookies,
            youtubeSapisid = app.session.youtubeSapisid,
            youtubeVisitorData = app.session.youtubeVisitorData,
            youtubeDataSyncId = app.session.youtubeDataSyncId,
            playlists = snapshot.playlists,
            history = snapshot.history,
            affinities = snapshot.affinities,
            tombstones = snapshot.tombstones,
            pairId = pairId,
            pairSecret = pairSecret,
            phoneName = phoneName,
        )
    }

    /** Instantâneo usado pela sincronização contínua (sem cookies nem senha). */
    suspend fun exportSnapshot(app: MystApp, userId: Long): PairSnapshot =
        withContext(Dispatchers.IO) {
            if (userId <= 0L) return@withContext PairSnapshot()
            val database = app.database
            var coverBudget = MAX_COVER_BUDGET_BYTES
            var trackBudget = MAX_TRACKS_TOTAL
            val playlists = database.playlistDao().playlists(userId).map { playlist ->
                val cover = if (coverBudget > 0) readCoverBase64(playlist.coverUri) else null
                val coverBase64 = if (cover != null && cover.second <= coverBudget) {
                    coverBudget -= cover.second
                    cover.first
                } else {
                    null
                }
                val allTracks = database.playlistDao().tracks(playlist.id)
                val exported = if (allTracks.size <= trackBudget) {
                    allTracks
                } else {
                    allTracks.take(trackBudget)
                }
                trackBudget = (trackBudget - exported.size).coerceAtLeast(0)
                PairPlaylist(
                    name = playlist.name,
                    coverUri = playlist.coverUri?.takeIf {
                        it.startsWith("https://") || it.startsWith("http://")
                    }?.take(MAX_URL_LENGTH),
                    coverBase64 = coverBase64,
                    createdAt = playlist.createdAt,
                    tracks = exported.map { track ->
                        PairTrack(
                            videoId = track.videoId,
                            title = track.title,
                            artist = track.artist,
                            thumbnailUrl = track.thumbnailUrl?.take(MAX_URL_LENGTH),
                            durationMs = track.durationMs,
                        )
                    },
                )
            }
            val history = database.historyDao().recent(userId, MAX_HISTORY).map { item ->
                PairHistory(
                    videoId = item.videoId,
                    title = item.title,
                    artist = item.artist,
                    thumbnailUrl = item.thumbnailUrl?.take(MAX_URL_LENGTH),
                    durationMs = item.durationMs,
                    playedMs = item.playedMs,
                    playedAt = item.playedAt,
                    completed = item.completed,
                )
            }
            val affinities = database.affinityDao().top(userId, MAX_AFFINITIES).map { item ->
                PairAffinity(
                    artist = item.artist,
                    score = item.score,
                    updatedAt = item.updatedAt,
                )
            }
            val tombstones = database.pairTombstoneDao().all(userId).take(MAX_TOMBSTONES).map {
                PairTombstone(key = it.key, deletedAt = it.deletedAt)
            }
            PairSnapshot(
                generatedAt = System.currentTimeMillis(),
                playlists = playlists,
                tombstones = tombstones,
                history = history,
                affinities = affinities,
            )
        }

    suspend fun apply(app: MystApp, bundle: PairBundle) = withContext(Dispatchers.IO) {
        val account = bundle.account
            ?: throw IllegalArgumentException("Payload sem conta Myst")
        val cookies = bundle.youtubeCookies
        if (cookies.isNullOrBlank()) {
            throw IllegalArgumentException("Payload sem a conta do YouTube")
        }
        // Compatibilidade com o celular da 0.1.0: o id da conta não vinha no
        // payload, mas pode ser derivado dos cookies.
        val youtubeAccountId = account.youtubeAccountId
            ?.takeIf { it.isNotBlank() }
            ?: YouTubeAccount.accountId(cookies)
        val youtubeName = account.youtubeDisplayName
            ?.takeIf { it.isNotBlank() }
            ?: "YouTube"

        val dao = app.database.userDao()
        val username = account.username.trim().ifBlank {
            "paired_${sha256Hex(account.displayName).take(12)}"
        }
        val existing = dao.findByUsername(username)
        val userId = existing?.id ?: dao.insert(
            UserEntity(
                username = username,
                displayName = account.displayName.ifBlank { username },
                accountType = SessionManager.TYPE_MYST,
            ),
        )
        app.session.saveMyst(userId, account.displayName.ifBlank { username })
        app.session.saveYouTube(
            accountId = youtubeAccountId,
            name = youtubeName,
            cookies = cookies,
            sapisid = bundle.youtubeSapisid,
        )
        app.session.youtubeVisitorData = bundle.youtubeVisitorData
        app.session.youtubeDataSyncId = bundle.youtubeDataSyncId

        if (!account.avatarBase64.isNullOrBlank()) {
            val avatarPath = AvatarStore.saveBase64(app, userId, account.avatarBase64)
            if (avatarPath != null) {
                dao.updateAvatar(userId, avatarPath)
            }
        }
        dao.updateYouTubeLink(userId, youtubeAccountId, youtubeName)

        mergeSnapshot(
            app = app,
            userId = userId,
            snapshot = PairSnapshot(
                playlists = bundle.playlists,
                history = bundle.history,
                affinities = bundle.affinities,
                tombstones = bundle.tombstones,
            ),
        )

        val pairId = bundle.pairId?.takeIf { it.isNotBlank() }
        val secret = bundle.pairSecret?.takeIf { it.isNotBlank() }
        if (pairId != null && secret != null) {
            app.session.saveTvPairing(
                pairId = pairId,
                secret = secret,
                name = bundle.phoneName.ifBlank { "Celular" },
            )
        } else {
            // Pareamento antigo (dev.5 ou anterior): continua entrando na
            // conta, mas sem canal para sincronizar/desconectar até parear de novo.
            app.session.tvPaired = true
        }
        AppLog.i(
            AppLog.TAG_PAIRING,
            "Pareamento aplicado: usuário=$userId " +
                "youtube=${!youtubeAccountId.isNullOrBlank()} " +
                "avatar=${!account.avatarBase64.isNullOrBlank()} " +
                "playlists=${bundle.playlists.size} canal=${pairId != null}",
        )
        if (pairId != null && secret != null) {
            app.pairedSync.onTvPaired()
        }
    }

    /**
     * Mescla um instantâneo na conta local. Deve ser chamada dentro de
     * `Dispatchers.IO`; abre uma única transação para não deixar a conta pela
     * metade se o processo morrer.
     */
    suspend fun mergeSnapshot(app: MystApp, userId: Long, snapshot: PairSnapshot) =
        withContext(Dispatchers.IO) {
            if (userId <= 0L) return@withContext
            val database = app.database
            val now = System.currentTimeMillis()
            var importedPlaylists = 0
            var importedTracks = 0
            var importedHistory = 0
            val coverWrites = mutableListOf<Pair<Long, PairPlaylist>>()

            database.withTransaction {
                val playlistDao = database.playlistDao()
                val tombstoneDao = database.pairTombstoneDao()
                val tombstoneMap = tombstoneDao.all(userId).associateBy { it.key }.toMutableMap()

                snapshot.tombstones.take(MAX_TOMBSTONES).forEach { incoming ->
                    val key = PlaylistRepository.normalizePlaylistName(incoming.key)
                    if (key.isBlank()) return@forEach
                    val at = incoming.deletedAt.coerceAtLeast(1L)
                    val previous = tombstoneMap[key]
                    if (previous == null || at > previous.deletedAt) {
                        tombstoneDao.upsert(
                            PairTombstoneEntity(
                                userId = userId,
                                key = key,
                                deletedAt = at,
                            ),
                        )
                        tombstoneMap[key] = PairTombstoneEntity(userId, key, at)
                    }
                    val local = playlistDao.playlists(userId)
                        .firstOrNull { PlaylistRepository.normalizePlaylistName(it.name) == key }
                    if (local != null && local.createdAt <= at) {
                        playlistDao.delete(local.id)
                        AppLog.d(
                            AppLog.TAG_PAIRING,
                            "Playlist removida pela sincronização: ${local.name}",
                        )
                    }
                }

                val existingByName = playlistDao.playlists(userId)
                    .associateBy { PlaylistRepository.normalizePlaylistName(it.name) }
                    .toMutableMap()

                snapshot.playlists.take(MAX_PLAYLISTS).forEach { incoming ->
                    val name = incoming.name.trim().take(MAX_NAME_LENGTH)
                    if (name.isBlank()) return@forEach
                    val key = PlaylistRepository.normalizePlaylistName(name)
                    val createdAt = incoming.createdAt.takeIf { it in 1..now } ?: now
                    val tombstone = tombstoneMap[key]
                    if (tombstone != null && tombstone.deletedAt >= createdAt) {
                        return@forEach
                    }
                    val existing = existingByName[key]
                    val playlistId = existing?.id ?: playlistDao.insert(
                        PlaylistEntity(
                            userId = userId,
                            name = name,
                            createdAt = createdAt,
                        ),
                    )
                    if (existing == null) {
                        existingByName[key] = PlaylistEntity(
                            id = playlistId,
                            userId = userId,
                            name = name,
                            createdAt = createdAt,
                        )
                        importedPlaylists++
                    }
                    if (existing == null || existing.coverUri.isNullOrBlank()) {
                        coverWrites += playlistId to incoming
                    }
                    val knownTracks = playlistDao.tracks(playlistId)
                        .mapTo(mutableSetOf()) { it.videoId }
                    var position = playlistDao.maxPosition(playlistId) + 1
                    incoming.tracks.take(MAX_TRACKS_PER_PLAYLIST).forEach { track ->
                        val videoId = track.videoId.trim().take(MAX_ID_LENGTH)
                        if (videoId.isBlank() || !knownTracks.add(videoId)) return@forEach
                        val inserted = playlistDao.insertTrack(
                            PlaylistTrackEntity(
                                playlistId = playlistId,
                                videoId = videoId,
                                title = track.title.trim().take(MAX_TITLE_LENGTH),
                                artist = track.artist.trim().take(MAX_ARTIST_LENGTH),
                                thumbnailUrl = track.thumbnailUrl?.take(MAX_URL_LENGTH),
                                durationMs = track.durationMs.coerceAtLeast(0L),
                                position = position,
                            ),
                        )
                        if (inserted != -1L) {
                            position++
                            importedTracks++
                        }
                    }
                }

                val historyDao = database.historyDao()
                val historyKeys = historyDao.keys(userId).toMutableSet()
                snapshot.history.take(MAX_HISTORY).forEach { item ->
                    val videoId = item.videoId.trim().take(MAX_ID_LENGTH)
                    if (videoId.isBlank()) return@forEach
                    val playedAt = item.playedAt.takeIf { it > 0L } ?: now
                    if (!historyKeys.add("$videoId|$playedAt")) return@forEach
                    historyDao.insert(
                        HistoryEntity(
                            userId = userId,
                            videoId = videoId,
                            title = item.title.trim().take(MAX_TITLE_LENGTH),
                            artist = item.artist.trim().take(MAX_ARTIST_LENGTH),
                            thumbnailUrl = item.thumbnailUrl?.take(MAX_URL_LENGTH),
                            durationMs = item.durationMs.coerceAtLeast(0L),
                            playedMs = item.playedMs.coerceAtLeast(0L),
                            playedAt = playedAt,
                            completed = item.completed,
                        ),
                    )
                    importedHistory++
                }

                val affinityDao = database.affinityDao()
                val knownAffinities = affinityDao.top(userId, MAX_AFFINITIES)
                    .associateBy { it.artist }
                snapshot.affinities.take(MAX_AFFINITIES).forEach { item ->
                    val artist = item.artist.trim().take(MAX_ARTIST_LENGTH)
                    if (artist.isBlank() || !item.score.isFinite()) return@forEach
                    val previous = knownAffinities[artist]
                    affinityDao.upsert(
                        ArtistAffinityEntity(
                            userId = userId,
                            artist = artist,
                            score = maxOf(previous?.score ?: 0.0, item.score.coerceAtLeast(0.0)),
                            updatedAt = maxOf(previous?.updatedAt ?: 0L, item.updatedAt),
                        ),
                    )
                }
            }

            if (coverWrites.isNotEmpty()) {
                var budget = MAX_COVER_BUDGET_BYTES
                coverWrites.forEach { (playlistId, incoming) ->
                    if (budget <= 0) return@forEach
                    val saved = saveCover(app, playlistId, incoming, budget)
                    if (saved != null) {
                        val size = runCatching { File(Uri.parse(saved).path.orEmpty()).length() }
                            .getOrDefault(0L)
                        budget = (budget - size).coerceAtLeast(0L)
                        database.playlistDao().updateCover(playlistId, saved)
                    }
                }
            }

            if (importedPlaylists > 0 || importedTracks > 0 || importedHistory > 0) {
                AppLog.i(
                    AppLog.TAG_PAIRING,
                    "Sincronização mesclada: playlists=$importedPlaylists " +
                        "faixas=$importedTracks histórico=$importedHistory",
                )
            }
        }

    private fun readCoverBase64(coverUri: String?): Pair<String, Int>? {
        if (coverUri.isNullOrBlank() || !coverUri.startsWith("file:")) return null
        return runCatching {
            val file = File(Uri.parse(coverUri).path.orEmpty())
            if (!file.isFile || file.length() <= 0L || file.length() > MAX_COVER_BYTES) {
                return null
            }
            val bytes = file.readBytes()
            Base64.encodeToString(bytes, Base64.NO_WRAP) to bytes.size
        }.onFailure {
            AppLog.failure(AppLog.TAG_PAIRING, "Falha lendo capa para a sincronização", it)
        }.getOrNull()
    }

    private fun saveCover(
        app: MystApp,
        playlistId: Long,
        incoming: PairPlaylist,
        budget: Long,
    ): String? {
        val encoded = incoming.coverBase64
        if (!encoded.isNullOrBlank() && encoded.length <= MAX_COVER_BYTES * 2) {
            val saved = runCatching {
                val bytes = Base64.decode(encoded, Base64.DEFAULT)
                if (bytes.isEmpty() || bytes.size > MAX_COVER_BYTES || bytes.size > budget) {
                    null
                } else {
                    val dir = File(app.filesDir, COVERS_DIR).apply { mkdirs() }
                    val target = File(dir, "$playlistId.jpg")
                    val temp = File(dir, "$playlistId.tmp")
                    temp.writeBytes(bytes)
                    if (!temp.renameTo(target)) {
                        temp.copyTo(target, overwrite = true)
                        temp.delete()
                    }
                    Uri.fromFile(target).toString()
                }
            }.onFailure {
                AppLog.failure(AppLog.TAG_PAIRING, "Falha restaurando capa da sincronização", it)
            }.getOrNull()
            if (saved != null) return saved
        }
        val uri = incoming.coverUri?.trim()?.take(MAX_URL_LENGTH) ?: return null
        return when {
            uri.startsWith("https://") || uri.startsWith("http://") -> uri
            else -> null
        }
    }

    private const val COVERS_DIR = "covers"
    private const val MAX_COVER_BYTES = 512 * 1024
    private const val MAX_COVER_BUDGET_BYTES = 4 * 1024 * 1024
    private const val MAX_PLAYLISTS = 2_000
    private const val MAX_TRACKS_PER_PLAYLIST = 10_000
    private const val MAX_TRACKS_TOTAL = 30_000
    private const val MAX_HISTORY = 2_000
    private const val MAX_AFFINITIES = 500
    private const val MAX_TOMBSTONES = 5_000
    private const val MAX_ID_LENGTH = 128
    private const val MAX_TITLE_LENGTH = 300
    private const val MAX_ARTIST_LENGTH = 200
    private const val MAX_NAME_LENGTH = 40
    private const val MAX_URL_LENGTH = 2_000
}
