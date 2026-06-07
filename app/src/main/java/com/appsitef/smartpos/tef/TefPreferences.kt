package com.appsitef.smartpos.tef

import android.content.Context
import java.io.File

object TefPreferences {
    private const val PREF_NAME = "tef_config"
    private const val MODULO_INI_FILE = "modulo.ini"

    /** Porta TCP do servidor SiTef (distinta da porta REST do DataSnap). */
    const val DEFAULT_SITEF_PORT = 4096

    private const val KEY_SITEF_HOST = "sitef_host"
    private const val KEY_CHIP_HOST = "chip_host"
    private const val KEY_PORT = "port"
    private const val KEY_CONNECTION_TYPE = "connection_type"
    private const val KEY_STORE_ID = "store_id"
    private const val KEY_TERMINAL_ID = "terminal_id"
    private const val KEY_OPERATOR = "operator"
    private const val KEY_AMOUNT = "amount"
    private const val KEY_COUPON = "coupon"
    private const val KEY_RESTRICTIONS = "restrictions"
    private const val KEY_ADDITIONAL_PARAMS = "additional_params"
    private const val KEY_TEF_CNPJ = "tef_cnpj"
    private const val KEY_TEF_COM_EXTERNA = "tef_com_externa"
    private const val KEY_TEF_IP = "tef_ip"
    private const val KEY_TEF_SITEF_PORT = "tef_sitef_port"
    private const val KEY_TEF_DOUBLE_VALIDATION = "tef_double_validation"
    private const val KEY_TEF_OTP = "tef_otp"
    private const val KEY_TEF_CNPJ_AUTOMACAO = "tef_cnpj_automacao"
    private const val KEY_TEF_TRANSACOES = "tef_transacoes"
    private const val KEY_TEF_OBRIGADO_OPERADOR = "tef_obrigado_operador"
    private const val KEY_TEF_POSTIPO = "tef_postipo"
    private const val KEY_TEF_MODELO = "tef_modelo"
    private const val KEY_TEF_IMPRIME_BANRI = "tef_imprime_banri"
    private const val KEY_TEF_ACEITA_PARCIAL = "tef_aceita_parcial"
    private const val KEY_TEF_VENDA_PRODUTO = "tef_venda_produto"
    private const val KEY_TEF_TIPO_DOCUMENTO = "tef_tipo_documento"
    private const val KEY_GERAL_CNPJ = "geral_cnpj"
    private const val KEY_GERAL_REGISTRO = "geral_registro"
    private const val KEY_GERAL_ATIVO = "geral_ativo"
    private const val KEY_GERAL_TIPO_VENDA = "geral_tipo_venda"
    private const val KEY_CONFIG_COMPLETED = "config_completed"

    private fun prefs(context: Context) = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    fun isConfigured(context: Context): Boolean {
        loadModuloIniIfExists(context)
        if (!File(context.filesDir, MODULO_INI_FILE).exists()) return false

        val port = getPort(context)
        val pdv = getOperator(context)
        if (port.isBlank() || pdv.isBlank()) return false

        val host = if (getConnectionType(context).equals("CHIP", ignoreCase = true)) {
            getChipHost(context)
        } else {
            getSitefHost(context)
        }
        if (host.isBlank()) return false

        val terminalOk = getTerminalId(context).isNotBlank()
        val savedOk = prefs(context).getBoolean(KEY_CONFIG_COMPLETED, false) ||
            prefs(context).getString(KEY_GERAL_ATIVO, "").equals("TRUE", ignoreCase = true)

        return terminalOk && savedOk
    }

    fun getSitefHost(context: Context): String = prefs(context).getString(KEY_SITEF_HOST, "") ?: ""
    fun getChipHost(context: Context): String = prefs(context).getString(KEY_CHIP_HOST, "") ?: ""
    fun getPort(context: Context): String = prefs(context).getString(KEY_PORT, "") ?: ""

    /** CNPJ do posto — usado na validação de cupom App Promo (`cnpj_posto`). */
    fun getPostoCnpj(context: Context): String {
        loadModuloIniIfExists(context)
        val geralCnpj = prefs(context).getString(KEY_GERAL_CNPJ, "").orEmpty().trim()
        if (geralCnpj.isNotEmpty()) return geralCnpj.filter { it.isDigit() }
        return prefs(context).getString(KEY_TEF_CNPJ, "").orEmpty().filter { it.isDigit() }
    }
    fun getConnectionType(context: Context): String = prefs(context).getString(KEY_CONNECTION_TYPE, "WIFI") ?: "WIFI"
    fun getStoreId(context: Context): String = prefs(context).getString(KEY_STORE_ID, "") ?: ""
    fun getTerminalId(context: Context): String = prefs(context).getString(KEY_TERMINAL_ID, "") ?: ""
    fun getTefIp(context: Context): String {
        loadModuloIniIfExists(context)
        return prefs(context).getString(KEY_TEF_IP, "") ?: ""
    }

