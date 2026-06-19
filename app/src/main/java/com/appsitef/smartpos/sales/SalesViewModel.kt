package com.appsitef.smartpos.sales

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.appsitef.smartpos.sales.model.Abastecimento
import com.appsitef.smartpos.sales.model.ClienteCnpjData
import com.appsitef.smartpos.sales.model.CupomPromo
import com.appsitef.smartpos.sales.model.CupomValidationResult
import com.appsitef.smartpos.tef.TefTransactionResult

class SalesViewModel : ViewModel() {

    private val _abastecimentos = MutableLiveData<List<Abastecimento>>(emptyList())
    val abastecimentos: LiveData<List<Abastecimento>> = _abastecimentos

    /** Seleção atual na lista (multi-select). */
    private val _selectedAbastecimentos = MutableLiveData<List<Abastecimento>>(emptyList())
    val selectedAbastecimentos: LiveData<List<Abastecimento>> = _selectedAbastecimentos

    /** Abastecimentos confirmados na venda (para cancelamento antes/depois do TEF). */
    private val _saleAbastecimentos = MutableLiveData<List<Abastecimento>>(emptyList())
    val saleAbastecimentos: LiveData<List<Abastecimento>> = _saleAbastecimentos

    private val _customerCode = MutableLiveData("")
    val customerCode: LiveData<String> = _customerCode

    private val _customerName = MutableLiveData("")
    val customerName: LiveData<String> = _customerName

    private val _cpfCnpj = MutableLiveData("")
    val cpfCnpj: LiveData<String> = _cpfCnpj

    /** Dados cadastrais da consulta CNPJ — mantidos até finalizar a venda TEF. */
    private val _clienteCnpjData = MutableLiveData<ClienteCnpjData?>(null)
    val clienteCnpjData: LiveData<ClienteCnpjData?> = _clienteCnpjData

    private val _vehicle = MutableLiveData("")
    val vehicle: LiveData<String> = _vehicle

    private val _km = MutableLiveData("")
    val km: LiveData<String> = _km

    private val _promoCode = MutableLiveData("")
    val promoCode: LiveData<String> = _promoCode

    private val _saleOperator = MutableLiveData("")
    val saleOperator: LiveData<String> = _saleOperator

    private val _saleValue = MutableLiveData("")
    val saleValue: LiveData<String> = _saleValue

    private val _totalDesconto = MutableLiveData(0.0)
    val totalDesconto: LiveData<Double> = _totalDesconto

    private val _cupomPromo = MutableLiveData<CupomPromo?>(null)
    val cupomPromo: LiveData<CupomPromo?> = _cupomPromo

    private val _lastTefTransactionResult = MutableLiveData<TefTransactionResult?>(null)
    val lastTefTransactionResult: LiveData<TefTransactionResult?> = _lastTefTransactionResult

    /** Código usado na última pesquisa REST com sucesso. */
    private var lastSearchedCustomerCode: String? = null

    /** Código App Promo validado com sucesso na API e aplicado na venda. */
    private var lastValidatedPromoCode: String? = null

    fun setAbastecimentos(items: List<Abastecimento>) {
        _abastecimentos.value = items
    }

    fun setSelectedAbastecimentos(items: List<Abastecimento>) {
        _selectedAbastecimentos.value = items
    }

    fun getTotalSelectedAbatot(): Double {
        return getNetTotal(_selectedAbastecimentos.value.orEmpty())
    }

    fun getSelectedCount(): Int = _selectedAbastecimentos.value.orEmpty().size

    fun commitSaleAbastecimentos() {
        val selected = _selectedAbastecimentos.value.orEmpty()
        _saleAbastecimentos.value = selected.toList()
    }

    fun getSaleTotalAbatot(): Double {
        return getGrossTotal(_saleAbastecimentos.value.orEmpty())
    }

    fun getSaleNetTotalAbatot(): Double {
        return getNetTotal(_saleAbastecimentos.value.orEmpty())
    }

