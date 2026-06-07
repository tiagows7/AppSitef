package com.appsitef.smartpos.sales.network

import com.appsitef.smartpos.tef.TefAmountFormatter
import com.appsitef.smartpos.tef.TefTransactionResult
import java.util.Locale

/**
 * Parâmetros do POST DataSnap — espelha Delphi [Tdmpri.cartao_movimento]
 * com segmentos de URL (`pkURLSEGMENT`), não JSON no body.
 */
data class CartaoMovimentoPostRequest(
    val parametro: String = "0",
    val pdv: String,
    val nsuHost: String,
    val dataSitef: String,
    val codTrans: String,
    val valor: String,
    val redeAut: String,
    val bandeira: String,
    val numParc: String,
    val tipoParc: String,
    val nsuSitef: String,
    val codAutorizacao: String,
    val horaSitef: String,
    val preDatado: String,
    val operador: String,
    val terminal: String,
    val cgccpf: String,
    val cliente: String,
    val veiculo: String,
    val km: String,
    val tentativa: String = "0",
) {
    fun toPathSegments(): String {
        val segments = listOf(
            parametro,
            pdv,
            nsuHost,
            dataSitef,
            codTrans,
            valor,
            redeAut,
            bandeira,
            numParc,
            tipoParc,
            nsuSitef,
            codAutorizacao,
            horaSitef,
            preDatado,
            operador,
            terminal,
            cgccpf,
            cliente,
            veiculo,
            km,
            tentativa,
        )
        return segments.mapIndexed { index, value ->
            when (index) {
                3 -> DatasnapPathEncoder.encodeDataSitefSegment(value)
                13 -> DatasnapPathEncoder.encodePreDatadoSegment(value)
                else -> DatasnapPathEncoder.encodeSegment(value)
            }
        }.joinToString(separator = "/", postfix = "/")
    }

    /** Valor legível de `DATASITEF` (`DD.MM.AAAA`) para log/diagnóstico. */
    fun dataSitefFormatado(): String = DatasnapPathEncoder.normalizeDataSitef(dataSitef)

    companion object {
        fun fromTefSale(
            pdv: String,
            terminal: String,
            saleOperator: String,
            saleAmount: String,
            customerCode: String,
            cpfCnpj: String,
            vehicle: String,
            km: String,
            result: TefTransactionResult,
            nsuHost: String,
            horaSitef: String,
            taxInvoiceDate: String,
        ): CartaoMovimentoPostRequest {
            return CartaoMovimentoPostRequest(
                pdv = pdv,
                nsuHost = nsuHost,
                dataSitef = formatDataSitef(
                    sitefDataHoraRaw = result.sitefDataHoraRaw,
                    dataTransacao = result.dataTransacao,
                    taxInvoiceDate = taxInvoiceDate,
                ),
                codTrans = result.codTrans,
                valor = TefAmountFormatter.toCartaoMovimentoValor(saleAmount),
                redeAut = result.redeAut,
                bandeira = result.bandeira,
                numParc = result.parcelas.ifBlank { "0" },
                tipoParc = result.tipoParc,
                nsuSitef = result.nsuSitef,
                codAutorizacao = result.codAutorizacao,
                horaSitef = horaSitef,
                preDatado = formatPreDatado(
                    sitefPreDatadoRaw = result.sitefPreDatadoRaw,
                    dataPreDatado = result.dataPreDatado,
                ),
                operador = formatOperador(saleOperator),
                terminal = terminal,
                cgccpf = cpfCnpj.filter { it.isDigit() },
                cliente = customerCode.trim(),
                veiculo = vehicle.trim(),
                km = km.trim(),
            )
        }

        private fun formatDataSitef(
            sitefDataHoraRaw: String,
            dataTransacao: String,
            taxInvoiceDate: String,
        ): String {
            val raw = when {
                sitefDataHoraRaw.isNotBlank() -> sitefDataHoraRaw
                dataTransacao.isNotBlank() -> dataTransacao
                else -> taxInvoiceDate
            }
            return DatasnapPathEncoder.normalizeDataSitef(raw)
        }

        private fun formatPreDatado(sitefPreDatadoRaw: String, dataPreDatado: String): String {
            val raw = sitefPreDatadoRaw.ifBlank { dataPreDatado }
            return DatasnapPathEncoder.normalizePreDatado(raw)
        }

        private fun formatOperador(raw: String): String {
            val digits = raw.trim().filter { it.isDigit() }
            if (digits.isEmpty()) return ""
            val number = digits.toIntOrNull() ?: return digits
            return String.format(Locale.US, "%03d", number)
        }

    }
}