    fun getSitefPort(context: Context): Int {
        loadModuloIniIfExists(context)
        return prefs(context).getInt(KEY_TEF_SITEF_PORT, DEFAULT_SITEF_PORT)
    }

    /** IP/hostname com porta SiTef (`host:porta`) — uso em logs/UI. */
    fun getSitefEndpoint(context: Context): String {
        return formatSitefEndpoint(getTefIp(context), getSitefPort(context))
    }

    /**
     * Endereço do [CliSiTef.configure] — igual ao GPOS Delphi homologado
     * (`configuraIntSiTefInterativoEx(EnderecoIPSitef, …)`): só IP/host, porta padrão 4096 na lib.
     */
    fun getSitefConfigureAddress(context: Context): String = getTefIp(context).trim()

    fun formatSitefEndpoint(ip: String, port: Int = DEFAULT_SITEF_PORT): String {
        val trimmed = ip.trim()
        if (trimmed.isEmpty()) return ""
        if (trimmed.contains(':')) return trimmed
        return "$trimmed:$port"
    }

    fun getTefTransacoesHabilitadas(context: Context): String {
        loadModuloIniIfExists(context)
        return prefs(context).getString(KEY_TEF_TRANSACOES, "") ?: ""
    }

    fun getTefComExterna(context: Context): String {
        loadModuloIniIfExists(context)
        return prefs(context).getString(KEY_TEF_COM_EXTERNA, "0") ?: "0"
    }

    fun getSitefConfigureAdditionalParams(context: Context): String {
        loadModuloIniIfExists(context)
        return TefClsitConfig.buildConfigureAdditionalParams(
            tipoComunicacaoExterna = getTefComExterna(context),
            cnpjAutomacao = prefs(context).getString(KEY_TEF_CNPJ_AUTOMACAO, "") ?: "",
            cnpjFacilitador = prefs(context).getString(KEY_TEF_CNPJ, "") ?: ""
        )
    }
    fun getOperator(context: Context): String = prefs(context).getString(KEY_OPERATOR, "0001") ?: "0001"
    fun getAmount(context: Context): String = prefs(context).getString(KEY_AMOUNT, "100") ?: "100"
    fun getCoupon(context: Context): String = prefs(context).getString(KEY_COUPON, "1") ?: "1"
    fun getRestrictions(context: Context): String = prefs(context).getString(KEY_RESTRICTIONS, "") ?: ""
    fun getAdditionalParams(context: Context): String = prefs(context).getString(KEY_ADDITIONAL_PARAMS, "") ?: ""

    fun getTefObrigadoOperador(context: Context): String {
        loadModuloIniIfExists(context)
        return prefs(context).getString(KEY_TEF_OBRIGADO_OPERADOR, "N") ?: "N"
    }

    fun isOperadorObrigatorio(context: Context): Boolean {
        return getTefObrigadoOperador(context).equals("S", ignoreCase = true)
    }

    fun saveBaseConfig(
        context: Context,
        sitefHost: String,
        storeId: String,
        terminalId: String
    ) {
        prefs(context).edit()
            .putString(KEY_SITEF_HOST, sitefHost)
            .putString(KEY_STORE_ID, storeId)
            .putString(KEY_TERMINAL_ID, terminalId)
            .apply()
    }

    data class TerminalRestConfig(
        val tefIp: String,
        val tefIdTerminal: String,
        val tefIdLoja: String,
        val tefCnpj: String,
        val tefComExterna: String,
        val tefIsDoubleValidation: String,
        val tefOtp: String,
        val tefCnpjAutomacao: String,
        val tefTransacoesHabilitadas: String,
        val tefObrigadoOperador: String,
        val tefPosTipo: String,
        val tefModelo: String,
        val tefImprimeBanri: String,
        val tefAceitaParcial: String,
        val tefVendaProduto: String,
        val tefTipoDocumento: String
    )

