package com.appsitef.smartpos.tef

import android.content.Context

/**
 * Cupom/data/hora da última transação TEF — necessário para cancelar pendências
 * via [CliSiTef.getQttPendingTransactions] / finishTransaction(CANCEL).
 */
data class TefFiscalRef(
    val coupon: String,
    val date: String,
    val time: String,
)

object TefPendingFiscal {
    private const val PREF_NAME = "tef_pending_fiscal"
    private const val KEY_COUPON = "coupon"
    private const val KEY_DATE = "date"
    private const val KEY_TIME = "time"
    private const val KEY_SEQ = "coupon_seq"
    private const val KEY_HISTORY = "history"
    private const val MAX_HISTORY = 8

    fun save(context: Context, coupon: String, date: String, time: String) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_COUPON, coupon)
            .putString(KEY_DATE, date)
            .putString(KEY_TIME, time)
            .apply()
        rememberAttempt(context, coupon, date, time)
    }

    fun load(context: Context): TefFiscalRef? {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val coupon = prefs.getString(KEY_COUPON, null)?.trim().orEmpty()
        val date = prefs.getString(KEY_DATE, null)?.trim().orEmpty()
        val time = prefs.getString(KEY_TIME, null)?.trim().orEmpty()
        if (coupon.isEmpty() || date.isEmpty()) return null
        return TefFiscalRef(coupon, date, time.ifBlank { "000000" })
    }

    fun loadHistory(context: Context): List<TefFiscalRef> {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_HISTORY, "").orEmpty()
        if (raw.isBlank()) {
            return load(context)?.let { listOf(it) }.orEmpty()
        }
        return raw.split('|')
            .mapNotNull { entry ->
                val parts = entry.split(',')
                if (parts.size < 2) return@mapNotNull null
                val coupon = parts[0].trim()
                val date = parts[1].trim()
                val time = parts.getOrNull(2)?.trim().orEmpty().ifBlank { "000000" }
                if (coupon.isEmpty() || date.isEmpty()) null else TefFiscalRef(coupon, date, time)
            }
            .distinctBy { "${it.date}|${it.coupon}" }
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit()
            .remove(KEY_COUPON)
            .remove(KEY_DATE)
            .remove(KEY_TIME)
            .apply()
    }

    fun clearAll(context: Context) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit()
            .remove(KEY_COUPON)
            .remove(KEY_DATE)
            .remove(KEY_TIME)
            .remove(KEY_HISTORY)
            .apply()
    }

    /** Cupom fiscal único por tentativa de venda (evita reutilizar cupom com pendência). */
    fun nextCoupon(context: Context): String {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val next = prefs.getLong(KEY_SEQ, 0L) + 1L
        prefs.edit().putLong(KEY_SEQ, next).apply()
        return next.toString().padStart(6, '0')
    }

    private fun rememberAttempt(context: Context, coupon: String, date: String, time: String) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val entry = "$coupon,$date,${time.ifBlank { "000000" }}"
        val history = prefs.getString(KEY_HISTORY, "").orEmpty()
            .split('|')
            .filter { it.isNotBlank() }
            .toMutableList()
        history.remove(entry)
        history.add(0, entry)
        while (history.size > MAX_HISTORY) {
            history.removeAt(history.lastIndex)
        }
        prefs.edit().putString(KEY_HISTORY, history.joinToString("|")).apply()
    }
}
