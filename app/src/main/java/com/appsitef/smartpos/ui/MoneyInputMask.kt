package com.appsitef.smartpos.ui

import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import com.google.android.material.textfield.TextInputEditText
import java.util.Locale

object MoneyInputMask {

    private val locale = Locale("pt", "BR")

    fun apply(editText: TextInputEditText) {
        editText.inputType = InputType.TYPE_CLASS_NUMBER
        val watcher = createWatcher(editText)
        editText.tag = watcher
        editText.addTextChangedListener(watcher)
    }

    fun formatFromDouble(value: Double): String {
        val cents = kotlin.math.round(value * 100).toLong().coerceAtLeast(0L)
        return formatCents(cents)
    }

    fun getFormattedText(editText: TextInputEditText): String {
        return editText.text?.toString()?.trim().orEmpty()
    }

    fun getNumericValue(editText: TextInputEditText): Double {
        return parseToDouble(getFormattedText(editText))
    }

    fun setValue(editText: TextInputEditText, raw: String) {
        val digits = raw.filter { it.isDigit() }
        val formatted = if (digits.isEmpty()) {
            ""
        } else {
            formatCents(digits.toLongOrNull() ?: 0L)
        }
        updateText(editText, formatted)
    }

    fun parseToDouble(formatted: String): Double {
        val digits = formatted.filter { it.isDigit() }
        if (digits.isEmpty()) return 0.0
        return (digits.toLongOrNull() ?: 0L) / 100.0
    }

    private fun createWatcher(editText: TextInputEditText): TextWatcher {
        return object : TextWatcher {
            private var selfChange = false

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

            override fun afterTextChanged(s: Editable?) {
                if (selfChange) return
                val digits = s?.toString().orEmpty().filter { it.isDigit() }
                val formatted = if (digits.isEmpty()) "" else formatCents(digits.toLongOrNull() ?: 0L)
                selfChange = true
                editText.setText(formatted)
                editText.setSelection(formatted.length)
                selfChange = false
            }
        }
    }

    private fun updateText(editText: TextInputEditText, formatted: String) {
        val watcher = editText.tag as? TextWatcher
        if (watcher != null) {
            editText.removeTextChangedListener(watcher)
        }
        editText.setText(formatted)
        editText.setSelection(formatted.length)
        if (watcher != null) {
            editText.addTextChangedListener(watcher)
        }
    }

    private fun formatCents(cents: Long): String {
        val reais = cents / 100
        val centavos = (cents % 100).toInt()
        return String.format(locale, "%,d,%02d", reais, centavos)
    }
}
