package com.iskezis.myst.ui.update

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.format.Formatter
import android.text.method.ScrollingMovementMethod
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.iskezis.myst.BuildConfig
import com.iskezis.myst.R
import com.iskezis.myst.data.update.ApkInstaller
import com.iskezis.myst.data.update.AppRelease
import com.iskezis.myst.data.update.UpdateCheckState
import com.iskezis.myst.data.update.UpdateDownloadState
import com.iskezis.myst.data.update.UpdateManager
import com.iskezis.myst.databinding.DialogUpdateAvailableBinding
import com.iskezis.myst.databinding.DialogUpdateProgressBinding
import com.iskezis.myst.util.AppLog
import com.iskezis.myst.util.MystTheme
import kotlinx.coroutines.launch
import java.io.File

/**
 * Liga o estado de atualização à Home: verifica ao abrir o app, mostra o
 * pop-up quando há uma versão nova, acompanha o download e abre o instalador
 * do Android. Nenhuma falha de rede vira crash: quando a verificação é
 * automática, o erro só fica no log; quando o usuário toca no botão do GitHub,
 * ele vê a mensagem e pode tentar de novo.
 */
class UpdateUi(
    private val fragment: Fragment,
    private val root: View,
    private val manager: UpdateManager,
) {

    private var availableDialog: AlertDialog? = null
    private var progressDialog: AlertDialog? = null
    private var auxiliaryDialog: AlertDialog? = null
    private var checkingSnackbar: Snackbar? = null
    private var observing = false
    private var manualCheckPending = false

    private val context get() = fragment.requireContext()

    fun start() {
        if (observing) return
        observing = true
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            fragment.viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { manager.checkState.collect(::renderCheck) }
                launch { manager.downloadState.collect(::renderDownload) }
            }
        }
    }

    fun checkAutomatically() {
        manager.check(manual = false)
    }

    fun checkManually() {
        manualCheckPending = true
        manager.check(manual = true)
    }

    fun onResume() {
        retryInstallIfAllowed()
        manager.check(manual = false)
    }

    fun stop() {
        availableDialog?.dismiss()
        availableDialog = null
        progressDialog?.dismiss()
        progressDialog = null
        auxiliaryDialog?.dismiss()
        auxiliaryDialog = null
        checkingSnackbar?.dismiss()
        checkingSnackbar = null
    }

    private fun renderCheck(state: UpdateCheckState) {
        when (state) {
            UpdateCheckState.Idle -> hideChecking()
            UpdateCheckState.Checking -> {
                if (manualCheckPending) showChecking()
            }
            UpdateCheckState.UpToDate -> {
                hideChecking()
                val manual = manualCheckPending
                manualCheckPending = false
                if (manual) snack(R.string.update_up_to_date)
            }
            is UpdateCheckState.Available -> {
                hideChecking()
                val manual = manualCheckPending
                manualCheckPending = false
                val release = state.release
                val alreadyHandled =
                    manager.dismissedVersionCode == release.versionCode ||
                        manager.popupShownVersionCode == release.versionCode
                if (!manual && alreadyHandled) return
                if (availableDialog?.isShowing == true) return
                showAvailable(release)
            }
            is UpdateCheckState.Failed -> {
                hideChecking()
                val manual = manualCheckPending
                manualCheckPending = false
                if (manual) {
                    Snackbar.make(
                        root,
                        state.messageRes,
                        Snackbar.LENGTH_LONG,
                    ).setAction(R.string.update_retry) {
                        checkManually()
                    }.show()
                }
            }
        }
    }

    private fun renderDownload(state: UpdateDownloadState) {
        when (state) {
            UpdateDownloadState.Idle -> dismissProgress()
            is UpdateDownloadState.Downloading -> {
                showProgress(state.release, state.downloaded, state.total)
            }
            is UpdateDownloadState.Ready -> {
                dismissProgress()
                installOrRequestPermission(state.file)
            }
            is UpdateDownloadState.Failed -> {
                dismissProgress()
                showDownloadError(state)
            }
        }
    }

    private fun showChecking() {
        checkingSnackbar?.dismiss()
        checkingSnackbar = Snackbar.make(
            root,
            R.string.update_checking,
            Snackbar.LENGTH_INDEFINITE,
        ).also { it.show() }
    }

    private fun hideChecking() {
        checkingSnackbar?.dismiss()
        checkingSnackbar = null
    }

    private fun snack(messageRes: Int) {
        Snackbar.make(root, messageRes, Snackbar.LENGTH_LONG).show()
    }

    private fun showAvailable(release: AppRelease) {
        val binding = DialogUpdateAvailableBinding.inflate(fragment.layoutInflater)
        binding.updateVersion.text = context.getString(
            R.string.update_version_format,
            BuildConfig.VERSION_NAME,
            release.versionName,
        )
        if (release.assetSize > 0) {
            binding.updateSize.isVisible = true
            binding.updateSize.text = context.getString(
                R.string.update_size_format,
                Formatter.formatShortFileSize(context, release.assetSize),
            )
        } else {
            binding.updateSize.isVisible = false
        }
        binding.updateNotes.text = release.notes.ifBlank {
            context.getString(R.string.update_notes_empty)
        }
        binding.updateNotes.movementMethod = ScrollingMovementMethod()

        val dialog = MaterialAlertDialogBuilder(context)
            .setView(binding.root)
            .setPositiveButton(R.string.update_now) { _, _ ->
                manager.dismiss(release)
                manager.download(release)
            }
            .setNegativeButton(R.string.update_later) { _, _ ->
                manager.dismiss(release)
            }
            .setOnCancelListener {
                manager.dismiss(release)
            }
            .create()

        dialog.setOnShowListener {
            val positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            if (BuildConfig.FLAVOR != "tv") {
                // Botão primário em pílula com o gradiente azul céu do app.
                positive.background = AppCompatResources.getDrawable(
                    context,
                    R.drawable.bg_button_gradient,
                )
                positive.setTextColor(MystTheme.color(context, R.attr.mystOnAccent))
                val horizontal = context.resources.getDimensionPixelSize(R.dimen.space_l)
                val vertical = context.resources.getDimensionPixelSize(R.dimen.space_s)
                positive.setPadding(horizontal, vertical, horizontal, vertical)
            } else {
                positive.post { positive.requestFocus() }
            }
        }
        dialog.setOnDismissListener {
            if (availableDialog === dialog) availableDialog = null
        }
        manager.markPopupShown(release)
        availableDialog = dialog
        dialog.show()
    }

    private fun showProgress(release: AppRelease, downloaded: Long, total: Long) {
        if (progressDialog?.isShowing == true) {
            updateProgressValues(downloaded, total)
            return
        }
        val binding = DialogUpdateProgressBinding.inflate(fragment.layoutInflater)
        binding.updateProgressVersion.text = context.getString(
            R.string.update_version_format,
            BuildConfig.VERSION_NAME,
            release.versionName,
        )
        val dialog = MaterialAlertDialogBuilder(context)
            .setView(binding.root)
            .setNegativeButton(R.string.update_cancel) { _, _ ->
                manager.cancelDownload()
            }
            .setCancelable(false)
            .create()
        dialog.setOnDismissListener {
            if (progressDialog === dialog) progressDialog = null
        }
        progressDialog = dialog
        progressBinding = binding
        updateProgressValues(downloaded, total)
        dialog.show()
        if (BuildConfig.FLAVOR == "tv") {
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.requestFocus()
        }
    }

    private var progressBinding: DialogUpdateProgressBinding? = null

    private fun updateProgressValues(downloaded: Long, total: Long) {
        val binding = progressBinding ?: return
        val known = total > 0
        binding.updateProgressBar.isIndeterminate = !known
        if (known) {
            val percent = ((downloaded.coerceAtLeast(0L) * 100L) / total)
                .toInt()
                .coerceIn(0, 100)
            binding.updateProgressBar.setProgressCompat(percent, true)
            binding.updateProgressPercent.text = context.getString(
                R.string.update_download_percent,
                percent,
            )
            binding.updateProgressBytes.text = context.getString(
                R.string.update_download_bytes,
                Formatter.formatShortFileSize(context, downloaded),
                Formatter.formatShortFileSize(context, total),
            )
        } else {
            binding.updateProgressPercent.text = ""
            binding.updateProgressBytes.text = context.getString(
                R.string.update_download_bytes_unknown,
                Formatter.formatShortFileSize(context, downloaded),
            )
        }
    }

    private fun dismissProgress() {
        progressDialog?.dismiss()
        progressDialog = null
        progressBinding = null
    }

    private fun showDownloadError(state: UpdateDownloadState.Failed) {
        auxiliaryDialog?.dismiss()
        auxiliaryDialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.update_download_failed_title)
            .setMessage(state.messageRes)
            .setNegativeButton(R.string.update_close, null)
            .setPositiveButton(R.string.update_retry) { _, _ ->
                manager.download(state.release)
            }
            .show()
    }

    private fun installOrRequestPermission(file: File) {
        if (!file.isFile) {
            showInstallError()
            return
        }
        // Já explicamos a permissão e estamos esperando o usuário voltar das
        // configurações; não repete o diálogo a cada retomada da tela.
        if (manager.installPermissionPending) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            manager.markInstallPermissionPending()
            showInstallPermission()
            return
        }
        launchInstaller(file)
    }

    private fun retryInstallIfAllowed() {
        if (!manager.installPermissionPending) return
        val state = manager.downloadState.value
        if (state !is UpdateDownloadState.Ready) {
            manager.clearInstallPermissionPending()
            return
        }
        val allowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            context.packageManager.canRequestPackageInstalls()
        if (allowed) {
            manager.clearInstallPermissionPending()
            launchInstaller(state.file)
        }
    }

    private fun showInstallPermission() {
        auxiliaryDialog?.dismiss()
        auxiliaryDialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.update_install_permission_title)
            .setMessage(R.string.update_install_permission_message)
            .setNegativeButton(R.string.update_later, null)
            .setPositiveButton(R.string.update_open_settings) { _, _ ->
                openInstallSettings()
            }
            .show()
    }

    private fun openInstallSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}"),
        )
        try {
            fragment.startActivity(intent)
        } catch (t: ActivityNotFoundException) {
            AppLog.failure(AppLog.TAG_UPDATE, "Tela de instalação não encontrada", t)
            showInstallError()
        }
    }

    private fun launchInstaller(file: File) {
        if (installing) return
        if (!file.isFile || file.length() <= 0L) {
            AppLog.w(AppLog.TAG_UPDATE, "APK de atualização ausente ou vazio")
            showInstallError()
            return
        }
        installing = true
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            val started = ApkInstaller(context).install(file)
            installing = false
            if (!fragment.isAdded) return@launch
            if (started) {
                manager.markInstallLaunched()
            } else {
                showInstallError()
            }
        }
    }

    private var installing = false

    private fun showInstallError() {
        auxiliaryDialog?.dismiss()
        auxiliaryDialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.update_install_error_title)
            .setMessage(
                context.getString(R.string.update_install_error) + "\n\n" +
                    context.getString(R.string.update_install_signature_hint),
            )
            .setPositiveButton(R.string.update_close, null)
            .show()
    }
}
