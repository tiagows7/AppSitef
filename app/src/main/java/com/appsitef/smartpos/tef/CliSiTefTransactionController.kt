package com.appsitef.smartpos.tef

import android.app.AlertDialog
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.util.Log
import android.view.LayoutInflater
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.button.MaterialButton
import br.com.softwareexpress.sitef.android.CliSiTef
import br.com.softwareexpress.sitef.android.ICliSiTefListener
import br.com.softwareexpress.sitef.android.modules.IPinPad
import com.appsitef.smartpos.R
import com.appsitef.smartpos.TefTransactionActivity
import com.appsitef.smartpos.sales.model.Abastecimento
import com.appsitef.smartpos.sales.model.SaleContext
import com.appsitef.smartpos.sales.network.AbastecimentoRemoteRepository
import com.appsitef.smartpos.sales.network.CartaoMovimentoPostRequest
import com.appsitef.smartpos.sales.network.CartaoRemoteRepository
import com.appsitef.smartpos.sales.network.DatasnapPathEncoder
import com.appsitef.smartpos.sales.network.RestJsonParser
import com.appsitef.smartpos.ui.MoneyInputMask
import com.appsitef.smartpos.ui.WaitDialog
import java.nio.charset.Charset
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Fluxo [CliSiTef] v2 — espelha [ClisitefControllerActivity] Java (Fiserv).
 * onData na thread do listener; respostas via continueTransaction(dado).
 */
