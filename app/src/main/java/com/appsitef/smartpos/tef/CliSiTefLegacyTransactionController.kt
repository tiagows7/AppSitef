package com.appsitef.smartpos.tef

import android.app.AlertDialog
import android.text.InputType
import android.util.Log
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import br.com.softwareexpress.sitef.android.CliSiTefI
import br.com.softwareexpress.sitef.android.modules.IPinPad
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Fluxo [CliSiTefI] igual ao GPOS Delphi (`nghc.clisitef.pas`).
 * [continuaFuncaoSiTefInterativo] roda em thread de fundo; UI só para coleta (como Delphi + esperaFimColeta).
 */
class CliSiTefLegacyTransactionController(
    private val activity: AppCompatActivity,
    private val cliSiTefI: CliSiTefI,
    private val onStatus: (String) -> Unit,
    private val onFinished: (success: Boolean, message: String) -> Unit,
    private val onShowMenu: ((
        title: String,
        items: List<Pair<String, String>>,
        onSelected: (String?) -> Unit
    ) -> Unit)? = null
) {

    private val running = AtomicBoolean(false)
    private val settled = AtomicBoolean(false)
    private var transactionAmount: String = ""
    private var cupomFiscal: String = ""
    private var dataFiscal: String = ""
    private var horaFiscal: String = ""
    private var terminalId: String = ""
    private var continuaThread: Thread? = null

    fun startSale(
        functionId: Int,
        amount: String,
        operator: String,
        restrictions: String
    ) {
        if (running.getAndSet(true)) return
        settled.set(false)

        TefPreferences.loadModuloIniIfExists(activity)
        CliSiTefAssetInstaller.ensureInstalled(activity)
        CliSiTefAssetInstaller.syncTransacoesHabilitadas(activity)

        val sitefIp = TefPreferences.getSitefConfigureAddress(activity)
        val storeId = TefPreferences.getStoreId(activity)
        terminalId = TefPreferences.getTerminalId(activity)

        if (sitefIp.isBlank() || storeId.isBlank() || terminalId.isBlank()) {
            finishWithError("Terminal TEF não configurado. Salve a configuração antes da venda.")
            return
        }

        GertecPinpadBootstrap.ensureGediReady(activity)

        val now = Date()
        dataFiscal = SimpleDateFormat("yyyyMMdd", Locale.US).format(now)
        horaFiscal = SimpleDateFormat("HHmmss", Locale.US).format(now)
        cupomFiscal = TefPreferences.getCoupon(activity).ifBlank { "654321" }
        transactionAmount = TefAmountFormatter.toCliSiTefAmount(amount)

        onStatus("SiTef: $sitefIp")

        activity.runOnUiThread {
            if (activity.isFinishing) return@runOnUiThread
            cliSiTefI.setActivity(activity)
            cliSiTefI.setDebug(true)

            onStatus("Configurando CliSiTef…")

            val configureCode = cliSiTefI.configuraIntSiTefInterativoEx(
                sitefIp,
                storeId,
                terminalId,
                TefPreferences.getSitefConfigureAdditionalParams(activity)
            )

            if (configureCode != 0) {
                finishWithError(describeConfigureError(configureCode))
                return@runOnUiThread
            }

            onStatus("Iniciando transação TEF…")

            val operadorParam = operator.trim().ifBlank { terminalId }
            val startSts = cliSiTefI.iniciaFuncaoSiTefInterativo(
                functionId,
                transactionAmount,
                cupomFiscal,
                dataFiscal,
                horaFiscal,
                operadorParam,
                restrictions
            )

            if (startSts != CONTINUA && startSts != 0) {
                finishWithError(describeTransactionError(startSts))
                return@runOnUiThread
            }

            if (startSts == 0) {
                finalizeTransaction()
            } else {
                startContinuaLoop()
            }
        }
    }

    private fun startContinuaLoop() {
        continuaThread = Thread({
            try {
                while (running.get() && !activity.isFinishing) {
                    val sts = cliSiTefI.continuaFuncaoSiTefInterativo()
                    if (sts != CONTINUA) {
                        activity.runOnUiThread {
                            if (sts == 0) {
                                finalizeTransaction()
                            } else {
                                finishWithError(describeTransactionError(sts))
                            }
                        }
                        return@Thread
                    }

                    val command = cliSiTefI.proximoComando
                    val latch = CountDownLatch(1)
                    var proceed = true

                    activity.runOnUiThread {
                        if (!running.get() || activity.isFinishing) {
                            latch.countDown()
                            return@runOnUiThread
                        }
                        handleCommand(command) { ok ->
                            proceed = ok
                            latch.countDown()
                        }
                    }

                    latch.await()
                    if (!proceed) {
                        activity.runOnUiThread {
                            finishWithError("Coleta TEF cancelada.")
                        }
                        return@Thread
                    }
                }
            } catch (error: Throwable) {
                Log.e(TAG, "continua loop", error)
                activity.runOnUiThread {
                    finishWithError("Erro no fluxo TEF: ${error.message}")
                }
            }
        }, "clisitef-continua").also { it.start() }
    }

    private fun finalizeTransaction() {
        var sts = cliSiTefI.finalizaTransacaoSiTefInterativoEx(1, cupomFiscal, dataFiscal, horaFiscal, "")
        if (sts == CONTINUA) {
            runFinalizeContinua(sts)
            return
        }
        if (sts == 0) {
            finishWithSuccess("Transação TEF concluída com sucesso.")
        } else {
            finishWithError(describeTransactionError(sts))
        }
    }

    private fun runFinalizeContinua(lastSts: Int) {
        continuaThread = Thread({
            try {
                var sts = lastSts
                while (running.get() && !activity.isFinishing) {
                    if (sts == CONTINUA) {
                        sts = cliSiTefI.continuaFuncaoSiTefInterativo()
                    }
                    if (sts != CONTINUA) {
                        activity.runOnUiThread {
                            if (sts == 0) {
                                finishWithSuccess("Transação TEF concluída com sucesso.")
                            } else {
                                finishWithError(describeTransactionError(sts))
                            }
                        }
                        return@Thread
                    }
                    val command = cliSiTefI.proximoComando
                    val latch = CountDownLatch(1)
                    var proceed = true
                    activity.runOnUiThread {
                        handleCommand(command) { ok ->
                            proceed = ok
                            latch.countDown()
                        }
                    }
                    latch.await()
                    if (!proceed) {
                        activity.runOnUiThread {
                            finishWithError("Erro ao finalizar TEF.")
                        }
                        return@Thread
                    }
                    sts = CONTINUA
                }
            } catch (error: Throwable) {
                Log.e(TAG, "finalize continua", error)
                activity.runOnUiThread {
                    finishWithError("Erro ao finalizar TEF: ${error.message}")
                }
            }
        }, "clisitef-finalize").also { it.start() }
    }

    private fun handleCommand(command: Int, onDone: (proceed: Boolean) -> Unit) {
        if (command == CliSiTefI.CMD_RETORNO_VALOR) {
            val buffer = cliSiTefI.buffer.orEmpty()
            if (buffer.isNotBlank() && !isIgnorableDisplayBuffer(buffer)) {
                onStatus(buffer)
            }
            cliSiTefI.buffer = ""
            onDone(true)
            return
        }

        val buffer = cliSiTefI.buffer.orEmpty()

        when (command) {
            CliSiTefI.CMD_OBTEM_VALOR -> {
                cliSiTefI.buffer = transactionAmount
                onDone(true)
            }

            CliSiTefI.CMD_CONFIRMA_CANCELA -> {
                cliSiTefI.buffer = "0"
                onDone(true)
            }

            CliSiTefI.CMD_MENSAGEM_OPERADOR,
            CliSiTefI.CMD_MENSAGEM_CLIENTE,
            CliSiTefI.CMD_MENSAGEM -> {
                if (buffer.isNotBlank() && !isIgnorableDisplayBuffer(buffer)) {
                    onStatus(buffer)
                }
                cliSiTefI.buffer = ""
                onDone(true)
            }

            CliSiTefI.CMD_TITULO_MENU,
            CliSiTefI.CMD_EXIBE_CABECALHO -> {
                if (buffer.isNotBlank()) onStatus(buffer)
                cliSiTefI.buffer = ""
                onDone(true)
            }

            CliSiTefI.CMD_REMOVE_MENSAGEM_OPERADOR,
            CliSiTefI.CMD_REMOVE_MENSAGEM_CLIENTE,
            CliSiTefI.CMD_REMOVE_MENSAGEM,
            CliSiTefI.CMD_REMOVE_TITULO_MENU,
            CliSiTefI.CMD_REMOVE_CABECALHO -> {
                cliSiTefI.buffer = ""
                onDone(true)
            }

            CMD_NUMERO_PARCELAS -> {
                cliSiTefI.buffer = "1"
                onDone(true)
            }

            CMD_OBTEM_CAMPO_SEM_COLETA,
            CMD_PINPAD_LEITURA -> {
                if (buffer.isNotBlank() && !isIgnorableDisplayBuffer(buffer)) {
                    onStatus(buffer)
                }
                cliSiTefI.buffer = ""
                onDone(true)
            }

            CliSiTefI.CMD_SELECIONA_MENU -> {
                if (isIgnorableDisplayBuffer(buffer)) {
                    cliSiTefI.buffer = "0"
                    onDone(true)
                    return
                }
                presentMenu(buffer, onDone)
            }

            CliSiTefI.CMD_OBTEM_QUALQUER_TECLA,
            CliSiTefI.CMD_PERGUNTA_SE_INTERROMPE -> {
                if (isIgnorableDisplayBuffer(buffer)) {
                    cliSiTefI.buffer = "0"
                    onDone(true)
                    return
                }
                if (buffer.isNotBlank()) onStatus(buffer)
                cliSiTefI.buffer = "0"
                onDone(true)
            }

            CliSiTefI.CMD_OBTEM_CAMPO,
            CliSiTefI.CMD_OBTEM_CHEQUE,
            CliSiTefI.CMD_OBTEM_CODIGO_EM_BARRAS -> {
                if (isIgnorableDisplayBuffer(buffer)) {
                    cliSiTefI.buffer = "0"
                    onDone(true)
                    return
                }
                showTextInput(
                    prompt = buffer.ifBlank { "Informe o dado:" },
                    maxLength = cliSiTefI.tamanhoMaximo.toInt()
                ) { value ->
                    if (value == null) {
                        onDone(false)
                    } else {
                        cliSiTefI.buffer = value
                        onDone(true)
                    }
                }
            }

            else -> {
                if (buffer.isNotBlank() && !isIgnorableDisplayBuffer(buffer)) {
                    onStatus("Cmd $command: $buffer")
                }
                cliSiTefI.buffer = if (isIgnorableDisplayBuffer(buffer)) "0" else ""
                onDone(true)
            }
        }
    }

    private fun presentMenu(menuBuffer: String, onDone: (proceed: Boolean) -> Unit) {
        val items = parseMenuItems(menuBuffer)
        if (items.isEmpty()) {
            cliSiTefI.buffer = "0"
            onDone(true)
            return
        }
        val showMenu = onShowMenu
        if (showMenu != null) {
            showMenu("Selecione a forma de pagamento", items) { selected ->
                if (selected == null) {
                    onDone(false)
                } else {
                    cliSiTefI.buffer = selected
                    onDone(true)
                }
            }
        } else {
            showMenuDialog(items, onDone)
        }
    }

    private fun showMenuDialog(items: List<Pair<String, String>>, onDone: (proceed: Boolean) -> Unit) {
        val labels = items.map { it.second }.toTypedArray()
        AlertDialog.Builder(activity)
            .setTitle("Selecione")
            .setItems(labels) { _, which ->
                cliSiTefI.buffer = items[which].first
                onDone(true)
            }
            .setOnCancelListener { onDone(false) }
            .show()
    }

    private fun showTextInput(prompt: String, maxLength: Int, onValue: (String?) -> Unit) {
        val input = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            if (maxLength > 0) {
                filters = arrayOf(android.text.InputFilter.LengthFilter(maxLength))
            }
        }
        AlertDialog.Builder(activity)
            .setMessage(prompt)
            .setView(input)
            .setPositiveButton("OK") { _, _ -> onValue(input.text.toString()) }
            .setNegativeButton("Cancelar") { _, _ -> onValue(null) }
            .show()
    }

    private fun parseMenuItems(menuBuffer: String): List<Pair<String, String>> {
        val parts = menuBuffer.split(';').map { it.trim() }.filter { it.isNotEmpty() }
        val items = mutableListOf<Pair<String, String>>()
        var index = 0
        while (index < parts.size) {
            val key = parts[index]
            val label = parts.getOrNull(index + 1) ?: key
            if (key.isNotEmpty()) items.add(key to label)
            index += 2
        }
        return items
    }

    private fun isIgnorableDisplayBuffer(buffer: String): Boolean {
        val trimmed = buffer.trim()
        return trimmed.isEmpty() || trimmed.all { it == '0' }
    }

    fun abort() {
        if (!running.get() || settled.get()) return
        running.set(false)
        continuaThread?.interrupt()
        activity.runOnUiThread {
            try {
                cliSiTefI.pinpadDesconecta()
            } catch (_: Throwable) {
                // Ignora.
            }
        }
        onStatus("Cancelando transação…")
    }

    fun release() {
        running.set(false)
        continuaThread?.interrupt()
    }

    private fun describeConfigureError(code: Int): String = when (code) {
        -12 -> "Transação TEF anterior pendente ($code). Reinicie o app."
        else -> "Erro configure CliSiTef: $code"
    }

    private fun describeTransactionError(code: Int): String = when (code) {
        IPinPad.PINPAD_COMMUNICATION_ERROR,
        31 -> "Erro pinpad (31). Verifique FactoryService Gertec e tela TEF dedicada."
        -43 -> "Erro pinpad (-43) nas rotinas do pinpad."
        -5 -> "Sem comunicação com o servidor SiTef."
        -2 -> "Operação cancelada no pinpad."
        else -> "Erro na transação TEF (código $code)."
    }

    private fun finishWithSuccess(message: String) {
        if (!settled.compareAndSet(false, true)) return
        running.set(false)
        onFinished(true, message)
    }

    private fun finishWithError(message: String) {
        if (!settled.compareAndSet(false, true)) return
        running.set(false)
        onFinished(false, message)
    }

    companion object {
        private const val TAG = "CliSiTefLegacy"
        private const val CONTINUA = 10000
        /** Coleta no pinpad virtual — não abrir diálogo (manual / Delphi). */
        private const val CMD_OBTEM_CAMPO_SEM_COLETA = 29
        private const val CMD_PINPAD_LEITURA = 40
        private const val CMD_NUMERO_PARCELAS = 505
    }
}