    fun saveConfigurationFromTerminal(
        context: Context,
        wifiHost: String,
        chipHost: String,
        port: String,
        pdv: String,
        connectionType: String,
        terminal: TerminalRestConfig
    ) {
        prefs(context).edit()
            .putString(KEY_SITEF_HOST, wifiHost)
            .putString(KEY_CHIP_HOST, chipHost)
            .putString(KEY_PORT, port)
            .putString(KEY_CONNECTION_TYPE, connectionType)
            .putString(KEY_STORE_ID, terminal.tefIdLoja)
            .putString(KEY_TERMINAL_ID, terminal.tefIdTerminal)
            .putString(KEY_OPERATOR, pdv.ifBlank { "0001" })
            .putString(KEY_TEF_CNPJ, terminal.tefCnpj)
            .putString(KEY_TEF_COM_EXTERNA, terminal.tefComExterna.ifBlank { "0" })
            .putString(KEY_TEF_IP, terminal.tefIp)
            .putInt(KEY_TEF_SITEF_PORT, DEFAULT_SITEF_PORT)
            .putString(KEY_TEF_DOUBLE_VALIDATION, terminal.tefIsDoubleValidation)
            .putString(KEY_TEF_OTP, terminal.tefOtp)
            .putString(KEY_TEF_CNPJ_AUTOMACAO, terminal.tefCnpjAutomacao)
            .putString(KEY_TEF_TRANSACOES, terminal.tefTransacoesHabilitadas)
            .putString(
                KEY_ADDITIONAL_PARAMS,
                TefClsitConfig.buildConfigureAdditionalParams(
                    tipoComunicacaoExterna = terminal.tefComExterna,
                    cnpjAutomacao = terminal.tefCnpjAutomacao,
                    cnpjFacilitador = terminal.tefCnpj
                )
            )
            .putString(KEY_TEF_OBRIGADO_OPERADOR, terminal.tefObrigadoOperador)
            .putString(KEY_TEF_POSTIPO, terminal.tefPosTipo)
            .putString(KEY_TEF_MODELO, terminal.tefModelo)
            .putString(KEY_TEF_IMPRIME_BANRI, terminal.tefImprimeBanri)
            .putString(KEY_TEF_ACEITA_PARCIAL, terminal.tefAceitaParcial)
            .putString(KEY_TEF_VENDA_PRODUTO, terminal.tefVendaProduto)
            .putString(KEY_TEF_TIPO_DOCUMENTO, terminal.tefTipoDocumento)
            .putString(KEY_GERAL_ATIVO, "TRUE")
            .putString(KEY_GERAL_TIPO_VENDA, "1")
            .putBoolean(KEY_CONFIG_COMPLETED, true)
            .commit()

        saveModuloIni(
            context = context,
            wifiHost = wifiHost,
            chipHost = chipHost,
            port = port,
            pdv = pdv,
            connectionType = connectionType,
            terminal = terminal
        )
    }

    private fun saveModuloIni(
        context: Context,
        wifiHost: String,
        chipHost: String,
        port: String,
        pdv: String,
        connectionType: String,
        terminal: TerminalRestConfig
    ) {
        val content = buildString {
            appendLine("[CONEXAO]")
            appendLine("CAMINHOWIFI=$wifiHost")
            appendLine("CAMINHOCHIP=$chipHost")
            appendLine("PORTA=$port")
            appendLine("PDV=$pdv")
            appendLine()
            appendLine("[GERAL]")
            appendLine("CNPJ=${prefs(context).getString(KEY_GERAL_CNPJ, "") ?: ""}")
            appendLine("REGISTRO=${prefs(context).getString(KEY_GERAL_REGISTRO, "") ?: ""}")
            appendLine("ATIVO=TRUE")
            appendLine("TIPOCONEXAO=$connectionType")
            appendLine("TIPOVENDA=${prefs(context).getString(KEY_GERAL_TIPO_VENDA, "1") ?: "1"}")
            appendLine()
            appendLine("[TEF]")
            appendLine("IP=${terminal.tefIp}")
            appendLine("PORTA=$DEFAULT_SITEF_PORT")
            appendLine("IDTERMINAL=${terminal.tefIdTerminal}")
            appendLine("IDLOJA=${terminal.tefIdLoja}")
            appendLine("CNPJ=${terminal.tefCnpj}")
            appendLine("COMEXTERNA=${terminal.tefComExterna.ifBlank { "0" }}")
            appendLine("ISDOUBLEVALIDATION=${terminal.tefIsDoubleValidation}")
            appendLine("OTP=${terminal.tefOtp}")
            appendLine("CNPJAUTOMACAO=${terminal.tefCnpjAutomacao}")
            appendLine("TRANSACAOHABILITADAS=${terminal.tefTransacoesHabilitadas}")
            appendLine("OBRIGADOOPERADOR=${terminal.tefObrigadoOperador}")
            appendLine("POSTIPO=${terminal.tefPosTipo}")
            appendLine("MODELO=${terminal.tefModelo}")
            appendLine("IMPRIMEBANRI=${terminal.tefImprimeBanri}")
            appendLine("ACEITAPARCIAL=${terminal.tefAceitaParcial}")
            appendLine("VENDAPRODUTO=${terminal.tefVendaProduto}")
            appendLine("TIPODOCUMENTO=${terminal.tefTipoDocumento}")
        }
        File(context.filesDir, MODULO_INI_FILE).writeText(content)
        CliSiTefAssetInstaller.syncTransacoesHabilitadas(context)
    }

