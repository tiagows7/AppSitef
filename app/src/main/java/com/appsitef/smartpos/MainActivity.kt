package com.appsitef.smartpos

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import com.appsitef.smartpos.tef.TefPreferences

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TefPreferences.loadModuloIniIfExists(this)
        setContentView(R.layout.activity_main)

        findViewById<Button>(R.id.btnVenda).setOnClickListener {
            startActivity(Intent(this, SalesActivity::class.java))
        }

        findViewById<Button>(R.id.btnAdministrativoTef).setOnClickListener {
            startActivity(Intent(this, AdministrativeTefActivity::class.java))
        }

        findViewById<Button>(R.id.btnConfiguracao).setOnClickListener {
            startActivity(Intent(this, ConfigurationActivity::class.java))
        }
    }
}
