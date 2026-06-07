package com.appsitef.smartpos.sales.network

import android.content.Context

object ClienteUrlBuilder {

    /**
     * Delphi: `Execute('TSrvMetodosGerais', baseRest, 'Cliente', LParams, rmGET)`
     * com `CODIGO` em segmento de URL → `.../cliente/{CODIGO}/`
     */
    fun buildClienteUrl(context: Context, codigo: String): String {
        val codigoSegment = codigo.trim()
        if (codigoSegment.isEmpty()) {
            error("Informe o código do cliente.")
        }
        return "${DatasnapRestUrlBuilder.buildBaseUrl(context)}/datasnap/rest/TSrvMetodosGerais/cliente/$codigoSegment/"
    }
}
