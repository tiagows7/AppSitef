package com.appsitef.smartpos.sales.network

class AbastecimentoRepository(private val apiService: AbastecimentoApiService) {

    suspend fun buscarPorBico(bico: String) = apiService.buscarAbastecimentos(bico)
}
