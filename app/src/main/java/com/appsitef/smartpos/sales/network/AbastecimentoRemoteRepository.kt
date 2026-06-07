package com.appsitef.smartpos.sales.network

import android.content.Context
import android.util.Log
import com.appsitef.smartpos.sales.model.Abastecimento
import com.appsitef.smartpos.tef.TefPreferences
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody

class AbastecimentoRemoteRepository(
    private val context: Context
) {

    private val client = OkHttpClient.Builder().build()

    fun buscarAbastecimentos(bico: String): List<Abastecimento> {
        val url = AbastecimentoUrlBuilder.buildAbastecimentosUrl(context, bico)
        val request = Request.Builder()
            .url(url)
            .header("Authorization", Credentials.basic(BASIC_USER, BASIC_PASSWORD))
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                error("HTTP ${response.code} ao consultar abastecimentos.")
            }
            val body = response.body?.string()?.trim().orEmpty()
            if (body.isBlank()) return emptyList()
            return RestJsonParser.parseAbastecimentos(body)
        }
    }

    fun registrarAbastecimento(abastecimento: Abastecimento) {
        postAbastecimento(AbastecimentoPostRequest.selecao(abastecimento), "registrar")
    }

    fun liberarAbastecimento(
        abastecimento: Abastecimento,
        cartaonsu: String = "",
        cartaohora: String = "",
    ) {
        postAbastecimento(
            AbastecimentoPostRequest.liberacao(abastecimento, cartaonsu, cartaohora),
            "liberar"
        )
    }

    fun liberarAbastecimentos(abastecimentos: List<Abastecimento>) {
        abastecimentos.forEach { liberarAbastecimento(it) }
    }

    fun registrarAbastecimentoPagamento(
        abastecimento: Abastecimento,
        cartaonsu: String,
        cartaohora: String,
    ) {
        postAbastecimento(
            AbastecimentoPostRequest.pagamento(abastecimento, cartaonsu, cartaohora),
            "registrar pagamento"
        )
    }

    /**
     * Delphi: loop com tentativas após TEF aprovado — tipo=1, cartaonsu=NSU_HOST, cartaohora=HORATRANSACAO.
     */
    fun registrarAbastecimentosPagamento(
        abastecimentos: List<Abastecimento>,
        cartaonsu: String,
        cartaohora: String,
        maxAttempts: Int = 10,
    ) {
        if (abastecimentos.isEmpty()) return

        var lastError: Exception? = null
        repeat(maxAttempts) { attempt ->
            try {
                abastecimentos.forEach { item ->
                    registrarAbastecimentoPagamento(item, cartaonsu, cartaohora)
                }
                return
            } catch (error: Exception) {
                lastError = error
                if (attempt < maxAttempts - 1) {
                    Thread.sleep(1000L)
                }
            }
        }
        throw lastError ?: IllegalStateException("Erro ao registrar pagamento dos abastecimentos.")
    }

    private fun postAbastecimento(request: AbastecimentoPostRequest, action: String) {
        TefPreferences.loadModuloIniIfExists(context)
        val url = AbastecimentoUrlBuilder.buildAbastecimentosSelecaoUrl(context, request)
        Log.d(TAG, "POST $action url=$url")

        val httpRequest = Request.Builder()
            .url(url)
            .header("Authorization", Credentials.basic(BASIC_USER, BASIC_PASSWORD))
            .post(RequestBody.create(null, ByteArray(0)))
            .build()

        client.newCall(httpRequest).execute().use { response ->
            if (!response.isSuccessful) {
                val body = response.body?.string().orEmpty()
                error(
                    "HTTP ${response.code} ao $action abastecimento." +
                        if (body.isBlank()) "" else " $body"
                )
            }
        }
    }

    companion object {
        private const val TAG = "AbastecimentoRest"
        private const val BASIC_USER = "modulo-info"
        private const val BASIC_PASSWORD = "@Modulo2023@"
    }
}
