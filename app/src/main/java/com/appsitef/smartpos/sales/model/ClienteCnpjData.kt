package com.appsitef.smartpos.sales.model

/** Dados cadastrais do cliente (consulta CNPJ + edição manual). */
data class ClienteCnpjData(
    val cnpj: String = "",
    val nome: String = "",
    val endereco: String = "",
    val numeroEndereco: String = "",
    val bairro: String = "",
    val cidade: String = "",
    val inscricaoEstadual: String = "",
    val uf: String = "",
)
