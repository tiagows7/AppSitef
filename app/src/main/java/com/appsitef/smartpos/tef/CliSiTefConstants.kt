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

    /**
     * Menu de reimpressão — último comprovante ou específico.
     * (112 = menu; 113 = específico; 114 = só o último)
     */
    const val FUNCTION_REPRINT = 112

    /** Reimpressão direta do último comprovante. */
    const val FUNCTION_REPRINT_LAST = 114

    /** Menu administrativo SiTef — modalidade 110 no Delphi GPOS720. */
    const val FUNCTION_ADMINISTRATIVE = 110

    /**
     * Consulta/tratamento de transações pendentes no terminal (queda de energia).
     * startTransaction(130) → continua até resultado != 10000 → finishTransaction.
     */
    const val FUNCTION_PENDING = 130

    const val CONFIRM_TRANSACTION = 1
    const val CANCEL_TRANSACTION = 0
}
