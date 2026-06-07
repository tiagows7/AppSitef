package com.appsitef.smartpos.tef

/**
 * Estado da sessão TEF entre aberturas da [com.appsitef.smartpos.TefTransactionActivity].
 * O singleton [br.com.softwareexpress.sitef.android.CliSiTef] pode ficar com processo
 * iterativo aberto (erro -12) mesmo quando [getQttPendingTransactions] retorna 0.
 */
object TefSessionGuard {

    @Volatile
    var needsCleanup: Boolean = false

    @Volatile
    var lastFiscal: TefFiscalRef? = null

    fun markTransactionStarted(coupon: String, date: String, time: String) {
        needsCleanup = true
        lastFiscal = TefFiscalRef(coupon, date, time)
    }

    fun markSessionClean() {
        needsCleanup = false
        lastFiscal = null
    }

    fun markSessionDirty() {
        needsCleanup = true
    }
}
