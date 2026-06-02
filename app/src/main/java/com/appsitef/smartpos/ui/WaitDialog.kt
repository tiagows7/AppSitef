package com.appsitef.smartpos.ui

import android.content.Context
import android.view.LayoutInflater
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import com.appsitef.smartpos.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Diálogo reutilizável de "aguarde" para operações em andamento.
 * Use [show] no início da operação e [dismiss] ao finalizar (sucesso ou erro).
 */
class WaitDialog private constructor(
    private val dialog: AlertDialog
) {

    val isShowing: Boolean
        get() = dialog.isShowing

    fun dismiss() {
        if (dialog.isShowing) {
            dialog.dismiss()
        }
    }

    companion object {
        fun show(
            context: Context,
            message: CharSequence
        ): WaitDialog {
            val view = LayoutInflater.from(context).inflate(R.layout.dialog_wait_progress, null)
            view.findViewById<TextView>(R.id.tvWaitMessage).text = message

            val alertDialog = MaterialAlertDialogBuilder(context)
                .setView(view)
                .setCancelable(false)
                .create()

            alertDialog.setCanceledOnTouchOutside(false)
            alertDialog.show()
            return WaitDialog(alertDialog)
        }

        fun show(
            context: Context,
            @StringRes messageResId: Int
        ): WaitDialog = show(context, context.getString(messageResId))
    }
}
