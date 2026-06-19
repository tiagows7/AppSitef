package com.appsitef.smartpos.sales.network

import com.appsitef.smartpos.sales.model.ClienteCnpjData
import com.appsitef.smartpos.ui.DocumentValidator
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Consulta CNPJ na web (publica.cnpj.ws) — dados cadastrais + inscrição estadual.
 */
class CnpjPublicRepository {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    fun consultarCnpj(cnpj: String): ClienteCnpjData {
        val digits = cnpj.filter { it.isDigit() }
        if (digits.length != 14) {
            error("Informe um CNPJ válido com 14 dígitos.")
        }
        if (!DocumentValidator.isValidCnpj(digits)) {
            error("CNPJ inválido.")
        }

        val request = Request.Builder()
            .url("$BASE_URL/$digits")
            .header("Accept", "application/json")
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            when (response.code) {
                404 -> error("CNPJ não encontrado.")
                429 -> error("Limite de consultas atingido. Aguarde e tente novamente.")
            }
            if (!response.isSuccessful) {
                error("Erro ao consultar CNPJ (HTTP ${response.code}).")
            }
            val body = response.body?.string().orEmpty()
            if (body.isBlank()) {
                error("Resposta vazia ao consultar CNPJ.")
            }
            return parseResponse(digits, body)
        }
    }

    private fun parseResponse(cnpj: String, body: String): ClienteCnpjData {
        val json = JSONObject(body)
        val razaoSocial = json.optString("razao_social").trim()
        val estabelecimento = json.optJSONObject("estabelecimento")
        val nomeFantasia = estabelecimento?.optString("nome_fantasia").orEmpty().trim()
        val ufSede = estabelecimento?.optJSONObject("estado")?.optString("sigla").orEmpty().trim()
        val cidade = estabelecimento?.optJSONObject("cidade")?.optString("nome").orEmpty().trim()

        val tipoLogradouro = estabelecimento?.optString("tipo_logradouro").orEmpty().trim()
        val logradouro = estabelecimento?.optString("logradouro").orEmpty().trim()
        val endereco = listOf(tipoLogradouro, logradouro)
            .filter { it.isNotBlank() }
            .joinToString(" ")

        val (inscricaoEstadual, ufIe) = pickInscricaoEstadual(
            inscricoes = estabelecimento?.optJSONArray("inscricoes_estaduais"),
            ufPreferida = ufSede,
        )

        return ClienteCnpjData(
            cnpj = cnpj,
            nome = nomeFantasia.ifBlank { razaoSocial },
            endereco = endereco,
            numeroEndereco = estabelecimento?.optString("numero").orEmpty().trim(),
            bairro = estabelecimento?.optString("bairro").orEmpty().trim(),
            cidade = cidade,
            inscricaoEstadual = inscricaoEstadual,
            uf = ufIe.ifBlank { ufSede },
        )
    }

    private fun pickInscricaoEstadual(
        inscricoes: JSONArray?,
        ufPreferida: String,
    ): Pair<String, String> {
        if (inscricoes == null || inscricoes.length() == 0) {
            return "" to ""
        }

        data class Entry(val ie: String, val uf: String, val ativo: Boolean)

        val entries = buildList {
            for (index in 0 until inscricoes.length()) {
                val item = inscricoes.optJSONObject(index) ?: continue
                val ie = item.optString("inscricao_estadual").trim()
                if (ie.isBlank()) continue
                val uf = item.optJSONObject("estado")?.optString("sigla").orEmpty().trim()
                add(Entry(ie = ie, uf = uf, ativo = item.optBoolean("ativo", true)))
            }
        }

        val pool = entries.filter { it.ativo }.ifEmpty { entries }
        if (ufPreferida.isNotBlank()) {
            pool.firstOrNull { it.uf.equals(ufPreferida, ignoreCase = true) }?.let {
                return it.ie to it.uf
            }
        }

        val first = pool.firstOrNull() ?: return "" to ""
        return first.ie to first.uf
    }

    companion object {
        private const val BASE_URL = "https://publica.cnpj.ws/cnpj"
    }
}
