package com.appsitef.smartpos.sales.model

data class Abastecimento(
    val id: String,
    val numeroBico: String,
    val quantidade: Double,
    val valorUnitario: Double,
    val valorTotal: Double,
    val operador: String
)
