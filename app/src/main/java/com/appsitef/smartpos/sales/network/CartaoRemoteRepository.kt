package com.appsitef.smartpos.sales.network

import android.content.Context
import android.util.Log
import com.appsitef.smartpos.tef.TefPreferences
import com.appsitef.smartpos.tef.TefTransactionFieldParser
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody

class CartaoRemoteRepository(
    private val context: Context,
) {

    private val client = OkHttpClient.Builder().build()

    /**
     * Delphi: loop com até 10 tentativas — `cartao_movimento`.
     * Repete também quando o POST retorna OK mas a chave ainda não veio na resposta.
     */
    fun registrarCartaoMovimento(
        request: CartaoMovimentoPostRequest,
        maxAttempts: Int = 10,
    ): CartaoMovimentoResult {
        var lastError: Exception? = null
        var registrado = false

        repeat(maxAttempts) { attempt ->
            try {
                val response = postCartaoMovimento(request)
                registrado = true
                if (response.chaveNota.isNotBlank()) {
                    return response.copy(registrado = true)
                }
                Log.w(
                    TAG,
                    "cartao_movimento tentativa ${attempt + 1}/$maxAttempts sem chave — aguardando…"
                )
            } catch (error: Exception) {
                lastError = error
                Log.w(TAG, "cartao_movimento tentativa ${attempt + 1}/$maxAttempts falhou", error)
            }
            if (attempt < maxAttempts - 1) {
                Thread.sleep(RETRY_DELAY_MS)
            }
        }

        if (registrado) {
            return CartaoMovimentoResult(chaveNota = "", registrado = true)
        }
        throw lastError ?: IllegalStateException("Erro ao registrar movimento de cartão.")
    }

    /**
     * Delphi `udmpri.cancela_cartao` / `ufrm_admtef` — POST com segmentos na URL.
     * Repete só em falha de comunicação (exceção), não quando o HTTP já respondeu.
     */
    fun cancelaCartao(
        pdv: String,
        data: String,
        nsuHost: String,
        nsuCanc: String,
        maxAttempts: Int = 20,
    ): RestJsonParser.ServerActionResult {
        val dataServidor = TefTransactionFieldParser.ensureDataSitefDotted(data)
        var lastResult = RestJsonParser.ServerActionResult(
            success = false,
            code = "",
            message = "Erro ao cancelar no servidor.",
        )

        repeat(maxAttempts) { attempt ->
            try {
                return postCancelaCartao(
                    pdv = pdv,
                    nsuHost = nsuHost,
                    data = dataServidor,
                    nsuCanc = nsuCanc,
                )
            } catch (error: Exception) {
                lastResult = RestJsonParser.ServerActionResult(
                    success = false,
                    code = "",
                    message = error.message ?: "Erro de comunicação.",
                )
                Log.w(TAG, "cancelaCartao tentativa ${attempt + 1}/$maxAttempts falhou", error)
            }
            if (attempt < maxAttempts - 1) {
                Thread.sleep(RETRY_DELAY_MS)
            }
        }
        return lastResult
    }

    /** Delphi: `SrvMetodosGeraisClient.DownloadFile(CHAVENOTA + '-nfe.xml')`. */
    fun downloadNfeXml(chaveNota: String): ByteArray {
        val chave = chaveNota.trim()
        if (chave.isEmpty()) {
            error("Chave da nota não informada.")
        }

        TefPreferences.loadModuloIniIfExists(context)
        val fileName = CartaoUrlBuilder.buildNfeXmlFileName(chave)
        val url = CartaoUrlBuilder.buildDownloadFileUrl(context, fileName)
        Log.d(TAG, "GET DownloadFile file=$fileName url=$url")

        val httpRequest = Request.Builder()
            .url(url)
            .header("Authorization", Credentials.basic(BASIC_USER, BASIC_PASSWORD))
            .get()
            .build()

        client.newCall(httpRequest).execute().use { response ->
            if (!response.isSuccessful) {
                val body = response.body?.string().orEmpty()
                error(
                    "HTTP ${response.code} ao baixar XML da nota." +
                        if (body.isBlank()) "" else " $body"
                )
            }
            val bytes = response.body?.bytes() ?: ByteArray(0)
            if (bytes.isEmpty()) {
                error("XML da nota vazio no servidor.")
            }
            val xmlBytes = DatasnapDownloadDecoder.decodeToBytes(bytes)
            Log.d(TAG, "DownloadFile xmlBytes=${xmlBytes.size}")
            return xmlBytes
        }
    }

    private fun postCancelaCartao(
        pdv: String,
        nsuHost: String,
        data: String,
        nsuCanc: String,
    ): RestJsonParser.ServerActionResult {
        TefPreferences.loadModuloIniIfExists(context)
        val url = CartaoUrlBuilder.buildCancelaCartaoUrl(
            context = context,
            pdv = pdv,
            nsuHost = nsuHost,
            data = data,
            nsuCanc = nsuCanc,
        )
        Log.d(
            TAG,
            "POST CancelaCartao pdv=[$pdv] nsuHost=[$nsuHost] datasitef=[$data] nsuCanc=[$nsuCanc] url=$url",
        )

        val httpRequest = Request.Builder()
            .url(url)
            .header("Authorization", Credentials.basic(BASIC_USER, BASIC_PASSWORD))
            .post(RequestBody.create(null, ByteArray(0)))
            .build()

        client.newCall(httpRequest).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            Log.d(
                TAG,
                "CancelaCartao HTTP ${response.code} body=[${responseBody.take(300)}]",
            )
            if (!response.isSuccessful) {
                error(
                    "HTTP ${response.code} ao cancelar cartão." +
                        if (responseBody.isBlank()) "" else " $responseBody"
                )
            }
            return RestJsonParser.parseServerActionResult(
                body = responseBody,
                httpSuccessful = true,
            )
        }
    }

    private fun postCartaoMovimento(request: CartaoMovimentoPostRequest): CartaoMovimentoResult {
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
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                error(
                    "HTTP ${response.code} ao registrar cartão." +
                        if (body.isBlank()) "" else " $body"
                )
            }

            val chaveNota = RestJsonParser.parseCartaoMovimentoChave(body).orEmpty()
            if (chaveNota.isBlank()) {
                Log.w(TAG, "cartao_movimento OK sem chave na resposta: [${body.take(300)}]")
            } else {
                Log.d(TAG, "cartao_movimento chaveNota=[$chaveNota]")
            }
            return CartaoMovimentoResult(chaveNota = chaveNota, registrado = true)
        }
    }

    companion object {
        private const val TAG = "CartaoRest"
        private const val BASIC_USER = "modulo-info"
        private const val BASIC_PASSWORD = "@Modulo2023@"
        private const val RETRY_DELAY_MS = 1500L
    }
}
