package com.iskezis.myst.util

import android.net.Uri
import android.util.Log
import java.util.Locale

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
 * debug/release. O handler de crash do [MystApp] também grava o stack trace com
 * a tag `MystCrash`, antes de delegar para o handler padrão do Android.
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
    const val TAG_PAIRING = "MystPairing"
    const val TAG_CRASH = "MystCrash"

    fun d(tag: String, message: String) {
        Log.d(tag, build(message))
    }

    fun i(tag: String, message: String) {
        Log.i(tag, build(message))
    }

    fun w(tag: String, message: String, error: Throwable? = null) {
        if (error == null) Log.w(tag, build(message)) else Log.w(tag, build(message), error)
    }

    fun e(tag: String, message: String, error: Throwable? = null) {
        if (error == null) Log.e(tag, build(message)) else Log.e(tag, build(message), error)
    }

    /** Log de erro com mensagem curta e stack trace completa. */
    fun failure(tag: String, message: String, error: Throwable) {
        Log.e(tag, build(message) + ": " + describe(error), error)
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
