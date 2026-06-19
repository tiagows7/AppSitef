package com.appsitef.smartpos.sales

import com.appsitef.smartpos.sales.model.ClienteCnpjData
import org.json.JSONObject

object ClienteCnpjDataSerializer {

    fun toJson(data: ClienteCnpjData): String {
        return JSONObject()
            .put("cnpj", data.cnpj)
            .put("nome", data.nome)
            .put("endereco", data.endereco)
            .put("numeroEndereco", data.numeroEndereco)
            .put("bairro", data.bairro)
            .put("cidade", data.cidade)
            .put("inscricaoEstadual", data.inscricaoEstadual)
            .put("uf", data.uf)
            .toString()
    }

    fun fromJson(raw: String?): ClienteCnpjData? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            val json = JSONObject(raw)
            ClienteCnpjData(
                cnpj = json.optString("cnpj", ""),
                nome = json.optString("nome", ""),
                endereco = json.optString("endereco", ""),
                numeroEndereco = json.optString("numeroEndereco", ""),
                bairro = json.optString("bairro", ""),
                cidade = json.optString("cidade", ""),
                inscricaoEstadual = json.optString("inscricaoEstadual", ""),
                uf = json.optString("uf", ""),
            )
        }.getOrNull()
    }
}