class CliSiTefTransactionController(
    private val activity: TefTransactionActivity,
    private val cliSiTef: CliSiTef,
    private val saleAbastecimentos: List<Abastecimento>,
    private val saleContext: SaleContext,
    private val operationMode: TefOperationMode = TefOperationMode.SALE,
    private val onStatus: (String) -> Unit,
    private val onOperatorMessage: (String?) -> Unit,
    private val onShowPixQrCode: (payload: String, hint: String?) -> Unit,
    private val onHidePixQrCode: () -> Unit,
    private val onPixQrMessage: (String) -> Unit,
    private val onFinished: (success: Boolean, message: String, result: TefTransactionResult?) -> Unit,
) : ICliSiTefListener {

    private val charset: Charset = Charset.forName("ISO-8859-1")
    private val running = AtomicBoolean(false)
    private val settled = AtomicBoolean(false)
    private val menuDialogOpen = AtomicBoolean(false)
    private var pendingMenuTitle: String? = null
    private val abortRequested = AtomicBoolean(false)
    private val abortFallbackPosted = AtomicBoolean(false)
    private var activeDialog: AlertDialog? = null
    private var abortFallbackRunnable: Runnable? = null
    private var awaitingCancelFinish = false
    private val cancelFinishInProgress = AtomicBoolean(false)
    private var cancelFinishPurpose = CancelFinishPurpose.USER_ABORT
    private var cancelOnComplete: (() -> Unit)? = null
    private var cancelFallbackMessage = ""
    private var configureRetryAttempts = 0
    private var ensureReadyAttempts = 0
    private var transactionStage1Received = false
    private var abortFallbackRetries = 0
    private var abortAwaitingLateStage1 = false
    private var cancelSettlementWatchdogPosted = false
    private var cancelSettlementWatchdogRunnable: Runnable? = null
    private var receiptFinalizeRunnable: Runnable? = null
    private var pendingConfirmWatchdogRunnable: Runnable? = null
    private var receiptFinalizeScheduled = false
    @Volatile
    private var merchantReceiptPrinted = false
    private val merchantReceiptLock = Object()
    private var transactionApproved = false
    private val approvedFinalizationStarted = AtomicBoolean(false)
    private val receipt = TefReceiptData()
    private val transactionResult = TefTransactionResult()
    private val abastecimentoRepository by lazy { AbastecimentoRemoteRepository(activity) }
    private val cartaoRepository by lazy { CartaoRemoteRepository(activity) }
    private var saleAmount: String = ""
    private var saleOperator: String = ""
    private var cartaoMovimentoRegistrado = false
    private var confirmingPendingAfterSuccess = false
    private val pendingConfirmQueue = ArrayDeque<TefFiscalRef>()
    private var pendingConfirmOnComplete: (() -> Unit)? = null
    private var pendingConfirmFinishParams: String = ""

    private lateinit var taxInvoiceNumber: String
    private lateinit var taxInvoiceDate: String
    private lateinit var taxInvoiceTime: String
    private var additionalParameters: String = ""

    fun startSale(
        functionId: Int,
        amount: String,
        operator: String,
        restrictions: String = ""
    ) {
        if (running.getAndSet(true)) return
        saleAmount = amount
        saleOperator = operator
        settled.set(false)
        abortRequested.set(false)
        abortFallbackPosted.set(false)
        awaitingCancelFinish = false
        cancelFinishInProgress.set(false)
        configureRetryAttempts = 0
        cancelOnComplete = null
        transactionStage1Received = false
        abortFallbackRetries = 0
        abortAwaitingLateStage1 = false
        ensureReadyAttempts = 0
        receipt.viaCliente = ""
        receipt.viaEstabelecimento = ""
        merchantReceiptPrinted = false
        transactionApproved = false
        approvedFinalizationStarted.set(false)
        receiptFinalizeScheduled = false
        cartaoMovimentoRegistrado = false
        resetTransactionResult()
        confirmingPendingAfterSuccess = false
        pendingConfirmQueue.clear()
        pendingConfirmOnComplete = null
        pendingConfirmFinishParams = ""

        try {
            TefPreferences.loadModuloIniIfExists(activity)
            CliSiTefAssetInstaller.ensureInstalled(activity)
            CliSiTefAssetInstaller.syncTransacoesHabilitadas(activity)

            if (TefPreferences.getSitefConfigureAddress(activity).isBlank() ||
                TefPreferences.getStoreId(activity).isBlank() ||
                TefPreferences.getTerminalId(activity).isBlank()
            ) {
                finishWithError("Terminal TEF não configurado. Salve a configuração antes da venda.")
                return
            }
            if (operationMode == TefOperationMode.ADMIN_REPRINT &&
                operator.filter { it.isDigit() }.isBlank() &&
                TefPreferences.getOperator(activity).filter { it.isDigit() }.isBlank()
            ) {
                finishWithError(activity.getString(R.string.tef_admin_operator_required))
                return
            }

            cliSiTef.setActivity(activity)
            cliSiTef.setDebug(true)
            GertecPinpadBootstrap.ensureGediReady(activity)

            runSale(functionId, amount, operator, restrictions)
        } catch (error: Throwable) {
            finishWithError("Erro ao iniciar TEF: ${error.message}")
        }
    }

    private fun runSale(
        functionId: Int,
        amount: String,
        operator: String,
        restrictions: String
    ) {
        val now = Date()
        taxInvoiceDate = SimpleDateFormat("yyyyMMdd", Locale.US).format(now)
        taxInvoiceTime = SimpleDateFormat("HHmmss", Locale.US).format(now)
        taxInvoiceNumber = TefPendingFiscal.nextCoupon(activity)
        val transactionParameters = buildStartTransactionParameters(restrictions)
        additionalParameters = transactionParameters.ifBlank {
            TefPreferences.getSitefConfigureAdditionalParams(activity)
        }

        ensureCliSiTefReady {
            configureAndStart(functionId, amount, operator, transactionParameters)
        }
    }

    private fun configureAndStart(
        functionId: Int,
        amount: String,
        operator: String,
        restrictions: String
    ) {
        onStatus("Configurando CliSiTef…")

        val sitefAddress = TefPreferences.getSitefConfigureAddress(activity)
        val additionalParams = TefPreferences.getSitefConfigureAdditionalParams(activity)
        onStatus("SiTef: $sitefAddress")

        val configureCode = cliSiTef.configure(
            sitefAddress,
            TefPreferences.getStoreId(activity),
            TefPreferences.getTerminalId(activity),
            additionalParams
        )

        if (configureCode == -12) {
            if (configureRetryAttempts >= 3) {
                finishWithError(describeConfigureError(configureCode))
                return
            }
            configureRetryAttempts++
            Log.w(TAG, "configure=-12, limpando sessão (tentativa $configureRetryAttempts)")
            TefSessionGuard.markSessionDirty()
            forceClearSession {
                configureAndStart(functionId, amount, operator, restrictions)
            }
            return
        }

        if (configureCode != CliSiTef.CONFIG_OK) {
            finishWithError(describeConfigureError(configureCode))
            return
        }

        val trnAmount = TefAmountFormatter.toCliSiTefAmount(amount)
        val cashierOperator = formatOperatorForSitef(
            operator.ifBlank { TefPreferences.getOperator(activity) },
        )

        TefPendingFiscal.save(activity, taxInvoiceNumber, taxInvoiceDate, taxInvoiceTime)
        TefSessionGuard.markTransactionStarted(taxInvoiceNumber, taxInvoiceDate, taxInvoiceTime)
        onStatus("Iniciando transação TEF…")

        Log.d(
            TAG,
            "startTransaction fn=$functionId transacoes=[${TefPreferences.resolveTransacoesHabilitadas(activity)}] " +
                "params=[$restrictions]",
        )

        val startResult = cliSiTef.startTransaction(
            this,
            functionId,
            trnAmount,
            taxInvoiceNumber,
            taxInvoiceDate,
            taxInvoiceTime,
            cashierOperator,
            restrictions,
        )

        if (startResult != 0 && startResult != CliSiTefConstants.CONTINUA) {
            finishWithError(describeStartError(startResult))
        }
    }

    fun abort() {
        if (!running.get() || settled.get()) return
        dispatchOnMain { performAbort() }
    }

    private fun performAbort() {
        if (!abortRequested.compareAndSet(false, true)) return

        dismissActiveDialog()
        menuDialogOpen.set(false)
        onHidePixQrCode()
        showOperatorMessage("Cancelando transação…")

        try {
            cliSiTef.setActivity(activity)
            val result = cliSiTef.abortTransaction(-1)
            Log.d(TAG, "abortTransaction(-1)=$result")
            scheduleAbortFallback()
        } catch (error: Throwable) {
            Log.e(TAG, "abort failed", error)
            finalizeInteractiveFunction(
                reason = "abortException",
                fallbackMessage = "Operação cancelada."
            )
        }
    }

    private fun scheduleAbortFallback() {
        if (!abortFallbackPosted.compareAndSet(false, true)) return
        val runnable = Runnable {
            abortFallbackPosted.set(false)
            if (!running.get() || settled.get() || awaitingCancelFinish) return@Runnable
            if (transactionStage1Received) {
                Log.d(TAG, "abort fallback skip — stage1 já recebido, aguardando finalize")
                return@Runnable
            }
            if (abortFallbackRetries < ABORT_FALLBACK_MAX_RETRIES) {
                abortFallbackRetries++
                try {
                    cliSiTef.setActivity(activity)
                    cliSiTef.abortTransaction(-1)
                    Log.d(TAG, "abort fallback retry=$abortFallbackRetries before stage1")
                } catch (error: Throwable) {
                    Log.w(TAG, "abort fallback retry", error)
                }
                scheduleAbortFallback()
                return@Runnable
            }
            Log.w(TAG, "abort fallback exhausted without stage1 — aguardando onTransactionResult")
            abortAwaitingLateStage1 = true
            TefSessionGuard.markSessionDirty()
            finishWithError("Operação cancelada. Aguarde antes de nova venda.")
        }
        abortFallbackRunnable = runnable
        activity.window.decorView.postDelayed(runnable, ABORT_FALLBACK_MS)
    }

    private fun cancelAbortFallback() {
        abortFallbackRunnable?.let { activity.window.decorView.removeCallbacks(it) }
        abortFallbackRunnable = null
        abortFallbackPosted.set(false)
    }

    fun release() {
        cancelAbortFallback()
        cancelCancelSettlementWatchdog()
        cancelReceiptFinalize()
        cancelPendingConfirmWatchdog()
        dismissActiveDialog()
        confirmingPendingAfterSuccess = false
        pendingConfirmQueue.clear()
        pendingConfirmOnComplete = null
        if (!settled.get()) {
            TefSessionGuard.markSessionDirty()
            TefPendingFiscal.load(activity)?.let { fiscal ->
                TefSessionGuard.lastFiscal = fiscal
            }
        }
        running.set(false)
    }

    override fun onData(
        currentStage: Int,
        command: Int,
        fieldId: Int,
        minLength: Int,
        maxLength: Int,
        input: ByteArray?
    ) {
        val effectiveFieldId = resolveFieldId(fieldId)
        val buffer = if (isResultDataCommand(command)) {
            resolveResultBuffer(input)
        } else {
            decodeBuffer(input)
        }

        if (isResultDataCommand(command) || isReceiptField(effectiveFieldId)) {
            captureTransactionField(effectiveFieldId, buffer)
            captureReceiptField(effectiveFieldId, buffer)
            if (isReceiptField(effectiveFieldId)) {
                Log.d(
                    TAG,
                    "receipt capture stage=$currentStage cmd=$command field=$effectiveFieldId len=${buffer.length}"
                )
            } else if (buffer.isNotBlank() && effectiveFieldId != 0) {
                Log.d(
                    TAG,
                    "field capture stage=$currentStage cmd=$command field=$effectiveFieldId value=[$buffer]"
                )
            }
        }
        Log.d(TAG, "onData stage=$currentStage cmd=$command field=$effectiveFieldId buf=[$buffer]")
        dispatchOnMain {
            handleDataCommand(command, effectiveFieldId, buffer, minLength, maxLength)
        }
    }

    override fun onTransactionResult(currentStage: Int, resultCode: Int) {
        Log.d(TAG, "onTransactionResult stage=$currentStage code=$resultCode")
        dispatchOnMain {
            cancelAbortFallback()
            appendStatus("TEF estágio $currentStage → código $resultCode")
            when (currentStage) {
                CliSiTefConstants.STAGE_TRANSACTION -> {
                    transactionStage1Received = true
                    if (settled.get() && abortAwaitingLateStage1) {
                        abortAwaitingLateStage1 = false
                        Log.d(TAG, "late stage1=$resultCode após timeout abort — limpando sessão")
                        finalizeInteractiveFunction(
                            reason = "lateStage1:$resultCode",
                            fallbackMessage = "",
                            onComplete = { TefSessionGuard.markSessionClean() }
                        )
                    } else if (resultCode == 0) {
                        awaitingCancelFinish = false
                        transactionApproved = true
                        // Manual CliSiTef v2.21: comprovante (122) chega no onData do estágio 2 (finishTransaction).
                        confirmApprovedTransaction()
                    } else if (!settled.get()) {
                        val fallback = describeCancelResult(resultCode)
                        finalizeInteractiveFunction(
                            reason = "stage1:$resultCode",
                            fallbackMessage = fallback
                        )
                    }
                }

                CliSiTefConstants.STAGE_SETTLEMENT -> {
                    if (awaitingCancelFinish) {
                        completeCancelFinishAfterSettlement(resultCode)
                    } else if (confirmingPendingAfterSuccess) {
                        if (resultCode != 0) {
                            Log.w(
                                TAG,
                                "confirmPending stage2 code=$resultCode — tentando próxima pendência"
                            )
                        }
                        processNextPendingConfirmation()
                    } else if (resultCode == 0) {
                        // Deixa o onData do estágio 2 (comprovante) ser processado antes do pipeline.
                        activity.window.decorView.post {
                            flushPendingCliSiTefMessages()
                            runServerRegistrationPipeline()
                        }
                    } else {
                        finishWithError(describeTransactionError(resultCode))
                    }
                }

                else -> finishWithError("Estágio TEF desconhecido: $currentStage ($resultCode)")
            }
        }
    }

    private fun handleDataCommand(
        command: Int,
        fieldId: Int,
        buffer: String,
        minLength: Int,
        maxLength: Int
    ) {
        when (command) {
            CliSiTef.CMD_RESULT_DATA,
            CliSiTef.CMD_GET_FIELD_INTERNAL -> {
                val effectiveFieldId = resolveFieldId(fieldId)
                captureTransactionField(effectiveFieldId, buffer)
                captureReceiptField(effectiveFieldId, buffer)
                continueWith("")
            }

            CliSiTef.CMD_SHOW_MSG_CASHIER,
            CliSiTef.CMD_SHOW_MSG_CASHIER_CUSTOMER -> {
                showOperatorMessage(buffer)
                continueWith("")
            }

            CliSiTef.CMD_SHOW_MSG_CUSTOMER -> {
                if (buffer.isNotBlank()) appendStatus("Cliente: $buffer")
                continueWith("")
            }

            CliSiTef.CMD_SHOW_MENU_TITLE,
            CliSiTef.CMD_SHOW_HEADER -> {
                pendingMenuTitle = buffer.ifBlank { null }
                showOperatorMessage(buffer)
                continueWith("")
            }

            CliSiTef.CMD_CLEAR_MSG_CASHIER,
            CliSiTef.CMD_CLEAR_MSG_CASHIER_CUSTOMER -> {
                clearOperatorMessage()
                continueWith("")
            }

            CliSiTef.CMD_CLEAR_MSG_CUSTOMER,
            CliSiTef.CMD_CLEAR_MENU_TITLE,
            CliSiTef.CMD_CLEAR_HEADER -> continueWith("")

            CliSiTef.CMD_CONFIRMATION,
            CliSiTef.CMD_CONFIRM_GO_BACK -> {
                if (shouldAutoContinueDuringFinalize()) {
                    continueWith("0")
                    return
                }
                if (buffer.isNotBlank()) appendStatus(buffer)
                showConfirmationDialog(buffer) { which ->
                    continueWith(which.toString())
                }
            }

            CliSiTef.CMD_GET_MENU_OPTION -> {
                if (shouldAutoContinueDuringFinalize()) {
                    val firstOption = parseMenuOptions(buffer).firstOrNull()?.code ?: "1"
                    continueWith(firstOption)
                    return
                }
                showPaymentMenu(buffer)
            }

            CliSiTef.CMD_GET_FIELD_INTERNAL,
            CliSiTef.CMD_GET_PINPAD_CONFIRMATION -> {
                showOperatorMessage(buffer)
                continueWith("")
            }

            CliSiTef.CMD_GET_FIELD_CURRENCY -> {
                if (shouldAutoContinueDuringFinalize()) {
                    continueWith("")
                    return
                }
                showCurrencyInput(buffer, minLength, maxLength) { value ->
                    continueWith(value)
                }
            }

            CliSiTef.CMD_GET_FIELD,
            CliSiTef.CMD_GET_MASKED_FIELD,
            CliSiTef.CMD_GET_FIELD_BARCODE,
            CliSiTef.CMD_GET_FIELD_CHEQUE,
            CliSiTef.CMD_GET_FIELD_TRACK,
            CliSiTef.CMD_GET_FIELD_PASSWORD -> {
                if (shouldAutoContinueDuringFinalize()) {
                    continueWith("")
                    return
                }
                if (isSaleValuePrompt(buffer)) {
                    showCurrencyInput(buffer, minLength, maxLength) { value ->
                        continueWith(value)
                    }
                } else {
                    showInput(buffer, minLength, maxLength) { value ->
                        continueWith(value)
                    }
                }
            }

            CliSiTef.CMD_PRESS_ANY_KEY -> {
                if (shouldAutoContinueDuringFinalize()) {
                    continueWith("")
                    return
                }
                if (buffer.isNotBlank()) appendStatus(buffer)
                showConfirmationDialog(buffer) { which ->
                    continueWith(which.toString())
                }
            }

            CliSiTef.CMD_ABORT_REQUEST -> {
                showOperatorMessage(buffer)
                continueWith("")
            }

            CliSiTef.CMD_MESSAGE_QRCODE -> {
                if (buffer.isNotBlank()) {
                    onPixQrMessage(buffer)
                    appendStatus(buffer)
                }
                continueWith("")
            }

            CliSiTef.CMD_SHOW_QRCODE_FIELD -> {
                val qrPayload = buffer.ifBlank { cliSiTef.buffer.orEmpty() }
                if (qrPayload.isNotBlank()) {
                    onShowPixQrCode(qrPayload, null)
                    appendStatus("QR Code PIX exibido")
                }
                continueWith("")
            }

            CliSiTef.CMD_REMOVE_QRCODE_FIELD -> {
                onHidePixQrCode()
                continueWith("")
            }

            CliSiTefFieldIds.CMD_NUMERO_PARCELAS -> {
                if (buffer.isNotBlank()) {
                    captureParcelas(buffer)
                }
                if (shouldAutoContinueDuringFinalize()) {
                    continueWith(transactionResult.parcelas.ifBlank { "1" })
                    return
                }
                showInput(buffer.ifBlank { "Informe o número de parcelas:" }, minLength, maxLength) { value ->
                    captureParcelas(value)
                    continueWith(value.ifBlank { transactionResult.parcelas.ifBlank { "1" } })
                }
            }

            else -> {
                appendStatus("Cmd $command: $buffer")
                continueWith("")
            }
        }
    }

    private data class MenuOption(val code: String, val label: String)

    /**
     * Suporta buffers SiTef:
     * - `1:Debito;2:Cartao de Credito` (formato real do terminal)
     * - `1;Debito;2;Credito`
     * - lista simples separada por `;`
     */
    private fun parseMenuOptions(raw: String): List<MenuOption> {
        val tokens = raw.split(';').map { it.trim() }.filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return emptyList()

        if (tokens.any { it.contains(':') }) {
            return tokens.mapNotNull { token ->
                val colon = token.indexOf(':')
                if (colon <= 0) return@mapNotNull null
                val code = token.substring(0, colon).trim()
                val label = token.substring(colon + 1).trim().ifBlank { code }
                if (code.isEmpty()) null else MenuOption(code, label)
            }
        }

        if (tokens.size >= 2 && tokens.size % 2 == 0 && tokens[0].matches(Regex("\\d+"))) {
            return (0 until tokens.size / 2).map { index ->
                MenuOption(code = tokens[index * 2], label = tokens[index * 2 + 1])
            }
        }

        return tokens.map { MenuOption(code = it, label = it) }
    }

    private fun showPaymentMenu(menuBuffer: String) {
        if (!menuDialogOpen.compareAndSet(false, true)) return

        val raw = menuBuffer.ifBlank { cliSiTef.buffer.orEmpty() }
        val options = parseMenuOptions(raw)
        appendStatus("Menu SiTef recebido")

        if (options.isEmpty()) {
            menuDialogOpen.set(false)
            continueWith("1")
            return
        }

        appendStatus("Escolha a forma de pagamento")

        if (activity.isFinishing) {
            menuDialogOpen.set(false)
            return
        }

        val title = pendingMenuTitle?.ifBlank { null } ?: "Forma de pagamento"
        pendingMenuTitle = null

        val dialogView = LayoutInflater.from(activity)
            .inflate(R.layout.dialog_tef_menu_options, null, false)
        dialogView.findViewById<TextView>(R.id.tvMenuTitle).text = title

        val buttonContainer = dialogView.findViewById<LinearLayout>(R.id.containerMenuButtons)
        val inflater = LayoutInflater.from(activity)
        options.forEach { option ->
            val button = inflater.inflate(
                R.layout.item_tef_menu_option_button,
                buttonContainer,
                false
            ) as MaterialButton
            button.text = option.label
            button.setOnClickListener {
                menuDialogOpen.set(false)
                dismissActiveDialog()
                capturePaymentMenuSelection(option.label, option.code)
                showOperatorMessage(option.label)
                appendStatus("Opção selecionada: ${option.label} (código ${option.code})")
                continueWith(option.code)
            }
            buttonContainer.addView(button)
        }

        dialogView.findViewById<MaterialButton>(R.id.btnMenuCancel).setOnClickListener {
            menuDialogOpen.set(false)
            dismissActiveDialog()
            abort()
        }

        showTrackedDialog(
            AlertDialog.Builder(activity)
                .setView(dialogView)
                .setCancelable(false)
                .setOnDismissListener { menuDialogOpen.set(false) }
        )
    }

    private fun showConfirmationDialog(message: String, onAnswer: (Int) -> Unit) {
        if (activity.isFinishing) return
        showTrackedDialog(
            AlertDialog.Builder(activity)
                .setMessage(message.ifBlank { "Continuar?" })
                .setCancelable(false)
                .setPositiveButton("OK") { _, _ -> onAnswer(0) }
                .setNegativeButton("Cancelar") { _, _ -> abort() }
        )
    }

    @Suppress("UNUSED_PARAMETER")
    private fun showInput(
        prompt: String,
        minLength: Int,
        maxLength: Int,
        onValue: (String) -> Unit
    ) {
        val input = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            if (maxLength > 0) {
                filters = arrayOf(android.text.InputFilter.LengthFilter(maxLength))
            }
        }
        showTrackedDialog(
            AlertDialog.Builder(activity)
                .setMessage(prompt.ifBlank { "Informe o dado solicitado:" })
                .setView(input)
                .setPositiveButton("OK") { _, _ -> onValue(input.text.toString()) }
                .setNegativeButton("Cancelar") { _, _ -> abort() }
        )
    }

    /** Campo monetário — máscara `1.234,56` (Delphi `FormatarMoeda`). */
    @Suppress("UNUSED_PARAMETER")
    private fun showCurrencyInput(
        prompt: String,
        minLength: Int,
        maxLength: Int,
        onValue: (String) -> Unit
    ) {
        val input = EditText(activity)
        MoneyInputMask.apply(input)
        showTrackedDialog(
            AlertDialog.Builder(activity)
                .setMessage(prompt.ifBlank { "Informe o valor:" })
                .setView(input)
                .setPositiveButton("OK") { _, _ ->
                    val masked = MoneyInputMask.getFormattedText(input)
                    onValue(TefAmountFormatter.toSitefCurrencyDigits(masked))
                }
                .setNegativeButton("Cancelar") { _, _ -> abort() }
        )
    }

    private fun isSaleValuePrompt(prompt: String): Boolean {
        val lower = prompt.lowercase(Locale.getDefault())
        return lower.contains("valor") || lower.contains("r$")
    }

    private fun showTrackedDialog(builder: AlertDialog.Builder): AlertDialog {
        dismissActiveDialog()
        val dialog = builder.create()
        activeDialog = dialog
        dialog.setOnDismissListener {
            if (activeDialog === dialog) activeDialog = null
        }
        dialog.show()
        return dialog
    }

    private fun dismissActiveDialog() {
        try {
            activeDialog?.dismiss()
        } catch (_: Throwable) {
            // Diálogo já fechado.
        }
        activeDialog = null
    }

    /** API v2: continueTransaction(dado) — igual exemplo Fiserv CliSiTefExemplo. */
    private fun continueWith(data: String) {
        dispatchOnMain {
            if (!running.get() || activity.isFinishing) return@dispatchOnMain
            try {
                cliSiTef.setActivity(activity)
                val result = cliSiTef.continueTransaction(data)
                Log.d(TAG, "continueTransaction([$data])=$result")
                when (result) {
                    CliSiTefConstants.CONTINUA -> appendStatus("Processando…")
                    0 -> appendStatus("continue OK")
                    else -> {
                        if (result < 0) {
                            finishWithError(describeTransactionError(result))
                        } else {
                            appendStatus("continue=$result")
                        }
                    }
                }
            } catch (error: Throwable) {
                Log.e(TAG, "continueTransaction native error", error)
                if (!settled.get()) {
                    finishWithError(describeNativeError(error))
                }
            }
        }
    }

    private fun dispatchOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            activity.runOnUiThread(block)
        }
    }

    private fun appendStatus(line: String) {
        if (line.isBlank()) return
        onStatus(line)
    }

    private fun showOperatorMessage(message: String) {
        if (message.isBlank()) return
        appendStatus(message)
        onOperatorMessage(message)
    }

    private fun clearOperatorMessage() {
        onOperatorMessage(null)
    }

    /** Durante encerramento/cancelamento/confirmação de pendências, não abrir diálogos. */
    private fun shouldAutoContinueDuringFinalize(): Boolean =
        abortRequested.get() || awaitingCancelFinish || confirmingPendingAfterSuccess

    /**
     * Garante CliSiTef livre antes de nova venda. O erro -12 no configure pode ocorrer
     * mesmo com [getQttPendingTransactions] = 0 (processo iterativo não encerrado).
     */
    private fun ensureCliSiTefReady(onReady: () -> Unit) {
        try {
            cliSiTef.setActivity(activity)
            cliSiTef.submitPendingMessages()

            val probeCode = probeConfigure()
            val pendingRef = findPendingFiscalRef()
            Log.d(
                TAG,
                "ensureReady probe=$probeCode needsCleanup=${TefSessionGuard.needsCleanup} " +
                    "pendingRef=${pendingRef?.coupon} attempt=$ensureReadyAttempts"
            )

            if (probeCode == -12 && ensureReadyAttempts < ENSURE_READY_MAX_ATTEMPTS) {
                ensureReadyAttempts++
                showOperatorMessage("Aguardando liberação do terminal TEF…")
                activity.window.decorView.postDelayed(
                    { ensureCliSiTefReady(onReady) },
                    ENSURE_READY_RETRY_MS
                )
                return
            }
            ensureReadyAttempts = 0

            when {
                probeCode == CliSiTef.CONFIG_OK && !TefSessionGuard.needsCleanup && pendingRef == null -> {
                    onReady()
                }
                probeCode == -12 || TefSessionGuard.needsCleanup || pendingRef != null -> {
                    showOperatorMessage("Liberando terminal TEF…")
                    forceClearSession(onReady)
                }
                else -> onReady()
            }
        } catch (error: Throwable) {
            Log.w(TAG, "ensureCliSiTefReady", error)
            onReady()
        }
    }

    private fun probeConfigure(): Int {
        return cliSiTef.configure(
            TefPreferences.getSitefConfigureAddress(activity),
            TefPreferences.getStoreId(activity),
            TefPreferences.getTerminalId(activity),
            TefPreferences.getSitefConfigureAdditionalParams(activity)
        )
    }

    /**
     * Após aprovação (stage 2), confirma cupons ainda pendentes no host SiTef
     * antes de imprimir comprovante e encerrar a venda.
     */
    private fun confirmRemainingPendingTransactions(onComplete: () -> Unit) {
        try {
            cliSiTef.setActivity(activity)
            cliSiTef.submitPendingMessages()
            pendingConfirmFinishParams = additionalParameters.ifBlank {
                TefPreferences.getSitefConfigureAdditionalParams(activity)
            }
            pendingConfirmQueue.clear()
            pendingConfirmOnComplete = onComplete

            collectKnownFiscalRefs().forEach { ref ->
                val count = countPendingTransactionsSafe(ref)
                Log.d(TAG, "pendingAfterSuccess cupom=${ref.coupon} count=$count")
                if (count > 0) {
                    pendingConfirmQueue.addLast(ref)
                }
            }

            if (pendingConfirmQueue.isEmpty()) {
                pendingConfirmOnComplete = null
                onComplete()
                return
            }

            confirmingPendingAfterSuccess = true
            appendStatus("Confirmando ${pendingConfirmQueue.size} transação(ões) pendente(s)…")
            showOperatorMessage("Confirmando transações pendentes…")
            schedulePendingConfirmWatchdog()
            processNextPendingConfirmation()
        } catch (error: Throwable) {
            Log.e(TAG, "confirmRemainingPendingTransactions native error", error)
            confirmingPendingAfterSuccess = false
            cancelPendingConfirmWatchdog()
            pendingConfirmOnComplete = null
            onComplete()
        }
    }

    private fun processNextPendingConfirmation() {
        val ref = pendingConfirmQueue.pollFirst()
        if (ref == null) {
            confirmingPendingAfterSuccess = false
            cancelPendingConfirmWatchdog()
            appendStatus("Transações pendentes confirmadas.")
            pendingConfirmOnComplete?.invoke()
            pendingConfirmOnComplete = null
            return
        }

        Log.d(TAG, "confirmPending start cupom=${ref.coupon}")
        when (val syncResult = confirmPendingTransactionSync(ref)) {
            0 -> processNextPendingConfirmation()
            CliSiTefConstants.CONTINUA, -12 -> {
                val asyncResult = cliSiTef.finishTransaction(
                    this,
                    CliSiTefConstants.CONFIRM_TRANSACTION,
                    ref.coupon,
                    ref.date,
                    ref.time,
                    pendingConfirmFinishParams
                )
                Log.d(TAG, "confirmPending async cupom=${ref.coupon} result=$asyncResult")
                when (asyncResult) {
                    0 -> processNextPendingConfirmation()
                    CliSiTefConstants.CONTINUA, -12 -> Unit
                    else -> {
                        Log.w(TAG, "confirmPending async failed cupom=${ref.coupon} code=$asyncResult")
                        processNextPendingConfirmation()
                    }
                }
            }
            else -> {
                Log.w(TAG, "confirmPending sync failed cupom=${ref.coupon} code=$syncResult")
                processNextPendingConfirmation()
            }
        }
    }

    private fun confirmPendingTransactionSync(ref: TefFiscalRef): Int {
        return try {
            cliSiTef.setActivity(activity)
            cliSiTef.submitPendingMessages()
            cliSiTef.finishTransaction(
                CliSiTefConstants.CONFIRM_TRANSACTION,
                ref.coupon,
                ref.date,
                ref.time,
                pendingConfirmFinishParams.ifBlank {
                    additionalParameters.ifBlank {
                        TefPreferences.getSitefConfigureAdditionalParams(activity)
                    }
                }
            )
        } catch (error: Throwable) {
            Log.e(TAG, "confirmPendingTransactionSync native error", error)
            -12
        }
    }

    private fun collectKnownFiscalRefs(): List<TefFiscalRef> {
        return buildList {
            addAll(TefPendingFiscal.loadHistory(activity))
            TefSessionGuard.lastFiscal?.let { add(it) }
            TefPendingFiscal.load(activity)?.let { add(it) }
            if (::taxInvoiceNumber.isInitialized) {
                add(TefFiscalRef(taxInvoiceNumber, taxInvoiceDate, taxInvoiceTime))
            }
        }.distinctBy { "${it.date}|${it.coupon}" }
    }

    private fun countPendingTransactionsSafe(ref: TefFiscalRef): Int {
        return try {
            cliSiTef.setActivity(activity)
            cliSiTef.getQttPendingTransactions(ref.date, ref.coupon)
        } catch (error: Throwable) {
            Log.w(TAG, "getQttPendingTransactions cupom=${ref.coupon}", error)
            0
        }
    }

    private fun findPendingFiscalRef(): TefFiscalRef? {
        val candidates = buildList {
            TefSessionGuard.lastFiscal?.let { add(it) }
            addAll(TefPendingFiscal.loadHistory(activity))
            TefPendingFiscal.load(activity)?.let { add(it) }
            add(TefFiscalRef(taxInvoiceNumber, taxInvoiceDate, taxInvoiceTime))
        }.distinctBy { "${it.date}|${it.coupon}" }

        return candidates.firstOrNull { ref ->
            val count = countPendingTransactionsSafe(ref)
            Log.d(TAG, "pendingCheck date=${ref.date} cupom=${ref.coupon} count=$count")
            count > 0
        }
    }

    private fun pickFiscalForCleanup(): TefFiscalRef {
        return findPendingFiscalRef()
            ?: TefSessionGuard.lastFiscal
            ?: TefPendingFiscal.load(activity)
            ?: TefFiscalRef(taxInvoiceNumber, taxInvoiceDate, taxInvoiceTime)
    }

    private fun forceClearSession(onDone: () -> Unit) {
        finalizeInteractiveFunction(reason = "forceClearStep1", fallbackMessage = "") {
            val pending = findPendingFiscalRef()
            if (pending != null) {
                cancelPendingFiscal(pending, onComplete = {
                    if (probeConfigure() == CliSiTef.CONFIG_OK) {
                        TefSessionGuard.markSessionClean()
                    }
                    onDone()
                })
            } else {
                resetInteractiveState("forceClearStep2")
                if (probeConfigure() == CliSiTef.CONFIG_OK) {
                    TefSessionGuard.markSessionClean()
                }
                onDone()
            }
        }
    }

    private enum class CancelFinishPurpose {
        USER_ABORT,
        PRE_START_CLEAR,
        CONFIGURE_RETRY,
    }

    /**
     * Encerra função iterativa após erro/cancelamento — espelha Delphi
     * `finalizaFuncaoSiTefInterativo(1, '', '', '', '')`.
     */
    private fun finalizeInteractiveFunction(
        reason: String,
        fallbackMessage: String,
        onComplete: (() -> Unit)? = null
    ) {
        if (!cancelFinishInProgress.compareAndSet(false, true)) {
            Log.d(TAG, "finalizeFunc ignored — in progress ($reason)")
            onComplete?.invoke()
            return
        }

        cancelFinishPurpose = if (onComplete != null) {
            CancelFinishPurpose.PRE_START_CLEAR
        } else {
            CancelFinishPurpose.USER_ABORT
        }
        cancelOnComplete = onComplete
        cancelFallbackMessage = fallbackMessage
        awaitingCancelFinish = true
        abortRequested.set(true)

        showOperatorMessage("Encerrando transação TEF…")

        try {
            cliSiTef.setActivity(activity)
            cliSiTef.submitPendingMessages()

            val syncResult = finalizeFunctionSync()
            Log.d(TAG, "finalizeFunc($reason) sync=$syncResult")
            when (syncResult) {
                0 -> completeCancelFinishImmediate()
                CliSiTefConstants.CONTINUA -> {
                    val asyncResult = cliSiTef.finishTransaction(
                        this,
                        CliSiTefConstants.CONFIRM_TRANSACTION,
                        "",
                        "",
                        "",
                        ""
                    )
                    Log.d(TAG, "finalizeFunc($reason) async=$asyncResult")
                    when (asyncResult) {
                        0 -> completeCancelFinishImmediate()
                        CliSiTefConstants.CONTINUA,
                        -12 -> {
                            Log.d(
                                TAG,
                                "finalizeFunc($reason) async=$asyncResult — aguardando stage2"
                            )
                            scheduleCancelSettlementWatchdog(
                                fallbackMessage.ifBlank { "Operação cancelada." }
                            )
                        }
                        else -> completeCancelFinishImmediate(
                            forceError = fallbackMessage.ifBlank { describeFinishError(asyncResult) }
                        )
                    }
                }
                -12 -> {
                    Log.d(TAG, "finalizeFunc($reason) sync=-12 — aguardando stage2")
                    scheduleCancelSettlementWatchdog(
                        fallbackMessage.ifBlank { "Operação cancelada." }
                    )
                }
                else -> completeCancelFinishImmediate(
                    forceError = fallbackMessage.ifBlank { describeFinishError(syncResult) }
                )
            }
        } catch (error: Throwable) {
            Log.w(TAG, "finalizeInteractiveFunction($reason)", error)
            completeCancelFinishImmediate(
                forceError = fallbackMessage.ifBlank { "Operação cancelada." }
            )
        }
    }

    /**
     * Cancela cupom pendente no host — `finishTransaction(0, cupom, data, hora, params)`.
     */
    private fun cancelPendingFiscal(
        fiscal: TefFiscalRef,
        onComplete: (() -> Unit)? = null
    ) {
        if (!cancelFinishInProgress.compareAndSet(false, true)) {
            Log.d(TAG, "cancelPending ignored — in progress")
            onComplete?.invoke()
            return
        }

        val finishParams = additionalParameters.ifBlank {
            TefPreferences.getSitefConfigureAdditionalParams(activity)
        }

        cancelFinishPurpose = CancelFinishPurpose.PRE_START_CLEAR
        cancelOnComplete = onComplete
        cancelFallbackMessage = ""
        awaitingCancelFinish = true

        showOperatorMessage("Cancelando transação pendente…")

        try {
            cliSiTef.setActivity(activity)
            cliSiTef.submitPendingMessages()

            val asyncResult = cliSiTef.finishTransaction(
                this,
                CliSiTefConstants.CANCEL_TRANSACTION,
                fiscal.coupon,
                fiscal.date,
                fiscal.time,
                finishParams
            )
            Log.d(
                TAG,
                "cancelPending cupom=${fiscal.coupon} date=${fiscal.date} result=$asyncResult"
            )
            when (asyncResult) {
                0 -> completeCancelFinishImmediate()
                CliSiTefConstants.CONTINUA,
                -12 -> scheduleCancelSettlementWatchdog("Operação cancelada.")
                else -> {
                    val syncResult = cancelPendingTransactionSync(
                        fiscal.coupon,
                        fiscal.date,
                        fiscal.time,
                        finishParams
                    )
                    when (syncResult) {
                        0 -> completeCancelFinishImmediate()
                        CliSiTefConstants.CONTINUA -> {
                            val retry = cliSiTef.finishTransaction(
                                this,
                                CliSiTefConstants.CANCEL_TRANSACTION,
                                fiscal.coupon,
                                fiscal.date,
                                fiscal.time,
                                finishParams
                            )
                            when (retry) {
                                0 -> completeCancelFinishImmediate()
                                CliSiTefConstants.CONTINUA ->
                                    scheduleCancelSettlementWatchdog("Operação cancelada.")
                                else -> completeCancelFinishImmediate(
                                    forceError = describeFinishError(retry)
                                )
                            }
                        }
                        else -> completeCancelFinishImmediate(
                            forceError = describeFinishError(asyncResult)
                        )
                    }
                }
            }
        } catch (error: Throwable) {
            Log.w(TAG, "cancelPendingFiscal", error)
            completeCancelFinishImmediate(forceError = "Erro ao cancelar pendência TEF.")
        }
    }

    private fun finalizeFunctionSync(): Int {
        return try {
            cliSiTef.setActivity(activity)
            cliSiTef.submitPendingMessages()
            cliSiTef.finishTransaction(
                CliSiTefConstants.CONFIRM_TRANSACTION,
                "",
                "",
                "",
                ""
            )
        } catch (error: Throwable) {
            Log.w(TAG, "finalizeFunctionSync", error)
            -1
        }
    }

    private fun completeCancelFinishImmediate(forceError: String? = null) {
        cancelCancelSettlementWatchdog()
        awaitingCancelFinish = false
        cancelFinishInProgress.set(false)

        val continuation = cancelOnComplete
        cancelOnComplete = null
        val purpose = cancelFinishPurpose
        cancelFinishPurpose = CancelFinishPurpose.USER_ABORT

        when (purpose) {
            CancelFinishPurpose.PRE_START_CLEAR,
            CancelFinishPurpose.CONFIGURE_RETRY -> {
                if (forceError == null) {
                    clearFiscalIfNoPending()
                }
                continuation?.invoke()
            }
            CancelFinishPurpose.USER_ABORT -> {
                if (forceError == null) {
                    clearFiscalIfNoPending()
                    TefSessionGuard.markSessionClean()
                }
                finishWithError(forceError ?: cancelFallbackMessage.ifBlank { "Operação cancelada." })
            }
        }
    }

    private fun completeCancelFinishAfterSettlement(resultCode: Int) {
        cancelCancelSettlementWatchdog()
        awaitingCancelFinish = false
        cancelFinishInProgress.set(false)

        val continuation = cancelOnComplete
        cancelOnComplete = null
        val purpose = cancelFinishPurpose
        val fallback = cancelFallbackMessage
        cancelFinishPurpose = CancelFinishPurpose.USER_ABORT
        cancelFallbackMessage = ""

        when (purpose) {
            CancelFinishPurpose.PRE_START_CLEAR,
            CancelFinishPurpose.CONFIGURE_RETRY -> {
                if (resultCode == 0) {
                    clearFiscalIfNoPending()
                }
                continuation?.invoke()
            }
            CancelFinishPurpose.USER_ABORT -> {
                if (resultCode == 0 || isBenignPinpadAbortCode(resultCode)) {
                    clearFiscalIfNoPending()
                    TefSessionGuard.markSessionClean()
                    finishWithError(fallback.ifBlank { "Operação cancelada." })
                } else {
                    TefSessionGuard.markSessionDirty()
                    finishWithError(describeTransactionError(resultCode))
                }
            }
        }
    }

    /**
     * Desarma estado iterativo preso (erro -12) — espelha Delphi
     * `finalizaFuncaoSiTefInterativo` com cupom/data vazios após erro.
     */
    private fun scheduleCancelSettlementWatchdog(fallbackMessage: String) {
        cancelCancelSettlementWatchdog()
        val runnable = Runnable {
            cancelSettlementWatchdogPosted = false
            if (settled.get() || !awaitingCancelFinish) return@Runnable
            Log.w(TAG, "cancel settlement watchdog timeout")
            awaitingCancelFinish = false
            cancelFinishInProgress.set(false)
            resetInteractiveState("watchdogTimeout")
            TefSessionGuard.markSessionDirty()
            finishWithError(fallbackMessage)
        }
        cancelSettlementWatchdogPosted = true
        cancelSettlementWatchdogRunnable = runnable
        activity.window.decorView.postDelayed(runnable, CANCEL_SETTLEMENT_TIMEOUT_MS)
    }

    private fun cancelCancelSettlementWatchdog() {
        cancelSettlementWatchdogRunnable?.let {
            activity.window.decorView.removeCallbacks(it)
        }
        cancelSettlementWatchdogRunnable = null
        cancelSettlementWatchdogPosted = false
    }

    private fun markSessionCleanIfProbeOk() {
        if (probeConfigure() == CliSiTef.CONFIG_OK) {
            TefSessionGuard.markSessionClean()
        } else {
            TefSessionGuard.markSessionDirty()
        }
    }

    private fun resetInteractiveState(reason: String) {
        val finishParams = additionalParameters.ifBlank {
            TefPreferences.getSitefConfigureAdditionalParams(activity)
        }
        try {
            cliSiTef.setActivity(activity)
            cliSiTef.submitPendingMessages()
            cliSiTef.abortTransaction(-1)

            val funcResult = finalizeFunctionSync()
            Log.d(TAG, "reset($reason) finalizeFunc=$funcResult")

            TefPendingFiscal.loadHistory(activity).forEach { ref ->
                val result = cancelPendingTransactionSync(
                    ref.coupon,
                    ref.date,
                    ref.time,
                    finishParams
                )
                Log.d(TAG, "reset($reason) cancel cupom=${ref.coupon} result=$result")
            }
        } catch (error: Throwable) {
            Log.w(TAG, "resetInteractiveState($reason)", error)
        }
    }

    private fun countKnownPendingTransactions(): Int {
        return try {
            cliSiTef.setActivity(activity)
            cliSiTef.submitPendingMessages()
            val refs = buildList {
                addAll(TefPendingFiscal.loadHistory(activity))
                TefPendingFiscal.load(activity)?.let { add(it) }
                if (::taxInvoiceNumber.isInitialized) {
                    add(TefFiscalRef(taxInvoiceNumber, taxInvoiceDate, taxInvoiceTime))
                }
            }.distinctBy { "${it.date}|${it.coupon}" }
            refs.sumOf { countPendingTransactionsSafe(it) }
        } catch (error: Throwable) {
            Log.w(TAG, "countKnownPendingTransactions", error)
            0
        }
    }

    private fun clearFiscalIfNoPending() {
        val saved = TefPendingFiscal.load(activity) ?: return
        try {
            cliSiTef.setActivity(activity)
            val pending = cliSiTef.getQttPendingTransactions(saved.date, saved.coupon)
            Log.d(TAG, "clearFiscalIfNoPending cupom=${saved.coupon} pending=$pending")
            if (pending <= 0) {
                TefPendingFiscal.clearAll(activity)
            }
        } catch (error: Throwable) {
            Log.w(TAG, "clearFiscalIfNoPending", error)
        }
    }

    /** Desarma pendência com finishTransaction(0) síncrono — não confirma a venda. */
    private fun cancelPendingTransactionSync(
        coupon: String = taxInvoiceNumber,
        date: String = taxInvoiceDate,
        time: String = taxInvoiceTime,
        finishParams: String = additionalParameters.ifBlank {
            TefPreferences.getSitefConfigureAdditionalParams(activity)
        }
    ): Int {
        if (!::taxInvoiceNumber.isInitialized) return -1
        return try {
            cliSiTef.setActivity(activity)
            cliSiTef.submitPendingMessages()
            cliSiTef.finishTransaction(
                CliSiTefConstants.CANCEL_TRANSACTION,
                coupon,
                date,
                time,
                finishParams
            )
        } catch (error: Throwable) {
            Log.w(TAG, "cancelPendingTransactionSync", error)
            try {
                cliSiTef.abortTransaction(-1)
            } catch (_: Throwable) {
                // Ignora.
            }
            -1
        }
    }

    private fun decodeBuffer(input: ByteArray?): String {
        if (input == null || input.isEmpty()) {
            return cliSiTef.buffer.orEmpty()
        }
        return String(input, charset).trim()
    }

    private fun describeConfigureError(code: Int): String = when (code) {
        CliSiTef.CONFIG_ERROR_INVALID_SITEF_ADDRESS -> "Endereço SiTef inválido ($code)."
        CliSiTef.CONFIG_ERROR_INVALID_STORE_ID -> "Código da loja inválido ($code)."
        CliSiTef.CONFIG_ERROR_INVALID_TERMINAL_ID -> "Terminal inválido ($code)."
        -12 -> "Transação TEF anterior pendente ($code). Reinicie o app."
        else -> "Erro configure CliSiTef: $code"
    }

    private fun describeStartError(code: Int): String = when (code) {
        -1 -> "Módulo CliSiTef não inicializado."
        -5 -> "Sem comunicação com o servidor SiTef."
        -43 -> describePinpadRoutineError()
        IPinPad.PINPAD_COMMUNICATION_ERROR,
        31 -> describePinpadCommunicationError()
        else -> "Falha ao iniciar TEF (código $code)."
    }

    private fun describeTransactionError(code: Int): String = when (code) {
        IPinPad.PINPAD_COMMUNICATION_ERROR,
        31 -> describePinpadCommunicationError()
        -43 -> describePinpadRoutineError()
        -2 -> "Operação cancelada."
        -5 -> "Sem comunicação com o servidor SiTef."
        -6 -> "Cancelado no pinpad."
        in 1..Int.MAX_VALUE -> "Transação negada (código $code)."
        else -> "Erro na transação TEF (código $code)."
    }

    /**
     * Mensagem ao encerrar após cancelamento do operador.
     * Pinpad pode devolver -43/-6/31 ao interromper leitura do cartão — não é falha real.
     */
    private fun describeCancelResult(code: Int): String {
        if (abortRequested.get() && isBenignPinpadAbortCode(code)) {
            return "Operação cancelada."
        }
        return describeTransactionError(code)
    }

    private fun isBenignPinpadAbortCode(code: Int): Boolean = when (code) {
        -2, -6, -43,
        IPinPad.PINPAD_COMMUNICATION_ERROR,
        31 -> true
        else -> false
    }

    private fun describePinpadCommunicationError(): String =
        "Erro pinpad (31). Verifique FactoryService Gertec."

    private fun describePinpadRoutineError(): String =
        "Erro pinpad (-43). Verifique FactoryService Gertec."

    private fun formatOperatorForSitef(raw: String): String {
        val digits = raw.filter { it.isDigit() }
        if (digits.isEmpty()) return "001"
        return digits.takeLast(3).padStart(3, '0')
    }

    /** Delphi `transacoesHabilitadas` no Intent / m-SiTef `restricoes`. */
    private fun buildStartTransactionParameters(explicitRestrictions: String): String {
        return TefClsitConfig.mergeStartTransactionParameters(
            explicitRestrictions = explicitRestrictions,
            transacoesHabilitadas = TefPreferences.resolveTransacoesHabilitadas(activity),
        )
    }

    private fun describeFinishError(code: Int): String = when (code) {
        -12 -> "Erro ao confirmar TEF: transação anterior pendente."
        else -> "Erro ao finalizar TEF: $code"
    }

    private fun describeNativeError(error: Throwable): String {
        val message = error.message?.trim().orEmpty()
        return if (message.contains("native", ignoreCase = true)) {
            "Erro interno CliSiTef. Aguarde e tente novamente."
        } else if (message.isNotBlank()) {
            "Erro TEF: $message"
        } else {
            "Erro interno CliSiTef."
        }
    }

    private fun resolveFieldId(fieldId: Int): Int {
        if (fieldId != 0) return fieldId
        return try {
            cliSiTef.fieldId
        } catch (_: Throwable) {
            fieldId
        }
    }

    private fun resolveResultBuffer(input: ByteArray?): String {
        if (input != null && input.isNotEmpty()) {
            return String(input, charset)
        }
        val buffer = cliSiTef.buffer.orEmpty()
        if (buffer.isNotEmpty()) return buffer
        val rx = cliSiTef.rxData
        if (rx != null && rx.isNotEmpty()) {
            return String(rx, charset)
        }
        return ""
    }

    private fun isReceiptField(fieldId: Int): Boolean =
        fieldId == CliSiTefFieldIds.COMPROVANTE_CLIENTE ||
            fieldId == CliSiTefFieldIds.COMPROVANTE_ESTAB

    private fun isResultDataCommand(command: Int): Boolean =
        command == CliSiTef.CMD_RESULT_DATA ||
            command == CliSiTef.CMD_GET_FIELD_INTERNAL

    private fun harvestCliSiTefPendingField() {
        try {
            val fieldId = resolveFieldId(cliSiTef.fieldId)
            val buffer = resolveResultBuffer(null)
            if (fieldId != 0 && buffer.isNotBlank()) {
                captureTransactionField(fieldId, buffer)
                captureReceiptField(fieldId, buffer)
            }
        } catch (error: Throwable) {
            Log.w(TAG, "harvestCliSiTefPendingField", error)
        }
    }

    private fun flushPendingCliSiTefMessages() {
        try {
            cliSiTef.setActivity(activity)
            cliSiTef.submitPendingMessages()
            harvestCliSiTefPendingField()
        } catch (error: Throwable) {
            Log.w(TAG, "flushPendingCliSiTefMessages", error)
        }
    }

    private fun flushPendingCliSiTefMessagesBlocking() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            flushPendingCliSiTefMessages()
            return
        }
        val latch = CountDownLatch(1)
        activity.runOnUiThread {
            try {
                flushPendingCliSiTefMessages()
            } finally {
                latch.countDown()
            }
        }
        latch.await(3, TimeUnit.SECONDS)
    }

    private fun scheduleFinalizeWithReceiptOnce() {
        if (receiptFinalizeScheduled) return
        receiptFinalizeScheduled = true
        scheduleFinalizeWithReceipt()
    }

    private fun schedulePendingConfirmWatchdog() {
        cancelPendingConfirmWatchdog()
        val runnable = Runnable {
            pendingConfirmWatchdogRunnable = null
            if (!confirmingPendingAfterSuccess) return@Runnable
            Log.w(TAG, "pending confirm timeout — liberando fluxo de comprovante")
            confirmingPendingAfterSuccess = false
            pendingConfirmQueue.clear()
            pendingConfirmOnComplete?.invoke()
            pendingConfirmOnComplete = null
        }
        pendingConfirmWatchdogRunnable = runnable
        activity.window.decorView.postDelayed(runnable, PENDING_CONFIRM_TIMEOUT_MS)
    }

    private fun cancelPendingConfirmWatchdog() {
        pendingConfirmWatchdogRunnable?.let { activity.window.decorView.removeCallbacks(it) }
        pendingConfirmWatchdogRunnable = null
    }

    private fun resolvePaymentRegistrationFields(): Pair<String, String> {
        harvestCliSiTefPendingField()
        val nsu = transactionResult.nsuHost.trim()
            .ifBlank { transactionResult.nsuSitef.trim() }
        val hora = transactionResult.horaTransacao.trim()
            .ifBlank {
                if (::taxInvoiceTime.isInitialized) {
                    TefTransactionFieldParser.formatHoraFromTaxInvoiceTime(taxInvoiceTime)
                } else {
                    ""
                }
            }
            .ifBlank { TefTransactionFieldParser.formatCurrentHora() }
        return nsu to hora
    }

    fun getTransactionResult(): TefTransactionResult = transactionResult

    private fun resetTransactionResult() {
        transactionResult.dataTransacao = ""
        transactionResult.sitefDataHoraRaw = ""
        transactionResult.horaTransacao = ""
        transactionResult.dataPreDatado = ""
        transactionResult.sitefPreDatadoRaw = ""
        transactionResult.parcelas = "0"
        transactionResult.codTrans = ""
        transactionResult.redeAut = ""
        transactionResult.bandeira = ""
        transactionResult.tipoParc = ""
        transactionResult.nsuSitef = ""
        transactionResult.nsuHost = ""
        transactionResult.nsuTransacaoOriginal = ""
        transactionResult.valorCancelamento = ""
        transactionResult.codAutorizacao = ""
        transactionResult.chaveNota = ""
    }

    private fun capturePaymentMenuSelection(label: String, menuCode: String) {
        transactionResult.codTrans = TefTransactionFieldParser.inferCodTransFromPaymentLabel(label, menuCode)
        transactionResult.tipoParc = TefTransactionFieldParser.inferTipoParcFromPaymentLabel(label)
        if (transactionResult.parcelas.isBlank()) {
            transactionResult.parcelas = "0"
        }
    }

    private fun captureParcelas(raw: String) {
        val value = raw.trim()
        transactionResult.parcelas = if (value.isBlank()) "0" else value
    }

    private fun captureTransactionField(fieldId: Int, buffer: String) {
        val value = buffer.trim()
        if (value.isBlank()) return

        if (TipoCamposJsonParser.applyTo(transactionResult, value)) {
            Log.d(
                TAG,
                "TIPO_CAMPOS capturado nsuOriginal=[${transactionResult.nsuTransacaoOriginal}] " +
                    "nsuCancel=[${transactionResult.nsuHost}] campo105=[${transactionResult.sitefDataHoraRaw}]",
            )
            return
        }

        when (fieldId) {
            CliSiTefFieldIds.NSU_SITEF -> transactionResult.nsuSitef = value
            CliSiTefFieldIds.NSU_HOST -> transactionResult.nsuHost = value
            CliSiTefFieldIds.NSU_TRANSACAO_ORIGINAL,
            CliSiTefFieldIds.NSU_TRANSACAO_ORIGINAL_ALT -> {
                if (value.length >= transactionResult.nsuTransacaoOriginal.length) {
                    transactionResult.nsuTransacaoOriginal = value
                }
            }
            CliSiTefFieldIds.VALOR_CANCELAMENTO -> {
                if (value.length >= transactionResult.valorCancelamento.length) {
                    transactionResult.valorCancelamento = value
                }
            }
            CliSiTefFieldIds.CODIGO_AUTORIZACAO -> transactionResult.codAutorizacao = value
            CliSiTefFieldIds.CODIGO_REDE -> transactionResult.redeAut = value
            CliSiTefFieldIds.TIPO_CARTAO -> transactionResult.bandeira = value
            CliSiTefFieldIds.PARCELAS -> captureParcelas(value)
            CliSiTefFieldIds.DATA_HORA_SITEF -> {
                transactionResult.sitefDataHoraRaw = value.filter { it.isDigit() }
                val (data, hora) = TefTransactionFieldParser.parseDataHoraSitef(value)
                if (data.isNotBlank()) transactionResult.dataTransacao = data
                if (hora.isNotBlank()) transactionResult.horaTransacao = hora
            }
            CliSiTefFieldIds.DATA_PREDATADO -> {
                transactionResult.sitefPreDatadoRaw = value.filter { it.isDigit() }
                val data = TefTransactionFieldParser.parseDataPreDatado(value)
                if (data.isNotBlank()) transactionResult.dataPreDatado = data
            }
        }
    }

    private fun captureReceiptField(fieldId: Int, buffer: String) {
        val text = formatCupom(buffer)
        if (text.isBlank()) return
        when (fieldId) {
            CliSiTefFieldIds.COMPROVANTE_CLIENTE -> {
                if (text.length >= receipt.viaCliente.length) {
                    receipt.viaCliente = text
                }
            }
            CliSiTefFieldIds.COMPROVANTE_ESTAB -> {
                if (text.length >= receipt.viaEstabelecimento.length) {
                    receipt.viaEstabelecimento = text
                    Log.d(TAG, "via estabelecimento capturada len=${text.length}")
                    synchronized(merchantReceiptLock) {
                        merchantReceiptLock.notifyAll()
                    }
                }
            }
        }
    }

    private fun formatCupom(raw: String): String {
        if (raw.isBlank()) return ""
        var result = raw.replace("\\n", "\n")
        repeat(4) {
            result = result.replace("\n\n\n\n\n", "\n")
        }
        return result
    }

    private fun scheduleFinalizeWithReceipt() {
        cancelReceiptFinalize()
        activity.runOnUiThread {
            flushPendingCliSiTefMessages()
            pollReceiptsAndFinalize(attempt = 0)
        }
    }

    private fun pollReceiptsAndFinalize(attempt: Int) {
        val delayMs = if (attempt == 0) RECEIPT_FINALIZE_DELAY_MS else RECEIPT_POLL_INTERVAL_MS
        val runnable = Runnable {
            receiptFinalizeRunnable = null
            if (settled.get()) return@Runnable

            flushPendingCliSiTefMessagesBlocking()

            val estabLen = receipt.viaEstabelecimento.length
            val clienteLen = receipt.viaCliente.length
            Log.d(
                TAG,
                "receipt poll attempt=$attempt estab=$estabLen cliente=$clienteLen"
            )

            if (!receipt.hasMerchantCopy() && attempt < RECEIPT_POLL_MAX_ATTEMPTS) {
                pollReceiptsAndFinalize(attempt + 1)
                return@Runnable
            }

            finalizeSuccessfulTransactionWithReceipt()
        }
        receiptFinalizeRunnable = runnable
        activity.window.decorView.postDelayed(runnable, delayMs)
    }

    private fun cancelReceiptFinalize() {
        receiptFinalizeRunnable?.let { activity.window.decorView.removeCallbacks(it) }
        receiptFinalizeRunnable = null
    }

    private fun confirmApprovedTransaction() {
        showOperatorMessage("Confirmando transação…")
        val finishResult = cliSiTef.finishTransaction(
            this,
            CliSiTefConstants.CONFIRM_TRANSACTION,
            taxInvoiceNumber,
            taxInvoiceDate,
            taxInvoiceTime,
            additionalParameters.ifBlank {
                TefPreferences.getSitefConfigureAdditionalParams(activity)
            }
        )
        if (finishResult != 0 && finishResult != CliSiTefConstants.CONTINUA) {
            finishWithError(describeFinishError(finishResult))
        }
    }

    /**
     * Estágio 2 (Delphi [ResultadoTEF]): imprime via estabelecimento de forma bloqueante
     * e só depois confirma pendências / grava no servidor.
     */
    private fun runServerRegistrationPipeline() {
        if (!approvedFinalizationStarted.compareAndSet(false, true)) {
            Log.w(TAG, "serverRegistrationPipeline já em execução")
            return
        }
        if (operationMode.isReceiptOnlyAdminFlow()) {
            runReceiptOnlyAdminPipeline()
            return
        }

        Thread({
            try {
                onHidePixQrCode()
                ensureMerchantReceiptPrintedBeforeServer()

                val confirmLatch = CountDownLatch(1)
                activity.runOnUiThread {
                    if (operationMode.skipsPendingConfirmOnFinalize()) {
                        TefSessionGuard.markSessionClean()
                        TefPendingFiscal.clearAll(activity)
                        confirmLatch.countDown()
                    } else {
                        confirmRemainingPendingTransactions {
                            TefSessionGuard.markSessionClean()
                            TefPendingFiscal.clearAll(activity)
                            confirmLatch.countDown()
                        }
                    }
                }
                confirmLatch.await(2, TimeUnit.MINUTES)

                if (!merchantReceiptPrinted) {
                    ensureMerchantReceiptPrintedBeforeServer()
                }
                blockServerUntilMerchantReceiptPrinted()

                val serverStatusMessage = if (operationMode.skipsServerRegistration()) {
                    null
                } else {
                    when (operationMode) {
                        TefOperationMode.ADMIN_CANCELLATION -> {
                            activity.runOnUiThread {
                                showOperatorMessage(activity.getString(R.string.tef_admin_cancellation_saving))
                            }
                            registrarCancelamentoNoServidor()
                        }
                        TefOperationMode.SALE -> {
                            activity.runOnUiThread {
                                showOperatorMessage("Registrando venda no servidor…")
                            }
                            registrarVendaNoServidor()
                        }
                        else -> null
                    }
                }
                activity.runOnUiThread {
                    serverStatusMessage?.let { appendStatus(it) }
                    proceedAfterServerRegistration()
                }
            } catch (error: Throwable) {
                Log.e(TAG, "serverRegistrationPipeline", error)
                activity.runOnUiThread {
                    appendStatus("Erro ao finalizar: ${error.message ?: error.javaClass.simpleName}")
                }
                blockServerUntilMerchantReceiptPrinted()
                if (!operationMode.skipsServerRegistration()) {
                    runCatching {
                        when (operationMode) {
                            TefOperationMode.ADMIN_CANCELLATION -> registrarCancelamentoNoServidor()
                            TefOperationMode.SALE -> registrarVendaNoServidor()
                            else -> null
                        }
                    }.onSuccess { serverMessage ->
                        activity.runOnUiThread {
                            serverMessage?.let { appendStatus(it) }
                            proceedAfterServerRegistration()
                        }
                    }.onFailure { serverError ->
                        Log.e(TAG, "serverRegistrationPipeline retry", serverError)
                        activity.runOnUiThread { proceedAfterServerRegistration() }
                    }
                } else {
                    activity.runOnUiThread { proceedAfterServerRegistration() }
                }
            }
        }, "tef-server-pipeline").start()
    }

    /**
     * Reimpressão (114) e administrativo (110) — sem servidor; não bloqueia 45s aguardando via 122.
     */
    private fun runReceiptOnlyAdminPipeline() {
        Thread({
            try {
                onHidePixQrCode()
                activity.runOnUiThread {
                    TefSessionGuard.markSessionClean()
                    TefPendingFiscal.clearAll(activity)
                }
                captureAdminReceiptsBriefly()
                printAdminReceiptsIfAvailable()
            } catch (error: Throwable) {
                Log.e(TAG, "receiptOnlyAdminPipeline", error)
                activity.runOnUiThread {
                    appendStatus("Erro ao finalizar: ${error.message ?: error.javaClass.simpleName}")
                }
            } finally {
                activity.runOnUiThread { completeTransactionFlow() }
            }
        }, "tef-admin-receipt-pipeline").start()
    }

    private fun captureAdminReceiptsBriefly() {
        val deadline = SystemClock.elapsedRealtime() + ADMIN_RECEIPT_CAPTURE_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            flushPendingCliSiTefMessagesBlocking()
            if (receipt.hasMerchantCopy() || receipt.hasCustomerCopy()) {
                return
            }
            Thread.sleep(ADMIN_RECEIPT_POLL_MS)
        }
        flushPendingCliSiTefMessagesBlocking()
    }

    /** Delphi reimpressão: via cliente e depois estabelecimento, se existirem. */
    private fun printAdminReceiptsIfAvailable() {
        when (operationMode) {
            TefOperationMode.ADMIN_REPRINT -> {
                if (receipt.hasCustomerCopy()) {
                    activity.runOnUiThread {
                        showOperatorMessage(activity.getString(R.string.tef_printing_customer))
                    }
                    printReceiptBlocking(receipt.viaCliente, "cliente")
                }
                if (receipt.hasMerchantCopy() && !merchantReceiptPrinted) {
                    printMerchantReceiptBlocking()
                }
            }
            TefOperationMode.ADMIN_MENU -> {
                if (receipt.hasMerchantCopy()) {
                    printMerchantReceiptBlocking()
                } else if (receipt.hasCustomerCopy()) {
                    activity.runOnUiThread {
                        showOperatorMessage(activity.getString(R.string.tef_printing_customer))
                    }
                    printReceiptBlocking(receipt.viaCliente, "cliente")
                }
            }
            else -> Unit
        }
        if (!receipt.hasMerchantCopy() && !receipt.hasCustomerCopy()) {
            Log.d(TAG, "adminReceipt: nenhum comprovante capturado mode=$operationMode")
            activity.runOnUiThread {
                appendStatus("SiTef concluído sem comprovante para impressão no PDV.")
            }
        }
    }

    private fun printReceiptBlocking(text: String, label: String) {
        if (text.isBlank()) return
        val latch = CountDownLatch(1)
        activity.runOnUiThread {
            try {
                GertecReceiptPrinter.printReceipt(activity, text)
                appendStatus("Via do $label enviada à impressora.")
            } catch (error: Throwable) {
                Log.w(TAG, "printReceiptBlocking $label", error)
                val mapped = GertecReceiptPrinter.mapPrintError(error)
                val message = mapped.message?.ifBlank { null } ?: "Erro ao imprimir comprovante."
                appendStatus("Erro ao imprimir via do $label: $message")
                showOperatorMessage(message)
            } finally {
                latch.countDown()
            }
        }
        latch.await(2, TimeUnit.MINUTES)
    }

    /**
     * Aguarda o SiTef liberar o comprovante (campo 122, estágio 2) e imprime na UI thread.
     */
    private fun ensureMerchantReceiptPrintedBeforeServer() {
        if (merchantReceiptPrinted) return

        activity.runOnUiThread {
            val messageRes = if (receipt.hasMerchantCopy()) {
                R.string.tef_printing_merchant
            } else {
                R.string.tef_waiting_receipt
            }
            showOperatorMessage(activity.getString(messageRes))
        }

        val deadline = SystemClock.elapsedRealtime() + MERCHANT_RECEIPT_MAX_WAIT_MS
        var attempt = 0
        while (!merchantReceiptPrinted && SystemClock.elapsedRealtime() < deadline) {
            flushPendingCliSiTefMessagesBlocking()
            if (receipt.hasMerchantCopy()) {
                printMerchantReceiptBlocking()
                if (merchantReceiptPrinted) {
                    Log.d(TAG, "ensureMerchantPrint ok attempt=$attempt")
                    return
                }
            }
            waitForMerchantReceiptSignal(
                if (attempt == 0) MERCHANT_RECEIPT_INITIAL_DELAY_MS else MERCHANT_RECEIPT_POLL_INTERVAL_MS
            )
            attempt++
        }

        if (!merchantReceiptPrinted && receipt.hasMerchantCopy()) {
            printMerchantReceiptBlocking()
        }
        if (!merchantReceiptPrinted) {
            Log.w(TAG, "ensureMerchantPrint: comprovante não disponível após espera")
        }
    }

    /** Não grava no servidor enquanto houver via do estabelecimento capturada e não impressa. */
    private fun blockServerUntilMerchantReceiptPrinted() {
        val deadline = SystemClock.elapsedRealtime() + MERCHANT_RECEIPT_BLOCK_SERVER_MS
        while (!merchantReceiptPrinted && receipt.hasMerchantCopy() &&
            SystemClock.elapsedRealtime() < deadline
        ) {
            Log.w(TAG, "bloqueando servidor — aguardando impressão da via estabelecimento")
            flushPendingCliSiTefMessagesBlocking()
            printMerchantReceiptBlocking()
            if (!merchantReceiptPrinted) {
                waitForMerchantReceiptSignal(MERCHANT_RECEIPT_POLL_INTERVAL_MS)
            }
        }
        if (!merchantReceiptPrinted && receipt.hasMerchantCopy()) {
            Log.e(TAG, "via estabelecimento não impressa — servidor bloqueado até timeout")
        }
    }

    private fun waitForMerchantReceiptSignal(timeoutMs: Long) {
        synchronized(merchantReceiptLock) {
            try {
                merchantReceiptLock.wait(timeoutMs)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    private fun printMerchantReceiptBlocking() {
        if (merchantReceiptPrinted) {
            Log.d(TAG, "printMerchantReceiptBlocking: já impressa")
            return
        }
        if (!receipt.hasMerchantCopy()) {
            Log.w(TAG, "printMerchantReceiptBlocking: comprovante estabelecimento vazio")
            return
        }

        val receiptText = receipt.viaEstabelecimento
        Log.d(TAG, "printMerchantReceiptBlocking: imprimindo len=${receiptText.length}")
        val latch = CountDownLatch(1)
        var printError: Throwable? = null
        activity.runOnUiThread {
            try {
                if (!merchantReceiptPrinted && receipt.hasMerchantCopy()) {
                    GertecReceiptPrinter.printReceipt(activity, receiptText)
                    merchantReceiptPrinted = true
                    appendStatus("Via do estabelecimento enviada à impressora.")
                    Log.d(TAG, "printMerchantReceiptBlocking ok")
                }
            } catch (error: Throwable) {
                merchantReceiptPrinted = false
                printError = error
                Log.w(TAG, "printMerchantReceiptBlocking", error)
                val mapped = GertecReceiptPrinter.mapPrintError(error)
                val message = mapped.message?.ifBlank { null } ?: "Erro ao imprimir via do estabelecimento."
                appendStatus("Erro ao imprimir via estabelecimento: $message")
                showOperatorMessage(message)
                Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
            } finally {
                latch.countDown()
            }
        }
        latch.await(2, TimeUnit.MINUTES)
        if (printError != null) {
            Log.w(TAG, "printMerchantReceiptBlocking falhou após latch")
        }
    }

    private fun finalizeSuccessfulTransactionWithReceipt() {
        if (approvedFinalizationStarted.get()) return
        runServerRegistrationPipeline()
    }

    private fun proceedAfterServerRegistration() {
        Log.d(
            TAG,
            "finalizeSuccessfulTransactionWithReceipt mode=$operationMode estab=${receipt.viaEstabelecimento.length} " +
                "cliente=${receipt.viaCliente.length} chaveNota=[${transactionResult.chaveNota}] " +
                "movimentoOk=$cartaoMovimentoRegistrado"
        )

        if (operationMode == TefOperationMode.ADMIN_CANCELLATION) {
            TefTerminalTotalsStore.recordCancelledAfterApproval(
                activity,
                resolveCancelledAmount(),
            )
        }

        when {
            operationMode.isReceiptOnlyAdminFlow() -> completeTransactionFlow()
            !receipt.hasMerchantCopy() && !receipt.hasCustomerCopy() -> {
                appendStatus("Comprovante não recebido do SiTef.")
                completeTransactionFlow()
            }
            else -> promptCustomerReceiptPrint()
        }
    }

    private fun TefOperationMode.isReceiptOnlyAdminFlow(): Boolean =
        this == TefOperationMode.ADMIN_REPRINT || this == TefOperationMode.ADMIN_MENU

    private fun TefOperationMode.skipsServerRegistration(): Boolean = isReceiptOnlyAdminFlow()

    private fun TefOperationMode.skipsPendingConfirmOnFinalize(): Boolean =
        this == TefOperationMode.ADMIN_CANCELLATION ||
            this == TefOperationMode.ADMIN_REPRINT ||
            this == TefOperationMode.ADMIN_MENU

    /**
     * Aguarda campos do cancelamento (Delphi `TIPO_CAMPOS` / estágio 2) antes do POST.
     */
    private fun waitForCancelamentoFieldsCaptured() {
        val deadline = SystemClock.elapsedRealtime() + CANCEL_FIELDS_MAX_WAIT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            flushPendingCliSiTefMessagesBlocking()
            val hasNsuOriginal = transactionResult.nsuTransacaoOriginal.isNotBlank()
            val hasNsuCancel = transactionResult.nsuHost.isNotBlank()
            if (hasNsuOriginal && hasNsuCancel) {
                return
            }
            Thread.sleep(CANCEL_FIELDS_POLL_MS)
        }
        flushPendingCliSiTefMessagesBlocking()
    }

    /**
     * Delphi `CancelaCartao` — grava cancelamento administrativo no servidor.
     */
    private fun registrarCancelamentoNoServidor(): String? {
        waitForCancelamentoFieldsCaptured()

        val nsuOriginal = transactionResult.nsuTransacaoOriginal.trim()
        val nsuCancel = transactionResult.nsuHost.trim()
        val raw105 = transactionResult.sitefDataHoraRaw.trim()
        val fallbackRaw = when {
            transactionResult.dataTransacao.isNotBlank() -> transactionResult.dataTransacao
            ::taxInvoiceDate.isInitialized -> taxInvoiceDate
            else -> ""
        }
        val dataCancelamento = TefTransactionFieldParser.ensureDataSitefDotted(
            raw105.ifBlank { fallbackRaw },
        )

        Log.d(
            TAG,
            "registrarCancelamentoNoServidor pdv=[${TefPreferences.getOperator(activity)}] " +
                "nsuOriginal=[$nsuOriginal] nsuCancel=[$nsuCancel] " +
                "campo105=[$raw105] fallback=[$fallbackRaw] datasitef=[$dataCancelamento] " +
                "valor=[${transactionResult.valorCancelamento}]",
        )

        if (nsuOriginal.isBlank() || nsuCancel.isBlank()) {
            return "NSU do cancelamento não capturado — servidor não atualizado."
        }
        if (!dataCancelamento.contains('.') || dataCancelamento.filter { it.isDigit() }.length < 8) {
            return "Data SiTef não capturada — cancelamento não registrado no servidor."
        }

        return try {
            val result = cartaoRepository.cancelaCartao(
                pdv = TefPreferences.getOperator(activity),
                data = dataCancelamento,
                nsuHost = nsuOriginal,
                nsuCanc = nsuCancel,
            )
            if (result.success) {
                activity.getString(R.string.tef_admin_cancellation_saved)
            } else {
                result.message.ifBlank { "Erro ao salvar cancelamento no servidor." }
            }
        } catch (error: Throwable) {
            Log.w(TAG, "registrarCancelamentoNoServidor", error)
            error.message ?: "Erro ao salvar cancelamento no servidor."
        }
    }

    private fun resolveCancelledAmount(): Double {
        val fromField146 = transactionResult.valorCancelamento.trim()
        if (fromField146.isNotBlank()) {
            RestJsonParser.parseDecimalString(fromField146).takeIf { it > 0.0 }?.let { return it }
        }

        val fromSale = TefAmountFormatter.toCliSiTefAmount(saleAmount).toLongOrNull() ?: 0L
        if (fromSale > 0L) return fromSale / 100.0

        val receiptText = when {
            receipt.viaEstabelecimento.isNotBlank() -> receipt.viaEstabelecimento
            receipt.viaCliente.isNotBlank() -> receipt.viaCliente
            else -> return 0.0
        }
        val normalized = receiptText.uppercase(Locale.getDefault()).replace("\\n", "\n")
        val valueIndex = normalized.indexOf("VALOR")
        if (valueIndex < 0) return 0.0

        val fragment = normalized.substring(valueIndex + 5).trim()
        val commaIndex = fragment.indexOf(',')
        if (commaIndex < 0) return 0.0

        val digitsBeforeComma = fragment.substring(0, commaIndex).filter { it.isDigit() || it == '.' }
        val centsPart = fragment.substring(commaIndex + 1).take(2).filter { it.isDigit() }
        val normalizedAmount = "$digitsBeforeComma.$centsPart"
        return normalizedAmount.toDoubleOrNull() ?: 0.0
    }

    /**
     * Grava abastecimentos + `cartao_movimento` após a via do estabelecimento.
     * Retorna mensagem de status (ou null se tudo OK) e preenche [transactionResult.chaveNota].
     */
    private fun registrarVendaNoServidor(): String? {
        val (nsuHost, horaTransacao) = resolvePaymentRegistrationFields()
        Log.d(
            TAG,
            "registrarVendaNoServidor abastecimentos=${saleAbastecimentos.size} " +
                "nsuHost=[$nsuHost] hora=[$horaTransacao] " +
                "valor=[$saleAmount] operador=[$saleOperator]"
        )

        var abastecimentoOk = true
        return try {
            if (saleAbastecimentos.isNotEmpty()) {
                if (nsuHost.isBlank()) {
                    abastecimentoOk = false
                    "NSU TEF não capturado — abastecimentos não registrados no servidor."
                } else {
                    abastecimentoRepository.registrarAbastecimentosPagamento(
                        abastecimentos = saleAbastecimentos,
                        cartaonsu = nsuHost,
                        cartaohora = horaTransacao,
                    )
                }
            }

            if (abastecimentoOk) {
                if (nsuHost.isBlank()) {
                    error("NSU TEF não capturado — movimento de cartão não registrado.")
                }
                val request = CartaoMovimentoPostRequest.fromTefSale(
                    pdv = TefPreferences.getOperator(activity),
                    terminal = TefPreferences.getTerminalId(activity),
                    saleOperator = saleOperator,
                    saleAmount = saleAmount,
                    customerCode = saleContext.customerCode,
                    cpfCnpj = saleContext.cpfCnpj,
                    vehicle = saleContext.vehicle,
                    km = saleContext.km,
                    result = transactionResult,
                    nsuHost = nsuHost,
                    horaSitef = horaTransacao,
                    taxInvoiceDate = taxInvoiceDate,
                )
                val movimento = cartaoRepository.registrarCartaoMovimento(request)
                cartaoMovimentoRegistrado = movimento.registrado
                transactionResult.chaveNota = movimento.chaveNota
            }

            buildList {
                if (saleAbastecimentos.isNotEmpty() && abastecimentoOk) {
                    add("Abastecimentos registrados no servidor.")
                }
                if (abastecimentoOk) {
                    add("Movimento de cartão registrado no servidor.")
                    if (transactionResult.chaveNota.isNotBlank()) {
                        add("Chave da nota: ${transactionResult.chaveNota}")
                    }
                }
            }.joinToString(separator = "\n").ifBlank { null }
        } catch (error: Throwable) {
            Log.w(TAG, "registrarVendaNoServidor", error)
            val message = error.message?.ifBlank { null }
                ?: "Erro ao registrar venda no servidor."
            activity.runOnUiThread {
                Toast.makeText(
                    activity,
                    "Erro ao salvar venda — venda pode não entrar no caixa.",
                    Toast.LENGTH_LONG
                ).show()
            }
            message
        }
    }

    private fun promptCustomerReceiptPrint() {
        if (!receipt.hasCustomerCopy()) {
            completeTransactionFlow()
            return
        }

        if (activity.isFinishing) {
            completeTransactionFlow()
            return
        }

        showTrackedDialog(
            AlertDialog.Builder(activity)
                .setTitle(R.string.tef_print_customer_title)
                .setMessage(R.string.tef_print_customer_message)
                .setCancelable(false)
                .setPositiveButton(R.string.yes) { dialog, _ ->
                    dialog.dismiss()
                    showOperatorMessage(activity.getString(R.string.tef_printing_customer))
                    printReceiptAsync(receipt.viaCliente) {
                        activity.runOnUiThread { completeTransactionFlow() }
                    }
                }
                .setNegativeButton(R.string.no) { dialog, _ ->
                    dialog.dismiss()
                    completeTransactionFlow()
                }
        )
    }

    /** Delphi: após comprovantes TEF, pergunta impressão do cupom fiscal (somente venda). */
    private fun completeTransactionFlow() {
        val successMessage = when (operationMode) {
            TefOperationMode.ADMIN_CANCELLATION ->
                activity.getString(R.string.tef_admin_cancellation_done)
            TefOperationMode.ADMIN_REPRINT ->
                activity.getString(R.string.tef_admin_reprint_done)
            TefOperationMode.ADMIN_MENU ->
                activity.getString(R.string.tef_admin_menu_done)
            TefOperationMode.SALE -> "Transação TEF concluída com sucesso."
        }
        if (activity.isFinishing) {
            finishWithSuccess(successMessage)
            return
        }
        if (operationMode == TefOperationMode.ADMIN_CANCELLATION ||
            operationMode == TefOperationMode.ADMIN_REPRINT ||
            operationMode == TefOperationMode.ADMIN_MENU
        ) {
            finishWithSuccess(successMessage)
            return
        }
        if (transactionResult.chaveNota.isBlank() && !cartaoMovimentoRegistrado) {
            finishWithSuccess(successMessage)
            return
        }
        promptNfeCupomPrint()
    }

    private fun promptNfeCupomPrint() {
        showTrackedDialog(
            AlertDialog.Builder(activity)
                .setTitle(R.string.tef_print_nfe_title)
                .setMessage(R.string.tef_print_nfe_message)
                .setCancelable(false)
                .setPositiveButton(R.string.yes) { dialog, _ ->
                    dialog.dismiss()
                    printNfeCupomAsync {
                        finishWithSuccess("Transação TEF concluída com sucesso.")
                    }
                }
                .setNegativeButton(R.string.no) { dialog, _ ->
                    dialog.dismiss()
                    finishWithSuccess("Transação TEF concluída com sucesso.")
                }
        )
    }

    private fun printNfeCupomAsync(onComplete: () -> Unit) {
        val chaveNota = transactionResult.chaveNota
        if (chaveNota.isBlank()) {
            val message = activity.getString(R.string.tef_print_nfe_chave_missing)
            appendStatus(message)
            showOperatorMessage(message)
            Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
            onComplete()
            return
        }

        showOperatorMessage(activity.getString(R.string.tef_printing_nfe))
        val waitDialog = WaitDialog.show(activity, R.string.tef_printing_nfe)

        Thread({
            try {
                Log.d(
                    TAG,
                    "printNfeCupom modelo=${NfeChaveAccessKey.modeloFromChave(chaveNota)} " +
                        "tipo=${NfeChaveAccessKey.documentLabel(chaveNota)}"
                )
                val xmlBytes = cartaoRepository.downloadNfeXml(chaveNota)
                val receipt = NfeXmlReceiptFormatter.formatForThermalPrinter(
                    xmlBytes = xmlBytes,
                    chaveNota = chaveNota,
                )
                GertecReceiptPrinter.printNfeCupom(activity, receipt)
                activity.runOnUiThread {
                    appendStatus(activity.getString(R.string.tef_print_nfe_success))
                }
            } catch (error: Throwable) {
                Log.w(TAG, "printNfeCupomAsync", error)
                val mapped = GertecReceiptPrinter.mapPrintError(error)
                val message = mapped.message?.ifBlank { null }
                    ?: error.message
                    ?: activity.getString(R.string.tef_print_nfe_error, "")
                activity.runOnUiThread {
                    appendStatus(activity.getString(R.string.tef_print_nfe_error, message))
                    showOperatorMessage(message)
                    Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
                }
            } finally {
                activity.runOnUiThread {
                    waitDialog.dismiss()
                    onComplete()
                }
            }
        }, "tef-print-nfe").start()
    }

    private fun printReceiptAsync(text: String, onComplete: () -> Unit) {
        Thread({
            try {
                GertecReceiptPrinter.printReceipt(activity, text)
                activity.runOnUiThread {
                    appendStatus("Comprovante enviado à impressora.")
                }
            } catch (error: Throwable) {
                Log.w(TAG, "printReceiptAsync", error)
                val mapped = GertecReceiptPrinter.mapPrintError(error)
                val message = mapped.message?.ifBlank { null } ?: "Erro ao imprimir comprovante."
                activity.runOnUiThread {
                    appendStatus("Erro ao imprimir: $message")
                    showOperatorMessage(message)
                    Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
                }
            } finally {
                activity.runOnUiThread { onComplete() }
            }
        }, "tef-print-receipt").start()
    }

    private fun finishWithSuccess(message: String) {
        if (!settled.compareAndSet(false, true)) return
        cancelAbortFallback()
        cancelReceiptFinalize()
        cancelPendingConfirmWatchdog()
        dismissActiveDialog()
        running.set(false)
        onHidePixQrCode()
        onOperatorMessage(message)
        onFinished(true, message, transactionResult)
    }

    private fun finishWithError(message: String) {
        if (!settled.compareAndSet(false, true)) return
        cancelAbortFallback()
        cancelReceiptFinalize()
        cancelPendingConfirmWatchdog()
        dismissActiveDialog()
        running.set(false)
        onHidePixQrCode()
        onOperatorMessage(message)
        onFinished(false, message, null)
    }

    companion object {
        private const val TAG = "CliSiTefCtrl"
        private const val ABORT_FALLBACK_MS = 800L
        /** ~16s de abort antes do timeout — pinpad pode levar ~6s para retornar stage1. */
        private const val ABORT_FALLBACK_MAX_RETRIES = 20
        private const val CANCEL_SETTLEMENT_TIMEOUT_MS = 8000L
        private const val ENSURE_READY_RETRY_MS = 2000L
        private const val ENSURE_READY_MAX_ATTEMPTS = 5
        private const val RECEIPT_FINALIZE_DELAY_MS = 2500L
        private const val RECEIPT_POLL_INTERVAL_MS = 1000L
        private const val RECEIPT_POLL_MAX_ATTEMPTS = 10
        private const val MERCHANT_RECEIPT_INITIAL_DELAY_MS = 200L
        private const val MERCHANT_RECEIPT_POLL_INTERVAL_MS = 500L
        private const val MERCHANT_RECEIPT_MAX_WAIT_MS = 45_000L
        private const val MERCHANT_RECEIPT_BLOCK_SERVER_MS = 60_000L
        private const val PENDING_CONFIRM_TIMEOUT_MS = 8000L
        private const val CANCEL_FIELDS_MAX_WAIT_MS = 8_000L
        private const val CANCEL_FIELDS_POLL_MS = 150L
        private const val ADMIN_RECEIPT_CAPTURE_MS = 3_000L
        private const val ADMIN_RECEIPT_POLL_MS = 120L

        fun isSdkPresent(): Boolean {
            return try {
                Class.forName("br.com.softwareexpress.sitef.android.CliSiTef")
                true
            } catch (_: Throwable) {
                false
            }
        }
    }
}
