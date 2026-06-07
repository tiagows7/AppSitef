package com.appsitef.smartpos.sales.model

data class Abastecimento(
    val id: String,
    val ababmb: String,
    val abaqtd: Double,
    val abavlruni: Double,
    val abatot: Double,
    val abanum: Int,
    val abaaba: Int,
    val abaopeaba: String,
    val abaopedes: String,
    val abaprodes: String,
    val abahoradia: String,
    val abapro: Int,
    val abadesconto: Double = 0.0
)
