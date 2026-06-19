package com.appsitef.smartpos.tef

/** Cupom fiscal térmico — texto + QR Code (NFC-e) ou código de barras da chave (NF-e). */
data class NfeThermalReceipt(
    val text: String,
    val qrCode: String? = null,
    /** Chave de acesso (44 dígitos) impressa em CODE_128 — DANFE simplificado NF-e. */
    val accessKeyBarCode: String? = null,
    val textAfterBarCode: String? = null,
)
