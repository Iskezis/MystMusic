package com.iskezis.myst.util

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Log centralizado do MystMusic.
 *
 * Regras:
 * - nunca registrar cookies, tokens, senhas ou a query string de URLs assinadas
 *   (a URL de áudio do YouTube carrega uma assinatura temporária);
 * - usar tags curtas e estáveis para facilitar o filtro no Logcat:
 *   `adb logcat -s MystMusic MystPlayer MystStream MystTube MystUI MystCrash`.
 *
 * Os logs são emitidos com [Log] e aparecem normalmente no Logcat em builds
 * debug/release. Além disso, a partir de [attach], as mesmas linhas são
 * gravadas em um arquivo privado do app (`files/logs/mystmusic.log`), que pode
 * ser aberto na tela "Logs e diagnóstico" ou compartilhado pelo usuário.
 *
 * O Android NÃO permite que um app leia os logs de outro sem a permissão
 * READ_LOGS (concedida por ADB/root). Por isso o arquivo e a tela interna são
 * o caminho que funciona sem depender de um leitor externo.
 */
object AppLog {

    const val TAG = "MystMusic"
    const val TAG_UI = "MystUI"
    const val TAG_PLAYER = "MystPlayer"
    const val TAG_STREAM = "MystStream"
    const val TAG_TUBE = "MystTube"
    const val TAG_DB = "MystDb"
    const val TAG_AUTH = "MystAuth"
    const val TAG_SPOTIFY = "MystSpotify"
    const val TAG_DISCORD = "MystDiscord"
    const val TAG_PAIRING = "MystPairing"
    const val TAG_CRASH = "MystCrash"

    private const val MAX_DIAGNOSTIC_LINES = 500
    private const val MAX_FILE_BYTES = 1L * 1024 * 1024
    private const val LOG_FILE_NAME = "mystmusic.log"
    private const val OLD_LOG_FILE_NAME = "mystmusic-old.log"

    private val diagnosticsLock = Any()
    private val diagnosticsBuffer = ArrayDeque<String>(MAX_DIAGNOSTIC_LINES)

    @Volatile
    private var logFile: File? = null

