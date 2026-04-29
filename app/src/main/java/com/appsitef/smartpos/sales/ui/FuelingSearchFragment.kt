package com.appsitef.smartpos.sales.ui

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.appsitef.smartpos.R
import com.appsitef.smartpos.SalesActivity
import com.appsitef.smartpos.sales.SalesViewModel
import com.appsitef.smartpos.sales.model.Abastecimento
import com.appsitef.smartpos.sales.network.AbastecimentoRepository
import com.appsitef.smartpos.sales.network.AbastecimentoServiceFactory
import kotlinx.coroutines.launch

class FuelingSearchFragment : Fragment(R.layout.fragment_fueling_search) {

    private val salesViewModel: SalesViewModel by activityViewModels()
    private val repository = AbastecimentoRepository(AbastecimentoServiceFactory.createApi())

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val etBico = view.findViewById<EditText>(R.id.etBico)
        val btnPesquisar = view.findViewById<Button>(R.id.btnPesquisarBico)
        val btnAvancar = view.findViewById<Button>(R.id.btnAvancarAbaCliente)
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

            lifecycleScope.launch {
                runCatching { repository.buscarPorBico(bico) }
                    .onSuccess { abastecimentos ->
                        adapter.updateItems(abastecimentos)
                        salesViewModel.setSelectedAbastecimento(null)
                        if (abastecimentos.isEmpty()) {
                            Toast.makeText(requireContext(), "Nenhum abastecimento encontrado.", Toast.LENGTH_SHORT).show()
                        }
                    }
                    .onFailure {
                        adapter.updateItems(emptyList())
                        salesViewModel.setSelectedAbastecimento(null)
                        Toast.makeText(
                            requireContext(),
                            "Erro ao consultar servidor REST. Verifique a URL e rede.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
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
}
