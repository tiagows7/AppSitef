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
        "16;26;27;28;30;40;43;56;57;58;130;3203;3624;3627"

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
        "16;26;27;28;30;40;43;56;57;58;60;61;70;71;72;130;3624;3625;3626;3627"

    /**
     * Lista fixa gravada em `[Geral] transacoesadicionaishabilitadas` do CLSIT.
     * Substitui o envio dessa chave no `startTransaction` (agora lida do CLSIT).
     */
    const val CLSIT_GERAL_TRANSACOES_ADICIONAIS =
        "7;8;9;14;16;17;26;27;28;123;3003;3006;3020;3289;3240;3021;3022;3919;4179;130;3203;3624;3627"

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
     * Delphi / m-SiTef — `transacoesadicionaishabilitadas` no ParamAdic / `restricoes` da transação.
     * Ex.: `transacoesadicionaishabilitadas=16;26;27;30;111`
     */
    fun buildTransacoesHabilitadasRestriction(transacoes: String): String {
        val normalized = normalizeTransacoesHabilitadas(transacoes)
        if (normalized.isBlank()) return ""
        return "transacoesadicionaishabilitadas=$normalized"
    }

    fun buildNumeroTerminalParameter(terminalId: String): String {
        val trimmed = terminalId.trim()
        if (trimmed.isBlank()) return ""
        return "NUMEROTERMINAL=$trimmed"
    }

    /**
     * Parâmetros adicionais do `configure` — sempre entre colchetes.
     * Ex.: `[TipoComunicacaoExterna=2;NUMEROTERMINAL=POS00001]`
     */
    fun mergeSitefParameters(base: String, terminalId: String): String {
        val numeroTerminal = buildNumeroTerminalParameter(terminalId)
        if (numeroTerminal.isBlank()) return ensureConfigureParamsBrackets(base.trim())

        val trimmedBase = base.trim()
        if (trimmedBase.isBlank()) return "[$numeroTerminal]"
        if (trimmedBase.contains("NUMEROTERMINAL=", ignoreCase = true)) {
            return ensureConfigureParamsBrackets(trimmedBase)
        }

        if (trimmedBase.startsWith("[") && trimmedBase.endsWith("]")) {
            val inner = trimmedBase.substring(1, trimmedBase.length - 1).trim().trimEnd(';')
            return if (inner.isBlank()) {
                "[$numeroTerminal]"
            } else {
                "[$inner;$numeroTerminal]"
            }
        }

        return "[$trimmedBase;$numeroTerminal]"
    }

    /** CliSiTef exige parâmetros adicionais do configure entre colchetes. */
    fun ensureConfigureParamsBrackets(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return ""
        if (trimmed.startsWith("[") && trimmed.endsWith("]")) return trimmed
        return "[$trimmed]"
    }

    /**
     * Campo `restricoes` do `startTransaction` — **sem** colchetes e **sem**
     * `NUMEROTERMINAL` (esse vai só no configure). Colchetes geram
     * "campo restricoes invalido ...numeroterminal=...]"
     */
    fun normalizeTransactionRestrictions(explicitRestrictions: String): String {
        val trimmed = explicitRestrictions.trim()
        if (trimmed.isBlank()) return ""
        val inner = if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
            trimmed.substring(1, trimmed.length - 1).trim().trimEnd(';')
        } else {
            trimmed.trimEnd(';')
        }
        if (inner.isBlank()) return ""
        return inner
            .split(';')
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("NUMEROTERMINAL=", ignoreCase = true) }
            .joinToString(";")
    }

    fun mergeStartTransactionParameters(
        explicitRestrictions: String,
        transacoesHabilitadas: String,
        @Suppress("UNUSED_PARAMETER") numeroTerminal: String = "",
    ): String {
        val transacoesPart = buildTransacoesHabilitadasRestriction(transacoesHabilitadas)
        val explicit = normalizeTransactionRestrictions(explicitRestrictions)
        return when {
            explicit.isBlank() -> transacoesPart
            transacoesPart.isBlank() -> explicit
            explicit.contains("transacoesadicionaishabilitadas=", ignoreCase = true) -> explicit
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
     * Parâmetros adicionais do `configure` (manual CliSiTef §5.1.2 / §9 TLS):
     * `[TipoComunicacaoExterna=...;ParmsClient=1=CNPJ;2=CNPJ_SW;NUMEROTERMINAL=...;...]`
     *
     * Id 1 = CNPJ do estabelecimento (`TEF_CNPJ`).
     * Id 2 = CNPJ da automação comercial (`TEF_CNPJAUTOMACAO`).
     *
     * COMEXTERNA=1 (GSurf):
     * `TipoComunicacaoExterna` ← `TEF_TLSEXTERNA`,
     * `GSurf.OTP` ← `TEF_OTP`,
     * `TerminalUUID` ← `TEF_GSURFUID`.
     *
     * COMEXTERNA=2 (GWP/TLS):
     * `TipoComunicacaoExterna` ← `TEF_TLSEXTERNA`,
     * `TokenRegistro` ← `TEF_TLSTOKEN`,
     * `GwpTipoProxy` ← `TEF_TLSTIPOPROXY`,
     * `GwpEnderecoProxy` ← `TEF_TLSENDERECOPROXY`.
     */
    fun buildConfigureAdditionalParams(
        tipoComunicacaoExterna: String,
        cnpjEstabelecimento: String,
        cnpjAutomacao: String,
        numeroTerminal: String = "",
        otp: String = "",
        tlsExterna: String = "",
        tlsToken: String = "",
        tlsTipoProxy: String = "",
        tlsEnderecoProxy: String = "",
        gsurfUid: String = "",
        /** Conteúdo de `tef_transacoes` / `TRANSACAOHABILITADAS` do modulo.ini. */
        transacoesAdicionaisHabilitadas: String = "",
    ): String {
        val parts = mutableListOf<String>()
        val comExterna = tipoComunicacaoExterna.trim().ifBlank { "0" }

        when (comExterna) {
            "1" -> {
                // Sempre inicia com TipoComunicacaoExterna (TLS externa ou COMEXTERNA).
                val tipo = tlsExterna.trim().ifBlank { comExterna }
                parts.add("TipoComunicacaoExterna=$tipo")
                otp.trim().takeIf { it.isNotEmpty() }?.let {
                    parts.add("GSurf.OTP=$it")
                }
                gsurfUid.trim().takeIf { it.isNotEmpty() }?.let {
                    parts.add("TerminalUUID=$it")
                }
            }
            "2" -> {
                val tipo = tlsExterna.trim().ifBlank { comExterna }
                parts.add("TipoComunicacaoExterna=$tipo")
                tlsToken.trim().takeIf { it.isNotEmpty() }?.let {
                    parts.add("TokenRegistro=$it")
                }
                tlsTipoProxy.trim().takeIf { it.isNotEmpty() }?.let {
                    parts.add("GwpTipoProxy=$it")
                }
                tlsEnderecoProxy.trim().takeIf { it.isNotEmpty() }?.let {
                    parts.add("GwpEnderecoProxy=$it")
                }
            }
            else -> {
                if (comExterna != "0") {
                    parts.add("TipoComunicacaoExterna=$comExterna")
                }
            }
        }

        val estabelecimento = cnpjEstabelecimento.filter { it.isDigit() }
        val automacao = cnpjAutomacao.filter { it.isDigit() }
        if (estabelecimento.isNotBlank() || automacao.isNotBlank()) {
            val parmsClient = buildString {
                append("ParmsClient=")
                val clientParts = mutableListOf<String>()
                if (estabelecimento.isNotBlank()) clientParts.add("1=$estabelecimento")
                if (automacao.isNotBlank()) clientParts.add("2=$automacao")
                append(clientParts.joinToString(";"))
            }
            parts.add(parmsClient)
        }

        val transacoes = normalizeTransacoesHabilitadas(transacoesAdicionaisHabilitadas)
        if (transacoes.isNotBlank()) {
            parts.add("TransacoesAdicionaisHabilitadas=$transacoes")
        }

        // Formato CliSiTef: [TipoComunicacaoExterna=...;ParmsClient=...;NUMEROTERMINAL=...]
        val base = if (parts.isEmpty()) "" else parts.joinToString(";")
        return mergeSitefParameters(base, numeroTerminal)
    }

    /** Remove uma chave de uma seção INI (ex.: limpar legado do CLSIT). */
    fun removeIniSectionKey(content: String, section: String, key: String): String {
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
        if (sectionStart < 0) return content

        val toRemove = mutableListOf<Int>()
        for (i in sectionStart + 1 until sectionEnd) {
            if (lines[i].trim().startsWith(keyPrefix, ignoreCase = true)) {
                toRemove.add(i)
            }
        }
        toRemove.asReversed().forEach { lines.removeAt(it) }
        return lines.joinToString("\n")
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
