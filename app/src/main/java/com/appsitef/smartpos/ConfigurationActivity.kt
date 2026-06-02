package com.appsitef.smartpos

import android.os.Bundle
import android.widget.RadioButton
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.appsitef.smartpos.tef.TefPreferences
import com.appsitef.smartpos.ui.WaitDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

class ConfigurationActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_configuration)
        setSupportActionBar(findViewById(R.id.toolbarConfiguration))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)

        val rbWifi = findViewById<RadioButton>(R.id.rbWifi)
        val rbChip = findViewById<RadioButton>(R.id.rbChip)
        val etServidorWifi = findViewById<TextInputEditText>(R.id.etServidorWifi)
        val etServidorChip = findViewById<TextInputEditText>(R.id.etServidorChip)
        val etServidorPorta = findViewById<TextInputEditText>(R.id.etServidorPorta)
        val etPdv = findViewById<TextInputEditText>(R.id.etPdv)
        val btnSalvar = findViewById<MaterialButton>(R.id.btnSalvarConfiguracao)

        loadSavedConfigurationIntoFields(rbWifi, rbChip, etServidorWifi, etServidorChip, etServidorPorta, etPdv)

        btnSalvar.setOnClickListener {
            val tipoConexao = if (rbWifi.isChecked) "WIFI" else "CHIP"
            val wifiHost = etServidorWifi.text.toString().trim()
            val chipHost = etServidorChip.text.toString().trim()
            val port = etServidorPorta.text.toString().trim()
            val pdv = etPdv.text.toString().trim()
            val selectedHost = if (tipoConexao == "CHIP") chipHost else wifiHost

            if (selectedHost.isBlank() || port.isBlank() || pdv.isBlank()) {
                Toast.makeText(
                    this,
                    "Preencha host, porta e PDV para continuar.",
                    Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }

            val endpointUrl =
                "http://$selectedHost:$port/datasnap/rest/TSrvMetodosGerais/terminal/$pdv"

            lifecycleScope.launch {
                val waitDialog = WaitDialog.show(
                    this@ConfigurationActivity,
                    R.string.wait_message_saving_configuration
                )
                btnSalvar.isEnabled = false
                try {
                    val terminalConfig = withContext(Dispatchers.IO) {
                        fetchTerminalConfig(endpointUrl)
                    }
                    TefPreferences.saveConfigurationFromTerminal(
                        context = this@ConfigurationActivity,
                        wifiHost = wifiHost,
                        chipHost = chipHost,
                        port = port,
                        pdv = pdv,
                        connectionType = tipoConexao,
                        terminal = terminalConfig
                    )
                    TefPreferences.saveTransactionDefaults(
                        this@ConfigurationActivity,
                        operator = pdv.ifBlank { "0001" },
                        amount = "100",
                        coupon = "1",
                        restrictions = "",
                        additionalParams = ""
                    )
                    Toast.makeText(
                        this@ConfigurationActivity,
                        "GPOS CONFIGURADO COM SUCESSO",
                        Toast.LENGTH_LONG
                    ).show()
                    finish()
                } catch (error: Exception) {
                    Toast.makeText(
                        this@ConfigurationActivity,
                        "Erro ao configurar terminal: ${error.message}",
                        Toast.LENGTH_LONG
                    ).show()
                } finally {
                    waitDialog.dismiss()
                    if (!isFinishing) {
                        btnSalvar.isEnabled = true
                    }
                }
            }
        }
    }

    private fun loadSavedConfigurationIntoFields(
        rbWifi: RadioButton,
        rbChip: RadioButton,
        etServidorWifi: TextInputEditText,
        etServidorChip: TextInputEditText,
        etServidorPorta: TextInputEditText,
        etPdv: TextInputEditText
    ) {
        TefPreferences.loadModuloIniIfExists(this)

        etServidorWifi.setText(TefPreferences.getSitefHost(this))
        etServidorChip.setText(TefPreferences.getChipHost(this))
        etServidorPorta.setText(TefPreferences.getPort(this))
        etPdv.setText(TefPreferences.getOperator(this))

        val isChip = TefPreferences.getConnectionType(this).equals("CHIP", ignoreCase = true)
        rbWifi.isChecked = !isChip
        rbChip.isChecked = isChip
    }

    private fun fetchTerminalConfig(url: String): TefPreferences.TerminalRestConfig {
        val client = OkHttpClient.Builder().build()
        val request = Request.Builder()
            .url(url)
            .header("Authorization", Credentials.basic("modulo-info", "@Modulo2023@"))
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                error("HTTP ${response.code()} ao consultar terminal.")
            }
            val body = response.body()?.string()?.trim().orEmpty()
            if (body.isBlank()) error("Resposta vazia do servidor.")

            val json = parseJsonPayload(body)

            return TefPreferences.TerminalRestConfig(
                tefIp = json.optString("TEF_IP"),
                tefIdTerminal = json.optString("TEF_IDTERMINAL"),
                tefIdLoja = json.optString("TEF_IDLOJA"),
                tefCnpj = json.optString("TEF_CNPJ"),
                tefIsDoubleValidation = json.optString("TEF_ISDOUBLEVALIDATION"),
                tefOtp = json.optString("TEF_OTP"),
                tefCnpjAutomacao = json.optString("TEF_CNPJAUTOMACAO"),
                tefTransacoesHabilitadas = json.optString("TEF_TRANSACAOHABILITADAS"),
                tefObrigadoOperador = json.optString("TEF_OBRIGADOOPERADOR"),
                tefPosTipo = json.optString("TEF_POSTIPO"),
                tefModelo = json.optString("TEF_MODELO"),
                tefImprimeBanri = json.optString("TEF_IMPRIMEBANRI"),
                tefAceitaParcial = json.optString("TEF_ACEITAVALORPARCIAL"),
                tefVendaProduto = json.optString("TEF_VENDAPRODUTO", "N"),
                tefTipoDocumento = json.optString("TEF_TIPODOCUMENTO", "N")
            )
        }
    }

    private fun parseJsonPayload(raw: String): JSONObject {
        return when {
            raw.startsWith("[") -> {
                val arr = JSONArray(raw)
                if (arr.length() == 0) error("JSON retornou lista vazia.")
                arr.getJSONObject(0)
            }
            else -> JSONObject(raw)
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
