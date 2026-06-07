package com.appsitef.smartpos.sales.network

import android.content.Context
import android.util.Log
import com.appsitef.smartpos.tef.TefPreferences
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody

class CartaoRemoteRepository(
    private val context: Context,
) {

    private val client = OkHttpClient.Builder().build()

    /**
     * Delphi: loop com até 10 tentativas após abastecimentos registrados — `cartao_movimento`.
     */
    fun registrarCartaoMovimento(
        request: CartaoMovimentoPostRequest,
        maxAttempts: Int = 10,
    ) {
        var lastError: Exception? = null
        repeat(maxAttempts) { attempt ->
            try {
                postCartaoMovimento(request)
                return
            } catch (error: Exception) {
                lastError = error
                if (attempt < maxAttempts - 1) {
                    Thread.sleep(1000L)
                }
            }
        }
        throw lastError ?: IllegalStateException("Erro ao registrar movimento de cartão.")
    }

    private fun postCartaoMovimento(request: CartaoMovimentoPostRequest) {
        TefPreferences.loadModuloIniIfExists(context)
        val url = CartaoUrlBuilder.buildCartaoMovimentoUrl(context, request)
        Log.d(
            TAG,
            "POST cartao_movimento dataSitef=[${request.dataSitefFormatado()}] " +
                "preDatado=[${DatasnapPathEncoder.normalizePreDatado(request.preDatado)}] url=$url"
        )

        val httpRequest = Request.Builder()
            .url(url)
            .header("Authorization", Credentials.basic(BASIC_USER, BASIC_PASSWORD))
            .post(RequestBody.create(null, ByteArray(0)))
            .build()

        client.newCall(httpRequest).execute().use { response ->
            if (!response.isSuccessful) {
                val body = response.body?.string().orEmpty()
                error(
                    "HTTP ${response.code} ao registrar cartão." +
                        if (body.isBlank()) "" else " $body"
                )
            }
        }
    }

    companion object {
        private const val TAG = "CartaoRest"
        private const val BASIC_USER = "modulo-info"
        private const val BASIC_PASSWORD = "@Modulo2023@"
    }
}
