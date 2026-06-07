package com.appsitef.smartpos.sales.network

import com.appsitef.smartpos.sales.model.Abastecimento
import java.net.URLEncoder

/**
 * Parâmetros do POST DataSnap — espelha Delphi [Tdmpri.AbastecimentoSelecionado]
 * com segmentos de URL (`pkURLSEGMENT`), não JSON no body.
 */
data class AbastecimentoPostRequest(
    val tipo: String = "0",
    val bomba: String,
    val numero: String,
    val cartaonsu: String = "",
    val cartaohora: String = "",
) {
    fun toPathSegments(): String {
        return listOf(tipo, bomba, numero, cartaonsu, cartaohora)
            .joinToString(separator = "/", postfix = "/") { encodeSegment(it) }
    }

    companion object {
        const val TIPO_SELECAO = "0"
        const val TIPO_PAGAMENTO = "1"
        const val TIPO_LIBERACAO = "2"

        fun selecao(abastecimento: Abastecimento): AbastecimentoPostRequest {
            return fromAbastecimento(abastecimento, TIPO_SELECAO)
        }

        fun pagamento(
            abastecimento: Abastecimento,
            cartaonsu: String,
            cartaohora: String,
        ): AbastecimentoPostRequest {
            return fromAbastecimento(
                abastecimento = abastecimento,
                tipo = TIPO_PAGAMENTO,
                cartaonsu = cartaonsu,
                cartaohora = cartaohora,
            )
        }

        fun liberacao(
            abastecimento: Abastecimento,
            cartaonsu: String = "",
            cartaohora: String = "",
        ): AbastecimentoPostRequest {
            return fromAbastecimento(
                abastecimento = abastecimento,
                tipo = TIPO_LIBERACAO,
                cartaonsu = cartaonsu,
                cartaohora = cartaohora,
            )
        }

        fun fromAbastecimento(
            abastecimento: Abastecimento,
            tipo: String = TIPO_SELECAO,
            cartaonsu: String = "",
            cartaohora: String = "",
        ): AbastecimentoPostRequest {
            return AbastecimentoPostRequest(
                tipo = tipo,
                bomba = abastecimento.ababmb,
                numero = abastecimento.abanum.toString(),
                cartaonsu = cartaonsu,
                cartaohora = cartaohora,
            )
        }

        private fun encodeSegment(value: String): String {
            return URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")
        }
    }
}
