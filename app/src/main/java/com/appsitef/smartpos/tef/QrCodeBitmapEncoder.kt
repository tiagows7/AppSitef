package com.appsitef.smartpos.tef

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

object QrCodeBitmapEncoder {

    /**
     * Gera QR em ARGB_8888 — mesmo formato do [BitmapToJBitmap] Delphi usado com DrawPictureExt.
     */
    fun encode(content: String, sizePx: Int): Bitmap? {
        if (content.isBlank() || sizePx <= 0) return null
        return try {
            val hints = mapOf(
                EncodeHintType.MARGIN to 2,
                EncodeHintType.CHARACTER_SET to "UTF-8",
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            )
            val matrix = MultiFormatWriter().encode(
                content,
                BarcodeFormat.QR_CODE,
                sizePx,
                sizePx,
                hints,
            )
            toArgbBitmap(matrix)
        } catch (_: Throwable) {
            null
        }
    }

    /** CODE_128 da chave NF-e (44 dígitos) para DrawPictureExt. */
    fun encodeCode128(content: String, widthPx: Int, heightPx: Int): Bitmap? {
        if (content.isBlank() || widthPx <= 0 || heightPx <= 0) return null
        return try {
            val hints = mapOf(
                EncodeHintType.MARGIN to 2,
                EncodeHintType.CHARACTER_SET to "UTF-8",
            )
            val matrix = MultiFormatWriter().encode(
                content,
                BarcodeFormat.CODE_128,
                widthPx,
                heightPx,
                hints,
            )
            toArgbBitmap(matrix)
        } catch (_: Throwable) {
            null
        }
    }

    private fun toArgbBitmap(matrix: BitMatrix): Bitmap {
        val width = matrix.width
        val height = matrix.height
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            val offset = y * width
            for (x in 0 until width) {
                pixels[offset + x] = if (matrix[x, y]) Color.BLACK else Color.WHITE
            }
        }
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
            it.setPixels(pixels, 0, width, 0, 0, width, height)
        }
    }
}
