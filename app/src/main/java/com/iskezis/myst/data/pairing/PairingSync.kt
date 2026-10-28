package com.iskezis.myst.data.pairing

import com.iskezis.myst.MystApp
import com.iskezis.myst.data.account.SessionManager
import com.iskezis.myst.data.db.PlaylistExport
import com.iskezis.myst.data.db.UserEntity
import com.iskezis.myst.data.youtube.Track
import com.iskezis.myst.util.AppLog
import com.iskezis.myst.util.sha256Hex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Serializa a conta e as playlists para o pareamento. O payload é cifrado pelo
 * PairingCrypto antes de sair do celular; a TV só consegue abrir com o código
 * exibido pelo celular.
 */
object PairingSync {

    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }

    suspend fun export(app: MystApp): PairBundle = withContext(Dispatchers.IO) {
        val session = app.session.session
            ?: throw IllegalStateException("Faça login antes de parear a TV")
        val user = app.accounts.currentUser()
        val account = PairAccount(
            type = session.type,
            username = user?.username ?: "paired_${sha256Hex(session.displayName).take(12)}",
            displayName = session.displayName.ifBlank {
                user?.displayName ?: user?.username ?: "MystMusic"
            },
        )
        val playlists = app.playlists.export(session.userId).map { export ->
            PairPlaylist(
                name = export.name,
                coverUri = export.coverUri,
                tracks = export.tracks.map { track ->
                    PairTrack(
                        videoId = track.videoId,
                        title = track.title,
                        artist = track.artist,
                        thumbnailUrl = track.thumbnailUrl,
                        durationMs = track.durationMs,
                    )
                },
            )
        }
        AppLog.i(
            AppLog.TAG_PAIRING,
            "Pareamento exportado: conta=${account.type} playlists=${playlists.size}",
        )
        PairBundle(
            account = account,
            youtubeCookies = app.session.youtubeCookies,
            youtubeSapisid = app.session.youtubeSapisid,
            youtubeVisitorData = app.session.youtubeVisitorData,
            youtubeDataSyncId = app.session.youtubeDataSyncId,
            playlists = playlists,
        )
    }

    suspend fun apply(app: MystApp, bundle: PairBundle) = withContext(Dispatchers.IO) {
        val account = bundle.account
            ?: throw IllegalArgumentException("Payload sem conta")
        val dao = app.database.userDao()
        val username = account.username.trim().ifBlank {
            "paired_${sha256Hex(account.displayName).take(12)}"
        }
        val existing = dao.findByUsername(username)
        val userId = existing?.id ?: dao.insert(
            UserEntity(
                username = username,
                displayName = account.displayName.ifBlank { username },
                accountType = account.type,
            ),
        )
        app.session.session = SessionManager.Session(
            type = account.type,
            userId = userId,
            displayName = account.displayName.ifBlank { username },
        )
        app.session.youtubeCookies = bundle.youtubeCookies
        app.session.youtubeSapisid = bundle.youtubeSapisid
        app.session.youtubeVisitorData = bundle.youtubeVisitorData
        app.session.youtubeDataSyncId = bundle.youtubeDataSyncId

        val exports = bundle.playlists.map { playlist ->
            PlaylistExport(
                name = playlist.name,
                // content:// do celular não é legível na TV. A capa remota
                // continua válida; as demais são regeneradas pelo app.
                coverUri = playlist.coverUri?.takeIf {
                    it.startsWith("https://") || it.startsWith("http://")
                },
                tracks = playlist.tracks.map { track ->
                    Track(
                        videoId = track.videoId,
                        title = track.title,
                        artist = track.artist,
                        thumbnailUrl = track.thumbnailUrl,
                        durationMs = track.durationMs,
                    )
                },
            )
        }
        app.playlists.replaceAll(userId, exports)
        app.session.tvPaired = true
        AppLog.i(
            AppLog.TAG_PAIRING,
            "Pareamento aplicado: usuário=$userId playlists=${exports.size}",
        )
    }
}
