package com.appsitef.smartpos.tef

import android.view.View
import android.widget.ImageView
import android.widget.TextView
import com.google.android.material.card.MaterialCardView

/**
 * Painel central com QR Code PIX (Carteira Digital) durante transação TEF.
 */
class TefPixQrCodePanel(
    private val qrCard: MaterialCardView,
    private val operatorCard: MaterialCardView,
    private val hintView: TextView,
    private val imageView: ImageView,
    private val defaultHint: String,
) {

    fun show(qrPayload: String, hint: String? = null) {
        val bitmap = QrCodeBitmapEncoder.encode(qrPayload, QR_SIZE_PX)
        if (bitmap == null) return

        hintView.text = hint?.trim().orEmpty().ifBlank { defaultHint }
        imageView.setImageBitmap(bitmap)
        operatorCard.visibility = View.GONE
        qrCard.visibility = View.VISIBLE
    }

    fun updateHint(message: String?) {
        if (!message.isNullOrBlank()) {
            hintView.text = message.trim()
        }
    }

    fun hide() {
        imageView.setImageDrawable(null)
        qrCard.visibility = View.GONE
        operatorCard.visibility = View.VISIBLE
    }

    companion object {
        private const val QR_SIZE_PX = 512
    }
}
