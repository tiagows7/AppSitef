package com.appsitef.smartpos.sales.network

import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.Charset

/**
 * DataSnap REST [DownloadFile] devolve JSON, não o arquivo bruto.
 * Formato observado no servidor: `{"result":[7574,[60,63,120,...]]}`
 * — índice 0 = tamanho, índice 1 = bytes do XML UTF-8.
 */
object DatasnapDownloadDecoder {

    private const val TAG = "DatasnapDownload"

    fun decodeToBytes(raw: ByteArray): ByteArray {
        if (raw.isEmpty()) error("Arquivo vazio no servidor.")

        val utf8Text = raw.toString(Charsets.UTF_8).trim()
        if (looksLikeXml(utf8Text)) {
            return raw
        }

        if (!utf8Text.startsWith("{") && !utf8Text.startsWith("[")) {
            return raw
        }

        return decodeJsonPayload(utf8Text)
    }

    fun decodeToXmlString(raw: ByteArray, charset: Charset = Charsets.UTF_8): String {
        val bytes = decodeToBytes(raw)
        val xml = bytes.toString(charset).trim()
        if (!looksLikeXml(xml)) {
            error("Conteúdo baixado não é XML de nota fiscal.")
        }
        return xml
    }

    private fun decodeJsonPayload(json: String): ByteArray {
        val root = when {
            json.startsWith("{") -> JSONObject(json)
            json.startsWith("[") -> JSONObject().put("result", JSONArray(json))
            else -> error("Formato JSON inválido no download.")
        }

        val result = root.opt("result")
            ?: root.opt("data")
            ?: error("Campo result/data ausente na resposta do download.")

        return when (result) {
            is String -> decodeStringPayload(result)
            is JSONArray -> decodeResultArray(result)
            else -> error("Tipo de result não suportado: ${result.javaClass.simpleName}")
        }
    }

    private fun decodeResultArray(array: JSONArray): ByteArray {
        if (array.length() == 0) {
            error("Array result vazio no download.")
        }

        // DataSnap: [tamanho, [byte, byte, ...]]
        if (array.length() >= 2 && array.get(1) is JSONArray) {
            val payload = array.getJSONArray(1)
            Log.d(TAG, "download nested bytes len=${payload.length()} declaredSize=${array.optInt(0)}")
            return jsonArrayToBytes(payload)
        }

        if (array.length() == 1) {
            when (val only = array.get(0)) {
                is String -> return decodeStringPayload(only)
                is JSONArray -> return jsonArrayToBytes(only)
            }
        }

        if (array.length() > 0 && array.get(0) is Number) {
            val first = array.getInt(0)
            if (first in 0..255) {
                return jsonArrayToBytes(array)
            }
        }

        val joined = buildString {
            for (i in 0 until array.length()) {
                when (val item = array.get(i)) {
                    is String -> append(item)
                }
            }
        }.trim()
        if (looksLikeXml(joined)) {
            return joined.toByteArray(Charsets.UTF_8)
        }

        error("Não foi possível extrair o XML do download DataSnap.")
    }

    private fun decodeStringPayload(value: String): ByteArray {
        val trimmed = value.trim()
        if (looksLikeXml(trimmed)) {
            return trimmed.toByteArray(Charsets.UTF_8)
        }
        return try {
            Base64.decode(trimmed, Base64.DEFAULT)
        } catch (error: IllegalArgumentException) {
            Log.w(TAG, "payload string não é XML nem base64", error)
            trimmed.toByteArray(Charsets.UTF_8)
        }
    }

    private fun jsonArrayToBytes(array: JSONArray): ByteArray {
        val bytes = ByteArray(array.length())
        for (i in 0 until array.length()) {
            val value = array.get(i)
            if (value !is Number) {
                error("Byte inválido no índice $i do download.")
            }
            val intValue = value.toInt()
            if (intValue !in 0..255) {
                error("Byte fora do intervalo no índice $i: $intValue")
            }
            bytes[i] = intValue.toByte()
        }
        return bytes
    }

    private fun looksLikeXml(text: String): Boolean {
        val trimmed = text.trimStart()
        return trimmed.startsWith("<?xml", ignoreCase = true) ||
            trimmed.startsWith("<nfeProc", ignoreCase = true) ||
            trimmed.startsWith("<NFe", ignoreCase = true) ||
            trimmed.startsWith("<nfceProc", ignoreCase = true)
    }
}
