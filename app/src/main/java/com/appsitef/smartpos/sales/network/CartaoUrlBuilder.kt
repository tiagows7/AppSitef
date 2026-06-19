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

    /** Delphi: `DownloadFile(CHAVENOTA + '-nfe.xml')`. */
    fun buildDownloadFileUrl(context: Context, fileName: String): String {
        val encoded = DatasnapPathEncoder.encodeSegment(fileName)
        return "${buildRestBaseUrl(context)}/DownloadFile/$encoded/"
    }

    fun buildNfeXmlFileName(chaveNota: String): String = "${chaveNota.trim()}-nfe.xml"

    /**
     * Delphi `udmpri.cancela_cartao` / `Execute(..., 'CancelaCartao', LParams, rmPOST)`:
     * `.../CancelaCartao/{PDV}/{NSU_HOST}/{DATA}/{NSU_CANC}/`
     * `DATA` / `DATASITEF` na URL com pontos (`06%2E06%2E2026`), como `cartao_movimento`.
     */
    fun buildCancelaCartaoUrl(
        context: Context,
        pdv: String,
        nsuHost: String,
        data: String,
        nsuCanc: String,
    ): String {
        val segments = listOf(
            DatasnapPathEncoder.encodeSegment(pdv),
            DatasnapPathEncoder.encodeSegment(nsuHost),
            DatasnapPathEncoder.encodeDataSitefSegment(data),
            DatasnapPathEncoder.encodeSegment(nsuCanc),
        )
        return "${buildRestBaseUrl(context)}/CancelaCartao/${segments.joinToString("/")}/"
    }

    private fun buildRestBaseUrl(context: Context): String {
        return "${DatasnapRestUrlBuilder.buildBaseUrl(context)}/datasnap/rest/TSrvMetodosGerais"
    }
}
