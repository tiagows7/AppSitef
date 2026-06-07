package com.appsitef.smartpos.sales

import com.appsitef.smartpos.sales.model.Abastecimento
import java.math.BigDecimal
import java.math.RoundingMode

object CupomDiscountCalculator {

    fun applyToAbastecimentos(
        items: List<Abastecimento>,
        produtoId: Int,
        tipoCupom: String,
        valorCupom: Double
    ): Pair<List<Abastecimento>, Boolean> {
        var cupomValido = false
        val updated = items.map { item ->
            if (item.abapro == produtoId) {
                cupomValido = true
                item.copy(abadesconto = calculateDiscount(item, tipoCupom, valorCupom))
            } else {
                item
            }
        }
        return updated to cupomValido
    }

    fun calculateDiscount(
        abastecimento: Abastecimento,
        tipoCupom: String,
        valorCupom: Double
    ): Double {
        val valorAbastecimento = abastecimento.abatot
        return when (tipoCupom.lowercase()) {
            "percentual" -> round2((valorAbastecimento * valorCupom) / 100.0)
            "valor" -> {
                val unitario = abastecimento.abavlruni - valorCupom
                val valorRecalculado = round2(abastecimento.abaqtd * unitario)
                round2(valorAbastecimento - valorRecalculado)
            }
            "valor_unitario" -> {
                val valorRecalculado = round2(abastecimento.abaqtd * valorCupom)
                round2(valorAbastecimento - valorRecalculado)
            }
            else -> 0.0
        }
    }

    private fun round2(value: Double): Double {
        return BigDecimal.valueOf(value)
            .setScale(2, RoundingMode.HALF_UP)
            .toDouble()
    }
}
