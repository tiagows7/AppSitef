package com.appsitef.smartpos.sales.network

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Codificação de segmentos DataSnap na URL.
 * Datas usam `%2E` entre dia/mês/ano para o ponto não sumir no path HTTP.
 */
object DatasnapPathEncoder {

    fun encodeSegment(value: String): String {
        return URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")
    }

    /**
     * SiTef `DDMMAAAA` / `DDMMAAAAHHMMSS` / `DD/MM/AAAA` → `DD.MM.AAAA` na URL (`06%2E06%2E2026`).
     */
    fun encodeDataSitefSegment(raw: String): String {
        return encodeDateDotsForUrl(normalizeDataSitef(raw))
    }

    /** Pré-datado `MMDDAAAA` / `MM/DD/AAAA` → `MM.DD.AAAA` na URL. */
    fun encodePreDatadoSegment(raw: String): String {
        if (raw.isBlank()) return ""
        return encodeDateDotsForUrl(normalizePreDatado(raw))
    }

    fun normalizeDataSitef(raw: String): String {
        val value = raw.trim()
        if (value.isBlank()) return ""

        val withSeparator = Regex("""(\d{2})[/.-](\d{2})[/.-](\d{4})""")
        withSeparator.find(value)?.let { match ->
            return "${match.groupValues[1]}.${match.groupValues[2]}.${match.groupValues[3]}"
        }

        val digits = value.filter { it.isDigit() }
        if (digits.length < 8) return value

        val dateDigits = digits.substring(0, 8)
        val yearAtEnd = dateDigits.substring(4, 8).toIntOrNull() ?: 0
        return if (yearAtEnd in 1900..2100) {
            "${dateDigits.substring(0, 2)}.${dateDigits.substring(2, 4)}.${dateDigits.substring(4, 8)}"
        } else {
            val yearAtStart = dateDigits.substring(0, 4).toIntOrNull() ?: 0
            if (yearAtStart in 1900..2100) {
                "${dateDigits.substring(6, 8)}.${dateDigits.substring(4, 6)}.${dateDigits.substring(0, 4)}"
            } else {
                "${dateDigits.substring(0, 2)}.${dateDigits.substring(2, 4)}.${dateDigits.substring(4, 8)}"
            }
        }
    }

    fun normalizePreDatado(raw: String): String {
        val value = raw.trim()
        if (value.isBlank()) return ""

        val withSeparator = Regex("""(\d{2})[/.-](\d{2})[/.-](\d{4})""")
        withSeparator.find(value)?.let { match ->
            return "${match.groupValues[1]}.${match.groupValues[2]}.${match.groupValues[3]}"
        }

        val digits = value.filter { it.isDigit() }
        if (digits.length < 8) return value

        val dateDigits = digits.substring(0, 8)
        return "${dateDigits.substring(0, 2)}.${dateDigits.substring(2, 4)}.${dateDigits.substring(4, 8)}"
    }

    private fun encodeDateDotsForUrl(dottedDate: String): String {
        if (dottedDate.isBlank()) return ""
        return dottedDate.map { char ->
            when (char) {
                '.' -> "%2E"
                ' ' -> "%20"
                in 'A'..'Z', in 'a'..'z', in '0'..'9', '-', '_', '~' -> char.toString()
                else -> URLEncoder.encode(char.toString(), StandardCharsets.UTF_8.name())
                    .replace("+", "%20")
            }
        }.joinToString("")
    }
}