    /**
     * Escrita serializada: [record] pode ser chamado de qualquer thread, e o
     * arquivo é aberto/fechado a cada linha para que um crash não perca os
     * últimos registros. Nunca lança nem bloqueia a thread chamadora.
     */
    private val fileWriter = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "MystLogWriter").apply { isDaemon = true }
    }

    /**
     * Define o arquivo de log. Chamado uma única vez no [android.app.Application].
     * Se o armazenamento externo não estiver disponível, usa o diretório
     * privado interno; em ambos os casos não há permissão especial envolvida.
     */
    fun attach(context: Context) {
        if (logFile != null) return
        synchronized(diagnosticsLock) {
            if (logFile != null) return
            val appContext = context.applicationContext
            val dir = runCatching { appContext.getExternalFilesDir("logs") }
                .getOrNull()
                ?: File(appContext.filesDir, "logs")
            runCatching { dir.mkdirs() }
            val target = File(dir, LOG_FILE_NAME)
            runCatching { rotate(target) }
            logFile = target
            val header = "=== MystMusic iniciado em ${java.util.Date()} ==="
            runCatching {
                // Também no Logcat, em prioridade alta, para aparecer mesmo em
                // leitores configurados para mostrar apenas W/E/Assert.
                Log.println(Log.ASSERT, TAG, header)
            }
            fileWriter.execute {
                runCatching { target.appendText(header + "\n") }
            }
        }
    }

    fun d(tag: String, message: String) {
        record("D", tag, message)
        Log.d(tag, build(message))
    }

    fun i(tag: String, message: String) {
        record("I", tag, message)
        Log.i(tag, build(message))
    }

    fun w(tag: String, message: String, error: Throwable? = null) {
        record("W", tag, message + error?.let { ": ${describe(it)}" }.orEmpty())
        if (error == null) Log.w(tag, build(message)) else Log.w(tag, build(message), error)
    }

    fun e(tag: String, message: String, error: Throwable? = null) {
        record("E", tag, message + error?.let { ": ${describe(it)}" }.orEmpty())
        if (error == null) {
            Log.e(tag, build(message))
        } else {
            Log.e(tag, build(message), error)
            // O diagnóstico interno mostra a pilha; sem isso um erro de rede
            // ou de player chegaria ao arquivo apenas como uma linha curta.
            appendToFile(error.stackTraceToString())
        }
    }

    /** Log de erro com mensagem curta e stack trace completa. */
    fun failure(tag: String, message: String, error: Throwable) {
        record("E", tag, "$message: ${describe(error)}")
        appendToFile(error.stackTraceToString())
        Log.e(tag, build(message) + ": " + describe(error), error)
    }

    /**
     * Igual a [failure], mas grava no arquivo antes de retornar. Usado pelo
     * handler de crash, quando o processo pode ser encerrado logo depois.
     */
    fun failureSync(tag: String, message: String, error: Throwable) {
        record("E", tag, "$message: ${describe(error)}", syncFile = true)
        appendToFileNow(error.stackTraceToString())
        Log.e(tag, build(message) + ": " + describe(error), error)
    }

    /**
     * Últimas linhas de log em memória. A tela "Logs e diagnóstico" e o botão
     * "Copiar diagnóstico" do player usam este texto.
     */
    fun diagnostics(): String = synchronized(diagnosticsLock) {
        diagnosticsBuffer.joinToString("\n")
    }

    /** Arquivo completo (até 1 MB) com todo o histórico desta instalação. */
    fun logFile(): File? = logFile?.takeIf { it.exists() }

    fun logFilePath(): String? = logFile?.absolutePath

    fun clear() {
        synchronized(diagnosticsLock) { diagnosticsBuffer.clear() }
        val target = logFile ?: return
        fileWriter.execute {
            runCatching { target.writeText("") }
        }
    }

    private fun record(level: String, tag: String, message: String, syncFile: Boolean = false) {
        val time = String.format(Locale.US, "%tT", System.currentTimeMillis())
        val line = "$time $level/$tag: $message"
        synchronized(diagnosticsLock) {
            if (diagnosticsBuffer.size >= MAX_DIAGNOSTIC_LINES) {
                diagnosticsBuffer.removeFirst()
            }
            diagnosticsBuffer.addLast(line)
        }
        if (syncFile) appendToFileNow(line) else appendToFile(line)
    }

    private fun appendToFile(line: String) {
        val target = logFile ?: return
        fileWriter.execute {
            runCatching {
                rotateIfNeeded(target)
                target.appendText(line + "\n")
            }
        }
    }

    private fun appendToFileNow(line: String) {
        val target = logFile ?: return
        runCatching {
            rotateIfNeeded(target)
            target.appendText(line + "\n")
        }
    }

    private fun rotateIfNeeded(target: File) {
        if (target.length() > MAX_FILE_BYTES) rotate(target)
    }

    private fun rotate(target: File) {
        if (!target.exists() || target.length() == 0L) return
        val old = File(target.parentFile, OLD_LOG_FILE_NAME)
        if (old.exists()) old.delete()
        if (!target.renameTo(old)) {
            // Se o sistema não permitir renomear, evita crescimento infinito.
            target.writeText("")
        }
    }

    /** Identifica a thread atual nos logs assíncronos. */
    fun threadName(): String = Thread.currentThread().name

    /** Mantém apenas o host e o path de uma URL; nunca expõe a query assinada. */
    fun urlForLog(url: String?): String {
        if (url.isNullOrBlank()) return "<vazia>"
        return runCatching {
            val parsed = Uri.parse(url)
            val host = parsed.host ?: "<sem-host>"
            val path = parsed.path.orEmpty()
            if (path.length > 60) host + path.take(60) + "…" else host + path
        }.getOrDefault("<url-inválida>")
    }

    fun describe(error: Throwable?): String {
        if (error == null) return "sem erro"
        val type = error.javaClass.simpleName
        val message = error.message?.take(300)
        return if (message.isNullOrBlank()) type else "$type: $message"
    }

    fun bytesForLog(value: Int): String = String.format(Locale.US, "%d bytes", value)

    fun durationForLog(ms: Long): String = when {
        ms <= 0L -> "desconhecida"
        ms < 1_000L -> "${ms}ms"
        else -> "${ms / 1_000}s"
    }

    private fun build(message: String): String {
        val thread = Thread.currentThread().name
        return "[$thread] $message"
    }
}
