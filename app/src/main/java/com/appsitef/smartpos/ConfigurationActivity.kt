package com.appsitef.smartpos

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.RadioButton
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class ConfigurationActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_configuration)

        val rbWifi = findViewById<RadioButton>(R.id.rbWifi)
        val etServidorWifi = findViewById<EditText>(R.id.etServidorWifi)
        val etServidorChip = findViewById<EditText>(R.id.etServidorChip)
        val etServidorPorta = findViewById<EditText>(R.id.etServidorPorta)
        val etPdv = findViewById<EditText>(R.id.etPdv)
        val btnSalvar = findViewById<Button>(R.id.btnSalvarConfiguracao)

        btnSalvar.setOnClickListener {
            val tipoConexao = if (rbWifi.isChecked) "WIFI" else "CHIP"
            val mensagem = """
                Configuracao salva:
                Tipo: $tipoConexao
                WiFi: ${etServidorWifi.text}
                CHIP: ${etServidorChip.text}
                Porta: ${etServidorPorta.text}
                PDV: ${etPdv.text}
            """.trimIndent()

            Toast.makeText(this, mensagem, Toast.LENGTH_LONG).show()
        }
    }
}
