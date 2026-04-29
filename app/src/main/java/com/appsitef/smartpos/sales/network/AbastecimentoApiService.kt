package com.appsitef.smartpos.sales.network

import com.appsitef.smartpos.sales.model.Abastecimento
import retrofit2.http.GET
import retrofit2.http.Query

interface AbastecimentoApiService {

    @GET("abastecimentos")
    suspend fun buscarAbastecimentos(@Query("bico") bico: String): List<Abastecimento>
}
