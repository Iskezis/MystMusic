package com.iskezis.myst.ui.playlists

import android.content.Context
import android.view.LayoutInflater
import androidx.annotation.StringRes
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.iskezis.myst.R
import com.iskezis.myst.databinding.DialogPlaylistNameBinding

/**
 * Pop-up único para criar e renomear playlists. O nome é obrigatório e limitado
 * a 40 caracteres, igual ao limite gravado no banco.
 */
fun showPlaylistNameDialog(
    context: Context,
    @StringRes titleRes: Int,
    initialName: String? = null,
    @StringRes positiveRes: Int = R.string.common_save,
    onConfirm: (String) -> Unit,
) {
    val binding = DialogPlaylistNameBinding.inflate(LayoutInflater.from(context))
    binding.dialogPlaylistInput.setText(initialName.orEmpty())
    binding.dialogPlaylistInput.setSelection(binding.dialogPlaylistInput.text?.length ?: 0)

    val dialog = MaterialAlertDialogBuilder(context)
        .setTitle(titleRes)
        .setView(binding.root)
        .setNegativeButton(R.string.common_cancel, null)
        .setPositiveButton(positiveRes, null)
        .create()

    dialog.setOnShowListener {
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val name = binding.dialogPlaylistInput.text?.toString()?.trim().orEmpty()
            if (name.isBlank()) {
                binding.dialogPlaylistLayout.error = context.getString(R.string.playlist_name_error)
                return@setOnClickListener
            }
            dialog.dismiss()
            onConfirm(name)
        }
        binding.dialogPlaylistInput.requestFocus()
    }
    dialog.show()
}
