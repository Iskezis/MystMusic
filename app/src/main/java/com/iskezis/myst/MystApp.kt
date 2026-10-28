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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

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

    private val warmUpScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val crashFile by lazy { File(filesDir, "myst_last_crash.txt") }

    override fun onCreate() {
        super.onCreate()
        AppLog.attach(this)
        installCrashLogger()
        AppLog.i(
            AppLog.TAG,
            "MystMusic iniciado (${BuildConfig.VERSION_NAME}, ${BuildConfig.FLAVOR}, " +
                "Android ${android.os.Build.VERSION.RELEASE}) em " +
                (AppLog.logFilePath() ?: "<sem arquivo de log>"),
        )
        AppLog.i(
            AppLog.TAG,
            "Sessão: ${session.session?.type ?: "nenhuma"} | YouTube cookies: " +
                if (session.youtubeCookies.isNullOrBlank()) "não" else "sim",
        )
        // Se o processo anterior morreu por uma exceção não tratada, a pilha
        // fica no diagnóstico copiável da próxima execução. Isso permite
        // investigar crashes no aparelho sem adb e sem expor credenciais.
        runCatching {
            if (crashFile.exists()) {
                val previous = crashFile.readText().take(6_000)
                crashFile.delete()
                if (previous.isNotBlank()) {
                    AppLog.e(AppLog.TAG_CRASH, "Crash anterior: $previous")
                }
            }
        }
        // Abre TLS e renova o visitorData antes do primeiro toque em uma faixa.
        warmUpScope.launch { innerTube.warmUp() }
    }

    private fun installCrashLogger() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val stack = throwable.stackTraceToString()
                crashFile.writeText(
                    "Thread ${thread.name}\n${throwable.javaClass.name}: " +
                        "${throwable.message.orEmpty()}\n${stack.take(12_000)}",
                )
                AppLog.failureSync(
                    AppLog.TAG_CRASH,
                    "Crash não tratado na thread ${thread.name}",
                    throwable,
                )
                AppLog.e(
                    AppLog.TAG_CRASH,
                    "Causa: ${stack.take(4_000)}",
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
