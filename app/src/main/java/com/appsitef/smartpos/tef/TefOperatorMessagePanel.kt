package com.appsitef.smartpos.tef

import android.widget.TextView
import com.google.android.material.card.MaterialCardView

/**
 * Painel destacado com a mensagem atual do SiTef para o operador durante a transação TEF.
 */
class TefOperatorMessagePanel(
    private val card: MaterialCardView,
    private val messageView: TextView,
    private val waitingText: String,
) {

    fun show(message: String?) {
        if (message.isNullOrBlank()) {
            clear()
            return
        }
        messageView.text = message.trim()
    }

    fun clear() {
        messageView.text = waitingText
    }
}
