package com.appsitef.smartpos.sales.network

import com.appsitef.smartpos.sales.model.Abastecimento
import com.appsitef.smartpos.sales.model.Cliente
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

object RestJsonParser {

    private val CHAVE_44_DIGITS_REGEX = Regex("""\d{44}""")

    data class ServerActionResult(
        val success: Boolean,
        val code: String,
        val message: String,
    )

    /**
     * Delphi: `CODIGO_RETORNO` = `200` indica sucesso em `CancelaCartao` / `GravaCartao`.
     * POST DataSnap por segmentos na URL costuma retornar corpo vazio ou `{"result":[true]}`.
     */
    fun parseServerActionResult(body: String, httpSuccessful: Boolean = false): ServerActionResult {
        var code = ""
        var message = ""
        var dataSnapTruthy = false
        val trimmed = body.trim()
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            runCatching {
                collectServerActionFields(
                    json = if (trimmed.startsWith("[")) JSONArray(trimmed) else JSONObject(trimmed),
                    onCode = { if (code.isBlank()) code = it },
                    onMessage = { if (message.isBlank()) message = it },
                    onDataSnapResult = { dataSnapTruthy = dataSnapTruthy || it },
                )
            }
        }
        if (code.isBlank()) {
            Regex(""""CODIGO_RETORNO"\s*:\s*"?(\d+)"?""", RegexOption.IGNORE_CASE).find(trimmed)?.let {
                code = it.groupValues[1]
            }
        }
        if (message.isBlank()) {
            Regex(""""MENSAGEM"\s*:\s*"([^"]*)"""", RegexOption.IGNORE_CASE).find(trimmed)?.let {
                message = it.groupValues[1]
            }
        }
        val normalizedCode = code.trim().trim('"')
        val explicitFailure = normalizedCode.isNotBlank() && normalizedCode != "200"
        val success = when {
            explicitFailure -> false
            normalizedCode == "200" -> true
            dataSnapTruthy || isDataSnapTruthyText(trimmed) -> true
            httpSuccessful && trimmed.isEmpty() -> true
            httpSuccessful && normalizedCode.isBlank() && !looksLikeServerError(trimmed) -> true
            else -> false
        }
        return ServerActionResult(
            success = success,
            code = normalizedCode.ifBlank { if (success) "200" else "" },
            message = message.ifBlank { if (success) "OK" else trimmed.take(200) },
        )
    }

    private fun isDataSnapTruthyText(trimmed: String): Boolean {
        if (trimmed.equals("true", ignoreCase = true)) return true
        return Regex(""""result"\s*:\s*\[\s*true\s*]""", RegexOption.IGNORE_CASE)
            .containsMatchIn(trimmed)
    }

    private fun looksLikeServerError(trimmed: String): Boolean {
        if (trimmed.isEmpty()) return false
        val lower = trimmed.lowercase(Locale.US)
        if (lower.contains("erro") || lower.contains("falha") || lower.contains("exception")) {
            return true
        }
        val code = Regex(""""CODIGO_RETORNO"\s*:\s*"?(\d+)"?""", RegexOption.IGNORE_CASE)
            .find(trimmed)
            ?.groupValues
            ?.getOrNull(1)
        return !code.isNullOrBlank() && code != "200"
    }

    private fun collectServerActionFields(
        json: Any,
        onCode: (String) -> Unit,
        onMessage: (String) -> Unit,
        onDataSnapResult: (Boolean) -> Unit = {},
    ) {
        when (json) {
            is JSONObject -> {
                json.keys().forEach { key ->
                    if (json.isNull(key)) return@forEach
                    when (key.uppercase(Locale.US)) {
                        "CODIGO_RETORNO" -> onCode(json.get(key).toString().trim('"'))
                        "MENSAGEM" -> onMessage(json.get(key).toString().trim('"'))
                        "RESULT" -> onDataSnapResult(isTruthyJsonValue(json.get(key)))
                    }
                    when (val value = json.get(key)) {
                        is JSONObject -> collectServerActionFields(value, onCode, onMessage, onDataSnapResult)
                        is JSONArray -> collectServerActionFields(value, onCode, onMessage, onDataSnapResult)
                    }
                }
            }
            is JSONArray -> {
                if (json.length() == 1 && isTruthyJsonValue(json.get(0))) {
                    onDataSnapResult(true)
                }
                for (index in 0 until json.length()) {
                    when (val element = json.get(index)) {
                        is JSONObject -> collectServerActionFields(element, onCode, onMessage, onDataSnapResult)
                        is JSONArray -> collectServerActionFields(element, onCode, onMessage, onDataSnapResult)
                        else -> if (isTruthyJsonValue(element)) onDataSnapResult(true)
                    }
                }
            }
        }
    }

    private fun isTruthyJsonValue(value: Any?): Boolean {
        return when (value) {
            is Boolean -> value
            is Number -> value.toInt() == 200
            else -> value.toString().trim().trim('"').equals("true", ignoreCase = true) ||
                value.toString().trim().trim('"') == "200"
        }
    }

    fun parseCliente(body: String): Cliente? {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return null

        return when {
            trimmed.startsWith("[") -> parseClienteFromArray(JSONArray(trimmed))
            trimmed.startsWith("{") -> parseClienteFromObject(JSONObject(trimmed))
            else -> null
        }
    }

    private fun parseClienteFromArray(array: JSONArray): Cliente? {
        for (i in 0 until array.length()) {
            when (val element = array.get(i)) {
                is JSONObject -> parseClienteObject(element)?.let { return it }
                is JSONArray -> parseClienteFromArray(element)?.let { return it }
            }
        }
        return null
    }

    private fun parseClienteFromObject(json: JSONObject): Cliente? {
        parseClienteObject(json)?.let { return it }

        val fromResult = parseOptionalClienteField(json, "result")
        if (fromResult != null) return fromResult

        val fromData = parseOptionalClienteField(json, "data")
        if (fromData != null) return fromData

        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (json.isNull(key)) continue
            when (val value = json.get(key)) {
                is JSONObject -> parseClienteObject(value)?.let { return it }
                is JSONArray -> parseClienteFromArray(value)?.let { return it }
            }
        }
        return null
    }

    private fun parseOptionalClienteField(json: JSONObject, field: String): Cliente? {
        if (!json.has(field) || json.isNull(field)) return null
        return when (val value = json.get(field)) {
            is JSONObject -> parseClienteObject(value)
            is JSONArray -> parseClienteFromArray(value)
            else -> null
        }
    }

    private fun parseClienteObject(json: JSONObject): Cliente? {
        val nome = findField(json, "CLINOM") ?: return null
        val cpf = findField(json, "CLICPF").orEmpty()
        val cnpj = findField(json, "CLICGC").orEmpty()
        val documento = cpf.ifBlank { cnpj }
        return Cliente(nome = nome, cpfCnpj = documento)
    }

    private fun findField(json: JSONObject, key: String): String? {
        val keys = json.keys()
        while (keys.hasNext()) {
            val current = keys.next()
            if (current.equals(key, ignoreCase = true)) {
                if (json.isNull(current)) return ""
                return json.get(current).toString().trim()
            }
        }
        return null
    }

    fun parseAbastecimentos(body: String): List<Abastecimento> {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return emptyList()

        return when {
            trimmed.startsWith("[") -> parseJsonArray(JSONArray(trimmed))
            trimmed.startsWith("{") -> parseRootObject(JSONObject(trimmed))
            else -> emptyList()
        }
    }

    private fun parseRootObject(json: JSONObject): List<Abastecimento> {
        if (isAbastecimentoRecord(json)) {
            return listOf(parseAbastecimentoObject(json))
        }

        val fromResult = parseOptionalArrayField(json, "result")
        if (fromResult.isNotEmpty()) return fromResult

        val fromData = parseOptionalArrayField(json, "data")
        if (fromData.isNotEmpty()) return fromData

        return extractAbastecimentosFromObject(json)
    }

    private fun parseOptionalArrayField(json: JSONObject, field: String): List<Abastecimento> {
        if (!json.has(field) || json.isNull(field)) return emptyList()
        return when (val value = json.get(field)) {
            is JSONArray -> parseJsonArray(value)
            is JSONObject -> listOfNotNull(parseAbastecimentoFromElement(value))
            else -> emptyList()
        }
    }

    private fun extractAbastecimentosFromObject(json: JSONObject): List<Abastecimento> {
        val items = mutableListOf<Abastecimento>()
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (json.isNull(key)) continue
            when (val value = json.get(key)) {
                is JSONArray -> items.addAll(parseJsonArray(value))
                is JSONObject -> {
                    if (isAbastecimentoRecord(value)) {
                        items.add(parseAbastecimentoObject(value))
                    } else {
                        items.addAll(extractAbastecimentosFromObject(value))
                    }
                }
            }
        }
        return items
    }

    private fun parseJsonArray(array: JSONArray): List<Abastecimento> {
        val items = mutableListOf<Abastecimento>()
        for (i in 0 until array.length()) {
            when (val element = array.get(i)) {
                is JSONArray -> items.addAll(parseJsonArray(element))
                else -> parseAbastecimentoFromElement(element)?.let { items.add(it) }
            }
        }
        return items
    }

    private fun parseAbastecimentoFromElement(element: Any?): Abastecimento? {
        if (element == null || element == JSONObject.NULL) return null

        return when (element) {
            is JSONObject -> {
                if (isAbastecimentoRecord(element)) {
                    parseAbastecimentoObject(element)
                } else {
                    extractAbastecimentosFromObject(element).firstOrNull()
                }
            }
            is JSONArray -> null
            is String -> {
                val text = element.trim()
                if (text.startsWith("{")) {
                    runCatching { parseAbastecimentoFromElement(JSONObject(text)) }.getOrNull()
                } else {
                    null
                }
            }
            else -> null
        }
    }

    private fun isAbastecimentoRecord(json: JSONObject): Boolean {
        return json.has("ABABMB") || json.has("ABANUM") || json.has("ABATOT")
    }

    fun parseAbastecimentoObject(json: JSONObject): Abastecimento {
        val abanum = parseInt(json, "ABANUM")
        val abaaba = parseInt(json, "ABAABA")
        return Abastecimento(
            id = "${abanum}-${abaaba}",
            ababmb = parseString(json, "ABABMB"),
            abaqtd = parseDouble(json, "ABAQTD"),
            abavlruni = parseDouble(json, "ABAVLRUNI"),
            abatot = parseDouble(json, "ABATOT"),
            abanum = abanum,
            abaaba = abaaba,
            abaopeaba = parseString(json, "ABAOPEABA"),
            abaopedes = parseString(json, "ABAOPEDES"),
            abaprodes = parseString(json, "ABAPRODES"),
            abahoradia = formatHoradia(parseString(json, "ABAHORADIA")),
            abapro = parseInt(json, "ABAPRO")
        )
    }

    private fun parseString(json: JSONObject, key: String): String {
        if (!json.has(key) || json.isNull(key)) return ""
        return json.get(key).toString().trim()
    }

    private fun parseDouble(json: JSONObject, key: String): Double {
        if (!json.has(key) || json.isNull(key)) return 0.0
        return when (val value = json.get(key)) {
            is Number -> value.toDouble()
            is String -> parseDecimalString(value)
            else -> parseDecimalString(value.toString())
        }
    }

    private fun parseInt(json: JSONObject, key: String): Int {
        if (!json.has(key) || json.isNull(key)) return 0
        return when (val value = json.get(key)) {
            is Number -> value.toInt()
            is String -> parseDecimalString(value).toInt()
            else -> parseDecimalString(value.toString()).toInt()
        }
    }

    /**
     * Aceita decimal com virgula (66,81) ou ponto (66.81), formato comum em REST DataSnap BR.
     */
    fun parseDecimalString(raw: String): Double {
        val normalized = raw.trim()
            .replace(" ", "")
            .replace(',', '.')
        return normalized.toDoubleOrNull() ?: 0.0
    }

    private fun formatHoradia(raw: String): String {
        if (raw.isBlank()) return ""
        return when {
            raw.contains("T") -> raw.substringAfter("T").take(8)
            raw.length >= 19 && raw.contains(":") -> raw.substring(11, 19)
            else -> raw
        }
    }

    /**
     * Delphi [Tdmpri.Execute] para coleção `Cartao`: percorre o JSON da resposta e
     * retorna o primeiro valor cuja chave de NF-e começa com `43` (heurística legada)
     * ou qualquer sequência de 44 dígitos (chave de acesso NF-e).
     */
    fun parseCartaoMovimentoChave(body: String): String? {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return null

        extractNfeChave(trimmed)?.let { return it }

        if (trimmed.startsWith("[") || trimmed.startsWith("{")) {
            runCatching {
                when {
                    trimmed.startsWith("[") -> findNfeChaveInJsonArray(JSONArray(trimmed))
                    else -> findNfeChaveInJsonObject(JSONObject(trimmed))
                }
            }.getOrNull()?.let { return it }
        }

        return findNfeChaveInText(trimmed)
    }

    private fun findNfeChaveInText(text: String): String? {
        CHAVE_44_DIGITS_REGEX.findAll(text).forEach { match ->
            extractNfeChave(match.value)?.let { return it }
        }
        return null
    }

    private fun findNfeChaveInJsonArray(array: JSONArray): String? {
        for (i in 0 until array.length()) {
            when (val element = array.get(i)) {
                is JSONArray -> findNfeChaveInJsonArray(element)?.let { return it }
                is JSONObject -> findNfeChaveInJsonObject(element)?.let { return it }
                else -> extractNfeChave(element.toString())?.let { return it }
            }
        }
        return null
    }

    private fun findNfeChaveInJsonObject(json: JSONObject): String? {
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (json.isNull(key)) continue
            when (val value = json.get(key)) {
                is JSONArray -> findNfeChaveInJsonArray(value)?.let { return it }
                is JSONObject -> findNfeChaveInJsonObject(value)?.let { return it }
                else -> extractNfeChave(value.toString())?.let { return it }
            }
        }
        return null
    }

    private fun extractNfeChave(raw: String): String? {
        val cleaned = raw.trim().trim('"').trim('\'')
        if (cleaned.isEmpty()) return null

        val digitsOnly = cleaned.filter { it.isDigit() }
        if (digitsOnly.length == 44) return digitsOnly

        // Compatível com Delphi: `copy(Value, 1, 2) = '43'`
        if (cleaned.length >= 44 && cleaned.startsWith("43")) {
            val fromHeuristic = cleaned.filter { it.isDigit() }
            if (fromHeuristic.length == 44) return fromHeuristic
        }

        return null
    }
}
