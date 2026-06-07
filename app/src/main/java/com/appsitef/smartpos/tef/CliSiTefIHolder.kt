package com.appsitef.smartpos.tef

import android.app.Activity
import br.com.softwareexpress.sitef.android.CliSiTefI

/**
 * Espelha o GPOS Delphi: [CliSiTefI] com a Activity visível da venda + [setActivity].
 */
object CliSiTefIHolder {

    @Volatile
    private var instance: CliSiTefI? = null

    /** Inicialização na thread principal — manual §7.1 (setActivity no onCreate da Activity de venda). */
    fun warmUp(activity: Activity) {
        synchronized(this) {
            val cli = instance ?: CliSiTefI(activity).also { instance = it }
            cli.setActivity(activity)
            cli.setDebug(true)
        }
    }

    /** Nova instância por transação — construtor e [setActivity] na Activity TEF visível. */
    fun bindForTransaction(activity: Activity): CliSiTefI {
        synchronized(this) {
            val cli = CliSiTefI(activity).also { instance = it }
            cli.setActivity(activity)
            cli.setDebug(true)
            return cli
        }
    }

    fun bind(activity: Activity): CliSiTefI = bindForTransaction(activity)
}
