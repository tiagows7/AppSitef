package com.appsitef.smartpos.tef

object CliSiTefFieldIds {
    const val COMPROVANTE_CLIENTE = 121
    const val COMPROVANTE_ESTAB = 122
    const val DATA_HORA_SITEF = 105
    const val CODIGO_REDE = 131
    const val TIPO_CARTAO = 132
    const val NSU_SITEF = 133
    const val NSU_HOST = 134
    /** NSU da transação original a cancelar (Delphi: 620 / 1321). */
    const val NSU_TRANSACAO_ORIGINAL = 620
    const val NSU_TRANSACAO_ORIGINAL_ALT = 1321
    /** Valor da transação cancelada (Delphi TIPO_CAMPOS `146`). */
    const val VALOR_CANCELAMENTO = 146
    const val CODIGO_AUTORIZACAO = 135
    const val DATA_PREDATADO = 506
    const val PARCELAS = 505

    const val CMD_NUMERO_PARCELAS = 505
}
