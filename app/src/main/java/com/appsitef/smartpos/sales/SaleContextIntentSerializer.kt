package com.appsitef.smartpos.sales

import com.appsitef.smartpos.sales.model.SaleContext
import org.json.JSONObject

object SaleContextIntentSerializer {

    fun toJson(context: SaleContext): String {
        return JSONObject()
            .put("customerCode", context.customerCode)
            .put("cpfCnpj", context.cpfCnpj)
            .put("nome", context.nome)
            .put("endereco", context.endereco)
            .put("numeroEndereco", context.numeroEndereco)
            .put("bairro", context.bairro)
            .put("cidade", context.cidade)
            .put("inscricaoEstadual", context.inscricaoEstadual)
            .put("uf", context.uf)
            .put("vehicle", context.vehicle)
            .put("km", context.km)
            .toString()
    }

    fun fromJson(raw: String?): SaleContext {
        if (raw.isNullOrBlank()) return SaleContext()
        return runCatching {
            val json = JSONObject(raw)
            SaleContext(
                customerCode = json.optString("customerCode", ""),
                cpfCnpj = json.optString("cpfCnpj", ""),
                nome = json.optString("nome", ""),
                endereco = json.optString("endereco", ""),
                numeroEndereco = json.optString("numeroEndereco", ""),
                bairro = json.optString("bairro", ""),
                cidade = json.optString("cidade", ""),
                inscricaoEstadual = json.optString("inscricaoEstadual", ""),
                uf = json.optString("uf", ""),
                vehicle = json.optString("vehicle", ""),
                km = json.optString("km", ""),
            )
        }.getOrDefault(SaleContext())
    }
}
