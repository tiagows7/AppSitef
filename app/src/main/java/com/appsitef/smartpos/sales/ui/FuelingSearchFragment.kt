package com.appsitef.smartpos.sales.ui

import android.os.Bundle
import android.view.View
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
import com.appsitef.smartpos.sales.network.AbastecimentoRemoteRepository
import com.appsitef.smartpos.tef.TefPreferences
import com.appsitef.smartpos.ui.KeyboardUtils
import com.appsitef.smartpos.ui.NumericInputFilters
import com.appsitef.smartpos.ui.WaitDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class FuelingSearchFragment : Fragment(R.layout.fragment_fueling_search) {

    private val salesViewModel: SalesViewModel by activityViewModels()
    private val repository by lazy { AbastecimentoRemoteRepository(requireContext()) }
    private lateinit var adapter: AbastecimentoAdapter

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        TefPreferences.loadModuloIniIfExists(requireContext())

        val etBico = view.findViewById<TextInputEditText>(R.id.etBico)
        NumericInputFilters.applyDigitsOnly(etBico, maxDigits = 2)
        val btnPesquisar = view.findViewById<MaterialButton>(R.id.btnPesquisarBico)
        val btnAvancar = view.findViewById<MaterialButton>(R.id.btnAvancarAbaCliente)
        val rvAbastecimentos = view.findViewById<RecyclerView>(R.id.rvAbastecimentos)

        adapter = AbastecimentoAdapter(
            onSelectionChanged = { selected ->
                salesViewModel.setSelectedAbastecimentos(selected)
            },
            onItemToggled = { item, selected ->
                if (selected) {
                    registrarAbastecimentoSelecionado(item)
                } else {
                    liberarAbastecimentoSelecionado(item)
                }
            }
        )

        rvAbastecimentos.layoutManager = LinearLayoutManager(requireContext())
        rvAbastecimentos.adapter = adapter

        salesViewModel.abastecimentos.observe(viewLifecycleOwner) { items ->
            adapter.updateItems(items, keepSelection = true)
            syncAdapterSelectionFromViewModel()
            if (items.isEmpty()) {
                etBico.text?.clear()
            }
        }

        salesViewModel.selectedAbastecimentos.observe(viewLifecycleOwner) {
            syncAdapterSelectionFromViewModel()
        }

        salesViewModel.saleAbastecimentos.observe(viewLifecycleOwner) {
            syncAdapterSelectionFromViewModel()
        }

        btnPesquisar.setOnClickListener {
            KeyboardUtils.hide(this)
            val bico = etBico.text.toString().trim()

            lifecycleScope.launch {
                val waitDialog = WaitDialog.show(
                    requireContext(),
                    R.string.wait_message_searching_fueling
                )
                btnPesquisar.isEnabled = false
                try {
                    val abastecimentos = withContext(Dispatchers.IO) {
                        repository.buscarAbastecimentos(bico)
                    }
                    salesViewModel.setSelectedAbastecimentos(emptyList())
                    salesViewModel.setAbastecimentos(abastecimentos)
                    if (abastecimentos.isEmpty()) {
                        Toast.makeText(
                            requireContext(),
                            "Nenhum abastecimento encontrado.",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                } catch (error: Exception) {
                    salesViewModel.setSelectedAbastecimentos(emptyList())
                    salesViewModel.setAbastecimentos(emptyList())
                    Toast.makeText(
                        requireContext(),
                        "Erro ao consultar abastecimentos: ${error.message}",
                        Toast.LENGTH_LONG
                    ).show()
                } finally {
                    waitDialog.dismiss()
                    btnPesquisar.isEnabled = true
                }
            }
        }

        btnAvancar.setOnClickListener {
            val selected = adapter.getSelectedItems()
            if (selected.isEmpty()) {
                Toast.makeText(
                    requireContext(),
                    "Selecione ao menos um abastecimento para continuar.",
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                salesViewModel.setSelectedAbastecimentos(selected)
                salesViewModel.commitSaleAbastecimentos()
                (activity as? SalesActivity)?.goToTab(1)
            }
        }
    }

    private fun syncAdapterSelectionFromViewModel() {
        if (!::adapter.isInitialized) return

        val selected = salesViewModel.selectedAbastecimentos.value.orEmpty()
        val sale = salesViewModel.saleAbastecimentos.value.orEmpty()
        val ids = when {
            selected.isNotEmpty() -> selected.map { it.id }
            sale.isNotEmpty() -> sale.map { it.id }
            else -> emptyList()
        }
        adapter.setSelectedIds(ids)
    }

    private fun registrarAbastecimentoSelecionado(item: Abastecimento) {
        executarPostAbastecimento(
            messageRes = R.string.wait_message_registering_fueling,
            onSuccess = { adapter.confirmSelection(item) },
            errorPrefix = "Erro ao registrar abastecimento",
        ) {
            repository.registrarAbastecimento(item)
        }
    }

    private fun liberarAbastecimentoSelecionado(item: Abastecimento) {
        executarPostAbastecimento(
            messageRes = R.string.wait_message_releasing_fueling,
            onSuccess = { adapter.confirmDeselection(item) },
            errorPrefix = "Erro ao liberar abastecimento",
        ) {
            repository.liberarAbastecimento(item)
        }
    }

    private fun executarPostAbastecimento(
        messageRes: Int,
        onSuccess: () -> Unit,
        errorPrefix: String,
        action: () -> Unit,
    ) {
        lifecycleScope.launch {
            adapter.setInteractionLocked(true)
            val waitDialog = WaitDialog.show(requireContext(), messageRes)
            try {
                withContext(Dispatchers.IO) { action() }
                onSuccess()
            } catch (error: Exception) {
                Toast.makeText(
                    requireContext(),
                    "$errorPrefix: ${error.message}",
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                waitDialog.dismiss()
                adapter.setInteractionLocked(false)
            }
        }
    }
}
