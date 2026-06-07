package com.appsitef.smartpos.ui

import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import com.google.android.material.textfield.TextInputEditText

object DocumentInputMask {

    fun apply(editText: TextInputEditText) {
        editText.inputType = InputType.TYPE_CLASS_NUMBER
        val watcher = createWatcher(editText)
        editText.tag = watcher
        editText.addTextChangedListener(watcher)
    }

    fun getDigits(editText: TextInputEditText): String {
        return editText.text?.toString().orEmpty().filter { it.isDigit() }
    }

    fun clear(editText: TextInputEditText) {
        setDigits(editText, "")
    }

    fun setDigits(editText: TextInputEditText, digits: String) {
        val watcher = editText.tag as? TextWatcher
        if (watcher != null) {
            editText.removeTextChangedListener(watcher)
        }
        val formatted = formatDigits(digits.filter { it.isDigit() })
        editText.setText(formatted)
        editText.setSelection(formatted.length)
        if (watcher != null) {
            editText.addTextChangedListener(watcher)
        }
    }

    private fun createWatcher(editText: TextInputEditText): TextWatcher {
        return object : TextWatcher {
            private var selfChange = false

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

            override fun afterTextChanged(s: Editable?) {
                if (selfChange) return
                val digits = s?.toString().orEmpty().filter { it.isDigit() }.take(14)
                val formatted = formatDigits(digits)
                selfChange = true
                editText.setText(formatted)
                editText.setSelection(formatted.length)
                selfChange = false
            }
        }
    }

    private fun formatDigits(digits: String): String = when {
        digits.length <= 11 -> formatCpf(digits)
        else -> formatCnpj(digits)
    }

    private fun formatCpf(digits: String): String {
        val builder = StringBuilder()
        digits.forEachIndexed { index, char ->
            builder.append(char)
            when (index) {
                2, 5 -> builder.append('.')
                8 -> builder.append('-')
            }
        }
        return builder.toString()
    }

    private fun formatCnpj(digits: String): String {
        val builder = StringBuilder()
        digits.forEachIndexed { index, char ->
            builder.append(char)
            when (index) {
                1, 4 -> builder.append('.')
                7 -> builder.append('/')
                11 -> builder.append('-')
            }
        }
        return builder.toString()
    }
}
