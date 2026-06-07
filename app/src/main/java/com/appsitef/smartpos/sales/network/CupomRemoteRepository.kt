package com.appsitef.smartpos.sales.network

import com.appsitef.smartpos.sales.model.CupomValidationResult
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class CupomRemoteRepository {

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .build()

    fun validarCupom(codigo: Int, cnpjPosto: String): CupomValidationResult {
        if (cnpjPosto.isBlank()) {
            return CupomValidationResult(
                sucesso = false,
                mensagem = "CNPJ do posto não configurado."
            )
        }

        val payload = JSONObject()
            .put("codigo_gerado", codigo.toString())
            .put("cnpj_posto", cnpjPosto)

        val request = Request.Builder()
            .url(VALIDAR_CUPOM_URL)
            .header("Authorization", "Bearer $AUTH_TOKEN")
            .header("Content-Type", "application/json")
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                return CupomValidationResult(
                    sucesso = false,
                    mensagem = "Erro HTTP: ${response.code}\n$body"
                )
            }
            return parseResponse(body)
        }
    }

    private fun parseResponse(body: String): CupomValidationResult {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) {
            return CupomValidationResult(
                sucesso = false,
                mensagem = "Cupom inválido ou já utilizado"
            )
        }

        val json = JSONObject(trimmed)
        if (!json.optBoolean("sucesso", false)) {
            return CupomValidationResult(
                sucesso = false,
                mensagem = "Cupom inválido ou já utilizado"
            )
        }

        val data = json.optJSONObject("data")
            ?: return CupomValidationResult(
                sucesso = false,
                mensagem = "Cupom inválido ou já utilizado"
            )

        return CupomValidationResult(
            sucesso = true,
            mensagem = "Cupom válido!",
            valorDesconto = parseDouble(data, "valor_unitario"),
            tipoDesconto = data.optString("tipo_cupom", ""),
            produto = data.optString("tipo_produto", "0").filter { it.isDigit() }.toIntOrNull() ?: 0
        )
    }

    private fun parseDouble(json: JSONObject, key: String): Double {
        if (!json.has(key) || json.isNull(key)) return 0.0
        return when (val value = json.get(key)) {
            is Number -> value.toDouble()
            is String -> RestJsonParser.parseDecimalString(value)
            else -> RestJsonParser.parseDecimalString(value.toString())
        }
    }

    companion object {
        private const val VALIDAR_CUPOM_URL =
            "https://josmwljxjmazingfizmc.supabase.co/functions/v1/validar-cupom"
        private const val AUTH_TOKEN = "sk_firebird_8f93b2a1a4c9d5e6"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
