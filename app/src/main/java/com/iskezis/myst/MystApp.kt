package com.iskezis.myst

import android.app.Application
import android.content.ComponentCallbacks2
import androidx.appcompat.app.AppCompatDelegate
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.iskezis.myst.data.account.AccountRepository
import com.iskezis.myst.data.account.MystAccountFileRepository
import com.iskezis.myst.data.account.SessionManager
import com.iskezis.myst.data.account.TvEphemeralData
import com.iskezis.myst.data.db.AppDatabase
import com.iskezis.myst.data.db.PlaylistRepository
import com.iskezis.myst.data.discord.DiscordPresenceManager
import com.iskezis.myst.data.pairing.PairedSyncManager
import com.iskezis.myst.data.settings.AppearancePreferences
import com.iskezis.myst.data.recommendations.RecommendationRepository
import com.iskezis.myst.data.spotify.SpotifyImporter
import com.iskezis.myst.data.stats.StatsRepository
import com.iskezis.myst.data.youtube.InnerTubeClient
import com.iskezis.myst.data.youtube.YouTubeLibraryRepository
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

class MystApp : Application(), ImageLoaderFactory {

    /**
     * O Coil usa bitmaps de hardware por padrão no Android 8+. As transições de
     * fragmento (MaterialSharedAxis) tiram um snapshot da tela em um Canvas de
     * software, e desenhar um bitmap de hardware nesse Canvas lança
     * `IllegalArgumentException: Software rendering doesn't support hardware
     * bitmaps` — exatamente o crash observado ao navegar com música tocando.
     * Desabilitar globalmente mantém todas as capas desenháveis no snapshot.
     */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .allowHardware(false)
        .build()

    /** Última falha de resolução de stream, usada pela UI para oferecer login. */
    @Volatile
    var lastStreamFailure: StreamFailure? = null

    val database: AppDatabase by lazy { AppDatabase.get(this) }
    val session: SessionManager by lazy { SessionManager(this) }
    val appearance: AppearancePreferences by lazy { AppearancePreferences(this) }
    val innerTube: InnerTubeClient by lazy { InnerTubeClient(this, session) }
    val youtube: YouTubeRepository by lazy { YouTubeRepository(innerTube) }
    val accountFiles: MystAccountFileRepository by lazy {
        MystAccountFileRepository(this, database, appearance, session) { token ->
            // A importação do Mystaccount.json pode acontecer com o app já
            // aberto; reconecta na hora em vez de esperar o próximo início.
            if (!token.isNullOrBlank()) discord.connect(token)
        }
    }
    val accounts: AccountRepository by lazy {
        AccountRepository(this, database, session, innerTube, accountFiles)
    }
    val playlists: PlaylistRepository by lazy { PlaylistRepository(database) }
    val ytLibrary: YouTubeLibraryRepository by lazy {
        YouTubeLibraryRepository(database, youtube, playlists, session)
    }
    val recommendations: RecommendationRepository by lazy { RecommendationRepository(database, youtube) }
    val stats: StatsRepository by lazy { StatsRepository(database) }
    val spotify: SpotifyImporter by lazy { SpotifyImporter(this, youtube, playlists) }
    val playerController: PlayerController by lazy { PlayerController(this) }
    val discord: DiscordPresenceManager by lazy {
        DiscordPresenceManager(this, session) {
            // No celular o token do Discord é uma credencial da conta: grava
            // o Mystaccount.json e leva a conexão à TV imediatamente. Na TV o
            // gerenciador apenas recebe o token do celular, sem escrever nada.
            if (BuildConfig.FLAVOR != "tv") {
                accountFiles.requestSync()
                pairedSync.requestSync()
            }
        }
    }
    val pairedSync: PairedSyncManager by lazy { PairedSyncManager(this) }

    val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val discordScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val crashFile by lazy { File(filesDir, "myst_last_crash.txt") }

