package com.appsitef.smartpos.tef

enum class TefOperationMode {
    SALE,
    ADMIN_CANCELLATION,
    /** Função 112 — menu de reimpressão (último ou específico). */
    ADMIN_REPRINT,
    /** Função 110 — menu administrativo SiTef (sem servidor). */
    ADMIN_MENU,
    /** Função 130 — consulta/tratamento de transações pendentes (sem servidor). */
    ADMIN_PENDING,
}