    fun loadModuloIniIfExists(context: Context) {
        val iniFile = File(context.filesDir, MODULO_INI_FILE)
        if (!iniFile.exists()) return

        val iniMap = parseIni(iniFile.readText())
        val conexao = iniMap["CONEXAO"].orEmpty()
        val tef = iniMap["TEF"].orEmpty()
        val geral = iniMap["GERAL"].orEmpty()

        prefs(context).edit()
            .putString(KEY_SITEF_HOST, conexao["CAMINHOWIFI"].orEmpty())
            .putString(KEY_CHIP_HOST, conexao["CAMINHOCHIP"].orEmpty())
            .putString(KEY_PORT, conexao["PORTA"].orEmpty())
            .putString(KEY_OPERATOR, conexao["PDV"].orEmpty().ifBlank { "0001" })
            .putString(KEY_CONNECTION_TYPE, geral["TIPOCONEXAO"] ?: "WIFI")
            .putString(KEY_GERAL_CNPJ, geral["CNPJ"].orEmpty())
            .putString(KEY_GERAL_REGISTRO, geral["REGISTRO"].orEmpty())
            .putString(KEY_GERAL_ATIVO, geral["ATIVO"].orEmpty())
            .putString(KEY_GERAL_TIPO_VENDA, geral["TIPOVENDA"] ?: "1")
            .putString(KEY_TEF_IP, tef["IP"].orEmpty())
            .putInt(
                KEY_TEF_SITEF_PORT,
                tef["PORTA"]?.toIntOrNull() ?: DEFAULT_SITEF_PORT
            )
            .putString(KEY_TERMINAL_ID, tef["IDTERMINAL"].orEmpty())
            .putString(KEY_STORE_ID, tef["IDLOJA"].orEmpty())
            .putString(KEY_TEF_CNPJ, tef["CNPJ"].orEmpty())
            .putString(KEY_TEF_COM_EXTERNA, tef["COMEXTERNA"]?.ifBlank { "0" } ?: "0")
            .putString(KEY_TEF_DOUBLE_VALIDATION, tef["ISDOUBLEVALIDATION"].orEmpty())
            .putString(KEY_TEF_OTP, tef["OTP"].orEmpty())
            .putString(KEY_TEF_CNPJ_AUTOMACAO, tef["CNPJAUTOMACAO"].orEmpty())
            .putString(KEY_TEF_TRANSACOES, tef["TRANSACAOHABILITADAS"].orEmpty())
            .putString(
                KEY_ADDITIONAL_PARAMS,
                TefClsitConfig.buildConfigureAdditionalParams(
                    tipoComunicacaoExterna = tef["COMEXTERNA"]?.ifBlank { "0" } ?: "0",
                    cnpjAutomacao = tef["CNPJAUTOMACAO"].orEmpty(),
                    cnpjFacilitador = tef["CNPJ"].orEmpty()
                )
            )
            .putString(KEY_TEF_OBRIGADO_OPERADOR, tef["OBRIGADOOPERADOR"].orEmpty())
            .putString(KEY_TEF_POSTIPO, tef["POSTIPO"].orEmpty())
            .putString(KEY_TEF_MODELO, tef["MODELO"].orEmpty())
            .putString(KEY_TEF_IMPRIME_BANRI, tef["IMPRIMEBANRI"].orEmpty())
            .putString(KEY_TEF_ACEITA_PARCIAL, tef["ACEITAPARCIAL"].orEmpty())
            .putString(KEY_TEF_VENDA_PRODUTO, tef["VENDAPRODUTO"].orEmpty())
            .putString(KEY_TEF_TIPO_DOCUMENTO, tef["TIPODOCUMENTO"].orEmpty())
            .apply()
    }

    private fun parseIni(content: String): Map<String, Map<String, String>> {
        val result = mutableMapOf<String, MutableMap<String, String>>()
        var currentSection = ""
        content.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isBlank() || line.startsWith(";") || line.startsWith("#")) return@forEach
            if (line.startsWith("[") && line.endsWith("]")) {
                currentSection = line.substring(1, line.length - 1)
                result.putIfAbsent(currentSection, mutableMapOf())
                return@forEach
            }
            val idx = line.indexOf('=')
            if (idx > 0 && currentSection.isNotBlank()) {
                val key = line.substring(0, idx).trim()
                val value = line.substring(idx + 1).trim()
                result.getOrPut(currentSection) { mutableMapOf() }[key] = value
            }
        }
        return result
    }

    fun saveTransactionDefaults(
        context: Context,
        operator: String,
        amount: String,
        coupon: String,
        restrictions: String,
        additionalParams: String
    ) {
        prefs(context).edit()
            .putString(KEY_OPERATOR, operator)
            .putString(KEY_AMOUNT, amount)
            .putString(KEY_COUPON, coupon)
            .putString(KEY_RESTRICTIONS, restrictions)
            .putString(KEY_ADDITIONAL_PARAMS, additionalParams)
            .apply()
    }
}