    override fun onCreate() {
        super.onCreate()
        // Precisa ser síncrono e antes de qualquer Activity: evita o flash do
        // tema errado ao abrir o app, inclusive quando o usuário escolheu
        // Claro/Escuro em vez de seguir o sistema.
        AppCompatDelegate.setDefaultNightMode(appearance.nightMode)
        AppLog.attach(this)
        installCrashLogger()
        if (BuildConfig.FLAVOR == "tv") {
            // TV = terminal temporário. Remove resíduos de versões antigas
            // antes de qualquer tela; o banco novo será em memória.
            TvEphemeralData.purge(this)
            if (!session.hasTvPairing) {
                // Sem pareamento não existe tema temporário do celular para
                // manter; a próxima tela usa o padrão do sistema.
                TvEphemeralData.resetAppearance(this)
            }
        }
        // Mantém o Mystaccount.json interno e a cópia externa (quando o
        // provedor permite escrita persistente) atualizados a cada mudança no
        // Room. A sincronização é debounced e nunca roda na thread principal.
        // Na TV o repositório ignora a chamada de propósito.
        accountFiles.start(session)
        AppLog.i(
            AppLog.TAG,
            "MystMusic iniciado (${BuildConfig.VERSION_NAME}, ${BuildConfig.FLAVOR}, " +
                "Android ${android.os.Build.VERSION.RELEASE}) em " +
                (AppLog.logFilePath() ?: "<sem arquivo de log>"),
        )
        AppLog.i(
            AppLog.TAG,
            "Aparência: modo=${appearance.themeMode.storageValue} " +
                "amoled=${appearance.amoled}",
        )
        AppLog.i(
            AppLog.TAG,
            "Sessão: Myst=${session.hasMyst} YouTube=${session.hasYouTube} " +
                "conflito=${session.youtubeConflict}",
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
        backgroundScope.launch { innerTube.warmUp() }
        observePlayerForDiscord()
        if (BuildConfig.FLAVOR == "tv" && session.hasTvPairing) {
            // O banco é em memória; recria a linha mínima do usuário para o
            // servidor de pareamento continuar aceitando o celular.
            backgroundScope.launch {
                runCatching { accounts.ensureTvSessionUser() }.onFailure {
                    AppLog.failure(
                        AppLog.TAG_AUTH,
                        "Falha preparando a identidade temporária da TV",
                        it,
                    )
                }
            }
        }
        // Na TV, mantém o canal de sincronização/desconexão vivo enquanto o
        // processo existir; no celular a sincronização é disparada pela tela.
        pairedSync.onAppCreated()
    }

    /**
     * O Discord precisa saber da pausa mesmo quando a MainActivity não está em
     * STARTED (por exemplo, pausa pelo botão da notificação ou tela apagada).
     * Observar o PlayerController no escopo da Application mantém a presença
     * sincronizada sem depender do ciclo de vida de uma tela.
     */
    private fun observePlayerForDiscord() {
        discordScope.launch {
            playerController.state.collect { state ->
                runCatching {
                    discord.onPlayerState(state)
                }.onFailure { t ->
                    AppLog.failure(
                        AppLog.TAG_DISCORD,
                        "Falha atualizando Discord; player continua",
                        t,
                    )
                }
            }
        }
    }

    /**
     * Reaplica o modo noturno atual antes de uma Activity ser criada. É o que
     * faz a TV adotar o tema recebido no pareamento sem precisar reiniciar o
     * processo: o `PairingSync` grava as preferências e a próxima tela já
     * nasce no tema do celular. No celular a chamada é idempotente.
     */
    fun applyAppearanceNightMode() {
        AppCompatDelegate.setDefaultNightMode(appearance.nightMode)
    }

    /**
     * O Android pode encerrar o processo depois que a interface some. Dispara
     * uma última gravação da conta no celular; a cópia automática também é
     * atualizada a cada mudança, então isso é uma proteção extra.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (BuildConfig.FLAVOR == "tv") return
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            accountFiles.requestSync()
        }
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
