package com.appsitef.smartpos.tef

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.util.Log
import androidx.appcompat.app.AlertDialog
import com.appsitef.smartpos.BuildConfig

/**
 * Em builds debug com COMEXTERNA=2 (TLS GWP), exibe diálogo antes do `configure`
 * com TipoComunicacaoExterna e TokenRegistro para print/homologação.
 */
object TefConfigureDebug {

    private const val TAG = "TefConfigureDebug"

    fun maybeShowGwpConfigureDialog(
        context: Context,
        additionalParams: String,
        onContinue: () -> Unit,
    ) {
        if (!BuildConfig.DEBUG || TefPreferences.getTefComExterna(context) != "2") {
            onContinue()
            return
        }

        val message = buildGwpConfigureMessage(context, additionalParams)
        Log.d(TAG, message.replace('\n', ' '))

        val activity = context.findActivity()
        if (activity == null || activity.isFinishing) {
            onContinue()
            return
        }

        activity.runOnUiThread {
            if (activity.isFinishing) {
                onContinue()
                return@runOnUiThread
            }
            AlertDialog.Builder(activity)
                .setTitle("Debug — configure TLS GWP")
                .setMessage(message)
                .setCancelable(false)
                .setPositiveButton(android.R.string.ok) { _, _ -> onContinue() }
                .show()
        }
    }

    fun buildGwpConfigureMessage(context: Context, additionalParams: String): String {
        val tipoComunicacaoExterna = extractConfigureParam(additionalParams, "TipoComunicacaoExterna")
            .ifBlank { TefPreferences.getTefTlsExterna(context).trim() }
            .ifBlank { "(vazio)" }
        val tokenRegistro = extractConfigureParam(additionalParams, "TokenRegistro")
            .ifBlank { TefPreferences.getTefTlsToken(context).trim() }
            .ifBlank { "(vazio)" }
        val sitefAddress = TefPreferences.getSitefConfigureAddress(context).ifBlank { "(vazio)" }

        return buildString {
            appendLine("Parâmetros do configure (COMEXTERNA=2):")
            appendLine()
            appendLine("SiTef: $sitefAddress")
            appendLine("Adicional:")
            appendLine(additionalParams.ifBlank { "(vazio)" })
            appendLine()
            appendLine("TipoComunicacaoExterna=$tipoComunicacaoExterna")
            appendLine("TokenRegistro=$tokenRegistro")
        }
    }

    private fun extractConfigureParam(additionalParams: String, key: String): String {
        if (additionalParams.isBlank()) return ""
        val pattern = Regex("""${Regex.escape(key)}=([^;\]]+)""", RegexOption.IGNORE_CASE)
        return pattern.find(additionalParams)?.groupValues?.getOrNull(1)?.trim().orEmpty()
    }

    private tailrec fun Context.findActivity(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
}
