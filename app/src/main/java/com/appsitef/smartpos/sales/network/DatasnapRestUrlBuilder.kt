package com.appsitef.smartpos.sales.network

import android.content.Context
import com.appsitef.smartpos.tef.TefPreferences

object DatasnapRestUrlBuilder {

    fun buildBaseUrl(context: Context): String {
        TefPreferences.loadModuloIniIfExists(context)

        val connectionType = TefPreferences.getConnectionType(context)
        val host = if (connectionType.equals("CHIP", ignoreCase = true)) {
            TefPreferences.getChipHost(context)
        } else {
            TefPreferences.getSitefHost(context)
        }
        val port = TefPreferences.getPort(context)

        if (host.isBlank() || port.isBlank()) {
            error("Configure host e porta em Configuracao antes de consultar o servidor.")
        }

        return "http://$host:$port"
    }
}
