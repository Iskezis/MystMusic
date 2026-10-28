package com.iskezis.myst

import android.app.Application
import com.iskezis.myst.data.account.AccountRepository
import com.iskezis.myst.data.account.SessionManager
import com.iskezis.myst.data.db.AppDatabase
import com.iskezis.myst.data.db.PlaylistRepository
import com.iskezis.myst.data.discord.DiscordPresenceManager
import com.iskezis.myst.data.recommendations.RecommendationRepository
import com.iskezis.myst.data.spotify.SpotifyImporter
import com.iskezis.myst.data.youtube.InnerTubeClient
import com.iskezis.myst.data.youtube.YouTubeRepository
import com.iskezis.myst.player.PlayerController
import com.iskezis.myst.util.AppLog

data class StreamFailure(
    val videoId: String,
    val loginRequired: Boolean,
    val at: Long = System.currentTimeMillis(),
)

class MystApp : Application() {

    /** Última falha de resolução de stream, usada pela UI para oferecer login. */
    @Volatile
    var lastStreamFailure: StreamFailure? = null

    val database: AppDatabase by lazy { AppDatabase.get(this) }
    val session: SessionManager by lazy { SessionManager(this) }
    val innerTube: InnerTubeClient by lazy { InnerTubeClient(this, session) }
    val youtube: YouTubeRepository by lazy { YouTubeRepository(innerTube) }
    val accounts: AccountRepository by lazy { AccountRepository(this, database, session, innerTube) }
    val playlists: PlaylistRepository by lazy { PlaylistRepository(database) }
    val recommendations: RecommendationRepository by lazy { RecommendationRepository(database, youtube) }
    val spotify: SpotifyImporter by lazy { SpotifyImporter(this, youtube, playlists) }
    val playerController: PlayerController by lazy { PlayerController(this) }
    val discord: DiscordPresenceManager by lazy { DiscordPresenceManager(this, session) }

    override fun onCreate() {
        super.onCreate()
        installCrashLogger()
        AppLog.i(AppLog.TAG, "MystMusic iniciado (${BuildConfig.VERSION_NAME})")
        AppLog.i(
            AppLog.TAG,
            "Sessão: ${session.session?.type ?: "nenhuma"} | YouTube cookies: " +
                if (session.youtubeCookies.isNullOrBlank()) "não" else "sim",
        )
    }

    private fun installCrashLogger() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                AppLog.e(
                    AppLog.TAG_CRASH,
                    "Crash não tratado na thread ${thread.name}",
                    throwable,
                )
                AppLog.e(
                    AppLog.TAG_CRASH,
                    "Causa: ${throwable.stackTraceToString().take(4000)}",
                )
            }
            if (previous != null) {
                previous.uncaughtException(thread, throwable)
            } else {
                android.os.Process.killProcess(android.os.Process.myPid())
            }
        }
    }
}
