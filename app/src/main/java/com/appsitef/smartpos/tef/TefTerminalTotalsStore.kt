package com.appsitef.smartpos.tef

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Totais acumulados do terminal TEF — espelha [que_total] e [que_totaloperador] do Delphi.
 *
 * - Vendas aprovadas somam em [totalSales] e por operador.
 * - Cancelamentos administrativos após aprovação somam em [totalCancelled].
 * - [resetSession] zera tudo ao finalizar o terminal no administrativo.
 */
object TefTerminalTotalsStore {

    private const val PREFS_NAME = "tef_terminal_totals"
    private const val KEY_SESSION_START = "session_start"
    private const val KEY_TOTAL_SALES = "total_sales"
    private const val KEY_TOTAL_CANCELLED = "total_cancelled"
    private const val KEY_OPERATORS_JSON = "operators_json"

    data class OperatorTotal(
        val operator: String,
        val amount: Double,
    )

    data class Snapshot(
        val sessionStart: String,
        val totalSales: Double,
        val totalCancelled: Double,
        val operators: List<OperatorTotal>,
    ) {
        val netTotal: Double get() = totalSales - totalCancelled
    }

    @Synchronized
    fun recordApprovedSale(context: Context, amount: Double, operator: String) {
        if (amount <= 0.0) return
        val prefs = prefs(context)
        val editor = prefs.edit()
        ensureSessionStart(editor, prefs)

        val newSales = prefs.getFloat(KEY_TOTAL_SALES, 0f).toDouble() + amount
        editor.putFloat(KEY_TOTAL_SALES, newSales.toFloat())

        val operators = loadOperators(prefs).toMutableList()
        val normalizedOperator = normalizeOperator(operator)
        val index = operators.indexOfFirst { it.operator == normalizedOperator }
        if (index >= 0) {
            val current = operators[index]
            operators[index] = current.copy(amount = current.amount + amount)
        } else {
            operators.add(OperatorTotal(normalizedOperator, amount))
        }
        editor.putString(KEY_OPERATORS_JSON, encodeOperators(operators))
        editor.apply()
    }

    @Synchronized
    fun recordCancelledAfterApproval(context: Context, amount: Double) {
        if (amount <= 0.0) return
        val prefs = prefs(context)
        val editor = prefs.edit()
        ensureSessionStart(editor, prefs)

        val newCancelled = prefs.getFloat(KEY_TOTAL_CANCELLED, 0f).toDouble() + amount
        editor.putFloat(KEY_TOTAL_CANCELLED, newCancelled.toFloat())
        editor.apply()
    }

    @Synchronized
    fun getSnapshot(context: Context): Snapshot {
        val prefs = prefs(context)
        return Snapshot(
            sessionStart = prefs.getString(KEY_SESSION_START, "").orEmpty(),
            totalSales = prefs.getFloat(KEY_TOTAL_SALES, 0f).toDouble(),
            totalCancelled = prefs.getFloat(KEY_TOTAL_CANCELLED, 0f).toDouble(),
            operators = loadOperators(prefs).sortedBy { it.operator },
        )
    }

    @Synchronized
    fun resetSession(context: Context) {
        val now = currentSessionTime()
        prefs(context).edit()
            .putString(KEY_SESSION_START, now)
            .putFloat(KEY_TOTAL_SALES, 0f)
            .putFloat(KEY_TOTAL_CANCELLED, 0f)
            .putString(KEY_OPERATORS_JSON, "[]")
            .apply()
    }

    fun formatMoney(value: Double): String =
        String.format(Locale("pt", "BR"), "R$ %,.2f", value)

    fun buildFinalizeReceipt(
        snapshot: Snapshot,
        terminalId: String = "",
        pdv: String = "",
    ): String {
        val closingTime = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale("pt", "BR")).format(Date())
        return buildString {
            appendLine("FINALIZACAO TERMINAL TEF")
            appendLine("--------------------------------")
            if (terminalId.isNotBlank()) appendLine("Terminal: $terminalId")
            if (pdv.isNotBlank()) appendLine("PDV: $pdv")
            if (snapshot.sessionStart.isNotBlank()) {
                appendLine("Abertura: ${snapshot.sessionStart}")
            }
            appendLine("Fechamento: $closingTime")
            appendLine("--------------------------------")
            appendLine("TOTAL VENDAS")
            appendLine(formatMoney(snapshot.totalSales))
            appendLine("TOTAL CANCELAMENTOS")
            appendLine(formatMoney(snapshot.totalCancelled))
            appendLine("TOTAL GERAL")
            appendLine(formatMoney(snapshot.netTotal))
            if (snapshot.operators.isNotEmpty()) {
                appendLine("--------------------------------")
                appendLine("POR OPERADOR")
                snapshot.operators.forEach { item ->
                    val label = item.operator.ifBlank { "---" }
                    appendLine("Op. $label ${formatMoney(item.amount)}")
                }
            }
            appendLine("--------------------------------")
            appendLine("FIM DO RELATORIO")
        }
    }

    fun normalizeOperator(operator: String): String {
        val digits = operator.filter { it.isDigit() }
        if (digits.isEmpty()) return ""
        return digits.padStart(3, '0').takeLast(3)
    }

    private fun ensureSessionStart(editor: SharedPreferences.Editor, prefs: SharedPreferences) {
        if (prefs.getString(KEY_SESSION_START, "").isNullOrBlank()) {
            editor.putString(KEY_SESSION_START, currentSessionTime())
        }
    }

    private fun currentSessionTime(): String =
        SimpleDateFormat("HH:mm:ss", Locale("pt", "BR")).format(Date())

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun loadOperators(prefs: SharedPreferences): List<OperatorTotal> {
        val raw = prefs.getString(KEY_OPERATORS_JSON, "[]").orEmpty()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    add(
                        OperatorTotal(
                            operator = item.optString("operator", ""),
                            amount = item.optDouble("amount", 0.0),
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun encodeOperators(operators: List<OperatorTotal>): String {
        val array = JSONArray()
        operators.forEach { item ->
            array.put(
                JSONObject()
                    .put("operator", item.operator)
                    .put("amount", item.amount)
            )
        }
        return array.toString()
    }
}
