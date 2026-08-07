package com.appsitef.smartpos.tef

import java.util.Locale

object TefAmountFormatter {

    private val ptBr = Locale("pt", "BR")

    /**
     * Valor do [CliSiTef.startTransaction]: decimal com vírgula e 2 casas
     * (ex.: R$ 1,50 → `"1,50"`).
     */
    fun toCliSiTefAmount(raw: String): String {
        val value = parseToDouble(raw)
        return String.format(ptBr, "%.2f", value)
    }

    /** Valor digitado com máscara (`10,50`) → centavos SiTef (`1050`) em coletas de campo. */
    fun toSitefCurrencyDigits(maskedValue: String): String {
        val cents = kotlin.math.round(parseToDouble(maskedValue) * 100.0).toLong()
        return cents.toString()
    }

    /** Delphi `VALORVENDA` — decimal com vírgula (ex.: `10,50`). */
    fun toCartaoMovimentoValor(raw: String): String = toCliSiTefAmount(raw)

    private fun parseToDouble(raw: String): Double {
        val normalized = raw.trim()
            .replace(".", "")
            .replace(",", ".")
        return normalized.toDoubleOrNull() ?: 0.0
    }
}
