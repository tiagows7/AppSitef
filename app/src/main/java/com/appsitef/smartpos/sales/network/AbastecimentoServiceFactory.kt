package com.appsitef.smartpos.sales.network

import com.appsitef.smartpos.BuildConfig
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

object AbastecimentoServiceFactory {

    fun createApi(): AbastecimentoApiService {
        return Retrofit.Builder()
            .baseUrl(BuildConfig.ABASTECIMENTO_BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(AbastecimentoApiService::class.java)
    }
}
