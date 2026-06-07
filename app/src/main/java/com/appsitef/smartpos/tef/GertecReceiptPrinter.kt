package com.appsitef.smartpos.tef

import android.app.Activity
import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.util.Log
import br.com.gertec.gedi.GEDI
import br.com.gertec.gedi.enums.GEDI_PRNTR_e_Status
import br.com.gertec.gedi.enums.GEDI_e_Ret
import br.com.gertec.gedi.exceptions.GediException
import br.com.gertec.gedi.interfaces.IPRNTR
import br.com.gertec.gedi.structs.GEDI_PRNTR_st_StringConfig

/**
 * Impressão térmica GPOS — espelha [TGEDIPrinter.PrintString] / [printCupom2] do Delphi.
 *
 * Código nativo 138 no [IPRNTR.Output] = impressora ocupada ou sem papel (GEDI).
 */
object GertecReceiptPrinter {

    private const val TAG = "GertecReceiptPrinter"
    private const val TEXT_SIZE = 20f
    private const val LINE_SPACE = 10
    private const val FEED_BLANK_LINES = 150
    private const val MAX_LINE_CHARS = 42
    private const val LINES_PER_BATCH = 35
    private const val PRINTER_READY_MAX_ATTEMPTS = 12
    private const val PRINTER_READY_DELAY_MS = 500L
    private const val OUTPUT_MAX_ATTEMPTS = 4
    private const val OUTPUT_RETRY_DELAY_MS = 800L
    private const val PRE_PRINT_DELAY_MS = 400L

    @Synchronized
    fun printReceipt(context: Context, text: String) {
        if (text.isBlank()) {
            Log.w(TAG, "printReceipt ignorado — texto vazio")
            return
        }

        val host = if (context is Activity) context else context.applicationContext
        GertecPinpadBootstrap.ensureGediReady(host)
        val printer = GEDI.getInstance(host).prntr

        Thread.sleep(PRE_PRINT_DELAY_MS)
        waitForPrinterReady(printer)

        val formatted = TefReceiptFormatter.formatCupom(text)
        val lines = formatted
            .split("\n")
            .map { normalizePrintableLine(it) }

        Log.d(TAG, "printReceipt lines=${lines.size}")
        val batches = lines.chunked(LINES_PER_BATCH)
        batches.forEachIndexed { index, batch ->
            printBatch(printer, batch, feedAfter = index == batches.lastIndex)
        }
        Log.d(TAG, "printReceipt ok batches=${batches.size}")
    }

    private fun printBatch(printer: IPRNTR, lines: List<String>, feedAfter: Boolean) {
        var initialized = false
        try {
            printer.Init()
            initialized = true
            val config = buildStringConfig()
            lines.forEach { line ->
                printer.DrawStringExt(config, line)
            }
            if (feedAfter) {
                printer.DrawBlankLine(FEED_BLANK_LINES)
            }
            outputWithRetry(printer)
        } catch (error: Throwable) {
            Log.e(TAG, "printBatch", error)
            flushPrinter(printer, initialized)
            throw mapPrintError(error)
        }
    }

    private fun waitForPrinterReady(printer: IPRNTR) {
        repeat(PRINTER_READY_MAX_ATTEMPTS) { attempt ->
            try {
                when (printer.Status()) {
                    GEDI_PRNTR_e_Status.OK -> return
                    GEDI_PRNTR_e_Status.OUT_OF_PAPER -> {
                        Log.w(TAG, "printer status=OUT_OF_PAPER attempt=$attempt")
                    }
                    else -> {
                        Log.d(TAG, "printer status wait attempt=$attempt")
                    }
                }
            } catch (error: GediException) {
                Log.d(TAG, "printer status exception attempt=$attempt code=${error.errorCode}", error)
                if (!isRetryablePrinterError(error)) throw mapPrintError(error)
            } catch (error: Throwable) {
                Log.d(TAG, "printer status throwable attempt=$attempt", error)
            }
            Thread.sleep(PRINTER_READY_DELAY_MS)
        }
    }

    private fun outputWithRetry(printer: IPRNTR) {
        var lastError: Throwable? = null
        repeat(OUTPUT_MAX_ATTEMPTS) { attempt ->
            try {
                printer.Output()
                return
            } catch (error: Throwable) {
                lastError = error
                Log.w(TAG, "printer Output attempt=$attempt", error)
                if (!isRetryablePrinterError(error) || attempt == OUTPUT_MAX_ATTEMPTS - 1) {
                    throw mapPrintError(error)
                }
                Thread.sleep(OUTPUT_RETRY_DELAY_MS)
                try {
                    printer.Init()
                } catch (initError: Throwable) {
                    Log.w(TAG, "printer re-Init attempt=$attempt", initError)
                }
            }
        }
        throw mapPrintError(lastError ?: IllegalStateException("Falha ao imprimir."))
    }

    private fun isRetryablePrinterError(error: Throwable): Boolean {
        if (error !is GediException) {
            return error.message?.contains("138") == true ||
                error.message?.contains("Native method returned") == true
        }
        return when (error.errorCode) {
            GEDI_e_Ret.PRNTR_NOT_READY,
            GEDI_e_Ret.PRNTR_OUT_OF_PAPER,
            GEDI_e_Ret.PRNTR_ERROR -> true
            else -> error.message?.contains("138") == true
        }
    }

    fun mapPrintError(error: Throwable): Exception {
        if (error is GediException) {
            val mapped = when (error.errorCode) {
                GEDI_e_Ret.PRNTR_OUT_OF_PAPER ->
                    "Impressora sem papel. Verifique o rolo térmico."
                GEDI_e_Ret.PRNTR_NOT_READY ->
                    "Impressora ocupada. Aguarde e tente novamente."
                GEDI_e_Ret.PRNTR_OVERHEAT ->
                    "Impressora superaquecida. Aguarde e tente novamente."
                else -> null
            }
            if (mapped != null) return Exception(mapped, error)
        }
        if (error.message?.contains("138") == true) {
            return Exception(
                "Impressora ocupada ou sem papel (138). Verifique o papel e tente novamente.",
                error
            )
        }
        return if (error is Exception) error else Exception(
            error.message?.ifBlank { null } ?: "Erro ao imprimir comprovante.",
            error
        )
    }

    private fun normalizePrintableLine(raw: String): String {
        val adjusted = TefReceiptFormatter.adjustLine(raw).ifBlank { " " }
        if (adjusted.length <= MAX_LINE_CHARS) return adjusted
        return adjusted.take(MAX_LINE_CHARS)
    }

    private fun buildStringConfig(): GEDI_PRNTR_st_StringConfig {
        val paint = Paint().apply {
            textSize = TEXT_SIZE
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            textAlign = Paint.Align.CENTER
        }
        return GEDI_PRNTR_st_StringConfig(paint, LINE_SPACE, 0)
    }

    fun describePrinterStatus(status: GEDI_PRNTR_e_Status): String? = when (status) {
        GEDI_PRNTR_e_Status.OUT_OF_PAPER -> "Impressora sem papel. Verifique o rolo térmico."
        GEDI_PRNTR_e_Status.OVERHEAT -> "Impressora superaquecida. Aguarde e tente novamente."
        GEDI_PRNTR_e_Status.UNKNOWN_ERROR -> "Erro desconhecido na impressora."
        else -> null
    }

    private fun flushPrinter(printer: IPRNTR, initialized: Boolean) {
        if (!initialized) return
        try {
            printer.Output()
        } catch (error: Throwable) {
            Log.w(TAG, "flushPrinter", error)
        }
    }
}
