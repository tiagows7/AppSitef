package com.appsitef.smartpos.tef

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.google.zxing.common.BitMatrix

object QrCodeBitmapEncoder {

    fun encode(content: String, sizePx: Int): Bitmap? {
        if (content.isBlank() || sizePx <= 0) return null
        return try {
            val matrix = MultiFormatWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx)
            toBitmap(matrix, sizePx)
        } catch (_: Throwable) {
            null
        }
    }

    private fun toBitmap(matrix: BitMatrix, size: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
        for (x in 0 until size) {
            for (y in 0 until size) {
                bitmap.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
            }
        }
        return bitmap
    }
}
