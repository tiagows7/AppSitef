package com.appsitef.smartpos.tef

import android.app.Activity
import android.content.Context
import br.com.softwareexpress.sitef.android.CliSiTef

/**
 * Uma única instância do CliSiTef por processo.
 *
 * No GPOS720 (pinpad interno), [CliSiTef.setActivity] só deve ser chamado na
 * [com.appsitef.smartpos.TefTransactionActivity] — ver manual CliSiTef Android § pinpad virtual.
 * Chamar em outras telas (ex.: configuração) causa erro 31.
 */
object CliSiTefHolder {

    @Volatile
    private var instance: CliSiTef? = null

    fun get(context: Context): CliSiTef {
        val appContext = context.applicationContext
        return synchronized(this) {
            instance ?: CliSiTef(appContext).also { instance = it }
        }
    }

    /**
     * Obrigatório no onCreate/onResume da activity de venda TEF (GPOS pinpad virtual).
     */
    fun bindTransactionActivity(activity: Activity): CliSiTef {
        val cli = get(activity)
        synchronized(this) {
            cli.setActivity(activity)
        }
        return cli
    }
}
