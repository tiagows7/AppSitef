package com.appsitef.smartpos.tef

/**
 * Monta parâmetros do CliSiTef (mesmo formato do [CliSiTefExemploKotlin]).
 */
object TefClsitConfig {

    fun normalizeTransacoesHabilitadas(raw: String): String {
        if (raw.isBlank()) return ""
        return raw
            .replace(',', ';')
            .split(';')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString(";")
    }

    /**
     * Parâmetros adicionais do configure — padrão Fiserv / projeto exemplo:
     * `[TipoComunicacaoExterna=X;ParmsClient=1=CNPJ_AUTOMACAO;2=CNPJ_FACILITADOR]`
     *
     * Transações habilitadas ficam no CLSIT ([Geral]/TransacoesHabilitadas), não neste campo.
     */
    fun buildConfigureAdditionalParams(
        tipoComunicacaoExterna: String,
        cnpjAutomacao: String,
        cnpjFacilitador: String
    ): String {
        val comExterna = tipoComunicacaoExterna.trim().ifBlank { "0" }
        // GPOS Delphi homologado: ParametrosAdicionais vazio com COMEXTERNA=0 (pinpad interno).
        if (comExterna == "0") return ""

        val automacao = cnpjAutomacao.trim()
        val facilitador = cnpjFacilitador.trim()
        return "[TipoComunicacaoExterna=$comExterna;ParmsClient=1=$automacao;2=$facilitador]"
    }

    fun patchIniSectionValue(
        content: String,
        section: String,
        key: String,
        value: String
    ): String {
        if (value.isBlank()) return content

        val sectionHeader = "[$section]"
        val keyPrefix = "$key="
        val lines = content.lines().toMutableList()
        var sectionStart = -1
        var sectionEnd = lines.size

        for (i in lines.indices) {
            val trimmed = lines[i].trim()
            if (trimmed.equals(sectionHeader, ignoreCase = true)) {
                sectionStart = i
                continue
            }
            if (sectionStart >= 0 && trimmed.startsWith("[") && trimmed.endsWith("]")) {
                sectionEnd = i
                break
            }
        }

        if (sectionStart < 0) {
            if (content.isNotBlank() && !content.endsWith("\n")) {
                lines.add("")
            }
            lines.add(sectionHeader)
            lines.add("$keyPrefix$value")
            return lines.joinToString("\n")
        }

        for (i in sectionStart + 1 until sectionEnd) {
            val trimmed = lines[i].trim()
            if (trimmed.startsWith(keyPrefix, ignoreCase = true)) {
                lines[i] = "$keyPrefix$value"
                return lines.joinToString("\n")
            }
        }

        lines.add(sectionEnd, "$keyPrefix$value")
        return lines.joinToString("\n")
    }
}