    fun getToolbarAbastecimentos(): List<Abastecimento> {
        val selected = _selectedAbastecimentos.value.orEmpty()
        if (selected.isNotEmpty()) return selected
        return _saleAbastecimentos.value.orEmpty()
    }

    fun getToolbarGrossTotal(): Double = getGrossTotal(getToolbarAbastecimentos())

    fun getToolbarNetTotal(): Double = getNetTotal(getToolbarAbastecimentos())

    fun getToolbarDiscountTotal(): Double = getDiscountTotal(getToolbarAbastecimentos())

    fun getAbastecimentosParaLiberar(): List<Abastecimento> {
        val selected = _selectedAbastecimentos.value.orEmpty()
        val sale = _saleAbastecimentos.value.orEmpty()
        return (selected + sale).distinctBy { it.id }
    }

    fun cancelSale() {
        clearTransactionData()
    }

    /** Limpa abastecimentos, cliente e fechamento após venda TEF concluída. */
    fun clearTransactionData() {
        _abastecimentos.value = emptyList()
        _selectedAbastecimentos.value = emptyList()
        _saleAbastecimentos.value = emptyList()
        _customerCode.value = ""
        _customerName.value = ""
        _cpfCnpj.value = ""
        _clienteCnpjData.value = null
        _vehicle.value = ""
        _km.value = ""
        _promoCode.value = ""
        _saleOperator.value = ""
        _saleValue.value = ""
        _totalDesconto.value = 0.0
        _cupomPromo.value = null
        lastSearchedCustomerCode = null
        lastValidatedPromoCode = null
        _lastTefTransactionResult.value = null
    }

    fun setLastTefTransactionResult(result: TefTransactionResult?) {
        _lastTefTransactionResult.value = result
    }

    fun setCustomerData(
        customerCode: String,
        customerName: String,
        cpfCnpj: String,
        vehicle: String,
        km: String,
        promoCode: String
    ) {
        _customerCode.value = customerCode
        if (_clienteCnpjData.value == null) {
            _customerName.value = customerName
        }
        _cpfCnpj.value = cpfCnpj
        _vehicle.value = vehicle
        _km.value = km
        _promoCode.value = promoCode
    }

    fun applyClienteCnpjData(data: ClienteCnpjData) {
        _clienteCnpjData.value = data
        _cpfCnpj.value = data.cnpj
        _customerName.value = data.nome
    }

    fun buildSaleContext(): com.appsitef.smartpos.sales.model.SaleContext {
        val cnpjData = _clienteCnpjData.value
        return com.appsitef.smartpos.sales.model.SaleContext(
            customerCode = _customerCode.value.orEmpty(),
            cpfCnpj = _cpfCnpj.value.orEmpty(),
            nome = cnpjData?.nome ?: _customerName.value.orEmpty(),
            endereco = cnpjData?.endereco.orEmpty(),
            numeroEndereco = cnpjData?.numeroEndereco.orEmpty(),
            bairro = cnpjData?.bairro.orEmpty(),
            cidade = cnpjData?.cidade.orEmpty(),
            inscricaoEstadual = cnpjData?.inscricaoEstadual.orEmpty(),
            uf = cnpjData?.uf.orEmpty(),
            vehicle = _vehicle.value.orEmpty(),
            km = _km.value.orEmpty(),
        )
    }

    fun applyCustomerSearchResult(codigo: String, nome: String, cpfCnpj: String) {
        lastSearchedCustomerCode = codigo.trim()
        _customerCode.value = codigo.trim()
        _customerName.value = nome
        _cpfCnpj.value = cpfCnpj
        _clienteCnpjData.value = null
    }

    fun onCustomerCodeEdited(code: String) {
        _customerCode.value = code
        val searchedCode = lastSearchedCustomerCode
        if (code.isEmpty() || (searchedCode != null && code != searchedCode)) {
            clearCustomerSearchResult(keepCode = code.isNotEmpty())
        }
    }

