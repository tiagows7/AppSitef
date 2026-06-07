package com.appsitef.smartpos.ui

import android.text.InputFilter
import android.text.InputType
import android.text.method.DigitsKeyListener
import com.google.android.material.textfield.TextInputEditText

object NumericInputFilters {

    fun applyDigitsOnly(editText: TextInputEditText, maxDigits: Int) {
        editText.inputType = InputType.TYPE_CLASS_NUMBER
        editText.keyListener = DigitsKeyListener.getInstance("0123456789")
        editText.filters = arrayOf(
            InputFilter.LengthFilter(maxDigits),
            digitsOnlyFilter()
        )
    }

    private fun digitsOnlyFilter(): InputFilter {
        return InputFilter { source, start, end, _, _, _ ->
            val chunk = source.subSequence(start, end).toString()
            if (chunk.all { it.isDigit() }) {
                null
            } else {
                chunk.filter { it.isDigit() }
            }
        }
    }
}
