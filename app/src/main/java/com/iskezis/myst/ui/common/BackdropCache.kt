package com.iskezis.myst.ui.common

import android.graphics.Bitmap
import android.util.LruCache

/**
 * Cache em memória do bitmap já borrado da capa atual.
 *
 * O player e a fila em tela cheia usam exatamente o mesmo bitmap, então abrir
 * a fila não refaz o desfoque nem baixa a capa novamente. A chave inclui o
 * videoId e a URL da arte para nunca misturar capas de faixas diferentes.
 *
 * Os bitmaps não são reciclados ao sair do cache: uma ImageView pode continuar
 * exibindo o bitmap depois de uma evicção. O coletor de lixo libera a memória
 * quando ninguém mais referencia a imagem.
 */
object BackdropCache {

    private const val MAX_ENTRIES = 8

    private val cache = object : LruCache<String, Bitmap>(MAX_ENTRIES) {
        override fun sizeOf(key: String, value: Bitmap): Int = 1
    }

    fun key(videoId: String?, artworkUrl: String?): String? {
        if (videoId.isNullOrBlank() || artworkUrl.isNullOrBlank()) return null
        return "$videoId\u0000$artworkUrl"
    }

    fun get(videoId: String?, artworkUrl: String?): Bitmap? {
        val cacheKey = key(videoId, artworkUrl) ?: return null
        val bitmap = cache.get(cacheKey) ?: return null
        if (bitmap.isRecycled) {
            cache.remove(cacheKey)
            return null
        }
        return bitmap
    }

    fun put(videoId: String?, artworkUrl: String?, blurred: Bitmap) {
        val cacheKey = key(videoId, artworkUrl) ?: return
        if (blurred.isRecycled || blurred.width <= 0 || blurred.height <= 0) return
        cache.put(cacheKey, blurred)
    }

    fun clear() {
        cache.evictAll()
    }
}
