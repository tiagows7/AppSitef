package com.appsitef.smartpos.sales.ui

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import java.util.Locale
import com.appsitef.smartpos.R
import com.appsitef.smartpos.sales.SalesViewModel

class SaleSummaryFragment : Fragment(R.layout.fragment_sale_summary) {

    private val salesViewModel: SalesViewModel by activityViewModels()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val tvTotalizador = view.findViewById<TextView>(R.id.tvTotalizadorVenda)
        val etOperadorVenda = view.findViewById<EditText>(R.id.etOperadorVenda)
        val etValorVenda = view.findViewById<EditText>(R.id.etValorVenda)
        val btnFecharVenda = view.findViewById<Button>(R.id.btnFecharVenda)

        salesViewModel.selectedAbastecimento.observe(viewLifecycleOwner) { abastecimento ->
            val total = abastecimento?.valorTotal ?: 0.0
            tvTotalizador.text = "Totalizador: R$ ${String.format(Locale(\"pt\", \"BR\"), \"%.2f\", total)}"
            if (abastecimento != null && etValorVenda.text.isNullOrBlank()) {
                etValorVenda.setText(String.format(Locale("pt", "BR"), "%.2f", total))
            }
        }

        btnFecharVenda.setOnClickListener {
            val operadorVenda = etOperadorVenda.text.toString().trim()
            val valorVenda = etValorVenda.text.toString().trim()
            if (operadorVenda.isEmpty() || valorVenda.isEmpty()) {
                Toast.makeText(requireContext(), "Informe operador e valor da venda.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            salesViewModel.setSaleData(operadorVenda, valorVenda)
            Toast.makeText(requireContext(), "Venda pronta para integração com TEF.", Toast.LENGTH_LONG).show()
        }
    }
}
