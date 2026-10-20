package com.appsitef.smartpos

import android.app.Application
import com.appsitef.smartpos.tef.GertecPinpadBootstrap
import com.appsitef.smartpos.tef.GertecTefPinpad
import com.appsitef.smartpos.tef.TefTraceLog

class SmartPosApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            TefTraceLog.write(
                this,
                "CRASH ${thread.name}: ${error.stackTraceToString()}",
            )
            previous?.uncaughtException(thread, error)
        }
        if (GertecTefPinpad.shouldInitGediBeforeSale()) {
            GertecPinpadBootstrap.initAsync(this)
        }
    }
}
