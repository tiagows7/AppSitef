package com.appsitef.smartpos.sales

import com.appsitef.smartpos.sales.model.Abastecimento
import org.json.JSONArray
import org.json.JSONObject

object AbastecimentoIntentSerializer {

    fun toJson(items: List<Abastecimento>): String {
        val array = JSONArray()
        items.forEach { item ->
            array.put(
                JSONObject()
                    .put("id", item.id)
                    .put("ababmb", item.ababmb)
                    .put("abaqtd", item.abaqtd)
                    .put("abavlruni", item.abavlruni)
                    .put("abatot", item.abatot)
                    .put("abanum", item.abanum)
                    .put("abaaba", item.abaaba)
                    .put("abaopeaba", item.abaopeaba)
                    .put("abaopedes", item.abaopedes)
                    .put("abaprodes", item.abaprodes)
                    .put("abahoradia", item.abahoradia)
                    .put("abapro", item.abapro)
                    .put("abadesconto", item.abadesconto)
            )
        }
        return array.toString()
    }

    fun fromJson(raw: String?): List<Abastecimento> {
        if (raw.isNullOrBlank()) return emptyList()
        val array = JSONArray(raw)
        val items = mutableListOf<Abastecimento>()
        for (index in 0 until array.length()) {
            val json = array.optJSONObject(index) ?: continue
            items.add(
                Abastecimento(
                    id = json.optString("id"),
                    ababmb = json.optString("ababmb"),
                    abaqtd = json.optDouble("abaqtd"),
                    abavlruni = json.optDouble("abavlruni"),
                    abatot = json.optDouble("abatot"),
                    abanum = json.optInt("abanum"),
                    abaaba = json.optInt("abaaba"),
                    abaopeaba = json.optString("abaopeaba"),
                    abaopedes = json.optString("abaopedes"),
                    abaprodes = json.optString("abaprodes"),
                    abahoradia = json.optString("abahoradia"),
                    abapro = json.optInt("abapro"),
                    abadesconto = json.optDouble("abadesconto"),
                )
            )
        }
        return items
    }
}
