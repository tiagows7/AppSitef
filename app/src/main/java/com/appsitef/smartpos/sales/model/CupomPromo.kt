package com.appsitef.smartpos.sales.model

data class CupomPromo(
    val codigo: String,
    val tipoDesconto: String,
    val valorDesconto: Double,
    val produto: Int
)

data class CupomValidationResult(
    val sucesso: Boolean,
    val mensagem: String,
    val valorDesconto: Double = 0.0,
    val tipoDesconto: String = "",
    val produto: Int = 0
)
