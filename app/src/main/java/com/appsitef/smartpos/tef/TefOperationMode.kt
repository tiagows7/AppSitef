package com.appsitef.smartpos.tef

enum class TefOperationMode {
    SALE,
    ADMIN_CANCELLATION,
    /** Função 114 — reimpressão de comprovante (sem servidor). */
    ADMIN_REPRINT,
    /** Função 110 — menu administrativo SiTef (sem servidor). */
    ADMIN_MENU,
}
