package com.appsitef.smartpos.tef

/**
 * Formatação de comprovante SiTef — espelha [FormataCupom] e [fncAjustaString] do Delphi.
 */
object TefReceiptFormatter {

    private val COLUMN_PREFIXES = arrayOf("SI", "MU", "LA", "DO")

    fun formatCupom(text: String): String {
        var result = text.replace("\r\n", "\n")
        val replacements = arrayOf(
            "\\n\\n\\n\\n\\n\\n" to "\n",
            "\n\n\n\n\n\n" to "\n",
            "\\n\\n\\n\\n\\n" to "\n",
            "\n\n\n\n\n" to "\n",
            "\\n\\n\\n" to "\n",
            "\n\n\n" to "\n"
        )
        for ((from, to) in replacements) {
            result = result.replace(from, to, ignoreCase = true)
        }
        return result
    }

    fun adjustLine(line: String): String {
        var local = line
        for (prefix in COLUMN_PREFIXES) {
            if (local.length >= 2 && local.substring(0, 2).equals(prefix, ignoreCase = true)) {
                local = local.substring(2)
            }
        }
        return local.trim()
    }
}
