package com.appsitef.smartpos

import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.appsitef.smartpos.util.LogSender

class AdministrativeTefActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_administrative_tef)

        findViewById<Button>(R.id.btnReimpressao).setOnClickListener {
            showMessage("Reimpressao acionada")
        }

        findViewById<Button>(R.id.btnCancelamento).setOnClickListener {
            showMessage("Cancelamento acionado")
        }

        findViewById<Button>(R.id.btnFinalizarTerminal).setOnClickListener {
            showMessage("Finalizar terminal acionado")
        }

        findViewById<Button>(R.id.btnEnviarLog).setOnClickListener {
            val status = LogSender.sendLog("log_tef.txt")
            showMessage(status)
        }
    }

    private fun showMessage(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
