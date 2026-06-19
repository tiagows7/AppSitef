package com.appsitef.smartpos

import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.appsitef.smartpos.tef.CliSiTefConstants
import com.appsitef.smartpos.tef.TefOperationMode
import com.appsitef.smartpos.tef.TefPreferences
import com.appsitef.smartpos.util.LogSender
import com.google.android.material.button.MaterialButton

class AdministrativeTefActivity : AppCompatActivity() {

    private val tefOperationLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val messageRes = when (pendingSuccessMessageRes) {
            R.string.tef_admin_cancellation_done -> R.string.tef_admin_cancellation_done
            R.string.tef_admin_reprint_done -> R.string.tef_admin_reprint_done
            R.string.tef_admin_menu_done -> R.string.tef_admin_menu_done
            else -> null
        }
        messageRes?.let { Toast.makeText(this, it, Toast.LENGTH_LONG).show() }
        pendingSuccessMessageRes = 0
    }

    private var pendingSuccessMessageRes: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_administrative_tef)
        setSupportActionBar(findViewById(R.id.toolbarAdministrativeTef))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)

        findViewById<MaterialButton>(R.id.btnAdministrativo).setOnClickListener {
            launchTefOperation(
                functionId = CliSiTefConstants.FUNCTION_ADMINISTRATIVE,
                operationMode = TefOperationMode.ADMIN_MENU,
                successMessageRes = R.string.tef_admin_menu_done,
            )
        }

        findViewById<MaterialButton>(R.id.btnReimpressao).setOnClickListener {
            launchTefOperation(
                functionId = CliSiTefConstants.FUNCTION_REPRINT,
                operationMode = TefOperationMode.ADMIN_REPRINT,
                successMessageRes = R.string.tef_admin_reprint_done,
            )
        }

        findViewById<MaterialButton>(R.id.btnCancelamento).setOnClickListener {
            launchTefOperation(
                functionId = CliSiTefConstants.FUNCTION_CANCELLATION,
                operationMode = TefOperationMode.ADMIN_CANCELLATION,
                successMessageRes = R.string.tef_admin_cancellation_done,
            )
        }

        findViewById<MaterialButton>(R.id.btnFinalizarTerminal).setOnClickListener {
            try {
                startActivity(android.content.Intent(this, FinalizeTerminalActivity::class.java))
            } catch (error: Throwable) {
                Log.e(TAG, "Falha ao abrir FinalizeTerminalActivity", error)
                showMessage(
                    getString(R.string.tef_finalize_open_error, error.message ?: error.javaClass.simpleName)
                )
            }
        }

        findViewById<MaterialButton>(R.id.btnEnviarLog).setOnClickListener {
            val status = LogSender.sendLog("log_tef.txt")
            showMessage(status)
        }
    }

    private fun launchTefOperation(
        functionId: Int,
        operationMode: TefOperationMode,
        successMessageRes: Int,
    ) {
        if (!isTefConfigured()) {
            showMessage(getString(R.string.tef_admin_not_configured))
            return
        }
        if (operationMode == TefOperationMode.ADMIN_REPRINT && !hasOperatorConfigured()) {
            showMessage(getString(R.string.tef_admin_operator_required))
            return
        }

        try {
            pendingSuccessMessageRes = successMessageRes
            val intent = TefTransactionActivity.intent(
                context = this,
                amount = "0",
                operator = TefPreferences.getOperator(this),
                functionId = functionId,
                operationMode = operationMode,
            )
            tefOperationLauncher.launch(intent)
        } catch (error: Throwable) {
            pendingSuccessMessageRes = 0
            Log.e(TAG, "Falha ao abrir operação TEF mode=$operationMode", error)
            showMessage(
                getString(R.string.tef_admin_operation_open_error, error.message ?: error.javaClass.simpleName)
            )
        }
    }

    private fun isTefConfigured(): Boolean {
        return TefPreferences.getSitefConfigureAddress(this).isNotBlank() &&
            TefPreferences.getStoreId(this).isNotBlank() &&
            TefPreferences.getTerminalId(this).isNotBlank()
    }

    private fun hasOperatorConfigured(): Boolean {
        return TefPreferences.getOperator(this).filter { it.isDigit() }.isNotBlank()
    }

    private fun showMessage(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    companion object {
        private const val TAG = "AdministrativeTef"
    }
}
