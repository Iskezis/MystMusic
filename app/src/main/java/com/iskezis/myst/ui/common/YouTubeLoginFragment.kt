package com.iskezis.myst.ui.common

import android.app.Activity
import android.content.Intent
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.iskezis.myst.MystApp
import com.iskezis.myst.R
import com.iskezis.myst.ui.auth.YouTubeLoginActivity
import com.iskezis.myst.util.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Base para telas que podem avisar "sessão do YouTube expirou". O login é
 * opcional e explícito: o app nunca apaga a sessão sozinho. Ao concluir, os
 * dados locais da conta Myst continuam intactos.
 */
abstract class YouTubeLoginFragment : Fragment() {

    private val youtubeLogin: ActivityResultLauncher<Intent> = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) {
            AppLog.i(AppLog.TAG_AUTH, "Login do YouTube cancelado")
            return@registerForActivityResult
        }
        val cookies = result.data?.getStringExtra(YouTubeLoginActivity.EXTRA_COOKIES).orEmpty()
        val host = activity ?: return@registerForActivityResult
        val app = host.application as? MystApp
        if (cookies.isBlank() || app == null) {
            AppLog.w(AppLog.TAG_AUTH, "Login do YouTube sem cookies válidos")
            Toast.makeText(host.applicationContext, R.string.player_login_failed, Toast.LENGTH_LONG)
                .show()
            return@registerForActivityResult
        }
        AppLog.i(AppLog.TAG_AUTH, "Cookies do YouTube recebidos: ${cookies.length} bytes")
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val name = app.accounts.linkYouTube(
                    cookies,
                    getString(R.string.youtube_account_fallback),
                )
                AppLog.i(AppLog.TAG_AUTH, "YouTube reconectado: $name")
                app.youtube.invalidate()
                Toast.makeText(
                    host.applicationContext,
                    R.string.youtube_relinked,
                    Toast.LENGTH_SHORT,
                ).show()
                onYouTubeRelinked()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                AppLog.failure(AppLog.TAG_AUTH, "Falha vinculando YouTube", t)
                Toast.makeText(
                    host.applicationContext,
                    R.string.player_login_failed,
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    protected fun requestYouTubeLogin() {
        AppLog.i(AppLog.TAG_AUTH, "Abrindo login do YouTube para renovar a sessão")
        try {
            youtubeLogin.launch(Intent(requireContext(), YouTubeLoginActivity::class.java))
        } catch (t: Throwable) {
            AppLog.failure(AppLog.TAG_AUTH, "Falha abrindo login do YouTube", t)
            Toast.makeText(requireContext(), R.string.player_login_failed, Toast.LENGTH_LONG).show()
        }
    }

    protected abstract fun onYouTubeRelinked()
}
