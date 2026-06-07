package com.appsitef.smartpos.ui

import android.content.Context
import android.view.View
import android.view.inputmethod.InputMethodManager
import androidx.fragment.app.Fragment

object KeyboardUtils {

    fun hide(context: Context, view: View?) {
        val target = view ?: return
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(target.windowToken, 0)
        target.clearFocus()
    }

    fun hide(fragment: Fragment) {
        val activity = fragment.activity ?: return
        val focused = activity.currentFocus ?: fragment.view
        hide(fragment.requireContext(), focused)
    }
}
