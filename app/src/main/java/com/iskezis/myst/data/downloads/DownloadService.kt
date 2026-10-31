package com.iskezis.myst.data.downloads

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.iskezis.myst.BuildConfig
import com.iskezis.myst.MainActivity
import com.iskezis.myst.MystApp
import com.iskezis.myst.R
import com.iskezis.myst.data.db.DownloadEntity
import com.iskezis.myst.data.youtube.LoginRequiredException
import com.iskezis.myst.data.youtube.StreamInfo
import com.iskezis.myst.data.youtube.Track
import com.iskezis.myst.player.StreamResolver
import com.iskezis.myst.util.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Fila de downloads em primeiro plano (dataSync).
 *
 * - Duas faixas em paralelo no celular e uma na TV.
 * - O arquivo é escrito em `.part` e só ganha o nome final ao concluir; uma
 *   nova tentativa continua com Range quando o servidor permitir.
 * - Até três tentativas por faixa, com URL renovada no InnerTube. Uma falha
 *   vira FAILED com motivo e a fila continua.
 * - A notificação mostra o progresso agregado e permite cancelar.
 */
class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var app: MystApp
    private lateinit var repository: DownloadRepository
    private lateinit var storage: DownloadStorage
    private lateinit var resolver: StreamResolver
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        // Um servidor que entrega bytes muito devagar não pode prender a fila
        // para sempre; o limite é generoso para arquivos grandes em 3G.
        .callTimeout(30, TimeUnit.MINUTES)
        .retryOnConnectionFailure(true)
        .followRedirects(true)
        .build()

    private val claimMutex = Mutex()
    private val cancelled = ConcurrentHashMap.newKeySet<String>()
    private val cancelRequested = AtomicBoolean(false)
    private val activeCalls = ConcurrentHashMap<String, Call>()
    private val activeWork = ConcurrentHashMap.newKeySet<String>()
    private val recovering = ConcurrentHashMap.newKeySet<String>()
    private val recoveryInFlight = AtomicBoolean(false)
    private var workerJob: Job? = null
    private var watchdogJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var foregroundStarted = false
    private var lastRecoveryAt = 0L
    private var total = 0
    private var finished = 0
    private var failed = 0
    private var lastNotifyAt = 0L
    private val finishedOnce = AtomicBoolean(false)

    override fun onCreate() {
        super.onCreate()
        app = application as MystApp
        repository = app.downloads
        storage = app.downloadStorage
        resolver = StreamResolver(app)
        createChannel()
        isRunning = true
        AppLog.i(AppLog.TAG_DOWNLOAD, "DownloadService criado")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL_ALL -> {
                if (!ensureForeground()) return START_NOT_STICKY
                cancelAll()
                return START_STICKY
            }
            ACTION_CANCEL_ONE -> {
                if (!ensureForeground()) return START_NOT_STICKY
                val videoId = intent.getStringExtra(EXTRA_VIDEO_ID)
                if (videoId.isNullOrBlank()) {
                    if (workerJob?.isActive != true) stopSelf()
                } else {
                    // Não encerra o serviço antes de a remoção terminar; um
                    // cancelamento de faixa enfileirada não pode ficar pela
                    // metade porque a UI já sumiu da tela.
                    scope.launch {
                        cancelOne(videoId)
                        if (workerJob?.isActive != true) stopSelf()
                    }
                }
                return START_STICKY
            }
            ACTION_RECOVER -> {
                if (!ensureForeground()) return START_NOT_STICKY
                // Mesmo com o serviço vivo, a recuperação manual precisa
                // destravar chamadas de rede pendentes e devolver a fila.
                startWork()
                recoverDownloads(force = true)
                return START_STICKY
            }
        }
        if (!ensureForeground()) return START_NOT_STICKY
        startWork()
        return START_STICKY
    }

    private fun ensureForeground(): Boolean {
        if (foregroundStarted) return true
        val started = startForegroundSafely(
            buildNotification(null, 0, 0, preparing = true, done = false),
        )
        if (started) foregroundStarted = true
        return started
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startWork() {
        synchronized(this) {
            // Uma nova fila pode chegar logo depois de "Cancelar todos"; sem
            // zerar estes sinais o serviço recém-reiniciado sairia na hora.
            cancelRequested.set(false)
            finishedOnce.set(false)
            if (workerJob?.isActive == true) return
            workerJob = scope.launch {
                runCatching { repository.resetInterrupted() }
                val userId = app.session.mystUserId
                total = if (userId > 0L) repository.activeCount(userId) else 0
                if (total <= 0) {
                    // Nada na fila: não deixa uma notificação "0 de 0".
                    releaseLocks()
                    stopForegroundCompat()
                    stopSelf()
                    return@launch
                }
                finished = 0
                failed = 0
                acquireLocks()
                startWatchdog()
                AppLog.i(AppLog.TAG_DOWNLOAD, "Fila iniciada com $total faixa(s)")
                try {
                    coroutineScope {
                        repeat(concurrency()) {
                            launch { worker() }
                        }
                    }
                } catch (t: CancellationException) {
                    throw t
                } catch (t: Throwable) {
                    AppLog.failure(AppLog.TAG_DOWNLOAD, "Fila de downloads interrompida", t)
                } finally {
                    stopWatchdog()
                    releaseLocks()
                    if (cancelRequested.get()) {
                        // O cancelamento precisa terminar mesmo que o serviço
                        // seja encerrado em seguida (stopSelf cancela o scope).
                        withContext(NonCancellable) {
                            runCatching { repository.cancelAll(userId) }
                        }
                        finishNotification(success = false, cancelled = true)
                    } else {
                        finishNotification(success = failed == 0)
                    }
                }
            }
        }
    }

    private suspend fun worker() {
        while (scope.isActive && !cancelRequested.get()) {
            val item = claimNext()
            if (item == null) {
                // Um worker pode chegar antes do outro terminar de reivindicar
                // a última linha. Se ainda houver fila real, espera em vez de
                // encerrar o serviço e deixar downloads órfãos.
                val userId = app.session.mystUserId
                val stillActive = userId > 0L &&
                    repository.activeCount(userId) > 0
                if (stillActive) {
                    // Inclui DOWNLOADING do outro worker e linhas devolvidas
                    // à fila no meio da recuperação. Não encerra a notificação
                    // enquanto existir trabalho real.
                    delay(300)
                    continue
                }
                // Pequena janela para o "Recuperar"/watchdog reenfileirar uma
                // linha logo depois da última verificação.
                delay(500)
                if (userId > 0L && repository.activeCount(userId) > 0) continue
                return
            }
            if (cancelled.contains(item.videoId)) continue
            activeWork += item.videoId
            try {
                val succeeded = downloadWithRetries(item)
                if (succeeded) {
                    finished++
                } else {
                    // Uma recuperação/cancelamento devolve a faixa à fila e
                    // não deve contar como falha.
                    val status = runCatching {
                        repository.find(item.userId, item.videoId)?.status
                    }.getOrNull()
                    if (status == DownloadStatus.FAILED) failed++
                }
            } finally {
                activeWork.remove(item.videoId)
                recovering.remove(item.videoId)
                cancelled.remove(item.videoId)
            }
            notifyProgress(null, 0, 0, done = false)
        }
    }

    private suspend fun claimNext(): DownloadEntity? = claimMutex.withLock {
        val userId = app.session.mystUserId
        if (userId <= 0L) return@withLock null
        val queued = repository.queued(userId)
        for (item in queued) {
            if (cancelled.contains(item.videoId)) continue
            if (repository.claim(item)) return@withLock item
        }
        null
    }

    private suspend fun downloadWithRetries(item: DownloadEntity): Boolean {
        if (cancelled.contains(item.videoId)) return false
        if (!hasEnoughSpace()) {
            repository.fail(item, "Espaço insuficiente no armazenamento")
            return false
        }
        val existing = storage.findAudio(item.videoId, item.path)
        if (existing != null && existing.length() > MIN_AUDIO_BYTES) {
            val cover = ensureCover(item)
            repository.complete(item, existing, cover, existing.length())
            app.audioCache.removeStream(item.videoId)
            return true
        }
        var lastError: Throwable? = null
        for (attempt in 1..MAX_ATTEMPTS) {
            if (cancelRequested.get() || cancelled.contains(item.videoId)) return false
            // Perder a rede não queima tentativas: a faixa fica aguardando e
            // continua sozinha quando a conexão voltar.
            if (!waitForNetwork(item.videoId)) {
                // A recuperação interrompeu a espera de rede. Devolve a linha
                // à fila imediatamente em vez de deixá-la presa em DOWNLOADING
                // até o próximo ciclo do watchdog.
                if (recovering.remove(item.videoId)) {
                    repository.requeueOne(item)
                }
                return false
            }
            try {
                downloadOnce(item)
                return true
            } catch (cancelledError: DownloadCancelledException) {
                // Recuperação mantém o .part para continuar com Range; um
                // cancelamento de verdade apaga o parcial.
                if (recovering.remove(item.videoId)) {
                    repository.requeueOne(item)
                } else {
                    storage.partFile(item.videoId).delete()
                }
                return false
            } catch (cancelledError: CancellationException) {
                throw cancelledError
            } catch (t: Throwable) {
                if (recovering.remove(item.videoId)) {
                    repository.requeueOne(item)
                    return false
                }
                lastError = t
                AppLog.w(
                    AppLog.TAG_DOWNLOAD,
                    "Tentativa $attempt/$MAX_ATTEMPTS falhou para ${item.videoId}: " +
                        AppLog.describe(t),
                )
                resolver.invalidate(item.videoId)
                if (attempt < MAX_ATTEMPTS) {
                    delay(1_500L * attempt)
                }
            }
        }
        val reason = friendlyReason(lastError)
        repository.fail(item, reason)
        return false
    }

    private suspend fun downloadOnce(item: DownloadEntity) {
        val info = withTimeoutOrNull(RESOLVE_TIMEOUT_MS) {
            resolver.resolve(item.videoId)
        } ?: throw IOException("Tempo esgotado ao preparar o áudio")
        val part = storage.partFile(item.videoId)
        part.parentFile?.mkdirs()
        val startOffset = if (part.isFile) part.length() else 0L
        val request = buildRequest(info, startOffset)
        val call = http.newCall(request)
        activeCalls[item.videoId] = call
        try {
            call.execute().use { response ->
            if (response.code == 416) {
                part.delete()
                throw IOException("Servidor recusou a retomada")
            }
            if (!response.isSuccessful) {
                throw HttpFailure(response.code, response.message)
            }
            val body = response.body ?: throw IOException("Resposta sem áudio")
            val contentType = body.contentType()?.toString().orEmpty()
            if (contentType.startsWith("text/", ignoreCase = true)) {
                throw IOException("Servidor devolveu uma página em vez de áudio")
            }
            val append = response.code == 206 && startOffset > 0L
            val initialBytes = if (append) startOffset else 0L
            val contentLength = body.contentLength()
            val rangeTotal = response.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull()
            val expectedTotal = when {
                rangeTotal != null && rangeTotal > 0L -> rangeTotal
                contentLength > 0L -> initialBytes + contentLength
                else -> 0L
            }
            if (storage.availableBytes() in 1 until MIN_FREE_BYTES) {
                throw IOException("Espaço insuficiente")
            }
            var written = initialBytes
            var lastDbUpdate = 0L
            FileOutputStream(part, append).use { output ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        if (cancelRequested.get() || cancelled.contains(item.videoId)) {
                            throw DownloadCancelledException()
                        }
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        written += read
                        val now = System.currentTimeMillis()
                        if (now - lastDbUpdate >= PROGRESS_INTERVAL_MS) {
                            lastDbUpdate = now
                            repository.progress(item, written, expectedTotal)
                            notifyProgress(item, written, expectedTotal, done = false)
                        }
                    }
                    output.fd.sync()
                }
            }
            if (written <= MIN_AUDIO_BYTES) {
                part.delete()
                throw IOException("Arquivo de áudio vazio ou inválido")
            }
            if (expectedTotal > 0L && written < expectedTotal) {
                throw IOException(
                    "Download incompleto: $written de $expectedTotal bytes",
                )
            }
            // A linha pode ter sido removida (Remover todas) ou cancelada
            // enquanto o arquivo era escrito. Não renomeia para não deixar um
            // arquivo órfão que a conta não consegue mais gerenciar.
            val current = repository.find(item.userId, item.videoId)
            val isRecovering = recovering.contains(item.videoId)
            if (
                cancelRequested.get() ||
                cancelled.contains(item.videoId) ||
                current == null ||
                current.status != DownloadStatus.DOWNLOADING
            ) {
                if (!isRecovering) part.delete()
                throw DownloadCancelledException()
            }
            val finalFile = storage.audioFile(item.videoId)
            finalFile.parentFile?.mkdirs()
            if (finalFile.exists()) finalFile.delete()
            if (!part.renameTo(finalFile)) {
                part.copyTo(finalFile, overwrite = true)
                part.delete()
            }
            val cover = ensureCover(item)
            repository.complete(item, finalFile, cover, expectedTotal)
            notifyProgress(item, finalFile.length(), finalFile.length(), done = false)
            AppLog.i(
                AppLog.TAG_DOWNLOAD,
                "Áudio salvo: ${finalFile.absolutePath} " +
                    "(${formatBytes(finalFile.length())})",
            )
            }
        } finally {
            activeCalls.remove(item.videoId, call)
        }
    }

    /**
     * Espera a conexão voltar sem marcar a faixa como falha. O usuário pode
     * cancelar pela notificação ou pela tela Baixadas a qualquer momento.
     */
    private suspend fun waitForNetwork(videoId: String): Boolean {
        if (app.networkMonitor.isOnline()) return true
        AppLog.i(
            AppLog.TAG_DOWNLOAD,
            "Sem conexão; $videoId aguardando a rede voltar",
        )
        notifyProgress(null, 0, 0, done = false, waiting = true)
        while (
            scope.isActive &&
            !cancelRequested.get() &&
            !cancelled.contains(videoId) &&
            !recovering.contains(videoId)
        ) {
            delay(NETWORK_POLL_MS)
            if (app.networkMonitor.isOnline()) return true
        }
        return false
    }

    private fun buildRequest(info: StreamInfo, startOffset: Long): Request {
        val headers = Headers.Builder().apply {
            info.headers.forEach { (name, value) -> add(name, value) }
        }.build()
        val builder = Request.Builder()
            .url(info.url)
            .headers(headers)
            .header("Accept", "*/*")
        if (startOffset > 0L) {
            builder.header("Range", "bytes=$startOffset-")
        }
        return builder.get().build()
    }

    private fun ensureCover(item: DownloadEntity): File? {
        val target = storage.coverFile(item.videoId)
        val existing = storage.findCover(item.videoId, item.coverPath)
        if (existing != null && existing.length() > 0L) return existing
        val track = Track(
            videoId = item.videoId,
            title = item.title,
            artist = item.artist,
            thumbnailUrl = item.thumbnailUrl,
            durationMs = item.durationMs,
            album = item.album,
        )
        val url = track.artworkUrl() ?: return null
        return runCatching {
            target.parentFile?.mkdirs()
            val temp = File(target.parentFile, "${target.name}.part")
            val request = Request.Builder().url(url).get().build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val body = response.body ?: return@use null
                temp.outputStream().use { output -> body.byteStream().copyTo(output) }
                if (temp.length() <= 0L) {
                    temp.delete()
                    return@use null
                }
                if (target.exists()) target.delete()
                if (!temp.renameTo(target)) {
                    temp.copyTo(target, overwrite = true)
                    temp.delete()
                }
                target
            }
        }.getOrNull()
    }

    private fun hasEnoughSpace(): Boolean {
        val free = storage.availableBytes()
        return free <= 0L || free >= MIN_FREE_BYTES
    }

    private fun friendlyReason(error: Throwable?): String = when {
        error is LoginRequiredException -> "Entre no YouTube novamente para baixar"
        error is HttpFailure && error.code in listOf(401, 403) ->
            "Faixa restrita ou conta do YouTube expirada"
        error is HttpFailure && error.code == 404 -> "Faixa indisponível"
        error is IOException && error.message?.contains("Espaço", ignoreCase = true) == true ->
            "Espaço insuficiente no armazenamento"
        error is IOException && error.message?.contains("conex", ignoreCase = true) == true ->
            "Sem conexão; tente baixar novamente"
        else -> "Não foi possível baixar; tente novamente"
    }

    private fun concurrency(): Int = if (BuildConfig.FLAVOR == "tv") 1 else 2

    /**
     * Recuperação em duas camadas: o watchdog chama sozinho quando o
     * progresso congela, e o botão "Recuperar" força a mesma rotina. Chamadas
     * OkHttp pendentes são canceladas, as linhas voltam para QUEUED e os
     * workers retomam o arquivo `.part` com Range.
     */
    private fun recoverDownloads(force: Boolean) {
        val userId = app.session.mystUserId
        if (userId <= 0L) return
        if (!recoveryInFlight.compareAndSet(false, true)) return
        scope.launch {
            try {
                val now = System.currentTimeMillis()
                if (!force && now - lastRecoveryAt < RECOVERY_COOLDOWN_MS) return@launch
                lastRecoveryAt = now
                AppLog.i(
                    AppLog.TAG_DOWNLOAD,
                    if (force) "Recuperação manual dos downloads" else "Recuperação automática",
                )
                val active = activeWork.toList()
                active.forEach { videoId ->
                    // Só sinaliza e cancela a chamada. O worker que é dono da
                    // linha faz o requeue no catch, preservando a exclusividade
                    // do arquivo `.part`.
                    recovering += videoId
                    activeCalls[videoId]?.cancel()
                }
                val recovered = runCatching {
                    repository.recoverStalled(userId, exclude = active.toSet())
                }.getOrDefault(0)
                // Se um worker terminar entre a foto de `activeWork` e a
                // marcação, o marcador não pode vazar para uma próxima
                // tentativa da mesma faixa.
                scope.launch {
                    delay(RECOVERY_MARKER_TTL_MS)
                    recovering.removeAll { it !in activeWork }
                }
                if (workerJob?.isActive != true) {
                    startWork()
                } else {
                    notifyProgress(null, 0, 0, done = false)
                }
                AppLog.i(
                    AppLog.TAG_DOWNLOAD,
                    "Recuperação concluída: $recovered faixa(s) antiga(s), ${active.size} ativa(s)",
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                AppLog.failure(AppLog.TAG_DOWNLOAD, "Falha recuperando downloads", t)
            } finally {
                recoveryInFlight.set(false)
            }
        }
    }

    private fun startWatchdog() {
        if (watchdogJob?.isActive == true) return
        watchdogJob = scope.launch {
            while (isActive) {
                delay(WATCHDOG_INTERVAL_MS)
                val userId = app.session.mystUserId
                if (userId <= 0L) continue
                val stalled = runCatching { repository.stalledCount(userId) }
                    .getOrDefault(0)
                if (stalled > 0) {
                    AppLog.w(
                        AppLog.TAG_DOWNLOAD,
                        "$stalled download(s) sem progresso; tentando recuperar",
                    )
                    recoverDownloads(force = false)
                } else if (workerJob?.isActive != true) {
                    val active = runCatching { repository.activeCount(userId) }
                        .getOrDefault(0)
                    if (active > 0) startWork()
                }
            }
        }
    }

    private fun stopWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = null
    }

    /**
     * O foreground service sozinho não impede o aparelho de dormir. Com a tela
     * apagada, o Android pode suspender a CPU/rede e o progresso congelar; os
     * locks mantêm a fila andando até o último arquivo.
     */
    private fun acquireLocks() {
        if (wakeLock?.isHeld != true) {
            runCatching {
                val power = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = power.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "MystMusic:downloads",
                ).apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }.onFailure {
                AppLog.failure(AppLog.TAG_DOWNLOAD, "Falha adquirindo WakeLock", it)
            }
        }
        if (wifiLock?.isHeld != true) {
            runCatching {
                val wifi = applicationContext
                    .getSystemService(Context.WIFI_SERVICE) as? WifiManager
                @Suppress("DEPRECATION")
                val mode = WifiManager.WIFI_MODE_FULL_HIGH_PERF
                wifiLock = wifi?.createWifiLock(mode, "MystMusic:downloads")?.apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }.onFailure {
                AppLog.failure(AppLog.TAG_DOWNLOAD, "Falha adquirindo WifiLock", it)
            }
        }
    }

    private fun releaseLocks() {
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        wakeLock = null
        runCatching { if (wifiLock?.isHeld == true) wifiLock?.release() }
        wifiLock = null
    }

    private fun startForegroundSafely(notification: Notification): Boolean {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }
        return runCatching {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
        }.onFailure {
            AppLog.failure(
                AppLog.TAG_DOWNLOAD,
                "Android recusou o serviço em primeiro plano de downloads",
                it,
            )
            stopSelf()
        }.isSuccess
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = notificationManager()
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.download_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.download_channel_description)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(
        item: DownloadEntity?,
        bytes: Long,
        totalBytes: Long,
        preparing: Boolean = false,
        done: Boolean = false,
        cancelled: Boolean = false,
        waiting: Boolean = false,
    ): Notification {
        val content = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            pendingFlags(),
        )
        val cancel = PendingIntent.getService(
            this,
            1,
            Intent(this, DownloadService::class.java).setAction(ACTION_CANCEL_ALL),
            pendingFlags(),
        )
        val recover = PendingIntent.getService(
            this,
            2,
            Intent(this, DownloadService::class.java).setAction(ACTION_RECOVER),
            pendingFlags(),
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_arrow_down)
            .setContentIntent(content)
            .setOnlyAlertOnce(true)
            .setOngoing(!done)
            .setAutoCancel(done)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        if (done) {
            builder.setContentTitle(
                getString(
                    if (cancelled) R.string.download_cancelled else R.string.download_finished,
                ),
            )
            builder.setContentText(
                getString(
                    if (failed > 0) R.string.download_finished_failed else R.string.download_finished_ok,
                    finished,
                    failed,
                ),
            )
            builder.setProgress(100, 100, false)
            return builder.build()
        }
        val completed = total.coerceAtLeast(finished)
        val aggregatePercent = if (completed > 0) {
            (finished * 100 / completed).coerceIn(0, 100)
        } else {
            0
        }
        builder.setContentTitle(
            getString(
                when {
                    waiting -> R.string.download_waiting_network
                    preparing -> R.string.download_preparing
                    else -> R.string.download_running
                },
            ),
        )
        val itemPercent = if (totalBytes > 0L) {
            (bytes * 100 / totalBytes).coerceIn(0, 100).toInt()
        } else {
            aggregatePercent
        }
        builder.setContentText(
            if (item != null) {
                getString(
                    R.string.download_progress,
                    finished.coerceAtMost(completed),
                    completed,
                    item.title.ifBlank { getString(R.string.player_unknown_title) },
                    itemPercent,
                )
            } else {
                getString(
                    R.string.download_progress_aggregate,
                    finished.coerceAtMost(completed),
                    completed,
                )
            },
        )
        builder.setProgress(100, itemPercent, (preparing || waiting) && item == null)
        builder.addAction(
            R.drawable.ic_sync,
            getString(R.string.download_recover),
            recover,
        )
        builder.addAction(
            R.drawable.ic_close,
            getString(R.string.download_cancel),
            cancel,
        )
        return builder.build()
    }

    private fun notifyProgress(
        item: DownloadEntity?,
        bytes: Long,
        totalBytes: Long,
        done: Boolean,
        waiting: Boolean = false,
    ) {
        val now = System.currentTimeMillis()
        if (!done && now - lastNotifyAt < NOTIFY_INTERVAL_MS) return
        lastNotifyAt = now
        runCatching {
            val notification = buildNotification(
                item,
                bytes,
                totalBytes,
                done = done,
                waiting = waiting,
            )
            notificationManager().notify(NOTIFICATION_ID, notification)
        }
    }

    private fun finishNotification(success: Boolean, cancelled: Boolean = false) {
        if (!finishedOnce.compareAndSet(false, true)) return
        stopWatchdog()
        releaseLocks()
        if (success) failed = 0
        runCatching {
            val notification = buildNotification(
                item = null,
                bytes = 0,
                totalBytes = 0,
                done = true,
                cancelled = cancelled,
            )
            notificationManager().notify(NOTIFICATION_ID, notification)
        }
        stopForegroundCompat()
        stopSelf()
    }

    private fun cancelAll() {
        AppLog.i(AppLog.TAG_DOWNLOAD, "Cancelamento de todos os downloads")
        cancelRequested.set(true)
        scope.launch {
            // Não deixa a fila presa se o serviço não tiver workers ativos.
            val userId = app.session.mystUserId
            withContext(NonCancellable) {
                runCatching { repository.cancelAll(userId) }
            }
            if (workerJob?.isActive != true) {
                finishNotification(success = false, cancelled = true)
            }
        }
    }

    private fun notificationManager(): NotificationManager =
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private suspend fun cancelOne(videoId: String) {
        AppLog.i(AppLog.TAG_DOWNLOAD, "Cancelando download $videoId")
        cancelled += videoId
        storage.deleteTransfer(videoId)
        storage.partFile(videoId).delete()
        val userId = app.session.mystUserId
        if (userId > 0L) {
            val item = repository.find(userId, videoId)
            if (item != null && item.status != DownloadStatus.DONE) {
                repository.remove(userId, videoId)
            }
            // Um item apenas na fila não tem worker que limpe o marcador; sem
            // isso, baixar a mesma faixa de novo nesta sessão seria ignorado.
            if (item?.status != DownloadStatus.DOWNLOADING) {
                cancelled.remove(videoId)
            }
        } else {
            cancelled.remove(videoId)
        }
    }

    private fun stopForegroundCompat() {
        foregroundStarted = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_DETACH)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(false)
        }
    }

    private fun pendingFlags(): Int = PendingIntent.FLAG_UPDATE_CURRENT or
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0

    override fun onDestroy() {
        AppLog.i(AppLog.TAG_DOWNLOAD, "DownloadService.onDestroy")
        isRunning = false
        cancelRequested.set(true)
        activeCalls.values.forEach { call -> runCatching { call.cancel() } }
        activeCalls.clear()
        workerJob?.cancel()
        stopWatchdog()
        releaseLocks()
        scope.cancel()
        super.onDestroy()
    }

    private class HttpFailure(val code: Int, message: String) :
        IOException("HTTP $code $message")

    private class DownloadCancelledException : Exception("Download cancelado")

    companion object {
        private const val CHANNEL_ID = "myst_downloads"
        private const val NOTIFICATION_ID = 41
        private const val ACTION_CANCEL_ALL = "com.iskezis.myst.action.CANCEL_DOWNLOADS"
        private const val ACTION_CANCEL_ONE = "com.iskezis.myst.action.CANCEL_DOWNLOAD"
        private const val ACTION_RECOVER = "com.iskezis.myst.action.RECOVER_DOWNLOADS"
        private const val EXTRA_VIDEO_ID = "videoId"
        private const val MIN_FREE_BYTES = 200L * 1024L * 1024L
        private const val MIN_AUDIO_BYTES = 24L * 1024L
        private const val BUFFER_SIZE = 64 * 1024
        private const val PROGRESS_INTERVAL_MS = 600L
        private const val NOTIFY_INTERVAL_MS = 900L
        private const val NETWORK_POLL_MS = 2_000L
        private const val MAX_ATTEMPTS = 3
        private const val WATCHDOG_INTERVAL_MS = 15_000L
        private const val RECOVERY_COOLDOWN_MS = 45_000L
        private const val RECOVERY_MARKER_TTL_MS = 10_000L
        private const val RESOLVE_TIMEOUT_MS = 90_000L

        /** Visível para a tela Baixadas mostrar o estado real do serviço. */
        @Volatile
        var isRunning: Boolean = false
            private set

        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, DownloadService::class.java),
                )
            }.onFailure {
                AppLog.failure(
                    AppLog.TAG_DOWNLOAD,
                    "Falha iniciando o serviço de downloads",
                    it,
                )
            }
        }

        fun cancelAll(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, DownloadService::class.java).setAction(ACTION_CANCEL_ALL),
                )
            }.onFailure {
                AppLog.failure(AppLog.TAG_DOWNLOAD, "Falha cancelando downloads", it)
            }
        }

        fun cancel(context: Context, videoId: String) {
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, DownloadService::class.java)
                        .setAction(ACTION_CANCEL_ONE)
                        .putExtra(EXTRA_VIDEO_ID, videoId),
                )
            }.onFailure {
                AppLog.failure(AppLog.TAG_DOWNLOAD, "Falha cancelando download $videoId", it)
            }
        }

        /**
         * Botão "Recuperar": acorda o serviço, cancela chamadas pendentes e
         * devolve a fila. Seguro mesmo se o serviço já estiver rodando.
         */
        fun recover(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, DownloadService::class.java).setAction(ACTION_RECOVER),
                )
            }.onFailure {
                AppLog.failure(AppLog.TAG_DOWNLOAD, "Falha recuperando downloads", it)
            }
        }

        suspend fun enqueueAndStart(
            context: Context,
            repository: DownloadRepository,
            userId: Long,
            tracks: List<Track>,
        ): EnqueueResult {
            val result = repository.enqueue(userId, tracks)
            if (result.added > 0) start(context)
            return result
        }
    }
}
