package com.appsitef.smartpos.sales.network

import android.content.Context

object AbastecimentoUrlBuilder {

    private const val PATH = "/datasnap/rest/TsmAbastecimentos/abastecimentos"

    /**
     * GET Delphi: `Execute(..., 'abastecimentos', LParams, rmGET)` com
     * `bomba` + `operador` em segmentos → `.../abastecimentos/{bico}/{operador}/`
     *
     * Só um segmento gera HTTP 300 (ambíguo entre Abastecimentos e AcceptAbastecimentos).
     */
    fun buildAbastecimentosUrl(
        context: Context,
        bico: String,
        operador: String = "",
    ): String {
        val bicoSegment = formatBicoForUrl(bico)
        val operadorSegment = operador.trim()
        return "${buildBaseUrl(context)}$PATH/$bicoSegment/$operadorSegment/"
    }

    /**
     * POST seleção — Delphi: `Execute('TsmAbastecimentos', baseRest, 'abastecimentos', LParams, rmPOST)`
     * URL: `.../datasnap/rest/TsmAbastecimentos/abastecimentos/{tipo}/{bomba}/{numero}/{cartaonsu}/{cartaohora}/`
     */
    fun buildAbastecimentosSelecaoUrl(
        context: Context,
        request: AbastecimentoPostRequest
    ): String {
        return "${buildRestBaseUrl(context)}/abastecimentos/${request.toPathSegments()}"
    }

    private fun buildRestBaseUrl(context: Context): String {
        return "${buildBaseUrl(context)}/datasnap/rest/TsmAbastecimentos"
    }

    private fun buildBaseUrl(context: Context): String = DatasnapRestUrlBuilder.buildBaseUrl(context)

    /**
     * Bico informado: 2 dígitos (ex.: 1 -> 01, 12 -> 12).
     * Bico em branco: segmento vazio (lista geral) — o operador ainda vai depois.
     */
    fun formatBicoForUrl(bico: String): String {
        val digits = bico.trim().filter { it.isDigit() }
        if (digits.isEmpty()) return ""
        return digits.padStart(2, '0').take(2)
    }
}
