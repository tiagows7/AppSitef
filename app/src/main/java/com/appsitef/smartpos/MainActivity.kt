package com.appsitef.smartpos

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.appsitef.smartpos.tef.CliSiTefAssetInstaller
import com.appsitef.smartpos.tef.CliSiTefConstants
import com.appsitef.smartpos.tef.CliSiTefIHolder
import com.appsitef.smartpos.tef.TefOperationMode
import com.appsitef.smartpos.tef.TefPendingFiscal
import com.appsitef.smartpos.tef.TefPreferences

class MainActivity : AppCompatActivity() {

    private lateinit var btnVenda: MaterialButton
    private lateinit var btnAdministrativoTef: MaterialButton
    private lateinit var btnConfiguracao: MaterialButton
    private lateinit var tvSetupHint: TextView

    private val pendingTefLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { /* Tratamento já ocorreu na tela TEF (função 130). */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        TefPreferences.loadModuloIniIfExists(this)
        CliSiTefAssetInstaller.ensureInstalled(this)
        CliSiTefIHolder.warmUp(this)

        btnVenda = findViewById(R.id.btnVenda)
        btnAdministrativoTef = findViewById(R.id.btnAdministrativoTef)
        btnConfiguracao = findViewById(R.id.btnConfiguracao)
        tvSetupHint = findViewById(R.id.tvSetupHint)

        btnVenda.setOnClickListener {
            startActivity(Intent(this, SalesActivity::class.java))
        }

        btnAdministrativoTef.setOnClickListener {
            startActivity(Intent(this, AdministrativeTefActivity::class.java))
        }

        btnConfiguracao.setOnClickListener {
            startActivity(Intent(this, ConfigurationActivity::class.java))
        }

        updateMenuVisibility()
        maybeCheckPendingTransactions()
    }

    override fun onResume() {
        super.onResume()
        TefPreferences.loadModuloIniIfExists(this)
        updateMenuVisibility()
    }

    /**
     * Ao entrar no app, verifica e trata transações pendentes (queda de energia).
     * Só dispara quando há resquício de venda não confirmada salva em [TefPendingFiscal]
     * e apenas uma vez por processo, para não reabrir a tela TEF a cada retomada.
     */
    private fun maybeCheckPendingTransactions() {
        if (pendingCheckDone) return
        if (!TefPreferences.isConfigured(this)) return

        val hasPending = TefPendingFiscal.load(this) != null ||
            TefPendingFiscal.loadHistory(this).isNotEmpty()
        if (!hasPending) {
            pendingCheckDone = true
            return
        }

        pendingCheckDone = true
        Toast.makeText(this, R.string.tef_pending_checking, Toast.LENGTH_SHORT).show()
        window.decorView.postDelayed({
            if (isFinishing) return@postDelayed
            try {
                pendingTefLauncher.launch(
                    TefTransactionActivity.intent(
                        context = this,
                        amount = "0",
                        operator = TefPreferences.getOperator(this),
                        functionId = CliSiTefConstants.FUNCTION_PENDING,
                        operationMode = TefOperationMode.ADMIN_PENDING,
                    )
                )
            } catch (error: Throwable) {
                Log.e(TAG, "Falha ao verificar transações pendentes no início", error)
            }
        }, PENDING_CHECK_DELAY_MS)
    }

    private fun updateMenuVisibility() {
        val configured = TefPreferences.isConfigured(this)

        btnVenda.visibility = if (configured) View.VISIBLE else View.GONE
        btnAdministrativoTef.visibility = if (configured) View.VISIBLE else View.GONE
        tvSetupHint.visibility = if (configured) View.GONE else View.VISIBLE

        if (!configured) {
            btnVenda.isEnabled = false
            btnAdministrativoTef.isEnabled = false
        } else {
            btnVenda.isEnabled = true
            btnAdministrativoTef.isEnabled = true
        }
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val PENDING_CHECK_DELAY_MS = 600L

        /** Uma verificação por processo (evita reabrir a tela TEF em cada retomada). */
        private var pendingCheckDone = false
    }
}
