package com.appsitef.smartpos.tef

import android.app.AlertDialog
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.text.method.PasswordTransformationMethod
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
import com.appsitef.smartpos.sales.SaleAmountRules
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
    /** Voltar: encerra a Activity TEF e pede reabertura do menu de pagamento. */
    private val onReopenPaymentMenu: () -> Unit = {},
) : ICliSiTefListener {

    private val charset: Charset = Charset.forName("ISO-8859-1")
    private val running = AtomicBoolean(false)
    private val settled = AtomicBoolean(false)
    private val menuDialogOpen = AtomicBoolean(false)
    private var pendingMenuTitle: String? = null
    private var lastOperatorPrompt: String = ""
    /**
     * Última mensagem “útil” do SiTef (negação / código de resposta do emissor).
     * Não sobrescrever com “Operação cancelada” genérico no stage1.
     */
    private var lastMeaningfulSitefMessage: String = ""
    /** Após "Erro de simulação" / negação: não pedir Confirmar/Cancelar nem senha supervisor. */
    private val denialFlowActive = AtomicBoolean(false)
    private var supervisorPasswordPending = false
    private var supervisorPasswordMinLength = 0
    /** Após escolher opção de menu na venda, permite Voltar (Continua=1 / reabrir menu). */
    private val backToPreviousMenuAvailable = AtomicBoolean(false)
    private val goingBackToPreviousMenu = AtomicBoolean(false)
    private val goBackRecoverInProgress = AtomicBoolean(false)
    /** true enquanto aguarda inserir/aproximar cartão — Voltar fica oculto. */
    private val awaitingCardPresentment = AtomicBoolean(false)
    /** Fluxo débito/crédito ativo (inclui menus à vista/parcelas até o cartão). */
    private val cardPaymentFlowActive = AtomicBoolean(false)
    /** Após À Vista/parcelas: GetPIN em curso — atrasa resposta ao CMD_ABORT. */
    private val pinEntryArmed = AtomicBoolean(false)
    private var goBackFailsafeRunnable: Runnable? = null
    private var goBackHardTimeoutRunnable: Runnable? = null
    private val mainHandler = android.os.Handler(Looper.getMainLooper())
    /** Solta o pinpad que trava o toque no menu crédito/débito do GPOS720. */
    private var menuTouchWatchdog: Thread? = null
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
    private var lastFunctionId: Int = CliSiTefConstants.FUNCTION_MENU
    private var lastRestrictions: String = ""
    private var cartaoMovimentoRegistrado = false
    private var confirmingPendingAfterSuccess = false
    private val pendingConfirmQueue = ArrayDeque<TefFiscalRef>()
    private var pendingConfirmOnComplete: (() -> Unit)? = null
    private var pendingConfirmFinishParams: String = ""
    /** Confirma (1) ou estorna (0) o cupom no finishTransaction do estágio 2. */
    private var pendingFinalizeConfirm: Int = CliSiTefConstants.CONFIRM_TRANSACTION
    /** Marca que a venda foi estornada por falha de impressão do comprovante TEF. */
    private var saleReversedForPrintFailure = false
    /**
     * Encerra o iterativo preso (erro -12) com finishTransaction(1) e cupom vazio.
     * Não estorna o cupom da venda já aprovada.
     */
    private var closingLeftoverInteractive = false
    private var ignoreStrayInteractive = false
    private var leftoverInteractiveOnDone: (() -> Unit)? = null
    private var leftoverCloseWatchdogRunnable: Runnable? = null
    /** ADMIN_PENDING: houve ao menos uma pendência confirmada neste fluxo. */
    private var pendingTransactionsWereResolved = false

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
        TefTraceLog.write(activity, "startSale mode=$operationMode fn=$functionId amount=$amount")
        if (running.getAndSet(true)) return
        saleAmount = amount
        saleOperator = operator

        if (operationMode == TefOperationMode.SALE) {
            val saleLimitCents = SaleAmountRules.netTotalCents(saleAbastecimentos)
            val amountCents = MoneyInputMask.parseToCents(amount)
            if (SaleAmountRules.exceedsLimitCents(amountCents, saleLimitCents)) {
                running.set(false)
                finishWithError(
                    activity.getString(
                        R.string.error_sale_value_exceeds_total,
                        MoneyInputMask.formatFromCents(saleLimitCents),
                    )
                )
                return
            }
        }

        if (operationMode == TefOperationMode.ADMIN_PENDING) {
            showOperatorMessage(activity.getString(R.string.tef_pending_checking))
        } else if (operationMode == TefOperationMode.SALE) {
            showOperatorMessage(activity.getString(R.string.tef_connecting_server))
        }

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
        pendingFinalizeConfirm = CliSiTefConstants.CONFIRM_TRANSACTION
        saleReversedForPrintFailure = false
        pendingTransactionsWereResolved = false
        closingLeftoverInteractive = false
        ignoreStrayInteractive = false
        leftoverInteractiveOnDone = null
        cancelLeftoverCloseWatchdog()
        lastOperatorPrompt = ""
        lastMeaningfulSitefMessage = ""
        denialFlowActive.set(false)
        supervisorPasswordPending = false
        supervisorPasswordMinLength = 0
        // Mantém goingBackToPreviousMenu se estivermos reabrindo após Voltar (suprimir diálogos).
        if (!goingBackToPreviousMenu.get()) {
            goBackRecoverInProgress.set(false)
            backToPreviousMenuAvailable.set(false)
            awaitingCardPresentment.set(false)
            cardPaymentFlowActive.set(false)
            cancelGoBackFailsafe()
        }
        lastFunctionId = functionId
        lastRestrictions = restrictions

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
            // Pendência só confirma cupom no host. Abrir o pinpad aqui (PP_Open no
            // GPOS700) derruba a sessão e a limpeza seguinte estorna a venda.
            if (operationMode != TefOperationMode.ADMIN_PENDING) {
                if (GertecTefPinpad.shouldInitGediBeforeSale()) {
                    GertecPinpadBootstrap.ensureGediReady(activity)
                }
                GertecTefPinpad.prepare(activity)
            }

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
        lastFunctionId = functionId
        lastRestrictions = restrictions
        val now = Date()
        taxInvoiceDate = SimpleDateFormat("yyyyMMdd", Locale.US).format(now)
        taxInvoiceTime = SimpleDateFormat("HHmmss", Locale.US).format(now)
        taxInvoiceNumber = TefPendingFiscal.nextCoupon(activity)
        val transactionParameters = buildStartTransactionParameters(restrictions)
        // finishTransaction usa params do configure (com colchetes), não as restrições.
        additionalParameters = TefPreferences.getSitefConfigureAdditionalParams(activity)

        // GPOS700: não sondar configure/getQtt antes do start (abort nativo).
        // GPOS720: ensureReady; TLS/GSurf (COMEXTERNA 1/2) já pulam probe+abort.
        if (GertecTefPinpad.shouldProbeAndLiberateBeforeSale()) {
            ensureCliSiTefReady {
                configureAndStart(functionId, amount, operator, transactionParameters)
            }
        } else {
            if (TefSessionGuard.needsCleanup) {
                Thread({
                    unlockStuckInteractive()
                    TefSessionGuard.markSessionClean()
                    activity.runOnUiThread {
                        if (!activity.isFinishing) {
                            configureAndStart(functionId, amount, operator, transactionParameters)
                        }
                    }
                }, "tef-gpos700-unlock").start()
            } else {
                configureAndStart(functionId, amount, operator, transactionParameters)
            }
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
        val numeroTerminal = TefPreferences.getTerminalId(activity)
        val additionalParams = TefPreferences.getSitefConfigureAdditionalParams(activity)
        onStatus("SiTef: $sitefAddress | Terminal: $numeroTerminal")

        runConfigureAndStart(
            functionId = functionId,
            amount = amount,
            operator = operator,
            restrictions = restrictions,
            additionalParams = additionalParams,
        )
    }

    private fun runConfigureAndStart(
        functionId: Int,
        amount: String,
        operator: String,
        restrictions: String,
        additionalParams: String,
    ) {
        traceTef("configure begin")
        val configureCode = cliSiTef.configure(
            TefPreferences.getSitefConfigureAddress(activity),
            TefPreferences.getStoreId(activity),
            TefPreferences.getTerminalId(activity),
            additionalParams,
        )

        traceTef("configure=$configureCode")
        if (configureCode == -12) {
            if (configureRetryAttempts >= 3) {
                finishWithError(
                    if (operationMode == TefOperationMode.ADMIN_PENDING) {
                        describePendingConfigureError(configureCode)
                    } else {
                        describeConfigureError(configureCode)
                    }
                )
                return
            }
            configureRetryAttempts++
            Log.w(
                TAG,
                "configure=-12, encerrando iterativo sem estornar (tentativa $configureRetryAttempts)",
            )
            closeLeftoverInteractive {
                if (!activity.isFinishing) {
                    configureAndStart(functionId, amount, operator, restrictions)
                }
            }
            return
        }

        if (configureCode != CliSiTef.CONFIG_OK) {
            finishWithError(
                if (operationMode == TefOperationMode.ADMIN_PENDING) {
                    describePendingConfigureError(configureCode)
                } else {
                    describeConfigureError(configureCode)
                }
            )
            return
        }

        // Tratamento de pendências (queda de energia): a função 130 apenas LISTA as
        // pendências (spec 5.5.2). A confirmação real é por documento fiscal via
        // finishTransaction(1, cupom, data, hora). Resolvemos direto por referência.
        if (operationMode == TefOperationMode.ADMIN_PENDING) {
            resolvePendingTransactions()
            return
        }

        val trnAmount = TefAmountFormatter.toCliSiTefAmount(amount)
        val operador = resolveOperatorForStart(operator)

        TefPendingFiscal.save(activity, taxInvoiceNumber, taxInvoiceDate, taxInvoiceTime)
        TefSessionGuard.markTransactionStarted(taxInvoiceNumber, taxInvoiceDate, taxInvoiceTime)

        beginStartTransaction(functionId, trnAmount, operador, restrictions)
    }

    private fun beginStartTransaction(
        functionId: Int,
        trnAmount: String,
        operador: String,
        restrictions: String,
    ) {
        ignoreStrayInteractive = false
        closingLeftoverInteractive = false
        onStatus("Iniciando transação TEF…")
        showOperatorMessage(activity.getString(R.string.tef_connecting_server))

        // TLS/GSurf ou GPOS700: nunca closePinPad antes do start — 2ª venda
        // corrompe refs JNI / aborta (SIGSEGV ou volta aos botões). Só setActivity.
        // GPOS720 sem TLS externa: resetPinpad após Voltar (Setis).
        val comExterna = TefPreferences.getTefComExterna(activity)
        if (isExternalTlsComExterna(comExterna) ||
            !GertecTefPinpad.allowsClosePinPadBeforeStart()
        ) {
            runCatching { cliSiTef.setActivity(activity) }
        } else if (GertecTefPinpad.shouldInitGediBeforeSale()) {
            resetPinpadForNewTransaction()
        } else {
            runCatching { cliSiTef.setActivity(activity) }
        }

        Log.d(
            TAG,
            "startTransaction fn=$functionId operador=[$operador] terminal=${TefPreferences.getTerminalId(activity)} " +
                "transacoes=[${TefPreferences.resolveTransacoesHabilitadas(activity)}] params=[$restrictions]",
        )

        traceTef(
            "start fn=$functionId amount=$trnAmount cupom=$taxInvoiceNumber op=$operador"
        )
        val startResult = cliSiTef.startTransaction(
            this,
            functionId,
            trnAmount,
            taxInvoiceNumber,
            taxInvoiceDate,
            taxInvoiceTime,
            operador,
            restrictions,
        )

        traceTef("startResult=$startResult")
        if (startResult != 0 && startResult != CliSiTefConstants.CONTINUA) {
            finishWithError(describeStartError(startResult))
        }
    }

    fun abort() {
        if (!running.get() || settled.get()) return
        dispatchOnMain { performAbort() }
    }

    /**
     * Retorna ao menu de pagamento: cancela a coleta atual em background e
     * reabre a tela TEF limpa (restart na mesma sessão travava em -12).
     */
    fun goBackToPreviousMenu() {
        dispatchOnMain { performGoBackToPreviousMenu() }
    }

    /**
     * Pede confirmação na Activity antes de abortar.
     * Se o usuário escolher Não, o fluxo TEF segue.
     */
    fun requestAbort() {
        dispatchOnMain {
            if (!running.get() || settled.get() || activity.isFinishing) return@dispatchOnMain
            if (abortRequested.get()) return@dispatchOnMain
            activity.confirmAndAbortTef()
        }
    }

    private fun performGoBackToPreviousMenu() {
        if (activity.isFinishing || operationMode != TefOperationMode.SALE) return
        if (!goingBackToPreviousMenu.compareAndSet(false, true)) return

        dismissActiveDialogQuietly()
        hideInlineMenuOptions()
        setBackButtonVisible(false)
        backToPreviousMenuAvailable.set(false)
        awaitingCancelFinish = false
        cancelFinishInProgress.set(false)
        cancelOnComplete = null
        abortRequested.set(false)
        cancelGoBackHardTimeout()
        // IMPORTANTE: manter running=true e NÃO fechar a Activity agora. Precisamos processar
        // os callbacks do abort até o onTransactionResult (estágio 1) para reabrir com a sessão
        // LIMPA. Reabrir antes disso deixa o iterativo pendente e trava o configure da próxima
        // sessão ("Liberando terminal TEF" sem sair).
        showOperatorMessage(activity.getString(R.string.tef_back_to_previous_menu_status))
        TefSessionGuard.markSessionDirty()

        // Cancela a espera de cartão / EMV em background (não bloqueia a UI). Os callbacks
        // seguintes são auto-respondidos (goingBackToPreviousMenu) até o onTransactionResult,
        // que chama recoverSaleToPreviousMenu → reabre o menu de pagamento com a sessão limpa.
        Thread({
            try {
                cliSiTef.setActivity(activity)
                val result = cliSiTef.abortTransaction(-1)
                Log.d(TAG, "goBack abortTransaction(-1)=$result")
            } catch (error: Throwable) {
                Log.w(TAG, "goBack background abort", error)
            }
        }, "tef-go-back-abort").start()

        scheduleGoBackFailsafe()
    }

    /**
     * Se o abort não produzir onTransactionResult a tempo, reabre o menu mesmo assim
     * (marcando a sessão como suja para a próxima liberar o terminal).
     */
    private fun scheduleGoBackFailsafe() {
        cancelGoBackFailsafe()
        val runnable = Runnable {
            goBackFailsafeRunnable = null
            if (activity.isFinishing || settled.get()) return@Runnable
            if (!goingBackToPreviousMenu.get()) return@Runnable
            Log.w(TAG, "goBack failsafe — onTransactionResult não chegou, reabrindo menu")
            finishGoBackAndReopenPayment()
        }
        goBackFailsafeRunnable = runnable
        mainHandler.postDelayed(runnable, GO_BACK_FAILSAFE_MS)
    }

    private fun finishGoBackAndReopenPayment() {
        if (activity.isFinishing) return
        cancelGoBackFailsafe()
        cancelGoBackHardTimeout()
        goBackRecoverInProgress.set(false)
        awaitingCancelFinish = false
        cancelFinishInProgress.set(false)
        cancelOnComplete = null
        abortRequested.set(false)
        running.set(false)
        TefSessionGuard.markSessionDirty()
        settled.set(true)
        dismissActiveDialogQuietly()
        onHidePixQrCode()
        onReopenPaymentMenu()
    }

    private fun dismissActiveDialogQuietly() {
        try {
            activeDialog?.setOnDismissListener(null)
            activeDialog?.dismiss()
        } catch (_: Throwable) {
            // Diálogo já fechado.
        }
        activeDialog = null
    }

    private fun cancelGoBackFailsafe() {
        goBackFailsafeRunnable?.let { mainHandler.removeCallbacks(it) }
        goBackFailsafeRunnable = null
    }

    private fun cancelGoBackHardTimeout() {
        goBackHardTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        goBackHardTimeoutRunnable = null
    }

    /** Reabre o pinpad virtual após cancelar leitura de cartão (só na UI thread). */
    private fun resetPinpadForNewTransaction() {
        try {
            cliSiTef.setActivity(activity)
            // TLS/GSurf: closePinPad na 2ª venda derruba o processo (SIGSEGV / tela branca).
            // GPOS700: closePinPad antes do start também aborta a sessão.
            if (GertecTefPinpad.allowsClosePinPadBeforeStart() &&
                !isExternalTlsComExterna(TefPreferences.getTefComExterna(activity))
            ) {
                runCatching {
                    val closeResult = cliSiTef.closePinPad()
                    Log.d(TAG, "closePinPad=$closeResult")
                }
            } else {
                Log.d(TAG, "resetPinpad: skip closePinPad (TLS/GPOS700)")
            }
            if (GertecTefPinpad.shouldInitGediBeforeSale()) {
                GertecPinpadBootstrap.ensureGediReady(activity)
            } else {
                GertecTefPinpad.prepare(activity)
            }
            cliSiTef.setActivity(activity)
        } catch (error: Throwable) {
            Log.w(TAG, "resetPinpadForNewTransaction", error)
        }
    }

    /**
     * Limpa sessão em background (não bloqueia UI) e chama [onReady] na main.
     */
    private fun liberateTerminalThen(onReady: () -> Unit) {
        showOperatorMessage("Liberando terminal TEF…")
        Thread({
            val finishParams = try {
                additionalParameters.ifBlank {
                    TefPreferences.getSitefConfigureAdditionalParams(activity)
                }
            } catch (_: Throwable) {
                ""
            }
            try {
                repeat(LIBERATE_MAX_ATTEMPTS) { attempt ->
                    runCatching { cliSiTef.submitPendingMessages() }
                    runCatching { cliSiTef.abortTransaction(-1) }
                    runCatching { finalizeFunctionSync() }
                    runCatching {
                        TefPendingFiscal.loadHistory(activity).forEach { ref ->
                            cliSiTef.finishTransaction(
                                CliSiTefConstants.CANCEL_TRANSACTION,
                                ref.coupon,
                                ref.date,
                                ref.time,
                                finishParams,
                            )
                        }
                    }
                    val code = runCatching { probeConfigure() }.getOrDefault(-12)
                    Log.d(TAG, "liberate bg attempt=${attempt + 1} probe=$code")
                    if (code == CliSiTef.CONFIG_OK) return@repeat
                    try {
                        Thread.sleep(LIBERATE_RETRY_MS)
                    } catch (_: InterruptedException) {
                        // ignore
                    }
                }
            } catch (error: Throwable) {
                Log.w(TAG, "liberateTerminalThen bg", error)
            } finally {
                TefSessionGuard.markSessionClean()
                activity.runOnUiThread {
                    awaitingCancelFinish = false
                    cancelFinishInProgress.set(false)
                    abortRequested.set(false)
                    if (!activity.isFinishing) {
                        resetPinpadForNewTransaction()
                    }
                    mainHandler.postDelayed(onReady, PINPAD_REARM_MS)
                }
            }
        }, "tef-liberate").start()
    }

    /** Mantido: se a coleta encerrar durante Voltar, ainda reabre o menu via Sales. */
    private fun recoverSaleToPreviousMenu(resultCode: Int) {
        Log.d(TAG, "recoverSaleToPreviousMenu stage1=$resultCode → reopen")
        finishGoBackAndReopenPayment()
    }

    private fun performAbort() {
        if (!abortRequested.compareAndSet(false, true)) return

        dismissActiveDialog()
        hideInlineMenuOptions()
        setBackButtonVisible(false)
        backToPreviousMenuAvailable.set(false)
        awaitingCardPresentment.set(false)
        cardPaymentFlowActive.set(false)
        onHidePixQrCode()
        showOperatorMessage("Cancelando transação…")

        try {
            cliSiTef.setActivity(activity)
            val result = cliSiTef.abortTransaction(-1)
            Log.d(TAG, "abortTransaction(-1)=$result")
            scheduleAbortFallback()
        } catch (error: Throwable) {
            Log.e(TAG, "abort failed", error)
            if (!settled.get()) {
                TefSessionGuard.markSessionDirty()
                finishInteractiveStageFailure(-1, "Operação cancelada.")
            }
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
        cancelGoBackFailsafe()
        cancelGoBackHardTimeout()
        dismissActiveDialog()
        hideInlineMenuOptions()
        confirmingPendingAfterSuccess = false
        pendingConfirmQueue.clear()
        pendingConfirmOnComplete = null
        cancelLeftoverCloseWatchdog()
        val leftoverDone = leftoverInteractiveOnDone
        leftoverInteractiveOnDone = null
        closingLeftoverInteractive = false
        leftoverDone?.invoke()
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
        traceTef(
            "onData stage=$currentStage cmd=$command field=$effectiveFieldId buf=${buffer.take(120)}"
        )

        // CMD 23: keepalive do pinpad (cartão e senha). Responder sync na thread do
        // listener — continue atrasado empilha threads e trava as teclas no GetPIN.
        if (command == CliSiTef.CMD_ABORT_REQUEST) {
            if (closingLeftoverInteractive || ignoreStrayInteractive) {
                continueTransactionSync("")
                return
            }
            val payload = if (abortRequested.get()) {
                traceTef("CMD_ABORT_REQUEST — operador já cancelou → interrompe (sync)")
                "1"
            } else {
                ""
            }
            continueTransactionSync(payload)
            return
        }

        dispatchOnMain {
            handleDataCommand(command, effectiveFieldId, buffer, minLength, maxLength)
        }
    }

    override fun onTransactionResult(currentStage: Int, resultCode: Int) {
        Log.d(TAG, "onTransactionResult stage=$currentStage code=$resultCode")
        traceTef("onTransactionResult stage=$currentStage code=$resultCode")
        dispatchOnMain {
            if (closingLeftoverInteractive) {
                Log.d(TAG, "closeLeftover stage=$currentStage code=$resultCode")
                finishLeftoverClose()
                return@dispatchOnMain
            }
            if (ignoreStrayInteractive) {
                Log.d(TAG, "stray stage=$currentStage code=$resultCode ignorado")
                return@dispatchOnMain
            }
            cancelAbortFallback()
            appendStatus("TEF estágio $currentStage → código $resultCode")
            when (currentStage) {
                CliSiTefConstants.STAGE_TRANSACTION -> {
                    transactionStage1Received = true
                    if (confirmingPendingAfterSuccess) {
                        // Confirmação de pendência (função por referência) pode encerrar já no
                        // estágio 1. Segue a fila de confirmação em vez de tratar como venda.
                        if (resultCode != 0) {
                            Log.w(TAG, "confirmPending stage1 code=$resultCode — próxima pendência")
                        }
                        processNextPendingConfirmation()
                    } else if (settled.get() && abortAwaitingLateStage1) {
                        abortAwaitingLateStage1 = false
                        Log.d(TAG, "late stage1=$resultCode após timeout abort — limpando sessão")
                        TefSessionGuard.markSessionClean()
                    } else if (resultCode == 0) {
                        awaitingCancelFinish = false
                        goingBackToPreviousMenu.set(false)
                        denialFlowActive.set(false)
                        transactionApproved = true
                        // Confirma no host antes de imprimir. A via do estabelecimento sai no estágio 2.
                        confirmApprovedTransaction()
                    } else if (!settled.get() && goingBackToPreviousMenu.get()) {
                        // Continua=1 encerrou — reabre menu sem diálogo "Operação cancelada".
                        cancelGoBackFailsafe()
                        recoverSaleToPreviousMenu(resultCode)
                    } else if (!settled.get()) {
                        val fallback = resolveStage1FailureMessage(resultCode)
                        // Painel: se já pediu "Retire o cartão", manter; a negação vai no OK.
                        if (!goingBackToPreviousMenu.get()) {
                            if (isRemoveCardPrompt(lastOperatorPrompt)) {
                                appendStatus(fallback)
                            } else {
                                showOperatorMessage(fallback)
                            }
                        }
                        finishInteractiveStageFailure(
                            resultCode,
                            resolveFinishErrorMessage(fallback)
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
                    } else if (saleReversedForPrintFailure) {
                        finishWithError(activity.getString(R.string.tef_sale_reversed_print_failure))
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
        // Cancelamento no pinpad: a msg "Operação cancelada" pode chegar ANTES do
        // onTransactionResult. Marcamos abort cedo para não abrir senha do supervisor.
        if (closingLeftoverInteractive || ignoreStrayInteractive) {
            continueWith("")
            return
        }
        if (isPinpadCancelMessage(buffer)) {
            markAbortFromPinpadCancel(buffer)
        }

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
                if (shouldSuppressInteractivePrompts() && isPasswordEntryPrompt(buffer)) {
                    // Após cancelar no pinpad, o SiTef ainda pode reenviar "Digite a senha".
                    appendStatus(buffer)
                    continueWith("")
                    return
                }
                noteDenialIfPresent(buffer)
                // Genéricos de cancelamento não sobrescrevem a negação no painel.
                // "Retire o cartão" aparece normalmente (cartão ainda inserido).
                if (shouldHoldDenialOperatorMessage(buffer)) {
                    appendStatus(buffer)
                } else {
                    showOperatorMessage(buffer)
                }
                continueWith("")
            }

            CliSiTef.CMD_SHOW_MSG_CUSTOMER -> {
                if (shouldSuppressInteractivePrompts() && isPasswordEntryPrompt(buffer)) {
                    appendStatus("Cliente: $buffer")
                    continueWith("")
                    return
                }
                if (buffer.isNotBlank()) {
                    appendStatus("Cliente: $buffer")
                    noteDenialIfPresent(buffer)
                    when {
                        isDenialMessage(buffer) -> showOperatorMessage(buffer)
                        shouldHoldDenialOperatorMessage(buffer) -> {
                            // Só status — painel mantém a negação / retire.
                        }
                        isRemoveCardPrompt(buffer) || isCardPresentmentPrompt(buffer) -> {
                            // Exibe no painel sem gravar como motivo da negação.
                            lastOperatorPrompt = buffer.trim()
                            if (isCardPresentmentPrompt(buffer) && !abortRequested.get()) {
                                awaitingCardPresentment.set(true)
                                setBackButtonVisible(false)
                            }
                            onOperatorMessage(buffer)
                        }
                    }
                }
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
                // Em cancelamento, não voltar para "aguardando" apagando "Operação cancelada".
                if (!shouldSuppressInteractivePrompts()) {
                    clearOperatorMessage()
                }
                continueWith("")
            }

            CliSiTef.CMD_CLEAR_MSG_CUSTOMER,
            CliSiTef.CMD_CLEAR_MENU_TITLE,
            CliSiTef.CMD_CLEAR_HEADER -> continueWith("")

            CliSiTef.CMD_CONFIRMATION,
            CliSiTef.CMD_CONFIRM_GO_BACK -> {
                if (shouldAutoAckDenialPrompts()) {
                    val prompt = buffer.ifBlank { lastMeaningfulSitefMessage }
                    if (prompt.isNotBlank()) {
                        rememberMeaningfulSitefMessage(prompt)
                        showOperatorMessage(prompt)
                    }
                    continueWith("0")
                    return
                }
                if (shouldSuppressInteractivePrompts()) {
                    if (buffer.isNotBlank()) appendStatus(buffer)
                    continueWith("0")
                    return
                }
                if (buffer.isNotBlank()) appendStatus(buffer)
                showConfirmationDialog(buffer) { which ->
                    continueWith(which.toString())
                }
            }

            CliSiTef.CMD_GET_MENU_OPTION -> {
                if (shouldSuppressInteractivePrompts()) {
                    val firstOption = parseMenuOptions(buffer).firstOrNull()?.code ?: "1"
                    continueWith(firstOption)
                    return
                }
                showPaymentMenu(buffer)
            }

            CliSiTef.CMD_GET_PINPAD_CONFIRMATION -> {
                if (shouldAutoAckDenialPrompts() || shouldSuppressInteractivePrompts()) {
                    dismissActiveDialog()
                    continueWith("")
                    return
                }
                val prompt = buffer.ifBlank { lastOperatorPrompt }
                showOperatorMessage(prompt)
                if (shouldInterceptSupervisorPassword(command, prompt)) {
                    showSupervisorPasswordDialog(prompt, minLength, maxLength)
                    return
                }
                continueWith("")
            }

            CliSiTef.CMD_GET_FIELD_CURRENCY -> {
                if (shouldSuppressInteractivePrompts()) {
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
                // Após "Erro de simulação 04" o SiTef ainda pode pedir campo/senha —
                // isso abria o diálogo Confirmar/Cancelar da senha do supervisor.
                if (shouldAutoAckDenialPrompts() || shouldSuppressInteractivePrompts()) {
                    dismissActiveDialog()
                    supervisorPasswordPending = false
                    continueWith("")
                    return
                }
                if (isSaleValuePrompt(buffer)) {
                    showCurrencyInput(buffer, minLength, maxLength) { value ->
                        continueWith(value)
                    }
                } else if (shouldInterceptSupervisorPassword(command, buffer)) {
                    showSupervisorPasswordDialog(buffer, minLength, maxLength)
                } else {
                    showInput(buffer, minLength, maxLength) { value ->
                        continueWith(value)
                    }
                }
            }

            CliSiTef.CMD_PRESS_ANY_KEY -> {
                if (buffer.isNotBlank()) {
                    appendStatus(buffer)
                    if (isPinpadErrorMessage(buffer) || isDenialMessage(buffer)) {
                        rememberMeaningfulSitefMessage(buffer)
                        showOperatorMessage(buffer)
                    }
                }
                // Negação / "TRANSACAO INVALIDA": auto-ack — senão o stage1 chega
                // com o diálogo aberto e a mensagem de falha fica errada.
                if (shouldAutoAckDenialPrompts() ||
                    shouldSuppressInteractivePrompts() ||
                    isPinpadErrorMessage(buffer) ||
                    isDenialMessage(buffer)
                ) {
                    continueWith("0")
                    return
                }
                showConfirmationDialog(buffer) { which ->
                    continueWith(which.toString())
                }
            }

            CliSiTef.CMD_ABORT_REQUEST -> {
                // Tratado em onData (continue sync na thread do listener).
                traceTef("CMD_ABORT_REQUEST ignorado em handleDataCommand (já respondido)")
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
                if (shouldSuppressInteractivePrompts()) {
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
        // Segundo menu com o flag ainda ligado: substituir as opções.
        // Ignorar o comando deixa o SiTef esperando e os botões visíveis sem resposta.
        if (!menuDialogOpen.compareAndSet(false, true)) {
            menuDialogOpen.set(true)
        }

        val raw = menuBuffer.ifBlank { cliSiTef.buffer.orEmpty() }
        // CLSIT TransacoesDesabilitadas=2 (cheque): o host ainda pode enviar a
        // opção no menu — ocultamos na venda (não no admin).
        val options = filterDisabledPaymentOptions(parseMenuOptions(raw))
        appendStatus("Menu SiTef recebido")

        if (options.isEmpty()) {
            menuDialogOpen.set(false)
            continueWith("1")
            return
        }

        appendStatus(
            when (operationMode) {
                TefOperationMode.ADMIN_REPRINT -> "Escolha: último ou específico"
                TefOperationMode.ADMIN_MENU -> "Escolha a opção administrativa"
                TefOperationMode.ADMIN_PENDING -> "Tratando transações pendentes"
                else -> "Escolha a forma de pagamento"
            }
        )

        if (activity.isFinishing) {
            menuDialogOpen.set(false)
            return
        }

        val title = pendingMenuTitle?.ifBlank { null }
            ?: when (operationMode) {
                TefOperationMode.ADMIN_MENU ->
                    activity.getString(R.string.tef_menu_title_admin)
                TefOperationMode.ADMIN_REPRINT ->
                    activity.getString(R.string.tef_menu_title_reprint)
                TefOperationMode.ADMIN_PENDING ->
                    activity.getString(R.string.tef_menu_title_pending)
                else ->
                    activity.getString(R.string.tef_menu_title_payment)
            }
        pendingMenuTitle = null

        dismissActiveDialog()
        showInlineMenuOptions(title, options)
    }

    private fun showInlineMenuOptions(title: String, options: List<MenuOption>) {
        val menuCard = activity.findViewById<android.view.View>(R.id.cardTefMenuOptions)
        val titleView = activity.findViewById<TextView>(R.id.tvMenuTitle)
        val buttonContainer = activity.findViewById<LinearLayout>(R.id.containerMenuButtons)
        val operatorCard = activity.findViewById<android.view.View>(R.id.cardOperatorMessage)
        val pixCard = activity.findViewById<android.view.View>(R.id.cardPixQrCode)
        val cancelButton = activity.findViewById<MaterialButton>(R.id.btnCancelarTef)

        titleView.text = title
        buttonContainer.removeAllViews()

        // Se já havia Voltar (pós-escolha do tipo), menus seguintes mantêm o botão.
        val keepBackOnNestedMenu =
            operationMode == TefOperationMode.SALE && backToPreviousMenuAvailable.get()

        val inflater = LayoutInflater.from(activity)
        options.forEach { option ->
            val button = inflater.inflate(
                R.layout.item_tef_menu_option_button,
                buttonContainer,
                false
            ) as MaterialButton
            button.text = option.label
            button.isEnabled = true
            button.isClickable = true
            button.setOnClickListener {
                hideInlineMenuOptions()
                capturePaymentMenuSelection(option.label, option.code)
                appendStatus("Opção selecionada: ${option.label} (código ${option.code})")
                if (operationMode == TefOperationMode.SALE) {
                    backToPreviousMenuAvailable.set(true)
                    applyBackButtonAfterMenuSelection(option.label)
                    // À vista / parcelas / pré-datado → janela de GetPIN.
                    if (isInstallmentOrPinMenuOption(option.label)) {
                        pinEntryArmed.set(true)
                        if (GertecTefPinpad.shouldOpenNumericPinpadBeforeContinue()) {
                            val code = option.code
                            Thread({
                                GertecTefPinpad.preparePinEntry(activity, cliSiTef)
                                activity.runOnUiThread {
                                    if (!activity.isFinishing) continueWith(code)
                                }
                            }, "gpos700-pin-before-continue").start()
                            return@setOnClickListener
                        }
                    }
                }
                continueWith(option.code)
            }
            buttonContainer.addView(button)
        }

        // Menu raiz de pagamento: sem Voltar. Menus seguintes: mantém Voltar.
        cancelGoBackFailsafe()
        cancelGoBackHardTimeout()
        goingBackToPreviousMenu.set(false)
        goBackRecoverInProgress.set(false)
        if (keepBackOnNestedMenu) {
            // Menu à vista/parcelas: Voltar ok. Escolha seguinte esconde de novo se for cartão.
            awaitingCardPresentment.set(false)
            setBackButtonVisible(true)
        } else {
            backToPreviousMenuAvailable.set(false)
            awaitingCardPresentment.set(false)
            cardPaymentFlowActive.set(false)
            setBackButtonVisible(false)
        }
        operatorCard.visibility = android.view.View.GONE
        operatorCard.isClickable = false
        pixCard.visibility = android.view.View.GONE
        pixCard.isClickable = false
        activity.findViewById<android.view.View?>(R.id.panelTefCancelConfirm)?.visibility =
            android.view.View.GONE
        menuCard.visibility = android.view.View.VISIBLE
        menuCard.isClickable = false
        menuCard.bringToFront()
        val density = activity.resources.displayMetrics.density
        menuCard.elevation = 16f * density
        menuCard.translationZ = 16f * density
        // Cancelar continua acima do menu, só na faixa de baixo.
        activity.findViewById<android.view.View?>(R.id.containerTefBottomActions)?.bringToFront()
        cancelButton.bringToFront()
        cancelButton.text = activity.getString(R.string.tef_menu_cancel)
        cancelButton.isEnabled = true
        // GPOS720: o pinpad Setis cobre o menu. GPOS700: não esconder — o
        // fragmento é o pinpad interno e o débito cai em erro 31.
        if (GertecTefPinpad.shouldHidePinpadDuringAppMenu()) {
            scheduleUnblockMenuTouches()
        }
    }

    /**
     * Enquanto o menu crédito/débito está na tela, o pinpad do GPOS720 pode
     * esperar no thread principal (a lista aparece e o toque não entra) ou
     * cobrir os botões. Uma thread à parte solta essa espera e esconde a janela.
     * hidePinpad não entra aqui: ele manda cancelar e a venda aborta.
     */
    private fun scheduleUnblockMenuTouches() {
        if (menuTouchWatchdog?.isAlive == true) return
        val thread = Thread({
            while (menuDialogOpen.get() && !activity.isFinishing) {
                releasePinpadCoveringMenu()
                try {
                    Thread.sleep(200)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }, "gpos720-menu-touch")
        menuTouchWatchdog = thread
        thread.isDaemon = true
        thread.start()
    }

    @Suppress("DEPRECATION")
    private fun releasePinpadCoveringMenu() {
        val pinpad = try {
            activity.fragmentManager.findFragmentByTag("Pinpad")
        } catch (error: Throwable) {
            Log.w(TAG, "releasePinpadCoveringMenu", error)
            null
        } ?: return
        try {
            pinpad.javaClass
                .getMethod("setPinpadVisible", java.lang.Boolean.TYPE)
                .invoke(null, true)
            synchronized(pinpad) {
                (pinpad as java.lang.Object).notifyAll()
            }
        } catch (error: Throwable) {
            Log.w(TAG, "releasePinpadCoveringMenu notify", error)
        }
        activity.runOnUiThread {
            if (!menuDialogOpen.get() || activity.isFinishing) return@runOnUiThread
            try {
                val dialog = (pinpad as? android.app.DialogFragment)?.dialog ?: return@runOnUiThread
                dialog.window?.addFlags(
                    android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                )
                if (dialog.isShowing) {
                    dialog.hide()
                }
            } catch (error: Throwable) {
                Log.w(TAG, "releasePinpadCoveringMenu hide", error)
            }
        }
    }

    /** O menu fechou: o próximo cartão/senha pode abrir o pinpad de novo. */
    @Suppress("DEPRECATION")
    private fun allowPinpadToOpenAgain() {
        try {
            val pinpad = activity.fragmentManager.findFragmentByTag("Pinpad") ?: return
            pinpad.javaClass
                .getMethod("setPinpadVisible", java.lang.Boolean.TYPE)
                .invoke(null, false)
        } catch (error: Throwable) {
            Log.w(TAG, "allowPinpadToOpenAgain", error)
        }
    }

    private fun hideInlineMenuOptions() {
        menuDialogOpen.set(false)
        menuTouchWatchdog?.interrupt()
        menuTouchWatchdog = null
        if (GertecTefPinpad.shouldHidePinpadDuringAppMenu()) {
            allowPinpadToOpenAgain()
        }
        val menuCard = activity.findViewById<android.view.View?>(R.id.cardTefMenuOptions)
        val buttonContainer = activity.findViewById<LinearLayout?>(R.id.containerMenuButtons)
        val operatorCard = activity.findViewById<android.view.View?>(R.id.cardOperatorMessage)
        buttonContainer?.removeAllViews()
        menuCard?.visibility = android.view.View.GONE
        if (operatorCard != null &&
            activity.findViewById<android.view.View?>(R.id.cardPixQrCode)?.visibility != android.view.View.VISIBLE
        ) {
            operatorCard.visibility = android.view.View.VISIBLE
        }
    }

    private fun setBackButtonVisible(visible: Boolean) {
        val backButton = activity.findViewById<MaterialButton?>(R.id.btnVoltarTef) ?: return
        // Trava de segurança: nunca exibir Voltar na etapa de cartão.
        val show = visible &&
            !awaitingCardPresentment.get() &&
            !isCardPresentmentPrompt(lastOperatorPrompt)
        backButton.visibility = if (show) android.view.View.VISIBLE else android.view.View.GONE
        backButton.isEnabled = show
        if (show) {
            backButton.bringToFront()
            activity.findViewById<android.view.View?>(R.id.containerTefBottomActions)?.bringToFront()
        }
    }

    private fun normalizeTefText(message: String): String =
        java.text.Normalizer
            .normalize(message, java.text.Normalizer.Form.NFD)
            .replace("\\p{M}+".toRegex(), "")
            .lowercase(Locale.getDefault())

    private fun isCardPresentmentPrompt(message: String): Boolean {
        val normalized = normalizeTefText(message)
        if (normalized.isBlank()) return false
        if (isRemoveCardPrompt(normalized)) return false
        val asksCardAction =
            normalized.contains("insira") ||
                normalized.contains("inserir") ||
                normalized.contains("aproxime") ||
                normalized.contains("aproxim") ||
                normalized.contains("passe o") ||
                normalized.contains("passe o cart") ||
                normalized.contains("apresente")
        val mentionsCard =
            normalized.contains("cartao") ||
                normalized.contains("chip") ||
                normalized.contains("leitora") ||
                normalized.contains("pinpad") ||
                normalized.contains("aproximacao")
        // Frase clássica SiTef: "Aproxime, insira ou passe o cartao..."
        if (normalized.contains("aproxime") && normalized.contains("insira")) return true
        if (normalized.contains("insira") && normalized.contains("cart")) return true
        if (normalized.contains("aproxime") && normalized.contains("cart")) return true
        return asksCardAction && mentionsCard
    }

    /** SiTef após cancelar/negar com cartão ainda inserido. */
    private fun isRemoveCardPrompt(message: String): Boolean {
        val normalized = normalizeTefText(message)
        if (normalized.isBlank()) return false
        return (normalized.contains("retire") ||
            normalized.contains("remova") ||
            normalized.contains("retire o") ||
            normalized.contains("pode retirar")) &&
            normalized.contains("cart")
    }

    /**
     * Em fluxo de negação, não deixar genéricos / senha / processamento trocarem
     * a mensagem do emissor. "Retire o cartão" DEVE aparecer (cartão inserido).
     */
    private fun shouldHoldDenialOperatorMessage(buffer: String): Boolean {
        if (!denialFlowActive.get() && !isDenialMessage(lastMeaningfulSitefMessage)) {
            return false
        }
        if (isDenialMessage(buffer)) return false
        if (isRemoveCardPrompt(buffer)) return false
        return isGenericCancelMessage(buffer) ||
            isCardPresentmentPrompt(buffer) ||
            isPasswordEntryPrompt(buffer) ||
            isTransientProcessingMessage(buffer)
    }

    /**
     * Remove meios desabilitados no CLSIT ([Geral] TransacoesDesabilitadas=2 = cheque)
     * do menu de pagamento da venda. Identifica pelo rótulo (o código do item no
     * menu SiTef não é a mesma função 2).
     */
    private fun filterDisabledPaymentOptions(options: List<MenuOption>): List<MenuOption> {
        if (operationMode != TefOperationMode.SALE) return options
        return options.filterNot { isChequePaymentMenuOption(it.label) }
    }

    private fun isChequePaymentMenuOption(label: String): Boolean {
        val normalized = normalizeTefText(label)
        return normalized.contains("cheque")
    }

    /** Menu pós-cartão que precede a senha (À Vista, parcelas, pré-datado). */
    private fun isInstallmentOrPinMenuOption(label: String): Boolean {
        val normalized = normalizeTefText(label)
        return normalized.contains("vista") ||
            normalized.contains("parcel") ||
            normalized.contains("pre-dat") ||
            normalized.contains("predat") ||
            normalized.contains("administradora") ||
            normalized.contains("estabelecimento")
    }

    private fun isNonCardPaymentMenuOption(label: String): Boolean {
        val normalized = normalizeTefText(label)
        return normalized.contains("pix") ||
            normalized.contains("qrcode") ||
            normalized.contains("qr code") ||
            normalized.contains("carteira digital") ||
            normalized.contains("voucher") ||
            normalized.contains("vale-") ||
            normalized.contains("vale ") ||
            normalized.contains("dinheiro") ||
            isChequePaymentMenuOption(label)
    }

    /** Opções de débito/crédito que seguem para leitura do cartão. */
    private fun isCardPaymentMenuOption(label: String): Boolean {
        val normalized = normalizeTefText(label)
        if (isNonCardPaymentMenuOption(label)) return false
        return normalized.contains("debito") ||
            normalized.contains("credito") ||
            normalized.contains("cartao")
    }

    /**
     * Após cada escolha de menu: no fluxo cartão (débito/crédito/à vista/parcelas)
     * esconde Voltar até o próximo menu de opções.
     */
    private fun applyBackButtonAfterMenuSelection(label: String) {
        when {
            isNonCardPaymentMenuOption(label) -> {
                cardPaymentFlowActive.set(false)
                awaitingCardPresentment.set(false)
                setBackButtonVisible(true)
            }
            isCardPaymentMenuOption(label) || cardPaymentFlowActive.get() -> {
                cardPaymentFlowActive.set(true)
                awaitingCardPresentment.set(true)
                setBackButtonVisible(false)
            }
            else -> {
                awaitingCardPresentment.set(false)
                setBackButtonVisible(true)
            }
        }
    }

    private fun updateBackButtonForOperatorPrompt(message: String) {
        if (operationMode != TefOperationMode.SALE) return
        if (!backToPreviousMenuAvailable.get()) {
            setBackButtonVisible(false)
            return
        }
        if (isCardPresentmentPrompt(message)) {
            awaitingCardPresentment.set(true)
            cardPaymentFlowActive.set(true)
            setBackButtonVisible(false)
            return
        }
        if (awaitingCardPresentment.get()) {
            setBackButtonVisible(false)
            return
        }
        setBackButtonVisible(true)
    }

    /** Voltar disponível de novo em menus/campos (fora da leitura do cartão). */
    private fun restoreBackButtonIfAllowed() {
        if (operationMode != TefOperationMode.SALE) return
        if (!backToPreviousMenuAvailable.get()) return
        if (awaitingCardPresentment.get() || isCardPresentmentPrompt(lastOperatorPrompt)) {
            setBackButtonVisible(false)
            return
        }
        setBackButtonVisible(true)
    }

    private fun showConfirmationDialog(message: String, onAnswer: (Int) -> Unit) {
        if (activity.isFinishing) return
        // Nunca exibir "Operação cancelada / Cancelar / OK" durante Voltar.
        if (goingBackToPreviousMenu.get()) {
            onAnswer(0)
            return
        }
        // Erro de simulação / negação: nunca Confirmar/Cancelar — só continua.
        if (isDenialMessage(message) || isDenialMessage(lastMeaningfulSitefMessage)) {
            val prompt = message.ifBlank { lastMeaningfulSitefMessage }
            if (prompt.isNotBlank()) {
                rememberMeaningfulSitefMessage(prompt)
                showOperatorMessage(prompt)
            }
            onAnswer(0)
            return
        }
        restoreBackButtonIfAllowed()
        showTrackedDialog(
            AlertDialog.Builder(activity)
                .setMessage(message.ifBlank { "Continuar?" })
                .setCancelable(false)
                .setPositiveButton("OK") { _, _ -> onAnswer(0) }
                .setNegativeButton("Cancelar") { _, _ -> abort() }
        )
    }

    private fun showInput(
        prompt: String,
        minLength: Int,
        maxLength: Int,
        onValue: (String) -> Unit
    ) {
        if (goingBackToPreviousMenu.get()) {
            onValue("")
            return
        }
        restoreBackButtonIfAllowed()
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

    private fun showSupervisorPasswordDialog(
        prompt: String,
        minLength: Int,
        maxLength: Int,
    ) {
        if (shouldAutoAckDenialPrompts() || shouldSuppressInteractivePrompts()) {
            Log.d(TAG, "senha supervisor ignorada — negação/cancelamento em curso")
            dismissActiveDialog()
            supervisorPasswordPending = false
            supervisorPasswordMinLength = 0
            continueWith("")
            return
        }
        val message = prompt.ifBlank { lastOperatorPrompt }.ifBlank {
            activity.getString(R.string.tef_supervisor_password_prompt)
        }
        // Prompt ainda é a msg de simulação/negação: não abrir Confirmar/Cancelar.
        if (isDenialMessage(message)) {
            noteDenialIfPresent(message)
            showOperatorMessage(message)
            continueWith("")
            return
        }
        supervisorPasswordPending = true
        if (minLength > 0) {
            supervisorPasswordMinLength = minLength
        }
        showOperatorMessage(message)
        showSupervisorPasswordInput(message, minLength, maxLength) { value ->
            submitSupervisorPassword(value, minLength)
        }
    }

    private fun showSupervisorPasswordInput(
        prompt: String,
        minLength: Int,
        maxLength: Int,
        onValue: (String) -> Unit,
    ) {
        val input = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            transformationMethod = PasswordTransformationMethod.getInstance()
            if (maxLength > 0) {
                filters = arrayOf(android.text.InputFilter.LengthFilter(maxLength))
            }
        }
        showTrackedDialog(
            AlertDialog.Builder(activity)
                .setMessage(prompt)
                .setView(input)
                .setPositiveButton(R.string.tef_supervisor_password_confirm) { _, _ ->
                    onValue(input.text.toString().trim())
                }
                .setNegativeButton("Cancelar") { _, _ -> abort() }
        )
    }

    private fun submitSupervisorPassword(value: String, minLength: Int) {
        if (!TefSupervisorPassword.isValid(value)) {
            Toast.makeText(
                activity,
                activity.getString(R.string.tef_supervisor_password_invalid),
                Toast.LENGTH_LONG,
            ).show()
            showSupervisorPasswordDialog(lastOperatorPrompt, minLength, 0)
            return
        }
        val payload = TefSupervisorPassword.formatForSitef(value, minLength)
        supervisorPasswordPending = false
        supervisorPasswordMinLength = 0
        appendStatus("Senha do supervisor aceita.")
        continueWith(payload)
    }

    private fun shouldInterceptSupervisorPassword(command: Int, prompt: String): Boolean {
        if (shouldAutoAckDenialPrompts()) return false
        val effective = prompt.ifBlank { lastOperatorPrompt }
        if (isDenialMessage(effective)) return false
        return TefSupervisorPassword.shouldIntercept(
            command = command,
            prompt = effective,
            isAdminOperation = operationMode != TefOperationMode.SALE,
        )
    }

    private fun showCurrencyInput(
        prompt: String,
        minLength: Int,
        maxLength: Int,
        onValue: (String) -> Unit
    ) {
        if (goingBackToPreviousMenu.get()) {
            onValue("")
            return
        }
        restoreBackButtonIfAllowed()
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
            // Já encerrado (ex.: cancelado no pinpad) — não reabrir senha do supervisor.
            if (settled.get()) {
                supervisorPasswordPending = false
                supervisorPasswordMinLength = 0
                return@dispatchOnMain
            }

            var payload = data
            if (supervisorPasswordPending && payload.isNotBlank()) {
                if (!TefSupervisorPassword.isValid(payload)) {
                    if (shouldSuppressInteractivePrompts()) {
                        supervisorPasswordPending = false
                        supervisorPasswordMinLength = 0
                        return@dispatchOnMain
                    }
                    Toast.makeText(
                        activity,
                        activity.getString(R.string.tef_supervisor_password_invalid),
                        Toast.LENGTH_LONG,
                    ).show()
                    showSupervisorPasswordDialog(lastOperatorPrompt, supervisorPasswordMinLength, 0)
                    return@dispatchOnMain
                }
                payload = TefSupervisorPassword.formatForSitef(payload, supervisorPasswordMinLength)
                supervisorPasswordPending = false
                supervisorPasswordMinLength = 0
            }

            continueTransactionSync(payload)
        }
    }

    /**
     * Continua a transação na thread atual (listener CliSiTef). Usar no CMD_ABORT
     * para não disputar a main com o DialogFragment do pinpad.
     */
    private fun continueTransactionSync(payload: String) {
        if (!running.get() || activity.isFinishing || settled.get()) return
        try {
            val result = cliSiTef.continueTransaction(payload)
            Log.d(TAG, "continueTransaction([$payload])=$result")
            when (result) {
                CliSiTefConstants.CONTINUA ->
                    dispatchOnMain { appendStatus("Processando…") }
                0 ->
                    dispatchOnMain { appendStatus("continue OK") }
                else -> {
                    if (closingLeftoverInteractive || ignoreStrayInteractive) {
                        Log.w(TAG, "continue durante close code=$result")
                        return
                    }
                    if (!settled.get()) {
                        val fallback = resolveStage1FailureMessage(result)
                            .ifBlank { describeTransactionError(result) }
                        dispatchOnMain {
                            finishInteractiveStageFailure(
                                result,
                                resolveFinishErrorMessage(fallback)
                            )
                        }
                    }
                }
            }
        } catch (error: Throwable) {
            Log.e(TAG, "continueTransaction native error", error)
            if (closingLeftoverInteractive || ignoreStrayInteractive) return
            if (!settled.get()) {
                dispatchOnMain { finishWithError(describeNativeError(error)) }
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
        // Após negação, ignore "Transação/Operação cancelada" no painel —
        // o operador precisa ler o motivo do emissor até o diálogo OK.
        // No cancelamento do pinpad (sem negação), a mensagem DEVE aparecer.
        if (isGenericCancelMessage(message) &&
            (denialFlowActive.get() || isDenialMessage(lastMeaningfulSitefMessage))
        ) {
            appendStatus(message)
            val keep = lastMeaningfulSitefMessage.trim().ifBlank { lastOperatorPrompt }
            if (keep.isNotBlank()) {
                onOperatorMessage(keep)
            }
            return
        }
        // "Retire o cartão": mostra no painel, mas não vira o motivo da negação.
        if (isRemoveCardPrompt(message)) {
            lastOperatorPrompt = message.trim()
            appendStatus(message)
            onOperatorMessage(message)
            return
        }
        lastOperatorPrompt = message.trim()
        rememberMeaningfulSitefMessage(message)
        if (TefSupervisorPassword.isSupervisorPasswordHint(message)) {
            supervisorPasswordPending = true
        }
        appendStatus(message)
        onOperatorMessage(message)
        updateBackButtonForOperatorPrompt(lastOperatorPrompt)
    }

    private fun clearOperatorMessage() {
        onOperatorMessage(null)
    }

    private fun rememberMeaningfulSitefMessage(message: String) {
        val trimmed = message.trim()
        if (trimmed.isBlank()) return
        if (isPasswordEntryPrompt(trimmed)) return
        if (isRemoveCardPrompt(trimmed)) return
        if (isCardPresentmentPrompt(trimmed)) return
        if (isTransientProcessingMessage(trimmed)) return
        // "Transação/Operação cancelada" do SiTef após a negação NÃO pode
        // sobrescrever o texto real do emissor (código / "Erro de simulação").
        if (isGenericCancelMessage(trimmed)) return
        // Não gravar título/opção de menu ("A Vista", "Cartao de Debito", …).
        if (isMenuOptionLabel(trimmed)) return

        val previous = lastMeaningfulSitefMessage
        if (previous.isNotBlank() &&
            (denialFlowActive.get() || isDenialMessage(previous) || isPinpadErrorMessage(previous)) &&
            !isDenialMessage(trimmed) &&
            !isPinpadErrorMessage(trimmed)
        ) {
            return
        }

        lastMeaningfulSitefMessage = trimmed
        if (isDenialMessage(trimmed) || isPinpadErrorMessage(trimmed)) {
            denialFlowActive.set(true)
        }
    }

    private fun noteDenialIfPresent(message: String) {
        if (!isDenialMessage(message)) return
        rememberMeaningfulSitefMessage(message)
        denialFlowActive.set(true)
        Log.d(TAG, "denialFlow ativo: ${message.take(80)}")
    }

    /** Status genérico de encerramento — não é o motivo da negação. */
    private fun isGenericCancelMessage(message: String): Boolean {
        val normalized = normalizeTefText(message)
        if (normalized.isBlank()) return false
        return normalized == "operacao cancelada" ||
            normalized == "operacao cancelada." ||
            normalized == "transacao cancelada" ||
            normalized == "transacao cancelada." ||
            normalized.startsWith("operacao cancelada") ||
            normalized.startsWith("transacao cancelada") ||
            normalized.startsWith("encerrando transacao") ||
            normalized.startsWith("cancelando")
    }

    /**
     * Preferência ao fechar com erro: texto da negação do host > fallback útil >
     * "Operação cancelada" só se realmente não houver motivo.
     */
    private fun resolveFinishErrorMessage(fallback: String): String {
        // Cancelamento no pinpad: não trocar por título de menu / prompt antigo.
        if (abortRequested.get() &&
            !denialFlowActive.get() &&
            !isDenialMessage(lastMeaningfulSitefMessage)
        ) {
            val fromPrompt = lastOperatorPrompt.trim()
            if (fromPrompt.isNotBlank() &&
                (isPinpadCancelMessage(fromPrompt) || isGenericCancelMessage(fromPrompt))
            ) {
                return fromPrompt
            }
            val fb = fallback.trim()
            return fb.ifBlank { "Operação cancelada." }
        }

        val denial = lastMeaningfulSitefMessage.trim()
        if (denial.isNotBlank() &&
            (isDenialMessage(denial) || denialFlowActive.get()) &&
            !isGenericCancelMessage(denial)
        ) {
            return denial
        }
        val fb = fallback.trim()
        if (fb.isNotBlank() && !isGenericCancelMessage(fb) && !isPasswordEntryPrompt(fb)) {
            return fb
        }
        if (denial.isNotBlank() && !isGenericCancelMessage(denial) && !isPasswordEntryPrompt(denial)) {
            return denial
        }
        return fb.ifBlank { "Operação cancelada." }
    }

    /** Após erro de simulação / negação: não abrir diálogos Confirmar/Cancelar. */
    private fun shouldAutoAckDenialPrompts(): Boolean =
        denialFlowActive.get() ||
            isDenialMessage(lastMeaningfulSitefMessage) ||
            isDenialMessage(lastOperatorPrompt)

    /** Durante encerramento/cancelamento/confirmação de pendências, não abrir diálogos. */
    private fun shouldAutoContinueDuringFinalize(): Boolean =
        abortRequested.get() || awaitingCancelFinish || confirmingPendingAfterSuccess ||
            closingLeftoverInteractive || ignoreStrayInteractive

    /**
     * Bloqueia menu/senha/confirmação na UI após cancelamento no pinpad ou sessão
     * já encerrada (senão a senha do supervisor sobe depois de "Operação cancelada").
     * Negação com mensagem do emissor NÃO entra aqui via PRESS_ANY_KEY (tratado à parte).
     */
    private fun shouldSuppressInteractivePrompts(): Boolean =
        settled.get() ||
            shouldAutoContinueDuringFinalize() ||
            goingBackToPreviousMenu.get()

    private fun isTransientProcessingMessage(message: String): Boolean {
        val normalized = normalizeTefText(message)
        return normalized.contains("processando") ||
            normalized.contains("aguarde") ||
            normalized.contains("conectando") ||
            normalized.contains("enviando") ||
            normalized.contains("recebendo")
    }

    private fun isDenialMessage(message: String): Boolean {
        val normalized = normalizeTefText(message)
        if (normalized.isBlank()) return false
        return normalized.contains("negad") ||
            normalized.contains("nao autoriz") ||
            normalized.contains("nao autorizada") ||
            normalized.contains("recus") ||
            normalized.contains("nao aprov") ||
            normalized.contains("desfaz") ||
            (normalized.contains("invalida") && normalized.contains("transac")) ||
            normalized.contains("senha bloqueada") ||
            normalized.contains("erro de simulacao") ||
            normalized.contains("erro simulacao") ||
            normalized.contains("simulacao 04") ||
            normalized.contains("simulacao04") ||
            (normalized.contains("simulacao") &&
                (normalized.contains("erro") || normalized.contains("04"))) ||
            normalized.contains("codigo de resposta") ||
            normalized.contains("codigo resposta") ||
            normalized.contains("cod. resposta") ||
            normalized.contains("cod resposta") ||
            (normalized.contains("resposta") &&
                (normalized.contains("codigo") || normalized.contains("cod"))) ||
            isPinpadErrorMessage(message)
    }

    /** Erro de rotina/comunicação do pinpad (ex.: "40 - Erro Pinpad"). */
    private fun isPinpadErrorMessage(message: String): Boolean {
        val normalized = normalizeTefText(message)
        if (normalized.isBlank()) return false
        return normalized.contains("erro pinpad") ||
            normalized.contains("erro no pinpad") ||
            normalized.contains("falha pinpad") ||
            normalized.contains("senha bloqueada") ||
            Regex("""^\s*\d+\s*-\s*erro\s+pinpad""", RegexOption.IGNORE_CASE)
                .containsMatchIn(message)
    }

    /** Opção curta de menu SiTef — não usar como motivo de falha. */
    private fun isMenuOptionLabel(message: String): Boolean {
        val trimmed = message.trim()
        if (trimmed.isBlank() || trimmed.length > 48) return false
        if (trimmed.contains('\n') || trimmed.contains(';')) return false
        val normalized = normalizeTefText(trimmed)
        // Status intermediário do pinpad, não motivo de falha.
        if (normalized.startsWith("selecionado")) return true
        if (trimmed.contains(':') && !normalized.startsWith("selecionado")) return false
        return normalized == "a vista" ||
            normalized == "pre-datado" ||
            normalized == "pre datado" ||
            normalized == "cheque" ||
            normalized == "cartao de debito" ||
            normalized == "cartao de credito" ||
            normalized == "cartao combustivel" ||
            normalized == "carteira digital" ||
            normalized == "debito" ||
            normalized == "credito" ||
            normalized == "pix" ||
            normalized.startsWith("selecione")
    }

    private fun isPinpadCancelMessage(message: String): Boolean {
        val normalized = normalizeTefText(message)
        if (normalized.isBlank()) return false
        // Negação do emissor / simulado não é cancelamento no pinpad.
        if (isDenialMessage(normalized)) return false
        if (denialFlowActive.get() || isDenialMessage(lastMeaningfulSitefMessage)) return false
        return normalized.contains("operacao cancelada") ||
            normalized.contains("transacao cancelada") ||
            normalized.contains("cancelado no pinpad") ||
            normalized.contains("cancelado pelo operador") ||
            (normalized.contains("cancelad") &&
                (normalized.contains("operacao") ||
                    normalized.contains("transacao") ||
                    normalized.contains("pelo usuario") ||
                    normalized.contains("pelo operador")))
    }

    private fun isPasswordEntryPrompt(message: String): Boolean {
        val normalized = normalizeTefText(message)
        if (normalized.isBlank()) return false
        return normalized.contains("senha") ||
            normalized.contains("digite o pin") ||
            normalized.contains("informe o pin") ||
            normalized.contains("pin do cart")
    }

    /**
     * Mensagem ao falhar no estágio 1: prioriza texto do SiTef (negação / código
     * de resposta do emissor). Só usa "Operação cancelada" em abort real do operador.
     */
    private fun resolveStage1FailureMessage(resultCode: Int): String {
        val denialStored = lastMeaningfulSitefMessage.trim()
        if (denialStored.isNotBlank() &&
            !isGenericCancelMessage(denialStored) &&
            !isMenuOptionLabel(denialStored) &&
            (denialFlowActive.get() || isDenialMessage(denialStored) || isPinpadErrorMessage(denialStored))
        ) {
            return denialStored
        }

        val fromHost = listOf(denialStored, lastOperatorPrompt.trim())
            .firstOrNull { msg ->
                msg.isNotBlank() &&
                    !isMenuOptionLabel(msg) &&
                    !isGenericCancelMessage(msg) &&
                    !isPasswordEntryPrompt(msg) &&
                    !isCardPresentmentPrompt(msg)
            }
            .orEmpty()

        val hostLooksDenial = fromHost.isNotBlank() &&
            (isDenialMessage(fromHost) || isPinpadErrorMessage(fromHost))

        if (hostLooksDenial) {
            return fromHost
        }

        if (abortRequested.get() && isBenignPinpadAbortCode(resultCode) && !denialFlowActive.get()) {
            return "Operação cancelada."
        }

        // Códigos positivos / negação padrão SiTef: preferir host se houver.
        if (fromHost.isNotBlank() &&
            !isPinpadCancelMessage(fromHost) &&
            resultCode != -2 &&
            resultCode != -6
        ) {
            return fromHost
        }

        return describeCancelResult(resultCode)
    }

    /**
     * Cancelamento no pinpad: limpa "Digite a senha", força abort da lib e agenda
     * fallback se o estágio 1 demorar (senão a tela fica presa na mensagem de senha).
     */
    private fun markAbortFromPinpadCancel(message: String) {
        if (settled.get() || transactionApproved) return
        // Mensagem de negação do host nunca deve armar abort de pinpad.
        if (isDenialMessage(message)) {
            rememberMeaningfulSitefMessage(message)
            showOperatorMessage(message.trim())
            return
        }
        supervisorPasswordPending = false
        supervisorPasswordMinLength = 0
        dismissActiveDialog()
        hideInlineMenuOptions()
        setBackButtonVisible(false)
        awaitingCardPresentment.set(false)
        onHidePixQrCode()

        val status = when {
            isPinpadCancelMessage(message) || isGenericCancelMessage(message) -> message.trim()
            isRemoveCardPrompt(message) -> message.trim()
            else -> "Operação cancelada."
        }
        showOperatorMessage(status.ifBlank { "Operação cancelada." })

        val firstMark = abortRequested.compareAndSet(false, true)
        Log.d(TAG, "abort pinpad first=$firstMark msg=${message.take(80)}")
        if (!firstMark) {
            // Já abortando: ainda assim exibir "Retire o cartão" se vier agora.
            if (isRemoveCardPrompt(message)) {
                showOperatorMessage(message.trim())
            }
            return
        }

        try {
            cliSiTef.setActivity(activity)
            val result = cliSiTef.abortTransaction(-1)
            Log.d(TAG, "abortTransaction(-1) from pinpad cancel=$result")
        } catch (error: Throwable) {
            Log.w(TAG, "abortTransaction from pinpad cancel", error)
        }
        scheduleAbortFallback()
    }
    /**
     * Garante CliSiTef livre antes de nova venda. O erro -12 no configure pode ocorrer
     * mesmo com [getQttPendingTransactions] = 0 (processo iterativo não encerrado).
     */
    private fun ensureCliSiTefReady(onReady: () -> Unit) {
        try {
            cliSiTef.setActivity(activity)
            runCatching { cliSiTef.submitPendingMessages() }

            val comExterna = TefPreferences.getTefComExterna(activity)
            val pendingRef = findPendingFiscalRef()

            // TLS GWP / GSurf: NÃO fazer probeConfigure + liberate com
            // abortTransaction antes do start — na 2ª venda isso aborta em
            // "Conectando servidor". configureAndStart já refaz o configure.
            if (isExternalTlsComExterna(comExterna)) {
                if (TefSessionGuard.needsCleanup || pendingRef != null) {
                    Log.w(
                        TAG,
                        "ensureReady TLS cleanup needsCleanup=${TefSessionGuard.needsCleanup} " +
                            "pending=${pendingRef?.coupon} — unlock sem abort",
                    )
                    Thread({
                        unlockStuckInteractive()
                        TefSessionGuard.markSessionClean()
                        activity.runOnUiThread {
                            if (!activity.isFinishing) onReady()
                        }
                    }, "tef-tls-unlock").start()
                } else {
                    onReady()
                }
                return
            }

            // GPOS700: nunca probe/liberate com abort (mesmo com COMEXTERNA=0).
            if (!GertecTefPinpad.shouldProbeAndLiberateBeforeSale()) {
                if (TefSessionGuard.needsCleanup || pendingRef != null) {
                    Log.w(
                        TAG,
                        "ensureReady GPOS700 cleanup pending=${pendingRef?.coupon} — unlock sem abort",
                    )
                    Thread({
                        unlockStuckInteractive()
                        TefSessionGuard.markSessionClean()
                        activity.runOnUiThread {
                            if (!activity.isFinishing) onReady()
                        }
                    }, "tef-gpos700-unlock").start()
                } else {
                    onReady()
                }
                return
            }

            val probeCode = probeConfigure()
            Log.d(
                TAG,
                "ensureReady probe=$probeCode needsCleanup=${TefSessionGuard.needsCleanup} " +
                    "pendingRef=${pendingRef?.coupon}"
            )

            // Tratamento de pendências: nunca abortar/estornar para "liberar" o
            // terminal. liberateTerminalThen chama abort + finishTransaction(0) e
            // cancela a venda que o operador acabou de ver como "verificando".
            if (operationMode == TefOperationMode.ADMIN_PENDING) {
                if (probeCode != CliSiTef.CONFIG_OK) {
                    Log.w(TAG, "pending probe=$probeCode — segue sem abortar")
                }
                onReady()
                return
            }

            if (probeCode == -12) {
                Log.w(TAG, "ensureReady probe=-12 — encerra iterativo sem estornar")
                closeLeftoverInteractive {
                    if (!activity.isFinishing) onReady()
                }
                return
            }

            if (probeCode == CliSiTef.CONFIG_OK &&
                !TefSessionGuard.needsCleanup &&
                pendingRef == null
            ) {
                onReady()
            } else {
                liberateTerminalThen(onReady)
            }
        } catch (error: Throwable) {
            Log.w(TAG, "ensureCliSiTefReady", error)
            TefSessionGuard.markSessionClean()
            onReady()
        }
    }

    private fun probeConfigure(): Int {
        val numeroTerminal = TefPreferences.getTerminalId(activity)
        return cliSiTef.configure(
            TefPreferences.getSitefConfigureAddress(activity),
            TefPreferences.getStoreId(activity),
            numeroTerminal,
            TefPreferences.getSitefConfigureAdditionalParams(activity)
        )
    }

    /** COMEXTERNA 1=GSurf, 2=TLS GWP / Five Service. */
    private fun isExternalTlsComExterna(comExterna: String): Boolean {
        val value = comExterna.trim()
        return value == "1" || value == "2"
    }

    /**
     * Após aprovação (stage 2) e impressão do comprovante, confirma cupons ainda
     * pendentes no host SiTef antes de gravar no servidor e encerrar a venda.
     */
    private fun confirmRemainingPendingTransactions(onComplete: () -> Unit) {
        finalizeRemainingPendingTransactions(
            confirm = CliSiTefConstants.CONFIRM_TRANSACTION,
            forceCurrent = false,
            onComplete = onComplete,
        )
    }

    /**
     * Fluxo ADMIN_PENDING (queda de energia): confirma as transações pendentes conhecidas
     * (cupons salvos localmente que continuam pendentes no host) via finishTransaction(1)
     * por documento fiscal. Não usa startTransaction(130), que apenas lista.
     */
    private fun resolvePendingTransactions() {
        showOperatorMessage(activity.getString(R.string.tef_pending_checking))
        val counts = collectKnownFiscalRefs().map { countPendingTransactionsSafe(it) }
        if (counts.any { it < 0 }) {
            Log.w(TAG, "resolvePending: consulta falhou counts=$counts")
            finishWithError(activity.getString(R.string.tef_pending_query_failed))
            return
        }
        val hasPending = counts.any { it > 0 }
        if (!hasPending) {
            Log.d(TAG, "resolvePending: nenhuma transação pendente no host")
            pendingTransactionsWereResolved = false
            TefSessionGuard.markSessionClean()
            TefPendingFiscal.clearAll(activity)
            appendStatus("Nenhuma transação pendente.")
            completeTransactionFlow()
            return
        }
        pendingTransactionsWereResolved = true
        confirmRemainingPendingTransactions {
            TefSessionGuard.markSessionClean()
            TefPendingFiscal.clearAll(activity)
            appendStatus("Transações pendentes confirmadas.")
            completeTransactionFlow()
        }
    }

    /**
     * Homologação CliSiTef 5.1.1: se o comprovante TEF não pôde ser impresso, a venda
     * NÃO é confirmada — estorna todos os pagamentos do cupom via finishTransaction(0).
     */
    private fun reverseCurrentSaleForPrintFailure(onComplete: () -> Unit) {
        finalizeRemainingPendingTransactions(
            confirm = CliSiTefConstants.CANCEL_TRANSACTION,
            forceCurrent = true,
            onComplete = onComplete,
        )
    }

    private fun finalizeRemainingPendingTransactions(
        confirm: Int,
        forceCurrent: Boolean,
        onComplete: () -> Unit,
    ) {
        try {
            cliSiTef.setActivity(activity)
            cliSiTef.submitPendingMessages()
            pendingFinalizeConfirm = confirm
            pendingConfirmFinishParams = additionalParameters.ifBlank {
                TefPreferences.getSitefConfigureAdditionalParams(activity)
            }
            pendingConfirmQueue.clear()
            pendingConfirmOnComplete = onComplete

            if (forceCurrent && ::taxInvoiceNumber.isInitialized) {
                // Estorno precisa acontecer mesmo que getQttPendingTransactions retorne 0.
                pendingConfirmQueue.addLast(
                    TefFiscalRef(taxInvoiceNumber, taxInvoiceDate, taxInvoiceTime)
                )
            }
            collectKnownFiscalRefs().forEach { ref ->
                if (pendingConfirmQueue.any { it.coupon == ref.coupon && it.date == ref.date }) {
                    return@forEach
                }
                val count = countPendingTransactionsSafe(ref)
                Log.d(TAG, "finalizePending confirm=$confirm cupom=${ref.coupon} count=$count")
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
            if (confirm == CliSiTefConstants.CONFIRM_TRANSACTION) {
                appendStatus("Confirmando ${pendingConfirmQueue.size} transação(ões) pendente(s)…")
                showOperatorMessage("Confirmando transações pendentes…")
            } else {
                appendStatus("Estornando ${pendingConfirmQueue.size} transação(ões)…")
                showOperatorMessage(activity.getString(R.string.tef_reversing_sale))
            }
            schedulePendingConfirmWatchdog()
            processNextPendingConfirmation()
        } catch (error: Throwable) {
            Log.e(TAG, "finalizeRemainingPendingTransactions native error", error)
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

        Log.d(TAG, "finalizePending start confirm=$pendingFinalizeConfirm cupom=${ref.coupon}")
        // Uma ÚNICA chamada com callback (como em cancelPendingFiscal). Chamar a versão
        // síncrona antes iniciava um iterativo sem callback e a chamada seguinte retornava
        // -12 ("iterativo anterior não finalizado"), impedindo a confirmação.
        try {
            cliSiTef.setActivity(activity)
            runCatching { cliSiTef.submitPendingMessages() }
            val result = cliSiTef.finishTransaction(
                this,
                pendingFinalizeConfirm,
                ref.coupon,
                ref.date,
                ref.time,
                pendingConfirmFinishParams.ifBlank {
                    additionalParameters.ifBlank {
                        TefPreferences.getSitefConfigureAdditionalParams(activity)
                    }
                }
            )
            Log.d(TAG, "confirmPending cupom=${ref.coupon} result=$result")
            when (result) {
                0 -> processNextPendingConfirmation()
                // CONTINUA → o iterativo é conduzido pelos callbacks onData até o
                // onTransactionResult do estágio 2 (branch confirmingPendingAfterSuccess).
                CliSiTefConstants.CONTINUA -> Unit
                else -> {
                    Log.w(TAG, "confirmPending failed cupom=${ref.coupon} code=$result")
                    processNextPendingConfirmation()
                }
            }
        } catch (error: Throwable) {
            Log.e(TAG, "processNextPendingConfirmation", error)
            processNextPendingConfirmation()
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
            val count = cliSiTef.getQttPendingTransactions(ref.date, ref.coupon)
            if (count < 0) {
                Log.w(TAG, "getQttPendingTransactions cupom=${ref.coupon} code=$count")
            }
            count
        } catch (error: Throwable) {
            Log.w(TAG, "getQttPendingTransactions cupom=${ref.coupon}", error)
            -1
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

        showOperatorMessage(
            when {
                goingBackToPreviousMenu.get() ->
                    activity.getString(R.string.tef_back_to_previous_menu_status)
                // Negação ou cancelamento com cartão inserido: manter "Retire o cartão".
                isRemoveCardPrompt(lastOperatorPrompt) ->
                    lastOperatorPrompt
                denialFlowActive.get() && lastMeaningfulSitefMessage.isNotBlank() ->
                    lastMeaningfulSitefMessage
                abortRequested.get() &&
                    !denialFlowActive.get() &&
                    (isPinpadCancelMessage(lastOperatorPrompt) ||
                        isGenericCancelMessage(lastOperatorPrompt)) ->
                    lastOperatorPrompt
                else -> "Encerrando transação TEF…"
            }
        )

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

    /**
     * Encerra só o iterativo preso (erro -12). Não chama finishTransaction(0) no cupom,
     * para não estornar a venda nem fechar a tela antes de conectar ao servidor.
     */
    private fun traceTef(line: String) {
        Log.d(TAG, line)
        TefTraceLog.write(activity, line)
    }

    /**
     * Fecha o processo iterativo que ficou aberto depois de uma venda já confirmada.
     * Usa finishTransaction(1) com cupom vazio — não chama finishTransaction(0),
     * então a venda aprovada no host não é estornada.
     */
    private fun closeLeftoverInteractive(onDone: () -> Unit) {
        if (activity.isFinishing) {
            onDone()
            return
        }
        try {
            cliSiTef.setActivity(activity)
            runCatching { cliSiTef.submitPendingMessages() }
            val probe = probeConfigure()
            if (probe != -12) {
                Log.d(TAG, "closeLeftover: configure=$probe — iterativo livre")
                onDone()
                return
            }
            Log.w(TAG, "closeLeftover: configure=-12 — encerrando iterativo sem estornar")
            beginLeftoverClose(onDone)
        } catch (error: Throwable) {
            Log.w(TAG, "closeLeftoverInteractive", error)
            onDone()
        }
    }

    private fun beginLeftoverClose(onDone: () -> Unit) {
        closingLeftoverInteractive = true
        ignoreStrayInteractive = false
        leftoverInteractiveOnDone = onDone
        val result = try {
            cliSiTef.setActivity(activity)
            cliSiTef.finishTransaction(
                this,
                CliSiTefConstants.CONFIRM_TRANSACTION,
                "",
                "",
                "",
                "",
            )
        } catch (error: Throwable) {
            Log.w(TAG, "closeLeftover finish", error)
            finishLeftoverClose()
            return
        }
        Log.d(TAG, "closeLeftover finish=$result")
        when (result) {
            0 -> finishLeftoverClose()
            CliSiTefConstants.CONTINUA -> scheduleLeftoverCloseWatchdog()
            -12 -> {
                runCatching { cliSiTef.abortTransaction(-1) }
                val retry = runCatching {
                    cliSiTef.finishTransaction(
                        this,
                        CliSiTefConstants.CONFIRM_TRANSACTION,
                        "",
                        "",
                        "",
                        "",
                    )
                }.getOrDefault(-1)
                Log.d(TAG, "closeLeftover retry=$retry")
                when (retry) {
                    0 -> finishLeftoverClose()
                    CliSiTefConstants.CONTINUA, -12 -> scheduleLeftoverCloseWatchdog()
                    else -> finishLeftoverClose()
                }
            }
            else -> finishLeftoverClose()
        }
    }

    private fun finishLeftoverClose() {
        val done = leftoverInteractiveOnDone ?: return
        leftoverInteractiveOnDone = null
        closingLeftoverInteractive = false
        ignoreStrayInteractive = true
        cancelLeftoverCloseWatchdog()
        mainHandler.post { done() }
    }

    private fun scheduleLeftoverCloseWatchdog() {
        cancelLeftoverCloseWatchdog()
        val runnable = Runnable {
            leftoverCloseWatchdogRunnable = null
            if (!closingLeftoverInteractive) return@Runnable
            Log.w(TAG, "closeLeftover timeout — segue sem estornar")
            finishLeftoverClose()
        }
        leftoverCloseWatchdogRunnable = runnable
        activity.window.decorView.postDelayed(runnable, LEFTOVER_CLOSE_TIMEOUT_MS)
    }

    private fun cancelLeftoverCloseWatchdog() {
        leftoverCloseWatchdogRunnable?.let { activity.window.decorView.removeCallbacks(it) }
        leftoverCloseWatchdogRunnable = null
    }

    private fun unlockStuckInteractive() {
        try {
            cliSiTef.setActivity(activity)
            runCatching { cliSiTef.submitPendingMessages() }
            // Sem abortTransaction(-1): isso era o que abortava a venda e voltava
            // aos botões antes da tela de comunicação.
            val finishCode = runCatching { finalizeFunctionSync() }.getOrNull()
            Log.d(TAG, "unlockStuckInteractive finish=$finishCode")
        } catch (error: Throwable) {
            Log.w(TAG, "unlockStuckInteractive", error)
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
                finishWithError(
                    resolveFinishErrorMessage(
                        forceError ?: cancelFallbackMessage.ifBlank { "Operação cancelada." }
                    )
                )
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
                    finishWithError(resolveFinishErrorMessage(fallback.ifBlank { "Operação cancelada." }))
                } else {
                    TefSessionGuard.markSessionDirty()
                    finishWithError(
                        resolveFinishErrorMessage(describeTransactionError(resultCode))
                    )
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

            val continuation = cancelOnComplete
            cancelOnComplete = null
            val purpose = cancelFinishPurpose
            cancelFinishPurpose = CancelFinishPurpose.USER_ABORT

            when (purpose) {
                CancelFinishPurpose.PRE_START_CLEAR,
                CancelFinishPurpose.CONFIGURE_RETRY -> {
                    TefSessionGuard.markSessionClean()
                    continuation?.invoke()
                }
                CancelFinishPurpose.USER_ABORT -> {
                    TefSessionGuard.markSessionDirty()
                    finishWithError(resolveFinishErrorMessage(fallbackMessage))
                }
            }
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
            // <=0: sem pendência. <0 (ex.: -13 em TLS): consulta falhou — cupom da
            // tentativa negada não deve ficar preso para a próxima venda.
            if (pending <= 0) {
                TefPendingFiscal.clear(activity)
            }
        } catch (error: Throwable) {
            Log.w(TAG, "clearFiscalIfNoPending", error)
            TefPendingFiscal.clear(activity)
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

    private fun describePendingConfigureError(code: Int): String {
        return activity.getString(
            R.string.tef_pending_configure_failed,
            describeConfigureError(code),
        )
    }

    private fun describeConfigureError(code: Int): String = when (code) {
        CliSiTef.CONFIG_ERROR_INVALID_SITEF_ADDRESS -> "Endereço SiTef inválido ($code)."
        CliSiTef.CONFIG_ERROR_INVALID_STORE_ID -> "Código da loja inválido ($code)."
        CliSiTef.CONFIG_ERROR_INVALID_TERMINAL_ID -> "Terminal inválido ($code)."
        -12 -> "Transação TEF anterior pendente ($code). Tente a venda novamente."
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
        in 1..Int.MAX_VALUE -> {
            val host = lastMeaningfulSitefMessage.trim()
            if (host.isNotBlank() && (isDenialMessage(host) || host.length > 8)) {
                host
            } else {
                "Transação negada (código $code)."
            }
        }
        else -> {
            val host = lastMeaningfulSitefMessage.trim()
            if (host.isNotBlank() && isDenialMessage(host)) host
            else "Erro na transação TEF (código $code)."
        }
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

    /**
     * Campo `restricoes` do startTransaction: sem colchetes e sem NUMEROTERMINAL
     * (terminal já vai no configure). Lista de transações fica no CLSIT [Geral].
     */
    private fun buildStartTransactionParameters(explicitRestrictions: String): String {
        return TefClsitConfig.normalizeTransactionRestrictions(explicitRestrictions)
    }

    /**
     * Manual CliSiTef Android: 7º parâmetro de [CliSiTef.startTransaction] é o operador
     * (não o número do terminal — esse vai no configure).
     */
    private fun resolveOperatorForStart(operator: String): String {
        val fromArg = operator.trim()
        if (fromArg.isNotBlank()) {
            val digits = fromArg.filter { it.isDigit() }
            return digits.ifBlank { fromArg }
        }
        val fromPrefs = TefPreferences.getOperator(activity).trim()
        if (fromPrefs.isNotBlank()) {
            val digits = fromPrefs.filter { it.isDigit() }
            return digits.ifBlank { fromPrefs }
        }
        val terminalDigits = TefPreferences.getTerminalId(activity).filter { it.isDigit() }
        return terminalDigits.ifBlank { "0" }
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
        // finishTransaction(1) antes da impressão. A via do estabelecimento sai no estágio 2.
        showOperatorMessage(activity.getString(R.string.tef_confirming_transaction))
        pendingFinalizeConfirm = CliSiTefConstants.CONFIRM_TRANSACTION
        sendStage1Finish(CliSiTefConstants.CONFIRM_TRANSACTION)
    }

    /** Estágio 1 (iterativo): finaliza a transação corrente confirmando (1) ou estornando (0). */
    private fun sendStage1Finish(confirm: Int) {
        val finishResult = cliSiTef.finishTransaction(
            this,
            confirm,
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

                // Homologação 5.1.1: confirmação/estorno SÓ após a impressão do comprovante.
                // Se havia comprovante TEF e ele não pôde ser impresso, estorna a venda.
                val merchantPrintFailed = operationMode == TefOperationMode.SALE &&
                    receipt.hasMerchantCopy() && !merchantReceiptPrinted

                val confirmLatch = CountDownLatch(1)
                activity.runOnUiThread {
                    when {
                        operationMode.skipsPendingConfirmOnFinalize() -> {
                            closeLeftoverInteractive {
                                TefSessionGuard.markSessionClean()
                                TefPendingFiscal.clearAll(activity)
                                confirmLatch.countDown()
                            }
                        }
                        merchantPrintFailed -> {
                            Log.w(TAG, "comprovante TEF não impresso — estornando venda")
                            reverseCurrentSaleForPrintFailure {
                                saleReversedForPrintFailure = true
                                closeLeftoverInteractive {
                                    TefSessionGuard.markSessionClean()
                                    TefPendingFiscal.clearAll(activity)
                                    confirmLatch.countDown()
                                }
                            }
                        }
                        else -> {
                            confirmRemainingPendingTransactions {
                                closeLeftoverInteractive {
                                    TefSessionGuard.markSessionClean()
                                    TefPendingFiscal.clearAll(activity)
                                    confirmLatch.countDown()
                                }
                            }
                        }
                    }
                }
                confirmLatch.await(2, TimeUnit.MINUTES)

                if (saleReversedForPrintFailure) {
                    activity.runOnUiThread {
                        finishWithError(activity.getString(R.string.tef_sale_reversed_print_failure))
                    }
                    return@Thread
                }

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
     * Reimpressão (112) e administrativo (110) — sem servidor; não bloqueia 45s aguardando via 122.
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
            TefOperationMode.ADMIN_MENU,
            TefOperationMode.ADMIN_PENDING -> {
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
        this == TefOperationMode.ADMIN_REPRINT ||
            this == TefOperationMode.ADMIN_MENU ||
            this == TefOperationMode.ADMIN_PENDING

    private fun TefOperationMode.skipsServerRegistration(): Boolean = isReceiptOnlyAdminFlow()

    private fun TefOperationMode.skipsPendingConfirmOnFinalize(): Boolean =
        this == TefOperationMode.ADMIN_CANCELLATION ||
            this == TefOperationMode.ADMIN_REPRINT ||
            this == TefOperationMode.ADMIN_MENU ||
            this == TefOperationMode.ADMIN_PENDING

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

        val fromSale = TefAmountFormatter.toSitefCurrencyDigits(saleAmount).toLongOrNull() ?: 0L
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
            TefOperationMode.ADMIN_PENDING ->
                if (pendingTransactionsWereResolved) {
                    activity.getString(R.string.tef_admin_pending_done)
                } else {
                    activity.getString(R.string.tef_admin_pending_none)
                }
            TefOperationMode.SALE -> "Transação TEF concluída com sucesso."
        }
        if (activity.isFinishing) {
            finishWithSuccess(successMessage)
            return
        }
        if (operationMode == TefOperationMode.ADMIN_CANCELLATION ||
            operationMode == TefOperationMode.ADMIN_REPRINT ||
            operationMode == TefOperationMode.ADMIN_MENU ||
            operationMode == TefOperationMode.ADMIN_PENDING
        ) {
            finishWithSuccess(successMessage)
            return
        }

        val chaveNota = transactionResult.chaveNota.trim()
        if (chaveNota.isBlank()) {
            val missing = if (cartaoMovimentoRegistrado) {
                activity.getString(R.string.tef_print_nfe_chave_missing)
            } else {
                activity.getString(R.string.tef_print_nfe_unavailable)
            }
            Log.w(TAG, "completeTransactionFlow sem chave — $missing")
            appendStatus(missing)
            showOperatorMessage(missing)
            Toast.makeText(activity, missing, Toast.LENGTH_LONG).show()
            finishWithSuccess(successMessage)
            return
        }

        promptNfeCupomPrint()
    }

    private fun promptNfeCupomPrint() {
        if (activity.isFinishing) {
            finishWithSuccess("Transação TEF concluída com sucesso.")
            return
        }
        try {
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
        } catch (error: Throwable) {
            Log.e(TAG, "promptNfeCupomPrint dialog", error)
            // Sem diálogo: tenta imprimir direto (Delphi após "Sim").
            Toast.makeText(
                activity,
                activity.getString(R.string.tef_printing_nfe),
                Toast.LENGTH_SHORT,
            ).show()
            printNfeCupomAsync {
                finishWithSuccess("Transação TEF concluída com sucesso.")
            }
        }
    }

    private fun printNfeCupomAsync(onComplete: () -> Unit) {
        val chaveNota = transactionResult.chaveNota.trim()
        if (chaveNota.isBlank()) {
            val message = activity.getString(R.string.tef_print_nfe_chave_missing)
            appendStatus(message)
            showOperatorMessage(message)
            Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
            onComplete()
            return
        }

        showOperatorMessage(activity.getString(R.string.tef_printing_nfe))
        val waitDialog = try {
            WaitDialog.show(activity, R.string.tef_printing_nfe)
        } catch (error: Throwable) {
            Log.w(TAG, "WaitDialog printNfe", error)
            null
        }

        Thread({
            var outcomeMessage: String? = null
            var outcomeOk = false
            try {
                Log.d(
                    TAG,
                    "printNfeCupom modelo=${NfeChaveAccessKey.modeloFromChave(chaveNota)} " +
                        "tipo=${NfeChaveAccessKey.documentLabel(chaveNota)} chave=$chaveNota"
                )
                val xmlBytes = cartaoRepository.downloadNfeXml(chaveNota)
                val fiscalReceipt = NfeXmlReceiptFormatter.formatForThermalPrinter(
                    xmlBytes = xmlBytes,
                    chaveNota = chaveNota,
                )
                Log.d(
                    TAG,
                    "printNfeCupom format text=${fiscalReceipt.text.length} " +
                        "after=${fiscalReceipt.textAfterBarCode?.length ?: 0} " +
                        "qr=${fiscalReceipt.qrCode?.length ?: 0} " +
                        "barcode=${fiscalReceipt.accessKeyBarCode?.length ?: 0}",
                )
                val isNfce = NfeChaveAccessKey.modeloFromChave(chaveNota) != "55"
                if (isNfce && fiscalReceipt.qrCode.isNullOrBlank()) {
                    throw IllegalStateException(
                        "QR Code da NFC-e ausente no XML baixado (chave=$chaveNota).",
                    )
                }
                // GEDI no worker (igual comprovante TEF) — evita deadlock com WaitDialog na UI.
                GertecReceiptPrinter.printNfeCupom(activity, fiscalReceipt)
                outcomeOk = true
                outcomeMessage = activity.getString(R.string.tef_print_nfe_success)
            } catch (error: Throwable) {
                Log.w(TAG, "printNfeCupomAsync", error)
                val mapped = GertecReceiptPrinter.mapPrintError(error)
                outcomeMessage = mapped.message?.ifBlank { null }
                    ?: error.message
                    ?: activity.getString(R.string.tef_print_nfe_error, "falha desconhecida")
            } finally {
                activity.runOnUiThread {
                    try {
                        waitDialog?.dismiss()
                    } catch (_: Throwable) {
                    }
                    val message = outcomeMessage.orEmpty()
                    if (message.isNotBlank()) {
                        appendStatus(
                            if (outcomeOk) {
                                message
                            } else {
                                activity.getString(R.string.tef_print_nfe_error, message)
                            }
                        )
                        showOperatorMessage(message)
                        Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
                    }
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

    /**
     * Estágio 1 ou Continua com retorno != 0: encerra a UI sem finishTransaction.
     * Evita dupla Finaliza e erro de processo interativo na homologação.
     */
    private fun finishInteractiveStageFailure(resultCode: Int, message: String) {
        Log.d(TAG, "interactiveStageFailure code=$resultCode — sem finalize")
        clearFiscalIfNoPending()
        TefSessionGuard.markSessionClean()
        finishWithError(message)
    }

    private fun finishWithError(message: String) {
        // Durante Voltar, reabre o menu via Sales em vez de ficar reiniciando a mesma sessão.
        if (goingBackToPreviousMenu.get() &&
            !transactionApproved &&
            operationMode == TefOperationMode.SALE
        ) {
            Log.d(TAG, "finishWithError no Voltar → reopen: $message")
            finishGoBackAndReopenPayment()
            return
        }
        TefTraceLog.write(activity, "finishWithError $message")
        pinEntryArmed.set(false)
        if (!settled.compareAndSet(false, true)) return
        supervisorPasswordPending = false
        supervisorPasswordMinLength = 0
        cancelAbortFallback()
        cancelReceiptFinalize()
        cancelPendingConfirmWatchdog()
        cancelGoBackFailsafe()
        cancelGoBackHardTimeout()
        dismissActiveDialog()
        running.set(false)
        onHidePixQrCode()
        onOperatorMessage(message)
        onFinished(false, message, null)
    }

    companion object {
        private const val TAG = "CliSiTefCtrl"
        /** Folga após closePinPad antes de startTransaction. */
        private const val PINPAD_REARM_MS = 300L
        /** Tentativas de limpeza do erro -12 (em background). */
        private const val LIBERATE_MAX_ATTEMPTS = 4
        private const val LIBERATE_RETRY_MS = 300L
        private const val ABORT_FALLBACK_MS = 800L
        /** Tempo máximo aguardando o abort do Voltar antes de reabrir o menu à força. */
        private const val GO_BACK_FAILSAFE_MS = 20000L
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
        private const val LEFTOVER_CLOSE_TIMEOUT_MS = 3000L
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
