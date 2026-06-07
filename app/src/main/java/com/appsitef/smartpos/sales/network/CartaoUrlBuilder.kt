package com.appsitef.smartpos.sales.network

import android.content.Context

object CartaoUrlBuilder {

    /**
     * Delphi: `Execute('TSrvMetodosGerais', baseRest, 'Cartao', LParams, rmPOST)`
     * URL: `.../datasnap/rest/TSrvMetodosGerais/Cartao/{parametro}/{pdv}/.../`
     */
    fun buildCartaoMovimentoUrl(
        context: Context,
        request: CartaoMovimentoPostRequest,
    ): String {
        return "${buildRestBaseUrl(context)}/Cartao/${request.toPathSegments()}"
    }

    private fun buildRestBaseUrl(context: Context): String {
        return "${DatasnapRestUrlBuilder.buildBaseUrl(context)}/datasnap/rest/TSrvMetodosGerais"
    }
}
