package com.appsitef.smartpos

import android.os.Bundle
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import br.com.softwareexpress.sitef.android.CliSiTef
import com.appsitef.smartpos.sales.AbastecimentoIntentSerializer
import com.appsitef.smartpos.sales.SaleAmountRules
import com.appsitef.smartpos.sales.SaleContextIntentSerializer
import com.appsitef.smartpos.tef.CliSiTefAssetInstaller
import com.appsitef.smartpos.tef.CliSiTefConstants
import com.appsitef.smartpos.tef.CliSiTefHolder
import com.appsitef.smartpos.tef.CliSiTefTransactionController
import com.appsitef.smartpos.tef.GertecPinpadBootstrap
import com.appsitef.smartpos.tef.TefOperatorMessagePanel
import com.appsitef.smartpos.tef.TefOperationMode
import com.appsitef.smartpos.tef.TefPixQrCodePanel
import com.appsitef.smartpos.ui.MoneyInputMask
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView

/**
 * Tela exclusiva TEF com [CliSiTef] v2 + [ICliSiTefListener] (exemplo Fiserv).
 * Área livre para pinpad virtual; PPComp via ContentProvider no manifest.
 */
class TefTransactionActivity : AppCompatActivity() {

    private lateinit var cliSiTef: CliSiTef
    private lateinit var tvStatus: TextView
    private lateinit var scrollStatus: ScrollView
    private lateinit var operatorMessagePanel: TefOperatorMessagePanel
    private lateinit var pixQrCodePanel: TefPixQrCodePanel
    private lateinit var panelCancelConfirm: View
    private lateinit var btnVoltar: MaterialButton
    private lateinit var btnCancelar: MaterialButton
    private var controller: CliSiTefTransactionController? = null
    private val statusLines = StringBuilder()
    private var lastTefResultJson: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tef_transaction)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        CliSiTefAssetInstaller.ensureInstalled(this)
        GertecPinpadBootstrap.ensureGediReady(this)

        tvStatus = findViewById(R.id.tvTefStatus)
        scrollStatus = findViewById(R.id.scrollTefStatus)
        panelCancelConfirm = findViewById(R.id.panelTefCancelConfirm)
        btnVoltar = findViewById(R.id.btnVoltarTef)
        btnCancelar = findViewById(R.id.btnCancelarTef)
        val operatorCard = findViewById<MaterialCardView>(R.id.cardOperatorMessage)
        operatorMessagePanel = TefOperatorMessagePanel(
            card = operatorCard,
            messageView = findViewById(R.id.tvOperatorMessage),
            waitingText = getString(R.string.tef_operator_message_waiting),
        )
        pixQrCodePanel = TefPixQrCodePanel(
            qrCard = findViewById(R.id.cardPixQrCode),
            operatorCard = operatorCard,
            hintView = findViewById(R.id.tvPixQrHint),
            imageView = findViewById(R.id.ivPixQrCode),
            defaultHint = getString(R.string.tef_pix_qr_hint),
        )
        btnCancelar.text = when (intent.getStringExtra(EXTRA_OPERATION_MODE)) {
            TefOperationMode.ADMIN_MENU.name,
            TefOperationMode.ADMIN_REPRINT.name,
            TefOperationMode.ADMIN_PENDING.name,
            TefOperationMode.ADMIN_CANCELLATION.name -> getString(R.string.tef_menu_cancel)
            else -> getString(R.string.tef_cancel_transaction)
        }
        val tvTefTransactionAmount = findViewById<android.widget.TextView>(R.id.tvTefTransactionAmount)

        findViewById<MaterialButton>(R.id.btnTefCancelConfirmYes).setOnClickListener {
            hideCancelConfirm()
            btnCancelar.isEnabled = false
            operatorMessagePanel.show("Cancelando transação…")
            controller?.abort()
        }
        findViewById<MaterialButton>(R.id.btnTefCancelConfirmNo).setOnClickListener {
            hideCancelConfirm()
        }

        cliSiTef = CliSiTefHolder.bindTransactionActivity(this)

        onBackPressedDispatcher.addCallback(this) {
            if (panelCancelConfirm.visibility == View.VISIBLE) {
                hideCancelConfirm()
            } else {
                confirmAndAbortTef()
            }
        }

        if (!CliSiTefTransactionController.isSdkPresent()) {
            showFatal("SDK CliSiTef não encontrado.")
            return
        }

        val amount = intent.getStringExtra(EXTRA_AMOUNT).orEmpty()
        val operator = intent.getStringExtra(EXTRA_OPERATOR).orEmpty()
        val functionId = intent.getIntExtra(EXTRA_FUNCTION, CliSiTefConstants.FUNCTION_MENU)
        val operationMode = intent.getStringExtra(EXTRA_OPERATION_MODE)
            ?.let { runCatching { TefOperationMode.valueOf(it) }.getOrNull() }
            ?: TefOperationMode.SALE
        val saleAbastecimentos = AbastecimentoIntentSerializer.fromJson(
            intent.getStringExtra(EXTRA_SALE_ABASTECIMENTOS_JSON)
        )
        val saleContext = SaleContextIntentSerializer.fromJson(
            intent.getStringExtra(EXTRA_SALE_CONTEXT_JSON)
        )

        if (amount.isBlank() && operationMode == TefOperationMode.SALE) {
            showFatal("Valor da venda não informado.")
            return
        }

        if (operationMode == TefOperationMode.SALE) {
            val saleLimitCents = SaleAmountRules.netTotalCents(saleAbastecimentos)
            val amountCents = MoneyInputMask.parseToCents(amount)
            if (SaleAmountRules.exceedsLimitCents(amountCents, saleLimitCents)) {
                showFatal(
                    getString(
                        R.string.error_sale_value_exceeds_total,
                        MoneyInputMask.formatFromCents(saleLimitCents),
                    )
                )
                return
            }
        }

        tvTefTransactionAmount.text = when (operationMode) {
            TefOperationMode.ADMIN_CANCELLATION ->
                getString(R.string.tef_admin_cancellation_label)
            TefOperationMode.ADMIN_REPRINT ->
                getString(R.string.tef_admin_reprint_label)
            TefOperationMode.ADMIN_MENU ->
                getString(R.string.tef_admin_menu_label)
            TefOperationMode.ADMIN_PENDING ->
                getString(R.string.tef_admin_pending_label)
            TefOperationMode.SALE -> formatAmountForDisplay(amount)
        }

        controller = CliSiTefTransactionController(
            activity = this,
            cliSiTef = cliSiTef,
            saleAbastecimentos = saleAbastecimentos,
            saleContext = saleContext,
            operationMode = operationMode,
            onStatus = { line -> appendStatus(line) },
            onOperatorMessage = { message -> operatorMessagePanel.show(message) },
            onShowPixQrCode = { payload, hint -> pixQrCodePanel.show(payload, hint) },
            onHidePixQrCode = { pixQrCodePanel.hide() },
            onPixQrMessage = { message -> pixQrCodePanel.updateHint(message) },
            onFinished = { success, message, result ->
                hideCancelConfirm()
                pixQrCodePanel.hide()
                btnVoltar.visibility = View.GONE
                btnVoltar.isEnabled = false
                appendStatus(message)
                btnCancelar.isEnabled = false
                if (success) {
                    lastTefResultJson = result?.toJson()
                    setResult(RESULT_OK, android.content.Intent().apply {
                        putExtra(EXTRA_TEF_RESULT_JSON, lastTefResultJson)
                        putExtra(EXTRA_TEF_MESSAGE, message)
                    })
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                    val delay = when (operationMode) {
                        TefOperationMode.ADMIN_PENDING -> FINISH_DELAY_PENDING_SUCCESS_MS
                        else -> FINISH_DELAY_SUCCESS_MS
                    }
                    btnCancelar.postDelayed({ finish() }, delay)
                } else {
                    // Negação / erro: diálogo só com OK para o operador ler o retorno.
                    setResult(
                        RESULT_CANCELED,
                        android.content.Intent().apply {
                            putExtra(EXTRA_TEF_MESSAGE, message)
                        },
                    )
                    operatorMessagePanel.show(message)
                    AlertDialog.Builder(this)
                        .setTitle(R.string.tef_transaction_result_title)
                        .setMessage(message)
                        .setCancelable(false)
                        .setPositiveButton(android.R.string.ok) { _, _ -> finish() }
                        .show()
                }
            },
            onReopenPaymentMenu = {
                hideCancelConfirm()
                pixQrCodePanel.hide()
                btnVoltar.visibility = View.GONE
                btnVoltar.isEnabled = false
                btnCancelar.isEnabled = false
                setResult(
                    RESULT_CANCELED,
                    android.content.Intent().apply {
                        putExtra(EXTRA_TEF_REOPEN_PAYMENT, true)
                        putExtra(EXTRA_AMOUNT, amount)
                        putExtra(EXTRA_OPERATOR, operator)
                        putExtra(EXTRA_FUNCTION, functionId)
                    },
                )
                finish()
            },
        )

        btnVoltar.setOnClickListener {
            if (!btnVoltar.isEnabled || isFinishing) return@setOnClickListener
            // Fecha a tela na hora (sem esperar abort/timeouts do CliSiTef).
            btnVoltar.isEnabled = false
            btnCancelar.isEnabled = false
            controller?.goBackToPreviousMenu()
        }
        btnCancelar.setOnClickListener {
            if (btnCancelar.isEnabled) {
                confirmAndAbortTef()
            }
        }

        operatorMessagePanel.show(getString(R.string.tef_operator_message_waiting))
        appendStatus("Conectando pinpad / SiTef…")
        val effectiveAmount = amount.ifBlank { "0" }
        controller?.startSale(
            functionId = functionId,
            amount = effectiveAmount,
            operator = operator,
            restrictions = ""
        )
    }

    /** Confirmação obrigatória na própria tela antes de abortar a rotina TEF. */
    fun confirmAndAbortTef() {
        if (isFinishing) return
        if (!btnCancelar.isEnabled) return
        if (panelCancelConfirm.visibility == View.VISIBLE) return

        panelCancelConfirm.visibility = View.VISIBLE
        panelCancelConfirm.bringToFront()
        panelCancelConfirm.requestFocus()
    }

    private fun hideCancelConfirm() {
        panelCancelConfirm.visibility = View.GONE
    }

    override fun onResume() {
        super.onResume()
        if (::cliSiTef.isInitialized) {
            cliSiTef.setActivity(this)
        }
    }

    private fun appendStatus(line: String) {
        if (line.isBlank()) return
        if (statusLines.isNotEmpty()) statusLines.append('\n')
        statusLines.append(line)
        tvStatus.text = statusLines.toString()
        scrollStatus.post { scrollStatus.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun formatAmountForDisplay(raw: String): String {
        val value = MoneyInputMask.parseToDouble(raw)
        return if (value > 0.0) {
            "R$ ${MoneyInputMask.formatFromDouble(value)}"
        } else {
            "R$ $raw"
        }
    }

    private fun showFatal(message: String) {
        tvStatus.text = message
        btnCancelar.setOnClickListener { finish() }
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    override fun onDestroy() {
        hideCancelConfirm()
        controller?.release()
        controller = null
        super.onDestroy()
    }

    companion object {
        private const val FINISH_DELAY_SUCCESS_MS = 1200L
        private const val FINISH_DELAY_PENDING_SUCCESS_MS = 4500L
        private const val FINISH_DELAY_ERROR_MS = 2200L

        const val EXTRA_AMOUNT = "extra_tef_amount"
        const val EXTRA_OPERATOR = "extra_tef_operator"
        const val EXTRA_FUNCTION = "extra_tef_function"
        const val EXTRA_OPERATION_MODE = "extra_tef_operation_mode"
        const val EXTRA_SALE_ABASTECIMENTOS_JSON = "extra_sale_abastecimentos_json"
        const val EXTRA_SALE_CONTEXT_JSON = "extra_sale_context_json"
        const val EXTRA_TEF_RESULT_JSON = "extra_tef_result_json"
        /** Mensagem de retorno (sucesso) para a Activity chamadora. */
        const val EXTRA_TEF_MESSAGE = "extra_tef_message"
        /** Resultado do Voltar: SalesActivity deve reabrir a tela TEF no menu de pagamento. */
        const val EXTRA_TEF_REOPEN_PAYMENT = "extra_tef_reopen_payment"

        fun intent(
            context: android.content.Context,
            amount: String,
            operator: String,
            functionId: Int = CliSiTefConstants.FUNCTION_MENU,
            operationMode: TefOperationMode = TefOperationMode.SALE,
            saleAbastecimentosJson: String = "[]",
            saleContextJson: String = "{}",
        ) = android.content.Intent(context, TefTransactionActivity::class.java).apply {
            putExtra(EXTRA_AMOUNT, amount)
            putExtra(EXTRA_OPERATOR, operator)
            putExtra(EXTRA_FUNCTION, functionId)
            putExtra(EXTRA_OPERATION_MODE, operationMode.name)
            putExtra(EXTRA_SALE_ABASTECIMENTOS_JSON, saleAbastecimentosJson)
            putExtra(EXTRA_SALE_CONTEXT_JSON, saleContextJson)
        }
    }
}
