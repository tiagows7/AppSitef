package com.appsitef.smartpos

import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.appsitef.smartpos.tef.GertecPinpadBootstrap
import com.appsitef.smartpos.tef.GertecReceiptPrinter
import com.appsitef.smartpos.tef.TefPreferences
import com.appsitef.smartpos.tef.TefTerminalTotalsStore
import com.appsitef.smartpos.ui.WaitDialog
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Resumo de fechamento do terminal — espelha aba [tbfinaliza] do Delphi.
 */
class FinalizeTerminalActivity : AppCompatActivity() {

    private lateinit var tvSessionStart: TextView
    private lateinit var tvTotalSales: TextView
    private lateinit var tvTotalCancelled: TextView
    private lateinit var tvTotalNet: TextView
    private lateinit var tvOperatorsTotals: TextView
    private lateinit var btnFinalizeTerminal: MaterialButton

    private var snapshot: TefTerminalTotalsStore.Snapshot =
        TefTerminalTotalsStore.Snapshot("", 0.0, 0.0, emptyList())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_finalize_terminal)
        setSupportActionBar(findViewById(R.id.toolbarFinalizeTerminal))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)

        GertecPinpadBootstrap.ensureGediReady(this)

        tvSessionStart = findViewById(R.id.tvFinalizeSessionStart)
        tvTotalSales = findViewById(R.id.tvFinalizeTotalSales)
        tvTotalCancelled = findViewById(R.id.tvFinalizeTotalCancelled)
        tvTotalNet = findViewById(R.id.tvFinalizeTotalNet)
        tvOperatorsTotals = findViewById(R.id.tvFinalizeOperatorsTotals)
        btnFinalizeTerminal = findViewById(R.id.btnConfirmFinalizeTerminal)

        btnFinalizeTerminal.setOnClickListener { finalizeAndPrint() }
        refreshScreen()
    }

    private fun refreshScreen() {
        snapshot = TefTerminalTotalsStore.getSnapshot(this)
        tvSessionStart.text = if (snapshot.sessionStart.isBlank()) {
            getString(R.string.tef_totals_session_not_started)
        } else {
            getString(R.string.tef_totals_session_start, snapshot.sessionStart)
        }
        tvTotalSales.text = TefTerminalTotalsStore.formatMoney(snapshot.totalSales)
        tvTotalCancelled.text = TefTerminalTotalsStore.formatMoney(snapshot.totalCancelled)
        tvTotalNet.text = TefTerminalTotalsStore.formatMoney(snapshot.netTotal)
        tvOperatorsTotals.text = formatOperatorsSummary(snapshot)
    }

    private fun formatOperatorsSummary(data: TefTerminalTotalsStore.Snapshot): String {
        if (data.operators.isEmpty()) {
            return getString(R.string.tef_totals_operators_empty)
        }
        return data.operators.joinToString(separator = "\n") { item ->
            val label = if (item.operator.isBlank()) {
                getString(R.string.tef_totals_operator_unknown)
            } else {
                getString(R.string.tef_totals_operator_line, item.operator)
            }
            "$label ${TefTerminalTotalsStore.formatMoney(item.amount)}"
        }
    }

    private fun finalizeAndPrint() {
        val closingSnapshot = TefTerminalTotalsStore.getSnapshot(this)
        btnFinalizeTerminal.isEnabled = false
        val waitDialog = WaitDialog.show(this, R.string.tef_finalize_printing)

        lifecycleScope.launch {
            val printError = withContext(Dispatchers.IO) {
                try {
                    val receipt = TefTerminalTotalsStore.buildFinalizeReceipt(
                        snapshot = closingSnapshot,
                        terminalId = TefPreferences.getTerminalId(this@FinalizeTerminalActivity),
                        pdv = TefPreferences.getOperator(this@FinalizeTerminalActivity),
                    )
                    GertecReceiptPrinter.printReceipt(this@FinalizeTerminalActivity, receipt)
                    null
                } catch (error: Throwable) {
                    GertecReceiptPrinter.mapPrintError(error).message
                        ?: error.message
                        ?: getString(R.string.tef_finalize_print_error)
                }
            }

            TefTerminalTotalsStore.resetSession(this@FinalizeTerminalActivity)
            waitDialog.dismiss()
            btnFinalizeTerminal.isEnabled = true

            if (printError != null) {
                Toast.makeText(
                    this@FinalizeTerminalActivity,
                    getString(R.string.tef_finalize_done_print_failed, printError),
                    Toast.LENGTH_LONG
                ).show()
            } else {
                Toast.makeText(
                    this@FinalizeTerminalActivity,
                    R.string.tef_finalize_done,
                    Toast.LENGTH_LONG
                ).show()
            }
            setResult(RESULT_OK)
            finish()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
