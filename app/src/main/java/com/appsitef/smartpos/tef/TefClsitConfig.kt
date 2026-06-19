package com.appsitef.smartpos.tef

/**
 * Monta parâmetros do CliSiTef (mesmo formato do [CliSiTefExemploKotlin]).
 */
object TefClsitConfig {

    /**
     * Padrão homologado GPOS / m-SiTef (menu 110).
     * Códigos de menu — ver tabela “meios de pagamento” da CliSiTef (não confundir com função 111/121/130).
     */
    const val DEFAULT_TRANSACOES_HABILITADAS =
        "16;26;27;30;40;43;56;57;58;130;3203;3624;3627"

    /**
     * Sempre presentes no menu administrativo (110), mesmo se o servidor enviar lista reduzida.
     * - 130: consulta de pendências no terminal
     * - 3203: executa teste de comunicação
     * - 3624: carga de tabelas no pinpad
     * - 3627: envio de trace (requer TraceRotativo no CLSIT)
     */
    val MANDATORY_ADMIN_TRANSACOES = listOf("130", "3203", "3624", "3627")

    /** Transações adicionais exigidas para carga de tabelas / trace no menu admin. */
    const val DEFAULT_TRANSACOES_ADICIONAIS_HABILITADAS =
        "40;56;57;58;60;61;70;71;72;3624;3625;3626"

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
     * Delphi / m-SiTef — `TransacoesHabilitadas` no ParamAdic / `restricoes` da transação.
     * Ex.: `TransacoesHabilitadas=16;26;27;30;111`
     */
    fun buildTransacoesHabilitadasRestriction(transacoes: String): String {
        val normalized = normalizeTransacoesHabilitadas(transacoes)
        if (normalized.isBlank()) return ""
        return "TransacoesHabilitadas=$normalized"
    }

    fun mergeStartTransactionParameters(
        explicitRestrictions: String,
        transacoesHabilitadas: String,
    ): String {
        val transacoesPart = buildTransacoesHabilitadasRestriction(transacoesHabilitadas)
        val explicit = explicitRestrictions.trim()
        return when {
            explicit.isBlank() -> transacoesPart
            transacoesPart.isBlank() -> explicit
            explicit.contains("TransacoesHabilitadas=", ignoreCase = true) -> explicit
            else -> "$explicit;$transacoesPart"
        }
    }

    fun ensureMandatoryAdminTransacoes(raw: String): String {
        val ordered = LinkedHashSet<String>()
        normalizeTransacoesHabilitadas(raw)
            .split(';')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { ordered.add(it) }
        MANDATORY_ADMIN_TRANSACOES.forEach { ordered.add(it) }
        return ordered.joinToString(";")
    }

    fun mergeTransacoesAdicionaisHabilitadas(existing: String): String {
        val ordered = LinkedHashSet<String>()
        normalizeTransacoesHabilitadas(existing)
            .split(';')
            .filter { it.isNotEmpty() }
            .forEach { ordered.add(it) }
        normalizeTransacoesHabilitadas(DEFAULT_TRANSACOES_ADICIONAIS_HABILITADAS)
            .split(';')
            .filter { it.isNotEmpty() }
            .forEach { ordered.add(it) }
        return ordered.joinToString(";")
    }

    fun readIniSectionValue(content: String, section: String, key: String): String {
        val sectionHeader = "[$section]"
        val keyPrefix = "$key="
        var inSection = false
        for (line in content.lineSequence()) {
            val trimmed = line.trim()
            when {
                trimmed.equals(sectionHeader, ignoreCase = true) -> inSection = true
                inSection && trimmed.startsWith("[") && trimmed.endsWith("]") -> break
                inSection && trimmed.startsWith(keyPrefix, ignoreCase = true) ->
                    return trimmed.substring(keyPrefix.length).trim()
            }
        }
        return ""
    }

    /**
     * Parâmetros adicionais do `configure` (manual CliSiTef §5.1.2):
     * `[ParmsClient=1=CNPJ_ESTABELECIMENTO;2=CNPJ_SOFTWARE_HOUSE]`
     *
     * Id 1 = CNPJ do estabelecimento (`TEF_CNPJ`).
     * Id 2 = CNPJ da automação comercial (`TEF_CNPJAUTOMACAO`).
     */
    fun buildConfigureAdditionalParams(
        tipoComunicacaoExterna: String,
        cnpjEstabelecimento: String,
        cnpjAutomacao: String,
    ): String {
        val estabelecimento = cnpjEstabelecimento.filter { it.isDigit() }
        val automacao = cnpjAutomacao.filter { it.isDigit() }
        if (estabelecimento.isBlank() && automacao.isBlank()) return ""

        val parmsClient = buildString {
            append("ParmsClient=")
            val parts = mutableListOf<String>()
            if (estabelecimento.isNotBlank()) parts.add("1=$estabelecimento")
            if (automacao.isNotBlank()) parts.add("2=$automacao")
            append(parts.joinToString(";"))
        }

        val comExterna = tipoComunicacaoExterna.trim().ifBlank { "0" }
        return if (comExterna == "0") {
            "[$parmsClient]"
        } else {
            "[TipoComunicacaoExterna=$comExterna;$parmsClient]"
        }
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
