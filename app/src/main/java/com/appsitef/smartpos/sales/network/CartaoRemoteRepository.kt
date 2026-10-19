package com.appsitef.smartpos.sales.network

import android.content.Context
import android.util.Log
import com.appsitef.smartpos.tef.TefPreferences
import com.appsitef.smartpos.tef.TefTransactionFieldParser
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import java.util.concurrent.TimeUnit

class CartaoRemoteRepository(
    private val context: Context,
) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    /**
     * Um único POST de `cartao_movimento`, esperando até 20s.
     * Se a chave/XML da nota não voltar, não reenvia a venda.
     */
    fun registrarCartaoMovimento(
        request: CartaoMovimentoPostRequest,
    ): CartaoMovimentoResult {
        val response = postCartaoMovimento(request)
        if (response.chaveNota.isBlank()) {
            Log.w(TAG, "cartao_movimento OK sem chave da nota — não reenvia")
        }
        return response.copy(registrado = true)
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

    /**
     * Delphi: `SrvMetodosGeraisClient.DownloadFile(CHAVENOTA + '-nfe.xml')`.
     * Repete enquanto o arquivo ainda não existe no servidor (comum logo após cartao_movimento).
     */
    fun downloadNfeXml(
        chaveNota: String,
        maxAttempts: Int = DOWNLOAD_NFE_MAX_ATTEMPTS,
    ): ByteArray {
        val chave = chaveNota.trim()
        if (chave.isEmpty()) {
            error("Chave da nota não informada.")
        }

        TefPreferences.loadModuloIniIfExists(context)
        val fileName = CartaoUrlBuilder.buildNfeXmlFileName(chave)
        val url = CartaoUrlBuilder.buildDownloadFileUrl(context, fileName)
        var lastError: Exception? = null

        repeat(maxAttempts) { attempt ->
            try {
                Log.d(TAG, "GET DownloadFile file=$fileName attempt=${attempt + 1}/$maxAttempts")
                val xmlBytes = getDownloadFileBytes(url)
                if (xmlBytes.isNotEmpty()) {
                    Log.d(TAG, "DownloadFile xmlBytes=${xmlBytes.size}")
                    return xmlBytes
                }
                lastError = IllegalStateException("XML da nota vazio no servidor.")
            } catch (error: Exception) {
                lastError = error
                Log.w(TAG, "DownloadFile tentativa ${attempt + 1}/$maxAttempts falhou", error)
            }
            if (attempt < maxAttempts - 1) {
                Thread.sleep(RETRY_DELAY_MS)
            }
        }

        throw lastError ?: IllegalStateException("Erro ao baixar XML da nota.")
    }

    private fun getDownloadFileBytes(url: String): ByteArray {
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
            return DatasnapDownloadDecoder.decodeToBytes(bytes)
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
        private const val DOWNLOAD_NFE_MAX_ATTEMPTS = 10
    }
}
