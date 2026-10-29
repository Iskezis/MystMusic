package com.iskezis.myst.ui.auth

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.iskezis.myst.R
import com.iskezis.myst.databinding.ActivityAvatarCropBinding
import com.iskezis.myst.ui.common.BaseActivity
import com.iskezis.myst.ui.common.PhotoLoader
import com.iskezis.myst.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Tela de recorte 1:1 da foto de perfil. Recebe a URI do seletor, mostra a
 * máscara circular, permite arrastar/ampliar e devolve um caminho JPEG 512x512
 * no cache. O AuthActivity/settings copiam esse arquivo para o AvatarStore.
 */
class AvatarCropActivity : BaseActivity() {

    private lateinit var binding: ActivityAvatarCropBinding
    private var bitmap: Bitmap? = null
    private var sourceUri: Uri? = null
    private var loadJob: Job? = null
    private var saving = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLog.i(AppLog.TAG_UI, "AvatarCropActivity.onCreate")
        binding = ActivityAvatarCropBinding.inflate(layoutInflater)
        setContentView(binding.root)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, binding.avatarCropRoot)
            .isAppearanceLightStatusBars = false
        WindowCompat.getInsetsController(window, binding.avatarCropRoot)
            .isAppearanceLightNavigationBars = false
        ViewCompat.setOnApplyWindowInsetsListener(binding.avatarCropRoot) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }

        sourceUri = intent.getStringExtra(EXTRA_SOURCE_URI)?.let { raw ->
            runCatching { Uri.parse(raw) }.getOrNull()
        }
        binding.avatarCropCancel.setOnClickListener { cancel() }
        binding.avatarCropSave.setOnClickListener { save() }
        binding.avatarCropRetry.setOnClickListener { load() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = cancel()
        })

        if (sourceUri == null) {
            showError()
        } else {
            load()
        }
    }

    private fun load() {
        val uri = sourceUri ?: run {
            showError()
            return
        }
        loadJob?.cancel()
        binding.avatarCropProgress.isVisible = true
        binding.avatarCropErrorRoot.isVisible = false
        binding.avatarCropView.isVisible = false
        binding.avatarCropHint.isVisible = false
        binding.avatarCropSave.isEnabled = false
        loadJob = lifecycleScope.launch {
            val decoded = PhotoLoader.decodeOriented(this@AvatarCropActivity, uri, 2048)
            if (isFinishing || isDestroyed) {
                decoded?.recycle()
                return@launch
            }
            if (decoded == null) {
                AppLog.w(AppLog.TAG_UI, "Não foi possível decodificar a foto escolhida")
                showError()
                return@launch
            }
            bitmap?.recycle()
            bitmap = decoded
            binding.avatarCropView.setBitmap(decoded)
            binding.avatarCropProgress.isVisible = false
            binding.avatarCropView.isVisible = true
            binding.avatarCropHint.isVisible = true
            binding.avatarCropSave.isEnabled = true
        }
    }

    private fun save() {
        if (saving || bitmap == null) return
        saving = true
        binding.avatarCropSave.isEnabled = false
        binding.avatarCropProgress.isVisible = true
        binding.avatarCropErrorRoot.isVisible = false
        lifecycleScope.launch {
            val file = try {
                // O recorte lê a matriz da View; fazê-lo na thread principal
                // evita concorrência com um arrasto que ainda esteja em curso.
                val cropped = binding.avatarCropView.cropBitmap(OUTPUT_SIZE)
                if (cropped == null) {
                    null
                } else {
                    withContext(Dispatchers.IO) { writeCropped(cropped) }
                }
            } catch (t: Throwable) {
                AppLog.failure(AppLog.TAG_UI, "Falha recortando avatar", t)
                null
            }
            if (isFinishing || isDestroyed) {
                file?.delete()
                return@launch
            }
            if (file == null) {
                saving = false
                showError()
                return@launch
            }
            setResult(
                RESULT_OK,
                Intent().putExtra(EXTRA_OUTPUT_PATH, file.absolutePath),
            )
            finish()
        }
    }

    private fun writeCropped(cropped: Bitmap): File? {
        val dir = File(cacheDir, CROP_DIR).apply { mkdirs() }
        val output = File(dir, "avatar_${System.currentTimeMillis()}.jpg")
        return try {
            val ok = FileOutputStream(output).use { stream ->
                cropped.compress(Bitmap.CompressFormat.JPEG, 90, stream)
            }
            if (!ok) {
                output.delete()
                null
            } else {
                AppLog.i(AppLog.TAG_UI, "Avatar recortado: ${output.absolutePath}")
                output
            }
        } catch (t: Throwable) {
            AppLog.failure(AppLog.TAG_UI, "Falha gravando recorte do avatar", t)
            output.delete()
            null
        } finally {
            cropped.recycle()
        }
    }

    private fun showError() {
        binding.avatarCropProgress.isVisible = false
        binding.avatarCropErrorRoot.isVisible = true
        binding.avatarCropView.isVisible = false
        binding.avatarCropHint.isVisible = false
        binding.avatarCropSave.isEnabled = false
    }

    private fun cancel() {
        if (saving) return
        setResult(RESULT_CANCELED)
        finish()
    }

    override fun onDestroy() {
        loadJob?.cancel()
        binding.avatarCropView.clear()
        bitmap?.recycle()
        bitmap = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_SOURCE_URI = "extra_avatar_source_uri"
        const val EXTRA_OUTPUT_PATH = "extra_avatar_output_path"

        private const val OUTPUT_SIZE = 512
        private const val CROP_DIR = "avatar_crops"
    }
}
