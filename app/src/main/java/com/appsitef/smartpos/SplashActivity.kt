package com.appsitef.smartpos

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.appsitef.smartpos.tef.CliSiTefAssetInstaller
import com.appsitef.smartpos.tef.GertecPinpadBootstrap
import com.appsitef.smartpos.tef.TefPreferences

class SplashActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        TefPreferences.loadModuloIniIfExists(this)
        CliSiTefAssetInstaller.ensureInstalled(this)
        GertecPinpadBootstrap.initAsync(applicationContext)

        window.decorView.postDelayed({
            if (!isFinishing) {
                startActivity(Intent(this, MainActivity::class.java))
                finish()
            }
        }, SPLASH_DURATION_MS)
    }

    companion object {
        private const val SPLASH_DURATION_MS = 900L
    }
}
