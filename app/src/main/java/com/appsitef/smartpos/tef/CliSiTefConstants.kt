package com.appsitef.smartpos.tef

object CliSiTefConstants {
    /** Processo iterativo em andamento — aguardar callbacks onData/onTransactionResult. */
    const val CONTINUA = 10000

    const val STAGE_TRANSACTION = 1
    const val STAGE_SETTLEMENT = 2

    /** Menu de funções / forma de pagamento no pinpad. */
    const val FUNCTION_MENU = 0

    const val FUNCTION_DEBIT = 2
    const val FUNCTION_CREDIT = 3

    const val CONFIRM_TRANSACTION = 1
    const val CANCEL_TRANSACTION = 0
}
