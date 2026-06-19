package com.appsitef.smartpos.tef

/**
 * Chave de acesso (44 dígitos) — posições 21-22 = modelo do documento fiscal.
 * `55` = NF-e, demais (ex.: `65`) = NFC-e.
 */
object NfeChaveAccessKey {

    private const val CHAVE_LENGTH = 44
    private const val MODELO_START_INDEX = 20
    private const val MODELO_END_INDEX = 22
    private const val MODELO_NFE = "55"

    fun normalize(raw: String): String = raw.filter { it.isDigit() }

    /** Modelo nas posições 21-22 da chave (1-based). */
    fun modeloFromChave(raw: String): String? {
        val chave = normalize(raw)
        if (chave.length < MODELO_END_INDEX) return null
        return chave.substring(MODELO_START_INDEX, MODELO_END_INDEX)
    }

    fun isNfe(raw: String): Boolean = modeloFromChave(raw) == MODELO_NFE

    fun isNfce(raw: String): Boolean {
        val modelo = modeloFromChave(raw) ?: return true
        return modelo != MODELO_NFE
    }

    fun documentLabel(raw: String): String = if (isNfe(raw)) "NF-e" else "NFC-e"

    fun isValid(raw: String): Boolean = normalize(raw).length == CHAVE_LENGTH
}
