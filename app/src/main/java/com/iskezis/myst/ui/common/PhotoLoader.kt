package com.iskezis.myst.ui.common

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.iskezis.myst.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Decodifica a foto escolhida da galeria com downscale e orientação EXIF.
 * Fazer isso antes da tela de recorte evita carregar uma imagem de 12 MP na
 * memória e mantém o recorte fluido em aparelhos antigos (minSdk 21).
 */
object PhotoLoader {

    suspend fun decodeOriented(context: Context, uri: Uri, maxSide: Int = 2048): Bitmap? =
        withContext(Dispatchers.IO) {
            val temp = File(context.cacheDir, "avatar_source_${System.currentTimeMillis()}.jpg")
            try {
                val copied = context.contentResolver.openInputStream(uri)?.use { input ->
                    temp.outputStream().use { output -> input.copyTo(output) }
                    true
                } ?: return@withContext null
                if (!copied) return@withContext null

                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(temp.absolutePath, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null

                val options = BitmapFactory.Options().apply {
                    inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxSide)
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                val decoded = BitmapFactory.decodeFile(temp.absolutePath, options)
                    ?: return@withContext null
                transform(decoded, readOrientation(temp))
            } catch (t: Throwable) {
                AppLog.failure(AppLog.TAG_UI, "Falha decodificando foto de perfil", t)
                null
            } finally {
                runCatching { temp.delete() }
            }
        }

    private fun sampleSize(width: Int, height: Int, maxSide: Int): Int {
        var sample = 1
        var w = width
        var h = height
        while (w / 2 >= maxSide && h / 2 >= maxSide) {
            sample *= 2
            w /= 2
            h /= 2
        }
        return sample
    }

    private fun readOrientation(file: File): Int = runCatching {
        ExifInterface(file.absolutePath).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL,
        )
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

    private fun transform(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            2 -> matrix.setScale(-1f, 1f) // espelhada na horizontal
            3 -> matrix.setRotate(180f)
            4 -> matrix.setScale(1f, -1f) // espelhada na vertical
            5 -> {
                matrix.setRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            6 -> matrix.setRotate(90f)
            7 -> {
                matrix.setRotate(-90f)
                matrix.postScale(-1f, 1f)
            }
            8 -> matrix.setRotate(-90f)
        }
        if (matrix.isIdentity) return bitmap
        val transformed = Bitmap.createBitmap(
            bitmap,
            0,
            0,
            bitmap.width,
            bitmap.height,
            matrix,
            true,
        )
        if (transformed !== bitmap) bitmap.recycle()
        return transformed
    }
}
