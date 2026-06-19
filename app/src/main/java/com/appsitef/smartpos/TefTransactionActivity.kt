package com.appsitef.smartpos

import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import br.com.softwareexpress.sitef.android.CliSiTef
import com.appsitef.smartpos.sales.AbastecimentoIntentSerializer
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
        val btnCancelar = findViewById<MaterialButton>(R.id.btnCancelarTef)
        val tvTefTransactionAmount = findViewById<android.widget.TextView>(R.id.tvTefTransactionAmount)

        cliSiTef = CliSiTefHolder.bindTransactionActivity(this)

        onBackPressedDispatcher.addCallback(this) {
            controller?.abort()
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

        tvTefTransactionAmount.text = when (operationMode) {
            TefOperationMode.ADMIN_CANCELLATION ->
                getString(R.string.tef_admin_cancellation_label)
            TefOperationMode.ADMIN_REPRINT ->
                getString(R.string.tef_admin_reprint_label)
            TefOperationMode.ADMIN_MENU ->
                getString(R.string.tef_admin_menu_label)
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
                pixQrCodePanel.hide()
                appendStatus(message)
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                if (success) {
                    lastTefResultJson = result?.toJson()
                    setResult(RESULT_OK, android.content.Intent().apply {
                        putExtra(EXTRA_TEF_RESULT_JSON, lastTefResultJson)
                    })
                } else {
                    setResult(RESULT_CANCELED)
                }
                btnCancelar.isEnabled = false
                val delay = if (success) FINISH_DELAY_SUCCESS_MS else FINISH_DELAY_ERROR_MS
                btnCancelar.postDelayed({ finish() }, delay)
            }
        )

        btnCancelar.setOnClickListener {
            if (btnCancelar.isEnabled) {
                btnCancelar.isEnabled = false
                operatorMessagePanel.show("Cancelando transação…")
                controller?.abort()
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
        findViewById<MaterialButton>(R.id.btnCancelarTef).setOnClickListener { finish() }
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    override fun onDestroy() {
        controller?.release()
        controller = null
        super.onDestroy()
    }

    companion object {
        private const val FINISH_DELAY_SUCCESS_MS = 1200L
        private const val FINISH_DELAY_ERROR_MS = 2200L

        const val EXTRA_AMOUNT = "extra_tef_amount"
        const val EXTRA_OPERATOR = "extra_tef_operator"
        const val EXTRA_FUNCTION = "extra_tef_function"
        const val EXTRA_OPERATION_MODE = "extra_tef_operation_mode"
        const val EXTRA_SALE_ABASTECIMENTOS_JSON = "extra_sale_abastecimentos_json"
        const val EXTRA_SALE_CONTEXT_JSON = "extra_sale_context_json"
        const val EXTRA_TEF_RESULT_JSON = "extra_tef_result_json"

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
