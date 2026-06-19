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

    /** Cancelamento administrativo — [FORMAPAGAMENTO_CANCELAMENTO] Delphi. */
    const val FUNCTION_CANCELLATION = 200

    /** Reimpressão administrativa — [FORMAPAGAMENTO_REIMPRESSAO] Delphi. */
    const val FUNCTION_REPRINT = 114

    /** Menu administrativo SiTef — modalidade 110 no Delphi GPOS720. */
    const val FUNCTION_ADMINISTRATIVE = 110

    const val CONFIRM_TRANSACTION = 1
    const val CANCEL_TRANSACTION = 0
}
