package com.appsitef.smartpos.tef

import android.content.Context
import java.io.File
import java.io.IOException

/**
 * Garante arquivos de configuração do CliSiTef no diretório interno do app.
 *
 * - [ASSET_CLSIT]: copiado automaticamente pela biblioteca ao instanciar [br.com.softwareexpress.sitef.android.CliSiTef].
 * - [ASSET_CHEQUES_INI] / [FILE_CHEQUE_INI]: copiados manualmente para filesDir (nomes usados pela lib nativa).
 */
object CliSiTefAssetInstaller {

    const val ASSET_CLSIT = "CLSIT"
    const val ASSET_CHEQUES_INI = "cheques.ini"
    const val FILE_CHEQUE_INI = "Cheque.ini"

    fun ensureInstalled(context: Context) {
        copyAssetIfNeeded(context, ASSET_CHEQUES_INI, ASSET_CHEQUES_INI)
        copyAssetIfNeeded(context, ASSET_CHEQUES_INI, FILE_CHEQUE_INI)
        ensureClsitPresent(context)
        syncClsitGeralSection(context)
    }

    /** Garante CLSIT em filesDir (base do asset) para a lib nativa ler [Geral]. */
    private fun ensureClsitPresent(context: Context) {
        copyAssetIfNeeded(context, ASSET_CLSIT, ASSET_CLSIT)
    }

    /** Atualiza [Geral] e trace rotativo no CLSIT do filesDir (base para menu admin 110). */
    fun syncTransacoesHabilitadas(context: Context) {
        syncClsitGeralSection(context)
    }

    private fun syncClsitGeralSection(context: Context) {
        TefPreferences.loadModuloIniIfExists(context)
        val transacoes = TefPreferences.resolveTransacoesHabilitadas(context)
        if (transacoes.isBlank()) return

        ensureClsitPresent(context)
        val clsitFile = File(context.filesDir, ASSET_CLSIT)
        if (!clsitFile.exists()) return

        var content = clsitFile.readText()
        content = TefClsitConfig.patchIniSectionValue(
            content = content,
            section = "Geral",
            key = "TransacoesHabilitadas",
            value = TefClsitConfig.normalizeTransacoesHabilitadas(transacoes),
        )
        content = TefClsitConfig.patchIniSectionValue(
            content = content,
            section = "Geral",
            key = "TransacoesAdicionaisHabilitadas",
            value = TefClsitConfig.mergeTransacoesAdicionaisHabilitadas(
                TefClsitConfig.readIniSectionValue(content, "Geral", "TransacoesAdicionaisHabilitadas"),
            ),
        )
        content = TefClsitConfig.patchIniSectionValue(
            content = content,
            section = "CliSiTef",
            key = "TraceRotativo",
            value = TefClsitConfig.readIniSectionValue(content, "CliSiTef", "TraceRotativo")
                .ifBlank { "10" },
        )
        content = TefClsitConfig.patchIniSectionValue(
            content = content,
            section = "CliSiTefI",
            key = "TraceRotativo",
            value = TefClsitConfig.readIniSectionValue(content, "CliSiTefI", "TraceRotativo")
                .ifBlank { "10" },
        )
        clsitFile.writeText(content)
    }

    private fun copyAssetIfNeeded(context: Context, assetName: String, targetFileName: String) {
        val target = File(context.filesDir, targetFileName)
        if (target.exists() && target.length() > 0L) return
        try {
            context.assets.open(assetName).use { input ->
                target.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        } catch (_: IOException) {
            // Asset opcional; não interrompe o fluxo.
        }
    }
}
