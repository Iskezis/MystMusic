package com.iskezis.myst.ui.playlists

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Copia a imagem escolhida para o armazenamento privado do app. Guardar só a
 * URI do seletor deixaria a capa quebrada quando o provedor revogasse acesso.
 */
object CoverStore {

    suspend fun save(context: Context, playlistId: Long, uri: Uri): String? =
        withContext(Dispatchers.IO) {
            try {
                val resolver = context.contentResolver
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                resolver.openInputStream(uri)?.use { stream ->
                    BitmapFactory.decodeStream(stream, null, bounds)
                }
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null

                val options = BitmapFactory.Options().apply {
                    inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight)
                }
                val decoded = resolver.openInputStream(uri)?.use { stream ->
                    BitmapFactory.decodeStream(stream, null, options)
                } ?: return@withContext null

                val scaled = scaleDown(decoded, MAX_SIDE)
                val dir = File(context.filesDir, "covers").apply { mkdirs() }
                val file = File(dir, "$playlistId.jpg")
                FileOutputStream(file).use { output ->
                    scaled.compress(Bitmap.CompressFormat.JPEG, 92, output)
                }
                if (scaled !== decoded) scaled.recycle()
                decoded.recycle()
                Uri.fromFile(file).toString()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                null
            }
        }

    private fun sampleSize(width: Int, height: Int): Int {
        var sample = 1
        var largest = maxOf(width, height)
        while (largest / 2 >= MAX_SIDE) {
            largest /= 2
            sample *= 2
        }
        return sample
    }

    private fun scaleDown(bitmap: Bitmap, maxSide: Int): Bitmap {
        val largest = maxOf(bitmap.width, bitmap.height)
        if (largest <= maxSide) return bitmap
        val ratio = maxSide.toFloat() / largest
        val width = (bitmap.width * ratio).toInt().coerceAtLeast(1)
        val height = (bitmap.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, width, height, true)
    }

    private const val MAX_SIDE = 1024
}
