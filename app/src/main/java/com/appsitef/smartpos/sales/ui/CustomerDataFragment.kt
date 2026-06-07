package com.appsitef.smartpos.sales.ui

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.appsitef.smartpos.R
import com.appsitef.smartpos.SalesActivity
import com.appsitef.smartpos.sales.SalesViewModel
import com.appsitef.smartpos.sales.network.ClienteRemoteRepository
import com.appsitef.smartpos.sales.network.CupomRemoteRepository
import com.appsitef.smartpos.tef.TefPreferences
import com.appsitef.smartpos.ui.DocumentInputMask
import com.appsitef.smartpos.ui.DocumentValidator
import com.appsitef.smartpos.ui.KeyboardUtils
import com.appsitef.smartpos.ui.NumericInputFilters
import com.appsitef.smartpos.ui.WaitDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CustomerDataFragment : Fragment(R.layout.fragment_customer_data) {

    private val salesViewModel: SalesViewModel by activityViewModels()
    private val clienteRepository by lazy { ClienteRemoteRepository(requireContext()) }
    private val cupomRepository by lazy { CupomRemoteRepository() }

    private var etCodigoCliente: TextInputEditText? = null
    private var tvNomeCliente: TextView? = null
    private var tilCpfCnpj: TextInputLayout? = null
    private var etCpfCnpj: TextInputEditText? = null

    private var loadedSearchCode: String? = null
    private var validatedPromoCode: String? = null
    private var suppressCustomerBinding = false
    private var syncingCustomerCode = false
    private var syncingPromoCode = false
    private var etCodigoAppPromo: TextInputEditText? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        etCodigoCliente = view.findViewById(R.id.etCodigoCliente)
        tvNomeCliente = view.findViewById(R.id.tvNomeCliente)
        val btnPesquisarCliente = view.findViewById<MaterialButton>(R.id.btnPesquisarCliente)
        tilCpfCnpj = view.findViewById(R.id.tilCpfCnpj)
        etCpfCnpj = view.findViewById(R.id.etCpfCnpj)
        val etVeiculo = view.findViewById<TextInputEditText>(R.id.etVeiculo)
        val etKm = view.findViewById<TextInputEditText>(R.id.etKm)
        etCodigoAppPromo = view.findViewById(R.id.etCodigoAppPromo)
        val etCodigoAppPromoField = etCodigoAppPromo!!
        val btnPesquisarAppPromo = view.findViewById<MaterialButton>(R.id.btnPesquisarAppPromo)
        val btnAvancar = view.findViewById<MaterialButton>(R.id.btnAvancarAbaFechamento)

        DocumentInputMask.apply(etCpfCnpj!!)
        NumericInputFilters.applyDigitsOnly(etCodigoCliente!!, maxDigits = 8)
        NumericInputFilters.applyDigitsOnly(etKm, maxDigits = 7)
        NumericInputFilters.applyDigitsOnly(etCodigoAppPromoField, maxDigits = 9)

        etCodigoAppPromoField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (syncingPromoCode) return
                etCodigoAppPromo?.post { handlePromoCodeChanged() }
            }
        })

        etCodigoCliente!!.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (syncingCustomerCode || suppressCustomerBinding) return
                etCodigoCliente?.post { handleCustomerCodeChanged() }
            }
        })

        etCodigoCliente!!.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                handleCustomerCodeChanged()
            }
        }

        btnPesquisarCliente.setOnClickListener {
            KeyboardUtils.hide(this)
            val codigo = currentCustomerCode()
            if (codigo.isEmpty()) {
                resetSearchCustomerData()
                Toast.makeText(
                    requireContext(),
                    getString(R.string.error_customer_code_required),
                    Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }

            lifecycleScope.launch {
                btnPesquisarCliente.isEnabled = false
                val waitDialog = WaitDialog.show(
                    requireContext(),
                    R.string.wait_message_searching_customer
                )
                try {
                    val cliente = withContext(Dispatchers.IO) {
                        clienteRepository.buscarCliente(codigo)
                    }
                    if (currentCustomerCode() != codigo) return@launch

                    loadedSearchCode = codigo
                    suppressCustomerBinding = true
                    salesViewModel.applyCustomerSearchResult(
                        codigo = codigo,
                        nome = cliente.nome,
                        cpfCnpj = cliente.cpfCnpj
                    )
                    tvNomeCliente?.text = cliente.nome
                    tvNomeCliente?.isVisible = cliente.nome.isNotBlank()
                    etCpfCnpj?.let { DocumentInputMask.setDigits(it, cliente.cpfCnpj) }
                } catch (error: Exception) {
                    tvNomeCliente?.isVisible = false
                    Toast.makeText(
                        requireContext(),
                        "Erro ao consultar cliente: ${error.message}",
                        Toast.LENGTH_LONG
                    ).show()
                } finally {
                    suppressCustomerBinding = false
                    waitDialog.dismiss()
                    btnPesquisarCliente.isEnabled = true
                }
            }
        }

        btnPesquisarAppPromo.setOnClickListener {
            KeyboardUtils.hide(this)
            val codigoPromo = currentPromoCode()
            if (codigoPromo.isEmpty()) {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.error_promo_code_required),
                    Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }

            val codigoInt = codigoPromo.toIntOrNull()
            if (codigoInt == null) {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.error_promo_code_required),
                    Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }

            TefPreferences.loadModuloIniIfExists(requireContext())
            val cnpjPosto = TefPreferences.getPostoCnpj(requireContext())
            if (cnpjPosto.isBlank()) {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.error_promo_cnpj_not_configured),
                    Toast.LENGTH_LONG
                ).show()
                return@setOnClickListener
            }

            lifecycleScope.launch {
                btnPesquisarAppPromo.isEnabled = false
                val waitDialog = WaitDialog.show(
                    requireContext(),
                    R.string.wait_message_searching_promo
                )
                try {
                    val validation = withContext(Dispatchers.IO) {
                        cupomRepository.validarCupom(codigoInt, cnpjPosto)
                    }
                    if (!validation.sucesso) {
                        invalidatePromoValidation()
                        Toast.makeText(
                            requireContext(),
                            validation.mensagem,
                            Toast.LENGTH_LONG
                        ).show()
                        return@launch
                    }

                    if (currentPromoCode() != codigoPromo) return@launch

                    val applied = salesViewModel.applyCupomPromo(codigoPromo, validation)
                    if (applied) {
                        validatedPromoCode = codigoPromo
                        Toast.makeText(requireContext(), validation.mensagem, Toast.LENGTH_LONG).show()
                    } else {
                        invalidatePromoValidation()
                        Toast.makeText(
                            requireContext(),
                            "PRODUTO DO CUPOM DIFERENTE DO PRODUTO INFORMADO",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                } catch (error: Exception) {
                    invalidatePromoValidation()
                    Toast.makeText(
                        requireContext(),
                        error.message ?: "Erro ao validar cupom.",
                        Toast.LENGTH_LONG
                    ).show()
                } finally {
                    waitDialog.dismiss()
                    btnPesquisarAppPromo.isEnabled = true
                }
            }
        }

        btnAvancar.setOnClickListener {
            KeyboardUtils.hide(this)
            val cpfCnpjField = etCpfCnpj ?: return@setOnClickListener
            val cpfCnpj = DocumentInputMask.getDigits(cpfCnpjField)
            val promoCode = currentPromoCode()
            tilCpfCnpj?.error = null
            if (cpfCnpj.isNotEmpty() && !DocumentValidator.isValidCpfOrCnpj(cpfCnpj)) {
                tilCpfCnpj?.error = getString(R.string.error_invalid_cpf_cnpj)
                return@setOnClickListener
            }
            if (promoCode.isNotEmpty() && !isPromoValidated(promoCode)) {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.error_promo_code_not_searched),
                    Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }

            salesViewModel.setCustomerData(
                customerCode = currentCustomerCode(),
                customerName = tvNomeCliente?.text?.toString()?.trim().orEmpty(),
                cpfCnpj = cpfCnpj,
                vehicle = etVeiculo.text.toString().trim(),
                km = etKm.text.toString().trim(),
                promoCode = promoCode
            )
            (activity as? SalesActivity)?.goToTab(2)
        }

        etCpfCnpj?.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                tilCpfCnpj?.error = null
            }
        }

        salesViewModel.customerCode.observe(viewLifecycleOwner) { code ->
            if (suppressCustomerBinding) return@observe
            val field = etCodigoCliente ?: return@observe
            if (field.text.toString() != code) {
                syncingCustomerCode = true
                field.setText(code)
                syncingCustomerCode = false
            }
        }
        salesViewModel.customerName.observe(viewLifecycleOwner) { nome ->
            if (suppressCustomerBinding) return@observe
            tvNomeCliente?.text = nome
            tvNomeCliente?.isVisible = nome.isNotBlank()
        }
        salesViewModel.cpfCnpj.observe(viewLifecycleOwner) { cpfCnpj ->
            if (suppressCustomerBinding) return@observe
            val field = etCpfCnpj ?: return@observe
            if (DocumentInputMask.getDigits(field) != cpfCnpj) {
                DocumentInputMask.setDigits(field, cpfCnpj)
            }
        }
        salesViewModel.vehicle.observe(viewLifecycleOwner) { etVeiculo.setText(it) }
        salesViewModel.km.observe(viewLifecycleOwner) { etKm.setText(it) }
        salesViewModel.promoCode.observe(viewLifecycleOwner) { code ->
            val field = etCodigoAppPromo ?: return@observe
            if (field.text.toString() != code) {
                syncingPromoCode = true
                field.setText(code)
                syncingPromoCode = false
            }
        }
        salesViewModel.cupomPromo.observe(viewLifecycleOwner) { cupom ->
            validatedPromoCode = cupom?.codigo
        }
    }

    override fun onDestroyView() {
        etCodigoCliente = null
        tvNomeCliente = null
        tilCpfCnpj = null
        etCpfCnpj = null
        etCodigoAppPromo = null
        super.onDestroyView()
    }

    private fun currentCustomerCode(): String {
        return etCodigoCliente?.text?.toString()?.filter { it.isDigit() }.orEmpty()
    }

    private fun currentPromoCode(): String {
        return salesViewModel.normalizePromoCode(
            etCodigoAppPromo?.text?.toString().orEmpty()
        )
    }

    private fun isPromoValidated(promoCode: String): Boolean {
        return validatedPromoCode == promoCode &&
            salesViewModel.isPromoCodeValidatedForAdvance(promoCode)
    }

    private fun handlePromoCodeChanged() {
        if (!isAdded || syncingPromoCode) return

        val code = currentPromoCode()
        if (code.isEmpty() || code != validatedPromoCode) {
            validatedPromoCode = null
        }
        salesViewModel.onPromoCodeEdited(code)
    }

    private fun invalidatePromoValidation() {
        validatedPromoCode = null
        salesViewModel.clearCupomDiscounts()
    }

    private fun handleCustomerCodeChanged() {
        if (!isAdded || suppressCustomerBinding || syncingCustomerCode) return

        val code = currentCustomerCode()
        when {
            code.isEmpty() -> resetSearchCustomerData()
            loadedSearchCode != null && code != loadedSearchCode -> resetSearchCustomerData(keepCode = true)
            else -> salesViewModel.onCustomerCodeEdited(code)
        }
    }

    private fun resetSearchCustomerData(keepCode: Boolean = false) {
        if (!isAdded) return

        suppressCustomerBinding = true
        loadedSearchCode = null
        salesViewModel.clearCustomerSearchResult(keepCode = keepCode)

        tvNomeCliente?.text = ""
        tvNomeCliente?.isVisible = false
        tilCpfCnpj?.error = null
        etCpfCnpj?.let { DocumentInputMask.clear(it) }

        if (!keepCode) {
            syncingCustomerCode = true
            etCodigoCliente?.setText("")
            syncingCustomerCode = false
        }

        view?.post { suppressCustomerBinding = false }
    }
}
