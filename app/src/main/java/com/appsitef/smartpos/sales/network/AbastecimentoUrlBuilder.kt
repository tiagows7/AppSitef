package com.appsitef.smartpos.sales.network

import android.content.Context

object AbastecimentoUrlBuilder {

    private const val PATH = "/datasnap/rest/TsmAbastecimentos/abastecimentos"
    private const val PATH_WITH_SLASH = "$PATH/"

    fun buildAbastecimentosUrl(context: Context, bico: String): String {
        val bicoSegment = formatBicoForUrl(bico)
        return "${buildBaseUrl(context)}$PATH_WITH_SLASH$bicoSegment"
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
     * Bico informado: envia com 2 dígitos (ex.: 1 -> 01, 2 -> 02, 12 -> 12).
     * Bico em branco: segmento vazio no final da URL.
     */
    fun formatBicoForUrl(bico: String): String {
        val digits = bico.trim().filter { it.isDigit() }
        if (digits.isEmpty()) return ""
        return digits.padStart(2, '0').take(2)
    }
}
