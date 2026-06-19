package com.appsitef.smartpos.tef

import org.json.JSONObject

/**
 * Delphi `TIPO_CAMPOS` — JSON retornado pelo m-SiTef / CliSiTef após cancelamento:
 * `{"105":{"A":[{"Value":"20260602"}]},"1321":{"A":[{"Value":"123456"}]},...}`
 */
object TipoCamposJsonParser {

    fun looksLikeTipoCampos(raw: String): Boolean {
        val trimmed = raw.trim()
        return trimmed.startsWith("{") &&
            trimmed.contains("\"A\"") &&
            (trimmed.contains("\"105\"") ||
                trimmed.contains("\"1321\"") ||
                trimmed.contains("\"620\"") ||
                trimmed.contains("\"134\"") ||
                trimmed.contains("\"146\""))
    }

    fun applyTo(result: TefTransactionResult, raw: String): Boolean {
        if (!looksLikeTipoCampos(raw)) return false

        return runCatching {
            val json = JSONObject(raw.trim())
            extractValue(json, "105")?.let { value ->
                result.sitefDataHoraRaw = value.filter { it.isDigit() }
                val (data, hora) = TefTransactionFieldParser.parseDataHoraSitef(value)
                if (data.isNotBlank()) result.dataTransacao = data
                if (hora.isNotBlank()) result.horaTransacao = hora
            }
            extractValue(json, "1321")?.let { value ->
                if (value.length >= result.nsuTransacaoOriginal.length) {
                    result.nsuTransacaoOriginal = value
                }
            }
            extractValue(json, "620")?.let { value ->
                if (result.nsuTransacaoOriginal.isBlank() ||
                    value.length >= result.nsuTransacaoOriginal.length
                ) {
                    result.nsuTransacaoOriginal = value
                }
            }
            extractValue(json, "134")?.let { value ->
                if (value.isNotBlank()) result.nsuHost = value
            }
            extractValue(json, "146")?.let { value ->
                if (value.length >= result.valorCancelamento.length) {
                    result.valorCancelamento = value
                }
            }
            true
        }.getOrDefault(false)
    }

    private fun extractValue(json: JSONObject, fieldKey: String): String? {
        val node = json.optJSONObject(fieldKey) ?: return null
        val array = node.optJSONArray("A") ?: return null
        if (array.length() == 0) return null
        val value = array.optJSONObject(0)?.optString("Value").orEmpty().trim()
        return value.ifBlank { null }
    }
}
