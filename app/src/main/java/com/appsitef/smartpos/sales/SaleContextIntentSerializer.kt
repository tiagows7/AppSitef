package com.appsitef.smartpos.sales

import com.appsitef.smartpos.sales.model.SaleContext
import org.json.JSONObject

object SaleContextIntentSerializer {

    fun toJson(context: SaleContext): String {
        return JSONObject()
            .put("customerCode", context.customerCode)
            .put("cpfCnpj", context.cpfCnpj)
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
                vehicle = json.optString("vehicle", ""),
                km = json.optString("km", ""),
            )
        }.getOrDefault(SaleContext())
    }
}
