package com.appsitef.smartpos

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class TefTransactionActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tef_transaction)
        setSupportActionBar(findViewById(R.id.toolbarTefTransaction))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)

        val tvStatus = findViewById<TextView>(R.id.tvTefStatus)
        tvStatus.text = "Fluxo TEF indisponivel neste APK.\nAdicione o clisitef-android.jar em app/libs para habilitar."

        findViewById<Button>(R.id.btnCancelarTef).setOnClickListener {
            finish()
        }

        Toast.makeText(
            this,
            "APK de homologacao gerado para instalacao no GPOS720.",
            Toast.LENGTH_LONG
        ).show()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
