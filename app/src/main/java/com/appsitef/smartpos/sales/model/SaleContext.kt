package com.appsitef.smartpos.sales.model

/** Dados da venda usados no POST `cartao_movimento` após TEF aprovado. */
data class SaleContext(
    val customerCode: String = "",
    val cpfCnpj: String = "",
    val vehicle: String = "",
    val km: String = "",
)
