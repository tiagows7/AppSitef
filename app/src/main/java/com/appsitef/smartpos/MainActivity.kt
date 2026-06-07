package com.appsitef.smartpos

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.appsitef.smartpos.tef.CliSiTefAssetInstaller
import com.appsitef.smartpos.tef.CliSiTefIHolder
import com.appsitef.smartpos.tef.TefPreferences

class MainActivity : AppCompatActivity() {

    private lateinit var btnVenda: MaterialButton
    private lateinit var btnAdministrativoTef: MaterialButton
    private lateinit var btnConfiguracao: MaterialButton
    private lateinit var tvSetupHint: TextView

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
    }

    override fun onResume() {
        super.onResume()
        TefPreferences.loadModuloIniIfExists(this)
        updateMenuVisibility()
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
}
