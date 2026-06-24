package com.appsitef.smartpos.sales.ui

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.appsitef.smartpos.R
import com.appsitef.smartpos.SalesActivity
import com.appsitef.smartpos.sales.SaleAmountRules
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

    private var etValorVenda: TextInputEditText? = null
    private var tilValorVenda: TextInputLayout? = null
    private var tvTotalVenda: TextView? = null
    private var btnFecharVenda: MaterialButton? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        TefPreferences.loadModuloIniIfExists(requireContext())

        val etOperadorVenda = view.findViewById<TextInputEditText>(R.id.etOperadorVenda)
        etValorVenda = view.findViewById(R.id.etValorVenda)
        tilValorVenda = view.findViewById(R.id.tilValorVenda)
        tvTotalVenda = view.findViewById(R.id.tvTotalVenda)
        btnFecharVenda = view.findViewById(R.id.btnFecharVenda)

        NumericInputFilters.applyDigitsOnly(etOperadorVenda, maxDigits = 3)
        MoneyInputMask.apply(etValorVenda!!) {
            salesViewModel.getSaleAmountLimitCents()
        }
        etValorVenda!!.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                etValorVenda?.post { updateSaleAmountUiState() }
            }
        })
        etValorVenda!!.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                tilValorVenda?.error = null
            } else {
                clampSaleAmountToLimit()
                updateSaleAmountUiState()
            }
        }

        salesViewModel.saleAmountLimitCents.observe(viewLifecycleOwner) {
            updateTotalLabel()
            refreshSaleAmountFromAbastecimentos()
            updateSaleAmountUiState()
        }

        salesViewModel.saleAbastecimentos.observe(viewLifecycleOwner) { abastecimentos ->
            salesViewModel.refreshSaleAmountLimit()
            val field = etValorVenda ?: return@observe
            if (abastecimentos.isEmpty()) {
                MoneyInputMask.setValue(field, "")
            }
            updateSaleAmountUiState()
        }

        salesViewModel.totalDesconto.observe(viewLifecycleOwner) {
            salesViewModel.refreshSaleAmountLimit()
            refreshSaleAmountFromAbastecimentos()
            updateSaleAmountUiState()
        }

        salesViewModel.saleOperator.observe(viewLifecycleOwner) { value ->
            if (etOperadorVenda.text?.toString() != value) {
                etOperadorVenda.setText(value)
            }
        }

        salesViewModel.saleValue.observe(viewLifecycleOwner) { value ->
            val field = etValorVenda ?: return@observe
            if (MoneyInputMask.getFormattedText(field) != value) {
                MoneyInputMask.setValue(field, value)
                clampSaleAmountToLimit()
                updateSaleAmountUiState()
            }
        }

        salesViewModel.refreshSaleAmountLimit()
        updateTotalLabel()
        refreshSaleAmountFromAbastecimentos()
        updateSaleAmountUiState()

        btnFecharVenda!!.setOnClickListener {
            if (btnFecharVenda?.isEnabled != true) return@setOnClickListener

            val field = etValorVenda ?: return@setOnClickListener
            val operadorVenda = etOperadorVenda.text.toString().trim()
            val limitCents = salesViewModel.getSaleAmountLimitCents()

            if (limitCents <= 0L) {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.error_sale_fueling_total_missing),
                    Toast.LENGTH_LONG,
                ).show()
                return@setOnClickListener
            }

            clampSaleAmountToLimit()
            val fieldCents = MoneyInputMask.getCents(field)
            val effectiveCents = if (fieldCents > 0L) fieldCents else limitCents

            if (SaleAmountRules.exceedsLimitCents(effectiveCents, limitCents)) {
                showSaleAmountExceedsTotalError(limitCents)
                return@setOnClickListener
            }

            val valorVenda = if (fieldCents > 0L) {
                MoneyInputMask.getFormattedText(field)
            } else {
                MoneyInputMask.formatFromCents(limitCents).also {
                    MoneyInputMask.setCents(field, limitCents)
                }
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
            val launched = (activity as? SalesActivity)?.launchTefSale(
                amount = valorVenda,
                operator = operadorVenda,
                functionId = CliSiTefConstants.FUNCTION_MENU
            ) ?: false
            if (!launched) {
                showSaleAmountExceedsTotalError(limitCents)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        salesViewModel.refreshSaleAmountLimit()
        updateTotalLabel()
        refreshSaleAmountFromAbastecimentos()
        updateSaleAmountUiState()
    }

    override fun onDestroyView() {
        etValorVenda = null
        tilValorVenda = null
        tvTotalVenda = null
        btnFecharVenda = null
        super.onDestroyView()
    }

    private fun updateTotalLabel() {
        val limitCents = salesViewModel.getSaleAmountLimitCents()
        tvTotalVenda?.text = if (limitCents > 0L) {
            getString(
                R.string.sale_summary_total_label,
                MoneyInputMask.formatFromCents(limitCents),
            )
        } else {
            ""
        }
    }

    private fun clampSaleAmountToLimit() {
        val field = etValorVenda ?: return
        val limitCents = salesViewModel.getSaleAmountLimitCents()
        if (limitCents <= 0L) return

        val fieldCents = MoneyInputMask.getCents(field)
        if (fieldCents > limitCents) {
            MoneyInputMask.setCents(field, limitCents)
            tilValorVenda?.error = getString(
                R.string.error_sale_value_exceeds_total,
                MoneyInputMask.formatFromCents(limitCents),
            )
        }
    }

    private fun updateSaleAmountUiState() {
        val field = etValorVenda ?: return
        val layout = tilValorVenda ?: return
        val button = btnFecharVenda ?: return
        val limitCents = salesViewModel.getSaleAmountLimitCents()

        if (limitCents <= 0L) {
            layout.error = null
            button.isEnabled = false
            button.isClickable = false
            return
        }

        val fieldCents = MoneyInputMask.getCents(field)
        if (fieldCents <= 0L) {
            layout.error = null
            button.isEnabled = true
            button.isClickable = true
            return
        }

        if (SaleAmountRules.exceedsLimitCents(fieldCents, limitCents)) {
            layout.error = getString(
                R.string.error_sale_value_exceeds_total,
                MoneyInputMask.formatFromCents(limitCents),
            )
            button.isEnabled = false
            button.isClickable = false
            return
        }

        layout.error = null
        button.isEnabled = true
        button.isClickable = true
    }

    private fun showSaleAmountExceedsTotalError(limitCents: Long) {
        val message = getString(
            R.string.error_sale_value_exceeds_total,
            MoneyInputMask.formatFromCents(limitCents),
        )
        tilValorVenda?.error = message
        etValorVenda?.requestFocus()
        Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
    }

    private fun refreshSaleAmountFromAbastecimentos() {
        val field = etValorVenda ?: return
        val limitCents = salesViewModel.getSaleAmountLimitCents()
        val hasDiscount = salesViewModel.totalDesconto.value.orDefault() > 0.0
        if (limitCents <= 0L) return

        if (MoneyInputMask.getCents(field) <= 0L || hasDiscount) {
            MoneyInputMask.setCents(field, limitCents)
        }
    }

    private fun Double?.orDefault(): Double = this ?: 0.0
}
