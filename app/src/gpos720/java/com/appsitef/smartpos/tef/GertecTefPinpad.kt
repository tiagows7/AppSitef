package com.appsitef.smartpos.tef

import android.app.Activity

/**
 * GPOS720: o pinpad virtual sobe via ContentProvider Setis. Não chamar PP_Open
 * (disputa o pinpad e gera erro 31).
 */
object GertecTefPinpad {

    fun shouldInitGediBeforeSale(): Boolean = true

    /** GPOS720: probe/liberate ok (TLS/GSurf já têm caminho sem abort em ensureReady). */
    fun shouldProbeAndLiberateBeforeSale(): Boolean = true

    /** GPOS720 Setis: closePinPad só fora de TLS (TLS pula no controller). */
    fun allowsClosePinPadBeforeStart(): Boolean = true

    /** No GPOS720 o pinpad Setis cobre o menu e impede o toque em débito/crédito. */
    fun shouldHidePinpadDuringAppMenu(): Boolean = true

    fun shouldOpenNumericPinpadBeforeContinue(): Boolean = false

    fun prepare(activity: Activity) = Unit

    fun probePinKeys(context: android.content.Context) = Unit

    fun preparePinEntry(
        activity: Activity,
        cliSiTef: br.com.softwareexpress.sitef.android.CliSiTef,
    ) = Unit

    fun ensureNumericPinpadForPin(
        activity: Activity,
        cliSiTef: br.com.softwareexpress.sitef.android.CliSiTef,
    ) = Unit
}
