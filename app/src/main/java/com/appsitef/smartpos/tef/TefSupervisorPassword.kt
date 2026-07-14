package com.appsitef.smartpos.tef

import br.com.softwareexpress.sitef.android.CliSiTef
import java.util.Calendar
import java.util.Locale

/**
 * Senha do supervisor TEF: ano + mês + dia + hora (24h) da data/hora atual.
 * Ex.: 02/06/2026 às 14h → 2026 + 6 + 2 + 14 = 2048
 */
object TefSupervisorPassword {

    fun computeExpectedPassword(now: Calendar = Calendar.getInstance()): Int {
        val year = now.get(Calendar.YEAR)
        val month = now.get(Calendar.MONTH) + 1
        val day = now.get(Calendar.DAY_OF_MONTH)
        val hour = now.get(Calendar.HOUR_OF_DAY)
        return year + month + day + hour
    }

    fun isValid(input: String, now: Calendar = Calendar.getInstance()): Boolean {
        val digits = input.filter { it.isDigit() }
        if (digits.isEmpty()) return false
        val informed = digits.toIntOrNull() ?: return false
        return informed == computeExpectedPassword(now)
    }

    fun isSupervisorPasswordHint(text: String): Boolean {
        val lower = text.lowercase(Locale.getDefault())
        if (lower.isBlank()) return false
        if (lower.contains("supervisor")) return true
        if (lower.contains("gerente")) return true
        if (lower.contains("codigo") && lower.contains("supervisor")) return true
        if (lower.contains("código") && lower.contains("supervisor")) return true
        if ((lower.contains("fornec") || lower.contains("informe")) &&
            (lower.contains("codigo") || lower.contains("código") || lower.contains("cod "))
        ) {
            return true
        }
        if (lower.contains("senha") &&
            !lower.contains("cart") &&
            !lower.contains("pin") &&
            !lower.contains("chip") &&
            !lower.contains("cartao") &&
            !lower.contains("cartão")
        ) {
            return true
        }
        return false
    }

    fun shouldIntercept(command: Int, prompt: String, isAdminOperation: Boolean): Boolean {
        if (isSupervisorPasswordHint(prompt)) return true
        return when (command) {
            CliSiTef.CMD_GET_FIELD_PASSWORD,
            CliSiTef.CMD_GET_MASKED_FIELD -> true
            CliSiTef.CMD_GET_PINPAD_CONFIRMATION -> isAdminOperation
            CliSiTef.CMD_GET_FIELD -> isSupervisorPasswordHint(prompt)
            else -> false
        }
    }

    fun formatForSitef(input: String, minLength: Int): String {
        val digits = input.filter { it.isDigit() }
        if (minLength > 0 && digits.length < minLength) {
            return digits.padStart(minLength, '0')
        }
        return digits
    }
}
