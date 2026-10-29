package com.iskezis.myst.ui.logs

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import com.iskezis.myst.ui.common.BaseActivity
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.iskezis.myst.R
import com.iskezis.myst.databinding.ActivityLogsBinding
import com.iskezis.myst.util.AppLog
import com.iskezis.myst.util.MystTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Tela de diagnóstico: mostra os últimos logs em memória, o caminho do arquivo
 * completo e ações de copiar/compartilhar. Funciona sem ADB e sem root, ao
 * contrário de leitores externos de Logcat (que precisam de READ_LOGS).
 */
class LogsActivity : BaseActivity() {

    private lateinit var binding: ActivityLogsBinding
    private var lastSnapshot: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLogsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        MystTheme.applySystemBars(this)
        val basePadding = listOf(
            binding.logsRoot.paddingLeft,
            binding.logsRoot.paddingTop,
            binding.logsRoot.paddingRight,
            binding.logsRoot.paddingBottom,
        )
        ViewCompat.setOnApplyWindowInsetsListener(binding.logsRoot) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(
                basePadding[0] + bars.left,
                basePadding[1] + bars.top,
                basePadding[2] + bars.right,
                basePadding[3] + bars.bottom,
            )
            insets
        }
        AppLog.i(AppLog.TAG_UI, "Tela de logs aberta")

        binding.logsBack.setOnClickListener { finish() }
        binding.logsRefresh.setOnClickListener {
            lastSnapshot = null
            render(forceScroll = true)
        }
        binding.logsCopy.setOnClickListener { copyLogs() }
        binding.logsShare.setOnClickListener { shareLogs() }
        binding.logsClear.setOnClickListener { confirmClear() }

        render(forceScroll = true)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    delay(1_500)
                    render()
                }
            }
        }
    }

    private fun render(forceScroll: Boolean = false) {
        val path = AppLog.logFilePath()
        binding.logsPath.text = if (path.isNullOrBlank()) {
            getString(R.string.logs_no_file)
        } else {
            getString(R.string.logs_path, path)
        }

        val snapshot = AppLog.diagnostics()
        if (!forceScroll && snapshot == lastSnapshot) return

        val scroll = binding.logsScroll
        val atBottom = forceScroll || isAtBottom()
        lastSnapshot = snapshot
        binding.logsText.text = snapshot.ifBlank { getString(R.string.logs_empty) }
        if (atBottom) {
            scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun isAtBottom(): Boolean {
        val child = binding.logsScroll.getChildAt(0) ?: return true
        val limit = child.bottom - binding.logsScroll.height
        return binding.logsScroll.scrollY >= limit - 48
    }

    private fun copyLogs() {
        val text = AppLog.diagnostics()
        if (text.isBlank()) {
            Toast.makeText(this, R.string.logs_empty, Toast.LENGTH_SHORT).show()
            return
        }
        runCatching {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.logs_title), text))
        }.onSuccess {
            Toast.makeText(this, R.string.logs_copied, Toast.LENGTH_SHORT).show()
        }.onFailure {
            AppLog.failure(AppLog.TAG_UI, "Falha ao copiar logs", it)
            Toast.makeText(this, R.string.logs_copy_error, Toast.LENGTH_LONG).show()
        }
    }

    private fun shareLogs() {
        val text = AppLog.diagnostics().ifBlank { getString(R.string.logs_empty) }
        val file = AppLog.logFile()
        runCatching {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, getString(R.string.logs_share_subject))
                putExtra(Intent.EXTRA_TEXT, text)
                if (file != null) {
                    val uri = FileProvider.getUriForFile(
                        this@LogsActivity,
                        "$packageName.logs",
                        file,
                    )
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
            startActivity(Intent.createChooser(send, getString(R.string.logs_share_title)))
        }.onFailure {
            AppLog.failure(AppLog.TAG_UI, "Falha ao compartilhar logs", it)
            Toast.makeText(this, R.string.logs_share_error, Toast.LENGTH_LONG).show()
        }
    }

    private fun confirmClear() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.logs_clear)
            .setMessage(R.string.logs_clear_confirm)
            .setNegativeButton(R.string.common_cancel, null)
            .setPositiveButton(R.string.logs_clear) { _, _ ->
                AppLog.clear()
                lastSnapshot = null
                render(forceScroll = true)
                Toast.makeText(this, R.string.logs_cleared, Toast.LENGTH_SHORT).show()
            }
            .show()
    }
}
