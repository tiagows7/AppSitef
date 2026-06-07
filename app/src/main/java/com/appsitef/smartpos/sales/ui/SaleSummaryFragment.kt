package com.appsitef.smartpos.sales.ui

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.appsitef.smartpos.R
import com.appsitef.smartpos.SalesActivity
import com.appsitef.smartpos.sales.SalesViewModel
import com.appsitef.smartpos.tef.CliSiTefConstants
import com.appsitef.smartpos.tef.TefPreferences
import com.appsitef.smartpos.ui.MoneyInputMask
import com.appsitef.smartpos.ui.NumericInputFilters
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

class SaleSummaryFragment : Fragment(R.layout.fragment_sale_summary) {

    private val salesViewModel: SalesViewModel by activityViewModels()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        TefPreferences.loadModuloIniIfExists(requireContext())

        val etOperadorVenda = view.findViewById<TextInputEditText>(R.id.etOperadorVenda)
        val etValorVenda = view.findViewById<TextInputEditText>(R.id.etValorVenda)
        val tilValorVenda = view.findViewById<TextInputLayout>(R.id.tilValorVenda)
        val btnFecharVenda = view.findViewById<MaterialButton>(R.id.btnFecharVenda)

        NumericInputFilters.applyDigitsOnly(etOperadorVenda, maxDigits = 3)
        MoneyInputMask.apply(etValorVenda)
        etValorVenda.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                tilValorVenda.error = null
            }
        }

        salesViewModel.saleAbastecimentos.observe(viewLifecycleOwner) { abastecimentos ->
            if (abastecimentos.isEmpty()) {
                MoneyInputMask.setValue(etValorVenda, "")
                return@observe
            }
            refreshSaleAmountFromAbastecimentos(etValorVenda)
        }

        salesViewModel.totalDesconto.observe(viewLifecycleOwner) {
            refreshSaleAmountFromAbastecimentos(etValorVenda)
        }

        salesViewModel.saleOperator.observe(viewLifecycleOwner) { value ->
            if (etOperadorVenda.text?.toString() != value) {
                etOperadorVenda.setText(value)
            }
        }

        salesViewModel.saleValue.observe(viewLifecycleOwner) { value ->
            if (MoneyInputMask.getFormattedText(etValorVenda) != value) {
                MoneyInputMask.setValue(etValorVenda, value)
            }
        }

        btnFecharVenda.setOnClickListener {
            val operadorVenda = etOperadorVenda.text.toString().trim()
            val valorVenda = resolveSaleAmount(etValorVenda)

            tilValorVenda.error = null
            if (valorVenda.isEmpty()) {
                tilValorVenda.error = getString(R.string.error_sale_value_required)
                return@setOnClickListener
            }

            if (TefPreferences.isOperadorObrigatorio(requireContext()) && operadorVenda.isEmpty()) {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.error_sale_operator_required),
                    Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }

            salesViewModel.setSaleData(operadorVenda, valorVenda)
            (activity as? SalesActivity)?.launchTefSale(
                amount = valorVenda,
                operator = operadorVenda,
                functionId = CliSiTefConstants.FUNCTION_MENU
            )
        }
    }

    /** Valor informado; se zero ou vazio, usa o total dos abastecimentos da venda. */
    private fun resolveSaleAmount(etValorVenda: TextInputEditText): String {
        val informed = MoneyInputMask.getNumericValue(etValorVenda)
        if (informed > 0.0) {
            return MoneyInputMask.getFormattedText(etValorVenda)
        }

        val operationTotal = salesViewModel.getSaleNetTotalAbatot()
        if (operationTotal > 0.0) {
            val formatted = MoneyInputMask.formatFromDouble(operationTotal)
            MoneyInputMask.setValue(etValorVenda, formatted)
            return formatted
        }

        return MoneyInputMask.getFormattedText(etValorVenda)
    }

    private fun refreshSaleAmountFromAbastecimentos(etValorVenda: TextInputEditText) {
        val total = salesViewModel.getSaleNetTotalAbatot()
        val hasDiscount = salesViewModel.totalDesconto.value.orDefault() > 0.0
        if (total <= 0.0) return

        if (MoneyInputMask.getFormattedText(etValorVenda).isBlank() || hasDiscount) {
            MoneyInputMask.setValue(etValorVenda, MoneyInputMask.formatFromDouble(total))
        }
    }

    private fun Double?.orDefault(): Double = this ?: 0.0
}
