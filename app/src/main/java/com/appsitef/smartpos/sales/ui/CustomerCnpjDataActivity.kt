package com.appsitef.smartpos.sales.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.appsitef.smartpos.R
import com.appsitef.smartpos.sales.ClienteCnpjDataSerializer
import com.appsitef.smartpos.sales.model.ClienteCnpjData
import com.appsitef.smartpos.ui.DocumentInputMask
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText

/**
 * Revisão/edição dos dados retornados pela consulta de CNPJ antes de salvar na venda.
 */
class CustomerCnpjDataActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_customer_cnpj_data)
        setSupportActionBar(findViewById(R.id.toolbarCustomerCnpjData))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)

        val initial = ClienteCnpjDataSerializer.fromJson(intent.getStringExtra(EXTRA_DATA_JSON))
            ?: run {
                Toast.makeText(this, R.string.customer_cnpj_data_missing, Toast.LENGTH_LONG).show()
                finish()
                return
            }

        val etCnpj = findViewById<TextInputEditText>(R.id.etCnpjReview)
        val etNome = findViewById<TextInputEditText>(R.id.etNomeReview)
        val etEndereco = findViewById<TextInputEditText>(R.id.etEnderecoReview)
        val etNumero = findViewById<TextInputEditText>(R.id.etNumeroEnderecoReview)
        val etBairro = findViewById<TextInputEditText>(R.id.etBairroReview)
        val etCidade = findViewById<TextInputEditText>(R.id.etCidadeReview)
        val etIe = findViewById<TextInputEditText>(R.id.etInscricaoEstadualReview)
        val etUf = findViewById<TextInputEditText>(R.id.etUfReview)

        DocumentInputMask.setDigits(etCnpj, initial.cnpj)
        etNome.setText(initial.nome)
        etEndereco.setText(initial.endereco)
        etNumero.setText(initial.numeroEndereco)
        etBairro.setText(initial.bairro)
        etCidade.setText(initial.cidade)
        etIe.setText(initial.inscricaoEstadual)
        etUf.setText(initial.uf)

        findViewById<MaterialButton>(R.id.btnFecharCustomerCnpjData).setOnClickListener {
            val nome = etNome.text?.toString()?.trim().orEmpty()
            if (nome.isBlank()) {
                etNome.error = getString(R.string.customer_cnpj_nome_required)
                etNome.requestFocus()
                return@setOnClickListener
            }

            val saved = ClienteCnpjData(
                cnpj = DocumentInputMask.getDigits(etCnpj),
                nome = nome,
                endereco = etEndereco.text?.toString()?.trim().orEmpty(),
                numeroEndereco = etNumero.text?.toString()?.trim().orEmpty(),
                bairro = etBairro.text?.toString()?.trim().orEmpty(),
                cidade = etCidade.text?.toString()?.trim().orEmpty(),
                inscricaoEstadual = etIe.text?.toString()?.trim().orEmpty(),
                uf = etUf.text?.toString()?.trim().orEmpty().uppercase(),
            )

            setResult(
                RESULT_OK,
                Intent().putExtra(RESULT_DATA_JSON, ClienteCnpjDataSerializer.toJson(saved)),
            )
            finish()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    companion object {
        const val EXTRA_DATA_JSON = "customer_cnpj_data_json"
        const val RESULT_DATA_JSON = "customer_cnpj_result_json"

        fun intent(context: Context, data: ClienteCnpjData): Intent {
            return Intent(context, CustomerCnpjDataActivity::class.java)
                .putExtra(EXTRA_DATA_JSON, ClienteCnpjDataSerializer.toJson(data))
        }
    }
}
