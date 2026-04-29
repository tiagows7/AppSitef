package com.appsitef.smartpos.sales

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.appsitef.smartpos.sales.model.Abastecimento

class SalesViewModel : ViewModel() {

    private val _selectedAbastecimento = MutableLiveData<Abastecimento?>(null)
    val selectedAbastecimento: LiveData<Abastecimento?> = _selectedAbastecimento

    private val _customerCode = MutableLiveData("")
    val customerCode: LiveData<String> = _customerCode

    private val _cpfCnpj = MutableLiveData("")
    val cpfCnpj: LiveData<String> = _cpfCnpj

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

    fun setSelectedAbastecimento(item: Abastecimento?) {
        _selectedAbastecimento.value = item
    }

    fun setCustomerData(
        customerCode: String,
        cpfCnpj: String,
        vehicle: String,
        km: String,
        promoCode: String
    ) {
        _customerCode.value = customerCode
        _cpfCnpj.value = cpfCnpj
        _vehicle.value = vehicle
        _km.value = km
        _promoCode.value = promoCode
    }

    fun setSaleData(operator: String, value: String) {
        _saleOperator.value = operator
        _saleValue.value = value
    }
}
