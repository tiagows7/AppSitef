package com.appsitef.smartpos.tef

import android.app.Activity
import android.content.Context
import android.util.Log
import br.com.gertec.gedi.GEDI

/**
 * Pinpad interno Gertec (GPOS700 / GPOS720): inicializa GEDI para impressão térmica.
 *
 * Não chama [PPComp.PP_Open]: o pinpad da venda é o pinpad virtual do CliSiTef ([CliSiTef.setActivity]),
 * conforme manual Android §7.1. Libs PPComp/GEDI vêm do product flavor ([BuildConfig.GERTEC_MODEL]).
 */
object GertecPinpadBootstrap {

    private const val TAG = "GertecPinpadBootstrap"

    @Synchronized
    fun ensureGediReady(context: Context) {
        val host = if (context is Activity) context else context.applicationContext
        try {
            GEDI.init(host)
            GEDI.getInstance(host)
        } catch (error: Throwable) {
            Log.e(TAG, "GEDI init", error)
        }
    }

    fun initAsync(context: Context) {
        Thread({
            ensureGediReady(context)
        }, "gertec-gedi-init").start()
    }
}
