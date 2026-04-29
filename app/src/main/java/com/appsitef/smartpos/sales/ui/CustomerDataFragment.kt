package com.appsitef.smartpos.sales.ui

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.appsitef.smartpos.R
import com.appsitef.smartpos.SalesActivity
import com.appsitef.smartpos.sales.SalesViewModel

class CustomerDataFragment : Fragment(R.layout.fragment_customer_data) {

    private val salesViewModel: SalesViewModel by activityViewModels()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val etCodigoCliente = view.findViewById<EditText>(R.id.etCodigoCliente)
        val btnPesquisarCliente = view.findViewById<Button>(R.id.btnPesquisarCliente)
        val etCpfCnpj = view.findViewById<EditText>(R.id.etCpfCnpj)
        val etVeiculo = view.findViewById<EditText>(R.id.etVeiculo)
        val etKm = view.findViewById<EditText>(R.id.etKm)
        val etCodigoAppPromo = view.findViewById<EditText>(R.id.etCodigoAppPromo)
        val btnPesquisarAppPromo = view.findViewById<Button>(R.id.btnPesquisarAppPromo)
        val btnAvancar = view.findViewById<Button>(R.id.btnAvancarAbaFechamento)

        btnPesquisarCliente.setOnClickListener {
            Toast.makeText(requireContext(), "Pesquisa de cliente será integrada ao servidor.", Toast.LENGTH_SHORT).show()
        }

        btnPesquisarAppPromo.setOnClickListener {
            Toast.makeText(requireContext(), "Pesquisa App Promo será integrada ao servidor.", Toast.LENGTH_SHORT).show()
        }

        btnAvancar.setOnClickListener {
            salesViewModel.setCustomerData(
                customerCode = etCodigoCliente.text.toString().trim(),
                cpfCnpj = etCpfCnpj.text.toString().trim(),
                vehicle = etVeiculo.text.toString().trim(),
                km = etKm.text.toString().trim(),
                promoCode = etCodigoAppPromo.text.toString().trim()
            )
            (activity as? SalesActivity)?.goToTab(2)
        }
    }
}
