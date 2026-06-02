package com.appsitef.smartpos.sales.ui

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.appsitef.smartpos.R
import com.appsitef.smartpos.SalesActivity
import com.appsitef.smartpos.sales.SalesViewModel
import com.appsitef.smartpos.sales.model.Abastecimento

class FuelingSearchFragment : Fragment(R.layout.fragment_fueling_search) {

    private val salesViewModel: SalesViewModel by activityViewModels()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val etBico = view.findViewById<TextInputEditText>(R.id.etBico)
        val btnPesquisar = view.findViewById<MaterialButton>(R.id.btnPesquisarBico)
        val btnAvancar = view.findViewById<MaterialButton>(R.id.btnAvancarAbaCliente)
        val rvAbastecimentos = view.findViewById<RecyclerView>(R.id.rvAbastecimentos)

        val adapter = AbastecimentoAdapter { item ->
            salesViewModel.setSelectedAbastecimento(item)
        }

        rvAbastecimentos.layoutManager = LinearLayoutManager(requireContext())
        rvAbastecimentos.adapter = adapter

        btnPesquisar.setOnClickListener {
            val bico = etBico.text.toString().trim()
            if (bico.isEmpty()) {
                Toast.makeText(requireContext(), "Informe o bico para pesquisar.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val abastecimentos = buildMockAbastecimentos(bico)
            adapter.updateItems(abastecimentos)
            salesViewModel.setSelectedAbastecimento(null)
            if (abastecimentos.isEmpty()) {
                Toast.makeText(requireContext(), "Nenhum abastecimento encontrado.", Toast.LENGTH_SHORT).show()
            }
        }

        btnAvancar.setOnClickListener {
            val selected = adapter.getSelectedItem()
            if (selected == null) {
                Toast.makeText(requireContext(), "Selecione um abastecimento para continuar.", Toast.LENGTH_SHORT).show()
            } else {
                salesViewModel.setSelectedAbastecimento(selected)
                (activity as? SalesActivity)?.goToTab(1)
            }
        }
    }

    private fun buildMockAbastecimentos(bicoFiltro: String): List<Abastecimento> {
        val mocks = listOf(
            Abastecimento(
                id = "ab-1001",
                ababmb = "01",
                abaqtd = 35.42,
                abavlruni = 5.79,
                abatot = 205.11,
                abanum = 1001,
                abaaba = 5001,
                abaopeaba = "101",
                abaopedes = "Carlos",
                abaprodes = "Gasolina Comum",
                abahoradia = "09:11:30",
                abapro = 1
            ),
            Abastecimento(
                id = "ab-1002",
                ababmb = "02",
                abaqtd = 22.80,
                abavlruni = 6.09,
                abatot = 138.85,
                abanum = 1002,
                abaaba = 5002,
                abaopeaba = "102",
                abaopedes = "Marina",
                abaprodes = "Gasolina Aditivada",
                abahoradia = "09:15:12",
                abapro = 2
            ),
            Abastecimento(
                id = "ab-1003",
                ababmb = "03",
                abaqtd = 48.30,
                abavlruni = 5.99,
                abatot = 289.32,
                abanum = 1003,
                abaaba = 5003,
                abaopeaba = "103",
                abaopedes = "Juliano",
                abaprodes = "Diesel S10",
                abahoradia = "09:19:47",
                abapro = 3
            )
        )

        return mocks.filter { it.ababmb == bicoFiltro }
    }
}
