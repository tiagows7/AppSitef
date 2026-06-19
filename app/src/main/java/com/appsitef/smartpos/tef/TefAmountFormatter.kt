package com.appsitef.smartpos.tef

import java.util.Locale

object TefAmountFormatter {

    /**
     * Formato do GPOS Delphi (`GetValorTransacao`): centavos sem separador (ex.: R$ 1,50 → "150").
     */
    fun toCliSiTefAmount(raw: String): String {
        val normalized = raw.trim()
            .replace(".", "")
            .replace(",", ".")
        val value = normalized.toDoubleOrNull() ?: 0.0
        val cents = kotlin.math.round(value * 100).toLong()
        return cents.toString()
    }

    /** Valor digitado com máscara (`10,50`) → centavos SiTef (`1050`), como Delphi `GetValorTransacao`. */
    fun toSitefCurrencyDigits(maskedValue: String): String = toCliSiTefAmount(maskedValue)

    /** Delphi `VALORVENDA` — decimal com vírgula (ex.: `10,50`). */
    fun toCartaoMovimentoValor(raw: String): String {
        val normalized = raw.trim()
            .replace(".", "")
            .replace(",", ".")
        val value = normalized.toDoubleOrNull() ?: 0.0
        return String.format(Locale("pt", "BR"), "%.2f", value)
    }
}
