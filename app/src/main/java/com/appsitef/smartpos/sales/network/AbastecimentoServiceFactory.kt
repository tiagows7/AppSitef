package com.appsitef.smartpos.sales.network

import com.appsitef.smartpos.BuildConfig
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

object AbastecimentoServiceFactory {

    private const val BASIC_USER = "modulo-info"
    private const val BASIC_PASSWORD = "@Modulo2023@"

    fun createApi(): AbastecimentoApiService {
        val basicAuthHeader = Credentials.basic(BASIC_USER, BASIC_PASSWORD)
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request: Request = chain.request()
                    .newBuilder()
                    .header("Authorization", basicAuthHeader)
                    .build()
                chain.proceed(request)
            }
            .build()

        return Retrofit.Builder()
            .baseUrl(BuildConfig.ABASTECIMENTO_BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(AbastecimentoApiService::class.java)
    }
}
