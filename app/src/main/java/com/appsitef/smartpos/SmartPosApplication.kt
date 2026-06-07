package com.appsitef.smartpos

import android.app.Application
import com.appsitef.smartpos.tef.GertecPinpadBootstrap

class SmartPosApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        GertecPinpadBootstrap.initAsync(this)
    }
}
