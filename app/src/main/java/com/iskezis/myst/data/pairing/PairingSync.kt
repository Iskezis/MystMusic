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
import com.iskezis.myst.data.db.PlayEventEntity
import com.iskezis.myst.data.db.PlaylistEntity
import com.iskezis.myst.data.db.PlaylistRepository
import com.iskezis.myst.data.db.PlaylistTrackEntity
import com.iskezis.myst.data.db.UserEntity
import com.iskezis.myst.data.db.YtHistoryEntity
import com.iskezis.myst.data.db.YtPlaylistEntity
import com.iskezis.myst.data.db.YtPlaylistTrackEntity
import com.iskezis.myst.data.db.YtSyncEntity
import com.iskezis.myst.data.settings.AppearanceSettings
import com.iskezis.myst.data.settings.ThemeMode
import com.iskezis.myst.data.youtube.Track
import com.iskezis.myst.util.AppLog
import com.iskezis.myst.util.sha256Hex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
        val extras = exportExtras(app)
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
                "histórico=${snapshot.history.size} lápides=${snapshot.tombstones.size} " +
                "yt_playlists=${snapshot.youtube.playlists.size} " +
                "yt_histórico=${snapshot.youtube.history.size}",
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
            playEvents = snapshot.playEvents,
            tombstones = snapshot.tombstones,
            appearance = extras.appearance,
            discord = extras.discord,
            youtube = snapshot.youtube,
            pairId = pairId,
            pairSecret = pairSecret,
            phoneName = phoneName,
        )
    }

    /** Estado do celular que não faz parte da conta e é aplicado ao vivo na TV. */
    fun exportExtras(app: MystApp): PairExtras {
        val appearance = app.appearance.snapshot()
        val discordToken = app.session.discordToken?.trim()?.takeIf { it.isNotBlank() }
        return PairExtras(
            appearance = PairAppearance(
                themeMode = appearance.themeMode.storageValue,
                amoled = appearance.amoled,
                effectiveDark = app.appearance.isDarkEffective(app),
            ),
            discord = if (discordToken == null) {
                null
            } else {
                PairDiscord(
                    token = discordToken,
                    name = app.session.discordName?.takeIf { it.isNotBlank() },
                )
            },
            discordKnown = true,
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
                            album = track.album,
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
                    album = item.album,
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
            val playEvents = database.playEventDao().recent(userId, MAX_PLAY_EVENTS).map { item ->
                PairPlayEvent(
                    videoId = item.videoId,
                    title = item.title,
                    artist = item.artist,
                    album = item.album,
                    thumbnailUrl = item.thumbnailUrl?.take(MAX_URL_LENGTH),
                    startedAt = item.startedAt,
                    playedMs = item.playedMs,
                    durationMs = item.durationMs,
                    completed = item.completed,
                    source = item.source.take(MAX_SOURCE_LENGTH),
                )
            }
            PairSnapshot(
                generatedAt = System.currentTimeMillis(),
                playlists = playlists,
                tombstones = tombstones,
                history = history,
                affinities = affinities,
                playEvents = playEvents,
                youtube = exportYouTube(app, userId),
            )
        }

    /**
     * Retrato TV → celular. A TV não altera a biblioteca do YouTube (ela é
     * somente leitura) nem as capas locais, então o envio leva apenas o que
     * pode ter mudado: playlists, histórico, afinidades, estatísticas e
     * lápides. Sem capas/YouTube o payload fica pequeno o bastante para ser
     * enviado após cada mudança, sem esperar o ciclo de 30 s.
     */
    suspend fun exportTvChanges(app: MystApp, userId: Long): PairSnapshot =
        withContext(Dispatchers.IO) {
            if (userId <= 0L) return@withContext PairSnapshot()
            val database = app.database
            var trackBudget = MAX_TRACKS_TOTAL
            val playlists = database.playlistDao().playlists(userId).map { playlist ->
                val allTracks = database.playlistDao().tracks(playlist.id)
                val exported = if (allTracks.size <= trackBudget) {
                    allTracks
                } else {
                    allTracks.take(trackBudget)
                }
                trackBudget = (trackBudget - exported.size).coerceAtLeast(0)
                PairPlaylist(
                    name = playlist.name,
                    // A capa é do celular; a TV não a altera e não precisa
                    // devolver o base64 em cada play.
                    coverUri = null,
                    coverBase64 = null,
                    createdAt = playlist.createdAt,
                    tracks = exported.map { track ->
                        PairTrack(
                            videoId = track.videoId,
                            title = track.title,
                            artist = track.artist,
                            album = track.album,
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
                    album = item.album,
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
            val playEvents = database.playEventDao().recent(userId, MAX_PLAY_EVENTS).map { item ->
                PairPlayEvent(
                    videoId = item.videoId,
                    title = item.title,
                    artist = item.artist,
                    album = item.album,
                    thumbnailUrl = item.thumbnailUrl?.take(MAX_URL_LENGTH),
                    startedAt = item.startedAt,
                    playedMs = item.playedMs,
                    durationMs = item.durationMs,
                    completed = item.completed,
                    source = item.source.take(MAX_SOURCE_LENGTH),
                )
            }
            PairSnapshot(
                generatedAt = System.currentTimeMillis(),
                playlists = playlists,
                tombstones = tombstones,
                history = history,
                affinities = affinities,
                playEvents = playEvents,
                youtube = PairYouTube(),
            )
        }

    /**
     * Exporta a biblioteca do YouTube Music (playlists, faixas e histórico).
     * Sem isso a seção Recentes da TV ficava só com o histórico local da conta
     * Myst, enquanto no celular a lista combina as duas fontes.
     */
    private suspend fun exportYouTube(app: MystApp, userId: Long): PairYouTube {
        val dao = app.database.youTubeLibraryDao()
        val tracksByPlaylist = dao.allPlaylistTracks(userId).groupBy { it.ytId }
        var trackBudget = MAX_YT_TRACKS_TOTAL
        val playlists = dao.playlists(userId).take(MAX_YT_PLAYLISTS).map { summary ->
            val available = tracksByPlaylist[summary.ytId].orEmpty()
            val limit = minOf(MAX_YT_TRACKS_PER_PLAYLIST, trackBudget)
            val tracks = if (limit > 0) available.take(limit) else emptyList()
            trackBudget = (trackBudget - tracks.size).coerceAtLeast(0)
            PairYouTubePlaylist(
                ytId = summary.ytId.trim().take(MAX_ID_LENGTH),
                title = summary.title.trim().take(MAX_TITLE_LENGTH),
                thumbnailUrl = summary.thumbnailUrl?.take(MAX_URL_LENGTH),
                trackCount = summary.trackCount.coerceAtLeast(tracks.size),
                liked = summary.liked,
                syncedAt = summary.syncedAt,
                tracks = tracks.map { track ->
                    PairYouTubeTrack(
                        videoId = track.videoId.trim().take(MAX_ID_LENGTH),
                        title = track.title.trim().take(MAX_TITLE_LENGTH),
                        artist = track.artist.trim().take(MAX_ARTIST_LENGTH),
                        album = track.album.trim().take(MAX_ALBUM_LENGTH),
                        thumbnailUrl = track.thumbnailUrl?.take(MAX_URL_LENGTH),
                        durationMs = track.durationMs.coerceAtLeast(0L),
                        position = track.position,
                        syncedAt = track.syncedAt,
                    )
                },
            )
        }.filter { it.ytId.isNotBlank() }
        val history = dao.history(userId, MAX_YT_HISTORY).map { item ->
            PairYouTubeHistory(
                videoId = item.videoId.trim().take(MAX_ID_LENGTH),
                title = item.title.trim().take(MAX_TITLE_LENGTH),
                artist = item.artist.trim().take(MAX_ARTIST_LENGTH),
                album = item.album.trim().take(MAX_ALBUM_LENGTH),
                thumbnailUrl = item.thumbnailUrl?.take(MAX_URL_LENGTH),
                durationMs = item.durationMs.coerceAtLeast(0L),
                playedAt = item.playedAt,
            )
        }.filter { it.videoId.isNotBlank() }
        val sync = dao.syncStates(userId).take(MAX_YT_SYNC_STATES).map { item ->
            PairYouTubeSync(
                section = item.section.trim().take(MAX_SYNC_SECTION_LENGTH),
                syncedAt = item.syncedAt,
            )
        }.filter { it.section.isNotBlank() }
        return PairYouTube(playlists = playlists, history = history, sync = sync)
    }

    suspend fun apply(app: MystApp, bundle: PairBundle) = withContext(Dispatchers.IO) {
        val account = bundle.account
            ?: throw IllegalArgumentException("Payload sem conta Myst")
        val cookies = bundle.youtubeCookies
        if (cookies.isNullOrBlank()) {
            throw IllegalArgumentException("Payload sem a conta do YouTube")
        }
        val pairId = bundle.pairId?.takeIf { it.isNotBlank() }
        val secret = bundle.pairSecret?.takeIf { it.isNotBlank() }
        if (pairId == null || secret == null) {
            // Sem o segredo não há como o celular desconectar ou sincronizar
            // depois. Falhar aqui evita uma TV "pareada" que volta para a tela
            // do código em loop.
            throw IllegalArgumentException(
                "Atualize o MystMusic no celular e pareie a TV novamente",
            )
        }
        // Compatibilidade com o celular da 0.1.0: o id da conta não vinha no
        // payload, mas pode ser derivado dos cookies.
        val youtubeAccountId = account.youtubeAccountId
            ?.takeIf { it.isNotBlank() }
            ?: YouTubeAccount.accountId(cookies)
        val youtubeName = account.youtubeDisplayName
            ?.takeIf { it.isNotBlank() }
            ?: "YouTube"

        // Um novo pareamento invalida qualquer limpeza atrasada do anterior.
        // Sem isso, o clearAllTables antigo podia apagar os dados recém-recebidos
        // e o resetAppearance podia desligar o AMOLED novo.
        app.pairedSync.prepareTvPairing()

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

        // A TV é temporária: o tema/AMOLED do celular vale só enquanto o
        // pareamento existir. É gravado nas preferências do aparelho para
        // sobreviver a uma recriação de Activity e apagado na desconexão/logout.
        applyAppearance(app, bundle.appearance, immediate = false)
        applyDiscord(app, bundle.discord, known = true)

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
                playEvents = bundle.playEvents,
                tombstones = bundle.tombstones,
                youtube = bundle.youtube ?: PairYouTube(),
            ),
        )

        app.session.saveTvPairing(
            pairId = pairId,
            secret = secret,
            name = bundle.phoneName.ifBlank { "Celular" },
        )
        AppLog.i(
            AppLog.TAG_PAIRING,
            "Pareamento aplicado: usuário=$userId " +
                "youtube=${!youtubeAccountId.isNullOrBlank()} " +
                "avatar=${!account.avatarBase64.isNullOrBlank()} " +
                "playlists=${bundle.playlists.size} canal=true",
        )
        app.pairedSync.onTvPaired()
    }

    /**
     * Aplica o estado que o celular envia em cada sincronização. A TV usa isso
     * para refletir AMOLED e Discord sem precisar de um novo pareamento.
     */
    fun applyExtras(app: MystApp, extras: PairExtras) {
        applyAppearance(app, extras.appearance, immediate = true)
        applyDiscord(app, extras.discord, known = extras.discordKnown)
    }

    /**
     * O AMOLED só existe quando o tema efetivo é escuro. Se o celular está no
     * modo Sistema com o sistema escuro, a TV pode estar no modo claro; nesse
     * caso o modo é fixado em Escuro para o preto AMOLED chegar ativo. Se o
     * celular está claro, o AMOLED é desativado na TV em vez de aparecer numa
     * tela clara onde ele não teria efeito.
     */
    private fun applyAppearance(
        app: MystApp,
        incoming: PairAppearance?,
        immediate: Boolean,
    ) {
        if (incoming == null) return
        val current = app.appearance.snapshot()
        var themeMode = incoming.themeMode
            ?.let { ThemeMode.fromStorageOrNull(it) }
            ?: current.themeMode
        var amoled = incoming.amoled ?: current.amoled
        val effectiveDark = incoming.effectiveDark
        if (effectiveDark != null) {
            // O modo Sistema depende do aparelho. Para a TV refletir o que o
            // usuário está vendo no celular, envia-se o resultado efetivo, não
            // apenas a preferência.
            themeMode = if (effectiveDark) ThemeMode.DARK else ThemeMode.LIGHT
        }
        if (effectiveDark == false) {
            amoled = false
        }
        if (amoled && effectiveDark != false && themeMode == ThemeMode.SYSTEM) {
            themeMode = ThemeMode.DARK
        }
        val settings = AppearanceSettings(themeMode = themeMode, amoled = amoled)
        if (settings != current) {
            app.appearance.apply(settings)
            AppLog.i(
                AppLog.TAG_PAIRING,
                "Aparência do celular aplicada na TV: " +
                    "modo=${settings.themeMode.storageValue} amoled=${settings.amoled} " +
                    "escuroEfetivo=${effectiveDark ?: "desconhecido"}",
            )
        }
        if (immediate) {
            // AppCompat recria as Activities abertas na thread principal. Sempre
            // reaplica, mesmo quando as preferências já eram iguais: se uma
            // recriação anterior falhou, o AMOLED volta sem exigir um novo
            // pareamento. `setDefaultNightMode` é idempotente.
            app.backgroundScope.launch(Dispatchers.Main) {
                runCatching { app.applyAppearanceNightMode() }.onFailure {
                    AppLog.failure(AppLog.TAG_PAIRING, "Falha reaplicando tema da TV", it)
                }
            }
        }
    }

    private fun applyDiscord(app: MystApp, incoming: PairDiscord?, known: Boolean) {
        if (!known) return
        val newToken = incoming?.token?.trim()?.takeIf { it.isNotBlank() }
        val currentToken = app.session.discordToken
        if (newToken == null) {
            if (!currentToken.isNullOrBlank()) {
                AppLog.i(AppLog.TAG_PAIRING, "Discord desconectado no celular; limpando na TV")
                app.discord.disconnect()
            } else {
                app.session.clearDiscord()
            }
            return
        }
        val incomingName = incoming.name?.trim()?.takeIf { it.isNotBlank() }
        if (incomingName != null) app.session.discordName = incomingName
        if (newToken != currentToken) {
            AppLog.i(AppLog.TAG_PAIRING, "Conexão do Discord recebida do celular")
            app.discord.connect(newToken)
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
            var importedPlayEvents = 0
            var importedYtPlaylists = 0
            var importedYtTracks = 0
            var importedYtHistory = 0
            val coverWrites = mutableListOf<Pair<Long, PairPlaylist>>()

            database.withTransaction {
                val playlistDao = database.playlistDao()
                val tombstoneDao = database.pairTombstoneDao()
                val tombstoneMap = tombstoneDao.all(userId).associateBy { it.key }.toMutableMap()
                val incomingTrackTombstones = snapshot.tombstones.take(MAX_TOMBSTONES)
                    .map { it.key.trim() }
                    .filterTo(mutableSetOf()) {
                        it.startsWith(PlaylistRepository.TRACK_TOMBSTONE_PREFIX)
                    }

                snapshot.tombstones.take(MAX_TOMBSTONES).forEach { incoming ->
                    val rawKey = incoming.key.trim()
                    if (rawKey.isBlank()) return@forEach
                    // Nomes de playlist são normalizados, mas o videoId de uma
                    // lápide de faixa diferencia maiúsculas de minúsculas.
                    val isTrack = rawKey.startsWith(PlaylistRepository.TRACK_TOMBSTONE_PREFIX)
                    val key = if (isTrack) {
                        rawKey
                    } else {
                        PlaylistRepository.normalizePlaylistName(rawKey)
                    }
                    if (key.isBlank()) return@forEach
                    if (isTrack && incoming.deletedAt <= 0L) {
                        // A faixa foi readicionada: o aviso apaga a lápide
                        // antiga no outro aparelho e libera a faixa no merge.
                        tombstoneDao.delete(userId, key)
                        tombstoneMap.remove(key)
                        incomingTrackTombstones.remove(key)
                        return@forEach
                    }
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
                    if (isTrack) return@forEach
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
                    val trackPrefix = "${PlaylistRepository.TRACK_TOMBSTONE_PREFIX}$key:"
                    val incomingRemovals = incomingTrackTombstones
                        .filterTo(mutableSetOf()) { it.startsWith(trackPrefix) }
                    var position = playlistDao.maxPosition(playlistId) + 1
                    incoming.tracks.take(MAX_TRACKS_PER_PLAYLIST).forEach { track ->
                        val videoId = track.videoId.trim().take(MAX_ID_LENGTH)
                        if (videoId.isBlank()) return@forEach
                        val tombstoneKey = PlaylistRepository.trackTombstoneKey(name, videoId)
                        val trackTombstone = tombstoneKey?.let { tombstoneMap[it] }
                        if (tombstoneKey != null && trackTombstone != null &&
                            trackTombstone.deletedAt >= createdAt
                        ) {
                            // A remoção ainda vale. Só uma lápide de readição
                            // (`deletedAt = 0`) chega aqui sem bloquear, e ela
                            // já foi removida no primeiro laço.
                            if (tombstoneKey in incomingRemovals) {
                                playlistDao.deleteTrack(playlistId, videoId)
                            }
                            return@forEach
                        }
                        if (!knownTracks.add(videoId)) return@forEach
                        val inserted = playlistDao.insertTrack(
                            PlaylistTrackEntity(
                                userId = userId,
                                playlistId = playlistId,
                                videoId = videoId,
                                title = track.title.trim().take(MAX_TITLE_LENGTH),
                                artist = track.artist.trim().take(MAX_ARTIST_LENGTH),
                                album = track.album.trim().take(MAX_ALBUM_LENGTH),
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
                    if (incomingRemovals.isNotEmpty()) {
                        // Faixas que o outro aparelho removeu da playlist.
                        // A lápide fica guardada para impedir que um retrato
                        // antigo (ou um sync atrasado) ressuscite a faixa.
                        playlistDao.tracks(playlistId).forEach { local ->
                            val tombstoneKey = PlaylistRepository.trackTombstoneKey(
                                name,
                                local.videoId,
                            ) ?: return@forEach
                            val trackTombstone = tombstoneMap[tombstoneKey] ?: return@forEach
                            if (tombstoneKey in incomingRemovals &&
                                trackTombstone.deletedAt >= createdAt
                            ) {
                                playlistDao.deleteTrack(playlistId, local.videoId)
                            }
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
                            album = item.album.trim().take(MAX_ALBUM_LENGTH),
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

                val playEventDao = database.playEventDao()
                val playEventKeys = playEventDao.keys(userId).toMutableSet()
                snapshot.playEvents.take(MAX_PLAY_EVENTS).forEach { item ->
                    val videoId = item.videoId.trim().take(MAX_ID_LENGTH)
                    if (videoId.isBlank() || item.playedMs <= 0L) return@forEach
                    val startedAt = item.startedAt.takeIf { it in 1..now } ?: now
                    val incomingPlayedMs = item.playedMs.coerceAtMost(MAX_PLAYED_MS)
                    val incomingDurationMs = item.durationMs.coerceIn(0L, MAX_DURATION_MS)
                    if (!playEventKeys.add("$videoId|$startedAt")) {
                        // A mesma reprodução pode chegar primeiro aos 10 s e
                        // depois com o tempo final. Mantém o maior trecho
                        // ouvido e nunca cria uma segunda reprodução.
                        val existing = playEventDao.find(userId, videoId, startedAt)
                        if (existing != null &&
                            (incomingPlayedMs > existing.playedMs ||
                                (item.completed && !existing.completed))
                        ) {
                            playEventDao.updateProgress(
                                id = existing.id,
                                playedMs = maxOf(existing.playedMs, incomingPlayedMs),
                                durationMs = maxOf(existing.durationMs, incomingDurationMs),
                                completed = existing.completed || item.completed,
                            )
                            importedPlayEvents++
                        }
                        return@forEach
                    }
                    playEventDao.insert(
                        PlayEventEntity(
                            userId = userId,
                            videoId = videoId,
                            title = item.title.trim().take(MAX_TITLE_LENGTH),
                            artist = item.artist.trim().take(MAX_ARTIST_LENGTH),
                            album = item.album.trim().take(MAX_ALBUM_LENGTH),
                            thumbnailUrl = item.thumbnailUrl?.take(MAX_URL_LENGTH),
                            startedAt = startedAt,
                            playedMs = incomingPlayedMs,
                            durationMs = incomingDurationMs,
                            completed = item.completed,
                            source = item.source.trim().take(MAX_SOURCE_LENGTH).ifBlank { "pairing" },
                        ),
                    )
                    importedPlayEvents++
                }

                val ytResult = mergeYouTube(app, userId, snapshot.youtube, now)
                importedYtPlaylists = ytResult.first
                importedYtTracks = ytResult.second
                importedYtHistory = ytResult.third
            }

            if (coverWrites.isNotEmpty()) {
                var budget = MAX_COVER_BUDGET_BYTES.toLong()
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

            if (importedPlaylists > 0 || importedTracks > 0 || importedHistory > 0 ||
                importedPlayEvents > 0 || importedYtPlaylists > 0 ||
                importedYtTracks > 0 || importedYtHistory > 0
            ) {
                AppLog.i(
                    AppLog.TAG_PAIRING,
                    "Sincronização mesclada: playlists=$importedPlaylists " +
                        "faixas=$importedTracks histórico=$importedHistory " +
                        "eventos=$importedPlayEvents yt_playlists=$importedYtPlaylists " +
                        "yt_faixas=$importedYtTracks yt_histórico=$importedYtHistory",
                )
            }
        }

    /**
     * Mescla o cache do YouTube Music. Playlists e histórico são somente
     * leitura no app, então o celular é a fonte principal; a TV só mantém o que
     * já tinha se vier mais recente. Retorna playlists/faixas/histórico novos.
     */
    private suspend fun mergeYouTube(
        app: MystApp,
        userId: Long,
        incoming: PairYouTube,
        now: Long,
    ): Triple<Int, Int, Int> {
        if (incoming.playlists.isEmpty() && incoming.history.isEmpty() &&
            incoming.sync.isEmpty()
        ) {
            return Triple(0, 0, 0)
        }
        val dao = app.database.youTubeLibraryDao()
        var newPlaylists = 0
        var newTracks = 0
        var newHistory = 0

        if (incoming.playlists.isNotEmpty()) {
            val existingPlaylists = dao.playlists(userId).associateBy { it.ytId }
            val playlists = incoming.playlists.take(MAX_YT_PLAYLISTS).mapNotNull { item ->
                val ytId = item.ytId.trim().take(MAX_ID_LENGTH)
                if (ytId.isBlank()) return@mapNotNull null
                val previous = existingPlaylists[ytId]
                if (previous == null) newPlaylists++
                YtPlaylistEntity(
                    userId = userId,
                    ytId = ytId,
                    title = item.title.trim().take(MAX_TITLE_LENGTH).ifBlank {
                        previous?.title.orEmpty()
                    },
                    thumbnailUrl = item.thumbnailUrl?.take(MAX_URL_LENGTH)
                        ?: previous?.thumbnailUrl,
                    trackCount = maxOf(previous?.trackCount ?: 0, item.trackCount),
                    liked = previous?.liked == true || item.liked,
                    syncedAt = maxOf(previous?.syncedAt ?: 0L, item.syncedAt),
                )
            }
            if (playlists.isNotEmpty()) dao.upsertPlaylists(playlists)

            val existingTracks = dao.allPlaylistTracks(userId)
                .associateBy { "${it.ytId}|${it.videoId}" }
                .toMutableMap()
            val allTracks = mutableListOf<YtPlaylistTrackEntity>()
            incoming.playlists.take(MAX_YT_PLAYLISTS).forEach { item ->
                val ytId = item.ytId.trim().take(MAX_ID_LENGTH)
                if (ytId.isBlank()) return@forEach
                item.tracks.take(MAX_YT_TRACKS_PER_PLAYLIST).forEachIndexed { index, track ->
                    val videoId = track.videoId.trim().take(MAX_ID_LENGTH)
                    if (videoId.isBlank()) return@forEachIndexed
                    val previous = existingTracks["$ytId|$videoId"]
                    val incomingAt = track.syncedAt.takeIf { it > 0L } ?: item.syncedAt
                    if (previous != null && previous.syncedAt > incomingAt) {
                        return@forEachIndexed
                    }
                    if (previous == null) newTracks++
                    val entity = YtPlaylistTrackEntity(
                        userId = userId,
                        ytId = ytId,
                        videoId = videoId,
                        title = track.title.trim().take(MAX_TITLE_LENGTH),
                        artist = track.artist.trim().take(MAX_ARTIST_LENGTH),
                        album = track.album.trim().take(MAX_ALBUM_LENGTH),
                        thumbnailUrl = track.thumbnailUrl?.take(MAX_URL_LENGTH),
                        durationMs = track.durationMs.coerceIn(0L, MAX_DURATION_MS),
                        position = track.position.coerceAtLeast(index),
                        syncedAt = maxOf(incomingAt, now),
                    )
                    existingTracks["$ytId|$videoId"] = entity
                    allTracks += entity
                }
            }
            if (allTracks.isNotEmpty()) dao.upsertPlaylistTracks(allTracks)
            // Mantém o contador coerente com as faixas que chegaram no mesmo
            // snapshot, sem apagar faixas antigas que a TV já tivesse.
            playlists.forEach { playlist ->
                val count = existingTracks.keys.count { it.startsWith("${playlist.ytId}|") }
                if (count > playlist.trackCount) {
                    dao.updatePlaylistCount(userId, playlist.ytId, count, now)
                }
            }
        }

        if (incoming.history.isNotEmpty()) {
            val existingHistory = dao.history(userId, MAX_YT_HISTORY)
                .associateBy { it.videoId }
            val history = incoming.history.take(MAX_YT_HISTORY).mapNotNull { item ->
                val videoId = item.videoId.trim().take(MAX_ID_LENGTH)
                if (videoId.isBlank()) return@mapNotNull null
                val playedAt = item.playedAt.takeIf { it > 0L } ?: now
                val previous = existingHistory[videoId]
                if (previous != null && previous.playedAt >= playedAt) return@mapNotNull null
                if (previous == null) newHistory++
                YtHistoryEntity(
                    userId = userId,
                    videoId = videoId,
                    title = item.title.trim().take(MAX_TITLE_LENGTH),
                    artist = item.artist.trim().take(MAX_ARTIST_LENGTH),
                    album = item.album.trim().take(MAX_ALBUM_LENGTH),
                    thumbnailUrl = item.thumbnailUrl?.take(MAX_URL_LENGTH),
                    durationMs = item.durationMs.coerceIn(0L, MAX_DURATION_MS),
                    playedAt = playedAt,
                )
            }
            if (history.isNotEmpty()) dao.upsertHistory(history)
        }

        incoming.sync.take(MAX_YT_SYNC_STATES).forEach { item ->
            val section = item.section.trim().take(MAX_SYNC_SECTION_LENGTH)
            if (section.isBlank()) return@forEach
            val previous = dao.sync(userId, section)
            if (previous == null || item.syncedAt > previous.syncedAt) {
                dao.upsertSync(YtSyncEntity(userId, section, item.syncedAt))
            }
        }
        return Triple(newPlaylists, newTracks, newHistory)
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
    private const val MAX_PLAY_EVENTS = 5_000
    private const val MAX_YT_PLAYLISTS = 500
    private const val MAX_YT_TRACKS_PER_PLAYLIST = 10_000
    private const val MAX_YT_TRACKS_TOTAL = 30_000
    private const val MAX_YT_HISTORY = 2_000
    private const val MAX_YT_SYNC_STATES = 500
    private const val MAX_SYNC_SECTION_LENGTH = 200
    private const val MAX_PLAYED_MS = 24L * 60L * 60L * 1000L
    private const val MAX_DURATION_MS = 24L * 60L * 60L * 1000L
    private const val MAX_TOMBSTONES = 5_000
    private const val MAX_ID_LENGTH = 128
    private const val MAX_TITLE_LENGTH = 300
    private const val MAX_ARTIST_LENGTH = 200
    private const val MAX_ALBUM_LENGTH = 200
    private const val MAX_SOURCE_LENGTH = 32
    private const val MAX_NAME_LENGTH = 40
    private const val MAX_URL_LENGTH = 2_000
}
