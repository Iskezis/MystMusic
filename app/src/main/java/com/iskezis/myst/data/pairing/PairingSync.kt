package com.iskezis.myst.data.pairing

import com.iskezis.myst.MystApp
import com.iskezis.myst.data.account.AvatarStore
import com.iskezis.myst.data.account.SessionManager
import com.iskezis.myst.data.account.YouTubeAccount
import com.iskezis.myst.data.db.PlaylistExport
import com.iskezis.myst.data.db.UserEntity
import com.iskezis.myst.data.youtube.Track
import com.iskezis.myst.util.AppLog
import com.iskezis.myst.util.sha256Hex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Serializa as duas contas, a foto de perfil e as playlists para o pareamento.
 * O payload é cifrado pelo PairingCrypto antes de sair do celular; a TV só
 * consegue abrir com o código exibido pelo celular.
 *
 * A TV não recebe senha: o perfil local chega pronto, com o vínculo do YouTube
 * que já estava na conta Myst. Sem as duas contas o pareamento é recusado.
 */
object PairingSync {

    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }

    suspend fun export(app: MystApp): PairBundle = withContext(Dispatchers.IO) {
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
            "Pareamento exportado: conta=${account.type} " +
                "youtube=${!account.youtubeAccountId.isNullOrBlank()} " +
                "avatar=${avatarBase64 != null} playlists=${playlists.size}",
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
            "Pareamento aplicado: usuário=$userId " +
                "youtube=${!youtubeAccountId.isNullOrBlank()} " +
                "avatar=${!account.avatarBase64.isNullOrBlank()} playlists=${exports.size}",
        )
    }
}
