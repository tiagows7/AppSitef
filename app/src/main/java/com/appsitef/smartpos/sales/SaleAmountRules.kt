package com.appsitef.smartpos.sales

import com.appsitef.smartpos.sales.model.Abastecimento
import com.appsitef.smartpos.ui.MoneyInputMask

object SaleAmountRules {

    fun netTotal(items: List<Abastecimento>): Double {
        return items.sumOf { it.abatot - it.abadesconto }
    }

    fun netTotalCents(items: List<Abastecimento>): Long {
        return MoneyInputMask.toCents(netTotal(items))
    }

    fun isWithinLimitCents(amountCents: Long, limitCents: Long): Boolean {
        if (amountCents <= 0L || limitCents <= 0L) return false
        return amountCents <= limitCents
    }

    fun exceedsLimitCents(amountCents: Long, limitCents: Long): Boolean {
        if (amountCents <= 0L) return false
        if (limitCents <= 0L) return true
        return amountCents > limitCents
    }

    fun isWithinLimit(amount: Double, limit: Double): Boolean {
        return isWithinLimitCents(MoneyInputMask.toCents(amount), MoneyInputMask.toCents(limit))
    }

    fun exceedsLimit(amount: Double, limit: Double): Boolean {
        return exceedsLimitCents(MoneyInputMask.toCents(amount), MoneyInputMask.toCents(limit))
    }
}
