package com.appsitef.smartpos.ui

import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.widget.EditText
import java.util.Locale

object MoneyInputMask {

    private val locale = Locale("pt", "BR")

    fun apply(editText: EditText, maxCentsProvider: (() -> Long)? = null) {
        editText.inputType = InputType.TYPE_CLASS_NUMBER
        val watcher = createWatcher(editText, maxCentsProvider)
        editText.tag = watcher
        editText.addTextChangedListener(watcher)
    }

    fun formatFromDouble(value: Double): String {
        val cents = kotlin.math.round(value * 100).toLong().coerceAtLeast(0L)
        return formatCents(cents)
    }

    fun formatFromCents(cents: Long): String = formatCents(cents.coerceAtLeast(0L))

    fun getFormattedText(editText: EditText): String {
        return editText.text?.toString()?.trim().orEmpty()
    }

    fun getNumericValue(editText: EditText): Double {
        return parseToDouble(getFormattedText(editText))
    }

    /** Centavos a partir do texto mascarado (ex.: "150,00" → 15000). */
    fun getCents(editText: EditText): Long {
        return parseToCents(getFormattedText(editText))
    }

    fun parseToCents(formatted: String): Long {
        val digits = formatted.filter { it.isDigit() }
        return digits.toLongOrNull() ?: 0L
    }

    fun setValue(editText: EditText, raw: String) {
        val digits = raw.filter { it.isDigit() }
        val formatted = if (digits.isEmpty()) {
            ""
        } else {
            formatCents(digits.toLongOrNull() ?: 0L)
        }
        updateText(editText, formatted)
    }

    fun setCents(editText: EditText, cents: Long) {
        if (cents <= 0L) {
            updateText(editText, "")
            return
        }
        updateText(editText, formatCents(cents))
    }

    fun parseToDouble(formatted: String): Double {
        return parseToCents(formatted) / 100.0
    }

    fun toCents(value: Double): Long = kotlin.math.round(value * 100).toLong()

    private fun createWatcher(
        editText: EditText,
        maxCentsProvider: (() -> Long)?,
    ): TextWatcher {
        return object : TextWatcher {
            private var selfChange = false

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

            override fun afterTextChanged(s: Editable?) {
                if (selfChange) return
                val digits = s?.toString().orEmpty().filter { it.isDigit() }
                var cents = digits.toLongOrNull() ?: 0L
                val maxCents = maxCentsProvider?.invoke() ?: 0L
                if (maxCents > 0L && cents > maxCents) {
                    cents = maxCents
                }
                val formatted = if (digits.isEmpty()) "" else formatCents(cents)
                selfChange = true
                editText.setText(formatted)
                editText.setSelection(formatted.length)
                selfChange = false
            }
        }
    }

    private fun updateText(editText: EditText, formatted: String) {
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
