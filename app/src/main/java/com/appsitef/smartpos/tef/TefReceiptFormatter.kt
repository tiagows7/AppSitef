package com.appsitef.smartpos.tef

/**
 * Formatação de comprovante SiTef — espelha [FormataCupom] e [fncAjustaString] do Delphi.
 *
 * Em homologação o próprio SiTef já envia:
 * - prefixos à esquerda `SI` / `MU` / `LA` / `DO` (lidos na vertical: SIMULADO)
 * - faixas centrais `....S..I..M..U..L..A..D..O....`
 *
 * Por isso, em homologação esses marcadores devem ser preservados na impressão.
 * Em produção, o prefixo residual (se vier) é removido como no Delphi.
 */
object TefReceiptFormatter {

    private val COLUMN_PREFIXES = arrayOf("SI", "MU", "LA", "DO")

    /** Prefixo `SI ` / `MU ` / `LA ` / `DO ` (2 letras + espaço). */
    const val SIMULADO_LEFT_PREFIX_CHARS = 3

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
        return trimTrailingBlankLines(result, TRAILING_BLANK_LINES_TRIM)
    }

    private const val TRAILING_BLANK_LINES_TRIM = 3

    fun trimTrailingBlankLines(text: String, maxTrim: Int = TRAILING_BLANK_LINES_TRIM): String {
        if (maxTrim <= 0) return text
        val lines = text.split("\n").toMutableList()
        var trimmed = 0
        while (lines.isNotEmpty() && lines.last().isBlank() && trimmed < maxTrim) {
            lines.removeAt(lines.lastIndex)
            trimmed++
        }
        return lines.joinToString("\n")
    }

    /**
     * @param stripSimuladoPrefixes `true` em produção (Delphi `fncAjustaString`);
     * `false` em homologação para manter o layout oficial SIMULADO do SiTef.
     */
    fun adjustLine(line: String, stripSimuladoPrefixes: Boolean = true): String {
        if (!stripSimuladoPrefixes) {
            // Mantém SI/MU/LA/DO e faixas SIMULADO exatamente como vieram do host.
            return line.trimEnd()
        }
        var local = line
        for (prefix in COLUMN_PREFIXES) {
            if (local.length >= 2 && local.substring(0, 2).equals(prefix, ignoreCase = true)) {
                local = local.substring(2)
            }
        }
        return local.trim()
    }

    fun startsWithSimuladoPrefix(line: String): Boolean {
        val trimmed = line.trimStart()
        if (trimmed.length < 2) return false
        val head = trimmed.substring(0, 2).uppercase()
        return COLUMN_PREFIXES.any { it == head }
    }

    fun isSimuladoBannerLine(line: String): Boolean {
        val compact = line.replace(" ", "").uppercase()
        return compact.contains("S.I.M.U.L.A.D.O") ||
            compact.contains("S..I..M..U..L..A..D..O") ||
            (compact.contains("SOFTWAREEXPRESS") && compact.contains("."))
    }
}
