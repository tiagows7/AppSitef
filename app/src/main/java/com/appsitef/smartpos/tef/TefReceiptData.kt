package com.appsitef.smartpos.tef

data class TefReceiptData(
    var viaCliente: String = "",
    var viaEstabelecimento: String = "",
) {
    fun hasMerchantCopy(): Boolean = viaEstabelecimento.isNotBlank()
    fun hasCustomerCopy(): Boolean = viaCliente.isNotBlank()
}
