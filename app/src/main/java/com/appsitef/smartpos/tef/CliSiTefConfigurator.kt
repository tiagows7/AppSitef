package com.appsitef.smartpos.tef

import android.content.Context
import br.com.softwareexpress.sitef.android.CliSiTef

/**
 * Aplica no CliSiTef os parâmetros gravados na configuração do terminal
 * (equivalente a CodigoLoja, NumeroTerminal, EnderecoIPSitef e PortaSitef no GPOS Delphi).
 */
object CliSiTefConfigurator {

    data class ConfigureResult(
        val code: Int,
        val success: Boolean,
        val message: String
    )

    fun configure(
        context: Context,
        cliSiTef: CliSiTef = CliSiTefHolder.get(context),
        abortIfBusy: Boolean = false
    ): ConfigureResult {
        if (!CliSiTefTransactionController.isSdkPresent()) {
            return ConfigureResult(
                code = -100,
                success = false,
                message = "SDK CliSiTef não encontrado."
            )
        }

        TefPreferences.loadModuloIniIfExists(context)
        CliSiTefAssetInstaller.ensureInstalled(context)

        val sitefEndpoint = TefPreferences.getSitefConfigureAddress(context)
        val storeId = TefPreferences.getStoreId(context)
        val terminalId = TefPreferences.getTerminalId(context)
        val additionalParams = TefPreferences.getSitefConfigureAdditionalParams(context)

        if (sitefEndpoint.isBlank() || storeId.isBlank() || terminalId.isBlank()) {
            return ConfigureResult(
                code = -101,
                success = false,
                message = "Dados TEF incompletos (IP, loja ou terminal)."
            )
        }

        // setActivity só na TefTransactionActivity (pinpad virtual GPOS) — ver manual CliSiTef.

        if (abortIfBusy) {
            try {
                cliSiTef.abortTransaction(0)
            } catch (_: Throwable) {
                // Ignora: não havia transação em andamento.
            }
        }

        try {
            cliSiTef.submitPendingMessages()
        } catch (_: Throwable) {
            // Opcional na lib.
        }

        val code = cliSiTef.configure(sitefEndpoint, storeId, terminalId, additionalParams)
        return ConfigureResult(
            code = code,
            success = code == CliSiTef.CONFIG_OK,
            message = describeConfigureError(code)
        )
    }

    private fun describeConfigureError(code: Int): String = when (code) {
        CliSiTef.CONFIG_OK -> "CliSiTef configurado."
        CliSiTef.CONFIG_ERROR_INVALID_SITEF_ADDRESS -> "Endereço SiTef inválido ou não resolvido."
        CliSiTef.CONFIG_ERROR_INVALID_STORE_ID -> "Código da loja inválido."
        CliSiTef.CONFIG_ERROR_INVALID_TERMINAL_ID -> "Número do terminal inválido."
        CliSiTef.CONFIG_ERROR_TCPIP_SETUP -> "Erro na inicialização TCP/IP do SiTef."
        CliSiTef.CONFIG_ERROR_OUT_OF_MEMORY -> "Memória insuficiente para o CliSiTef."
        CliSiTef.CONFIG_ERROR_CLISITEF_LIBRARY_NOT_FOUND ->
            "Biblioteca CliSiTef não encontrada ou com problemas."
        CliSiTef.CONFIG_ERROR_INVALID_PATH -> "Caminho de configuração inválido."
        -12 -> "Transação TEF anterior não foi encerrada. Aguarde o pinpad ou reinicie o app."
        else -> "Erro ao configurar CliSiTef (código $code)."
    }
}
