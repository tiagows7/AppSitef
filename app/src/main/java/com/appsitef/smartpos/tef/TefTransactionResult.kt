package com.appsitef.smartpos.tef

import org.json.JSONObject

/**
 * Campos da transação TEF aprovada — espelha TLibrary Delphi após autorização.
 */
data class TefTransactionResult(
    var dataTransacao: String = "",
    /** Buffer bruto do campo 105 — `DDMMAAAA` ou `DDMMAAAAHHMMSS` sem pontuação. */
    var sitefDataHoraRaw: String = "",
    var horaTransacao: String = "",
    var dataPreDatado: String = "",
    /** Buffer bruto do campo 506 — `MMDDAAAA` sem pontuação. */
    var sitefPreDatadoRaw: String = "",
    var parcelas: String = "0",
    var codTrans: String = "",
    var redeAut: String = "",
    var bandeira: String = "",
    var tipoParc: String = "",
    var nsuSitef: String = "",
    var nsuHost: String = "",
    var codAutorizacao: String = "",
) {
    fun toJson(): String {
        return JSONObject()
            .put("dataTransacao", dataTransacao)
            .put("sitefDataHoraRaw", sitefDataHoraRaw)
            .put("horaTransacao", horaTransacao)
            .put("dataPreDatado", dataPreDatado)
            .put("sitefPreDatadoRaw", sitefPreDatadoRaw)
            .put("parcelas", parcelas)
            .put("codTrans", codTrans)
            .put("redeAut", redeAut)
            .put("bandeira", bandeira)
            .put("tipoParc", tipoParc)
            .put("nsuSitef", nsuSitef)
            .put("nsuHost", nsuHost)
            .put("codAutorizacao", codAutorizacao)
            .toString()
    }

    companion object {
        fun fromJson(raw: String?): TefTransactionResult? {
            if (raw.isNullOrBlank()) return null
            return runCatching {
                val json = JSONObject(raw)
                TefTransactionResult(
                    dataTransacao = json.optString("dataTransacao", ""),
                    sitefDataHoraRaw = json.optString("sitefDataHoraRaw", ""),
                    horaTransacao = json.optString("horaTransacao", ""),
                    dataPreDatado = json.optString("dataPreDatado", ""),
                    sitefPreDatadoRaw = json.optString("sitefPreDatadoRaw", ""),
                    parcelas = json.optString("parcelas", "0"),
                    codTrans = json.optString("codTrans", ""),
                    redeAut = json.optString("redeAut", ""),
                    bandeira = json.optString("bandeira", ""),
                    tipoParc = json.optString("tipoParc", ""),
                    nsuSitef = json.optString("nsuSitef", ""),
                    nsuHost = json.optString("nsuHost", ""),
                    codAutorizacao = json.optString("codAutorizacao", ""),
                )
            }.getOrNull()
        }
    }
}