    fun clearCustomerSearchResult(keepCode: Boolean = false) {
        lastSearchedCustomerCode = null
        if (!keepCode) {
            _customerCode.value = ""
        }
        _customerName.value = ""
        _cpfCnpj.value = ""
        _clienteCnpjData.value = null
    }

    fun setSaleData(operator: String, value: String) {
        _saleOperator.value = operator
        _saleValue.value = value
    }

    fun applyCupomPromo(codigo: String, validation: CupomValidationResult): Boolean {
        val baseItems = _saleAbastecimentos.value.orEmpty()
            .ifEmpty { _selectedAbastecimentos.value.orEmpty() }
        if (baseItems.isEmpty()) {
            error("Selecione abastecimentos antes de validar o cupom.")
        }

        val (updatedItems, cupomValido) = CupomDiscountCalculator.applyToAbastecimentos(
            items = baseItems,
            produtoId = validation.produto,
            tipoCupom = validation.tipoDesconto,
            valorCupom = validation.valorDesconto
        )
        if (!cupomValido) {
            clearCupomDiscounts()
            return false
        }

        val normalizedCode = normalizePromoCode(codigo)
        val cupom = CupomPromo(
            codigo = normalizedCode,
            tipoDesconto = validation.tipoDesconto,
            valorDesconto = validation.valorDesconto,
            produto = validation.produto
        )
        lastValidatedPromoCode = normalizedCode
        _promoCode.value = normalizedCode
        _cupomPromo.value = cupom
        replaceAbastecimentosWithDiscounts(updatedItems)
        _totalDesconto.value = getDiscountTotal(updatedItems)
        return true
    }

    fun clearCupomDiscounts() {
        lastValidatedPromoCode = null
        _cupomPromo.value = null
        _totalDesconto.value = 0.0
        replaceAbastecimentosWithDiscounts(clearDiscountsInList(_abastecimentos.value.orEmpty()))
    }

    fun onPromoCodeEdited(code: String) {
        val normalized = normalizePromoCode(code)
        _promoCode.value = normalized
        val validatedCode = lastValidatedPromoCode
        if (normalized.isEmpty() || (validatedCode != null && validatedCode != normalized)) {
            clearCupomDiscounts()
        }
    }

    fun clearClienteCnpjData() {
        _clienteCnpjData.value = null
        _customerName.value = ""
    }

    fun isPromoCodeValidatedForAdvance(fieldCode: String): Boolean {
        val normalized = normalizePromoCode(fieldCode)
        if (normalized.isEmpty()) return true
        return lastValidatedPromoCode != null &&
            lastValidatedPromoCode == normalized &&
            _cupomPromo.value != null
    }

    fun normalizePromoCode(code: String): String {
        return code.filter { it.isDigit() }
    }

    private fun replaceAbastecimentosWithDiscounts(updatedBase: List<Abastecimento>) {
        val updatedById = updatedBase.associateBy { it.id }

        _saleAbastecimentos.value = _saleAbastecimentos.value.orEmpty().map { item ->
            updatedById[item.id] ?: item.copy(abadesconto = 0.0)
        }
        _selectedAbastecimentos.value = _selectedAbastecimentos.value.orEmpty().map { item ->
            updatedById[item.id] ?: item.copy(abadesconto = 0.0)
        }
        _abastecimentos.value = _abastecimentos.value.orEmpty().map { item ->
            updatedById[item.id] ?: item.copy(abadesconto = 0.0)
        }
    }

    private fun clearDiscountsInList(items: List<Abastecimento>): List<Abastecimento> {
        return items.map { it.copy(abadesconto = 0.0) }
    }

    private fun getGrossTotal(items: List<Abastecimento>): Double {
        return items.sumOf { it.abatot }
    }

    private fun getDiscountTotal(items: List<Abastecimento>): Double {
        return items.sumOf { it.abadesconto }
    }

    private fun getNetTotal(items: List<Abastecimento>): Double {
        return getGrossTotal(items) - getDiscountTotal(items)
    }
}
