package com.appsitef.smartpos.tef

import com.appsitef.smartpos.sales.network.DatasnapPathEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object TefTransactionFieldParser {

    /** Converte hora fiscal SiTef (`HHmmss`) para `HH:MM:SS` (Delphi `HORATRANSACAO`). */
    fun formatHoraFromTaxInvoiceTime(raw: String): String {
        val digits = raw.trim().filter { it.isDigit() }
        if (digits.length < 6) return ""
        return buildString {
            append(digits.substring(0, 2))
            append(':')
            append(digits.substring(2, 4))
            append(':')
            append(digits.substring(4, 6))
        }
    }

    fun formatCurrentHora(): String =
        SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())

    /**
     * Campo 105 — SiTef Android costuma retornar `DDMMAAAA` ou `DDMMAAAAHHMMSS` sem pontuação.
     * Converte para exibição interna `DD/MM/AAAA` e `HH:MM:SS`.
     */
    fun parseDataHoraSitef(raw: String): Pair<String, String> {
        val digits = raw.trim().filter { it.isDigit() }
        if (digits.length < 8) return "" to ""

        val dateDigits = digits.substring(0, 8)
        val dataTransacao = formatDdMmYyyyWithSlashes(dateDigits)
        val horaTransacao = if (digits.length >= 14) {
            formatHoraFromDigits(digits.substring(8, 14))
        } else {
            ""
        }
        return dataTransacao to horaTransacao
    }

    /** Campo 506 — SiTef retorna `MMDDAAAA` sem pontuação. */
    fun parseDataPreDatado(raw: String): String {
        val digits = raw.trim().filter { it.isDigit() }
        if (digits.length < 8) return ""
        return formatMmDdYyyyWithSlashes(digits.substring(0, 8))
    }

    /**
     * Cancelamento administrativo — Delphi `TIPO_CAMPOS` campo 105:
     * `copy(7,2) + '.' + copy(5,2) + '.' + copy(1,4)` sobre `YYYYMMDD…` (índice 1-based).
     */
    fun formatDataSitefCancelamento(raw: String): String {
        val digits = raw.trim().filter { it.isDigit() }
        if (digits.length < 8) return ""

        val year = digits.substring(0, 4).toIntOrNull() ?: 0
        if (year in 1900..2100) {
            return formatDelphiYyyyMmDdDots(digits.substring(0, 8))
        }

        val (data, _) = parseDataHoraSitef(digits)
        return data.replace('/', '.')
    }

    /**
     * Garante `DD.MM.AAAA` antes de enviar ao servidor (JSON ou URL).
     * Nunca devolve 8 dígitos contínuos sem separador.
     */
    fun ensureDataSitefDotted(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""

        if (DOTTED_DATE_REGEX.matches(trimmed)) return trimmed

        val fromCancelamento = formatDataSitefCancelamento(trimmed)
        if (fromCancelamento.isNotBlank()) return fromCancelamento

        val normalized = DatasnapPathEncoder.normalizeDataSitef(trimmed)
        if (normalized.contains('.')) return normalized

        val digits = trimmed.filter { it.isDigit() }
        if (digits.length >= 8) {
            val forced = DatasnapPathEncoder.normalizeDataSitef(digits.substring(0, 8))
            if (forced.contains('.')) return forced

            val yearAtStart = digits.substring(0, 4).toIntOrNull() ?: 0
            return if (yearAtStart in 1900..2100) {
                formatDelphiYyyyMmDdDots(digits.substring(0, 8))
            } else {
                "${digits.substring(0, 2)}.${digits.substring(2, 4)}.${digits.substring(4, 8)}"
            }
        }

        return normalized
    }

    private fun formatDelphiYyyyMmDdDots(yyyymmdd: String): String {
        if (yyyymmdd.length != 8) return ""
        return "${yyyymmdd.substring(6, 8)}.${yyyymmdd.substring(4, 6)}.${yyyymmdd.substring(0, 4)}"
    }

    /**
     * `udmpri.cancela_cartao` — `DATA` sem separador (`StringReplace(sDATA,'/','')` → `DDMMAAAA`).
     */
    fun formatDataCancelamentoServidor(raw: String): String {
        val digits = raw.trim().filter { it.isDigit() }
        if (digits.length < 8) return digits

        val dateDigits = digits.substring(0, 8)
        val yearAtStart = dateDigits.substring(0, 4).toIntOrNull() ?: 0
        return if (yearAtStart in 1900..2100) {
            "${dateDigits.substring(6, 8)}${dateDigits.substring(4, 6)}${dateDigits.substring(0, 4)}"
        } else {
            dateDigits
        }
    }

    private val DOTTED_DATE_REGEX = Regex("""^\d{2}\.\d{2}\.\d{4}$""")

    /** `cartao_movimento` — data SiTef (`DDMMAAAA` ou `DDMMAAAAHHMMSS`) → `DD.MM.AAAA`. */
    fun formatSitefDateForCartaoMovimento(raw: String): String {
        val digits = raw.trim().filter { it.isDigit() }
        if (digits.length < 8) return formatDateWithDots(raw)
        return formatDdMmYyyyWithDots(digits.substring(0, 8))
    }

    /** `cartao_movimento` — pré-datado (`MMDDAAAA`) → `MM.DD.AAAA`. */
    fun formatPreDatadoForCartaoMovimento(raw: String): String {
        val digits = raw.trim().filter { it.isDigit() }
        if (digits.length < 8) return formatDateWithDots(raw)
        return formatMmDdYyyyWithDots(digits.substring(0, 8))
    }

    /** Data fiscal fallback (`AAAAMMDD`) → `DD.MM.AAAA`. */
    fun formatTaxInvoiceDateForCartaoMovimento(raw: String): String {
        val digits = raw.trim().filter { it.isDigit() }
        if (digits.length != 8) return ""
        val year = digits.substring(0, 4).toIntOrNull() ?: 0
        return if (year in 1900..2100) {
            "${digits.substring(6, 8)}.${digits.substring(4, 6)}.${digits.substring(0, 4)}"
        } else {
            formatDdMmYyyyWithDots(digits)
        }
    }

    private fun formatDdMmYyyyWithSlashes(ddmmyyyy: String): String {
        if (ddmmyyyy.length != 8) return ""
        val year = ddmmyyyy.substring(4, 8).toIntOrNull() ?: 0
        return if (year in 1900..2100) {
            "${ddmmyyyy.substring(0, 2)}/${ddmmyyyy.substring(2, 4)}/${ddmmyyyy.substring(4, 8)}"
        } else {
            "${ddmmyyyy.substring(6, 8)}/${ddmmyyyy.substring(4, 6)}/${ddmmyyyy.substring(0, 4)}"
        }
    }

    private fun formatMmDdYyyyWithSlashes(mmddyyyy: String): String {
        if (mmddyyyy.length != 8) return ""
        return "${mmddyyyy.substring(2, 4)}/${mmddyyyy.substring(0, 2)}/${mmddyyyy.substring(4, 8)}"
    }

    private fun formatDdMmYyyyWithDots(ddmmyyyy: String): String =
        formatDdMmYyyyWithSlashes(ddmmyyyy).replace('/', '.')

    private fun formatMmDdYyyyWithDots(mmddyyyy: String): String =
        formatMmDdYyyyWithSlashes(mmddyyyy).replace('/', '.')

    private fun formatHoraFromDigits(hhmmss: String): String {
        if (hhmmss.length < 6) return ""
        return buildString {
            append(hhmmss.substring(0, 2))
            append(':')
            append(hhmmss.substring(2, 4))
            append(':')
            append(hhmmss.substring(4, 6))
        }
    }

    private fun formatDateWithDots(raw: String): String {
        val value = raw.trim()
        if (value.isBlank()) return ""
        val withSeparator = Regex("""(\d{2})[/.-](\d{2})[/.-](\d{4})""")
        withSeparator.find(value)?.let { match ->
            return "${match.groupValues[1]}.${match.groupValues[2]}.${match.groupValues[3]}"
        }
        return value.replace('/', '.').replace('-', '.')
    }

    fun inferCodTransFromPaymentLabel(label: String, menuCode: String): String {
        val lower = label.lowercase(Locale.getDefault())
        return when {
            lower.contains("debit") || lower.contains("débit") -> "01"
            lower.contains("credit") || lower.contains("crédit") -> "02"
            lower.contains("pix") -> "99"
            menuCode.matches(Regex("\\d+")) -> menuCode.padStart(2, '0').take(2)
            else -> menuCode
        }
    }

    fun inferTipoParcFromPaymentLabel(label: String): String {
        val lower = label.lowercase(Locale.getDefault())
        return when {
            lower.contains("credit") || lower.contains("crédit") -> "01"
            else -> "00"
        }
    }
}
