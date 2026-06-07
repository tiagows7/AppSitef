package com.appsitef.smartpos.sales.network

import android.content.Context
import com.appsitef.smartpos.sales.model.Cliente
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request

class ClienteRemoteRepository(
    private val context: Context
) {

    private val client = OkHttpClient.Builder().build()

    fun buscarCliente(codigo: String): Cliente {
        val url = ClienteUrlBuilder.buildClienteUrl(context, codigo)
        val request = Request.Builder()
            .url(url)
            .header("Authorization", Credentials.basic(BASIC_USER, BASIC_PASSWORD))
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                error("HTTP ${response.code} ao consultar cliente.")
            }
            val body = response.body?.string()?.trim().orEmpty()
            if (body.isBlank()) {
                error("Cliente não encontrado.")
            }
            return RestJsonParser.parseCliente(body)
                ?: error("Cliente não encontrado.")
        }
    }

    companion object {
        private const val BASIC_USER = "modulo-info"
        private const val BASIC_PASSWORD = "@Modulo2023@"
    }
}
