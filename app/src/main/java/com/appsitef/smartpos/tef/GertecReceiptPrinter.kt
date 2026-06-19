package com.appsitef.smartpos.tef

import android.app.Activity
import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.util.Log
import br.com.gertec.gedi.GEDI
import br.com.gertec.gedi.enums.GEDI_PRNTR_e_BarCodeType
import br.com.gertec.gedi.enums.GEDI_PRNTR_e_Status
import br.com.gertec.gedi.enums.GEDI_e_Ret
import br.com.gertec.gedi.exceptions.GediException
import br.com.gertec.gedi.interfaces.IPRNTR
import br.com.gertec.gedi.structs.GEDI_PRNTR_st_BarCodeConfig
import br.com.gertec.gedi.structs.GEDI_PRNTR_st_StringConfig

/**
 * Impressão térmica GPOS — espelha [TGEDIPrinter.PrintString] / [printCupom2] do Delphi.
 *
 * Código nativo 138 no [IPRNTR.Output] = impressora ocupada ou sem papel (GEDI).
 */
object GertecReceiptPrinter {

    private const val TAG = "GertecReceiptPrinter"
    /** Cupom fiscal (NF-e/NFC-e) e comprovantes TEF. */
    private const val FISCAL_TEXT_SIZE = 16f
    private const val FISCAL_LINE_SPACE = 8
    private const val FISCAL_MAX_LINE_CHARS = 50
    private const val TEF_RECEIPT_FEED_BLANK_LINES = 145
    private const val NFE_CUPOM_FEED_BLANK_LINES = 142
    private const val LINES_PER_BATCH = 35
    private const val PRINTER_READY_MAX_ATTEMPTS = 12
    private const val PRINTER_READY_DELAY_MS = 500L
    private const val OUTPUT_MAX_ATTEMPTS = 4
    private const val OUTPUT_RETRY_DELAY_MS = 800L
    private const val PRE_PRINT_DELAY_MS = 400L
    private const val QR_CODE_HEIGHT = 200
    private const val QR_CODE_WIDTH = 280
    private const val QR_CODE_WHITE_SPACE = 2
    private const val FISCAL_QR_CODE_HEIGHT = 170
    private const val FISCAL_QR_CODE_WIDTH = 240
    private const val CODE_128_HEIGHT = 80
    private const val CODE_128_WIDTH = 380
    private const val CODE_128_WHITE_SPACE = 2

    @Synchronized
    fun printNfeCupom(context: Context, receipt: NfeThermalReceipt) {
        if (receipt.text.isBlank() &&
            receipt.textAfterBarCode.isNullOrBlank() &&
            receipt.qrCode.isNullOrBlank() &&
            receipt.accessKeyBarCode.isNullOrBlank()
        ) {
            Log.w(TAG, "printNfeCupom ignorado — cupom vazio")
            return
        }

        val host = if (context is Activity) context else context.applicationContext
        GertecPinpadBootstrap.ensureGediReady(host)
        val printer = GEDI.getInstance(host).prntr

        Thread.sleep(PRE_PRINT_DELAY_MS)
        waitForPrinterReady(printer)

        val isNfce = !receipt.qrCode.isNullOrBlank()

        var initialized = false
        try {
            printer.Init()
            initialized = true
            val config = buildStringConfig(FISCAL_TEXT_SIZE, FISCAL_LINE_SPACE)
            var lineCount = drawCupomText(printer, config, receipt.text, FISCAL_MAX_LINE_CHARS)
            receipt.accessKeyBarCode?.let { payload ->
                Log.d(TAG, "printNfeCupom CODE_128 chave len=${payload.length}")
                printer.DrawBarCode(buildCode128Config(), payload)
            }
            receipt.textAfterBarCode?.let { after ->
                lineCount += drawCupomText(printer, config, after, FISCAL_MAX_LINE_CHARS)
            }
            receipt.qrCode?.let { payload ->
                Log.d(TAG, "printNfeCupom QR len=${payload.length} nfce=$isNfce")
                printer.DrawBarCode(buildQrCodeConfig(compact = true), payload)
            }
            printer.DrawBlankLine(NFE_CUPOM_FEED_BLANK_LINES)
            outputWithRetry(printer)
            Log.d(
                TAG,
                "printNfeCupom ok lines=$lineCount " +
                    "barcode=${!receipt.accessKeyBarCode.isNullOrBlank()} " +
                    "qr=${!receipt.qrCode.isNullOrBlank()}",
            )
        } catch (error: Throwable) {
            Log.e(TAG, "printNfeCupom", error)
            flushPrinter(printer, initialized)
            throw mapPrintError(error)
        }
    }

    private fun drawCupomText(
        printer: IPRNTR,
        config: GEDI_PRNTR_st_StringConfig,
        text: String,
        maxLineChars: Int = FISCAL_MAX_LINE_CHARS,
    ): Int {
        if (text.isBlank()) return 0
        val lines = TefReceiptFormatter.formatCupom(text)
            .split("\n")
            .flatMap { expandPrintableLines(it, maxLineChars) }
        lines.forEach { line ->
            printer.DrawStringExt(config, line)
        }
        return lines.size
    }

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
            .flatMap { expandPrintableLines(it, FISCAL_MAX_LINE_CHARS) }

        Log.d(TAG, "printReceipt lines=${lines.size}")
        val config = buildStringConfig(FISCAL_TEXT_SIZE, FISCAL_LINE_SPACE)
        val batches = lines.chunked(LINES_PER_BATCH)
        batches.forEachIndexed { index, batch ->
            printBatch(printer, config, batch, feedAfter = index == batches.lastIndex)
        }
        Log.d(TAG, "printReceipt ok batches=${batches.size}")
    }

    private fun printBatch(
        printer: IPRNTR,
        config: GEDI_PRNTR_st_StringConfig,
        lines: List<String>,
        feedAfter: Boolean,
    ) {
        var initialized = false
        try {
            printer.Init()
            initialized = true
            lines.forEach { line ->
                printer.DrawStringExt(config, line)
            }
            if (feedAfter) {
                printer.DrawBlankLine(TEF_RECEIPT_FEED_BLANK_LINES)
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

    private fun expandPrintableLines(raw: String, maxLineChars: Int = FISCAL_MAX_LINE_CHARS): List<String> {
        val adjusted = TefReceiptFormatter.adjustLine(raw).ifBlank { " " }
        if (adjusted.length <= maxLineChars) return listOf(adjusted)

        val lines = mutableListOf<String>()
        var remaining = adjusted
        while (remaining.isNotEmpty()) {
            if (remaining.length <= maxLineChars) {
                lines += remaining
                break
            }
            var breakAt = remaining.lastIndexOf(',', maxLineChars.coerceAtMost(remaining.length - 1))
            if (breakAt <= 0) breakAt = remaining.lastIndexOf(' ', maxLineChars)
            if (breakAt <= 0) breakAt = maxLineChars
            lines += remaining.substring(0, breakAt).trimEnd(',', ' ')
            remaining = remaining.substring(breakAt).trimStart(',', ' ')
        }
        return lines
    }

    private fun buildStringConfig(
        textSize: Float = FISCAL_TEXT_SIZE,
        lineSpace: Int = FISCAL_LINE_SPACE,
    ): GEDI_PRNTR_st_StringConfig {
        val paint = Paint().apply {
            this.textSize = textSize
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            textAlign = Paint.Align.CENTER
        }
        return GEDI_PRNTR_st_StringConfig(paint, lineSpace, 0)
    }

    private fun buildQrCodeConfig(compact: Boolean = false): GEDI_PRNTR_st_BarCodeConfig {
        return GEDI_PRNTR_st_BarCodeConfig(
            GEDI_PRNTR_e_BarCodeType.QR_CODE,
            if (compact) FISCAL_QR_CODE_HEIGHT else QR_CODE_HEIGHT,
            if (compact) FISCAL_QR_CODE_WIDTH else QR_CODE_WIDTH,
            QR_CODE_WHITE_SPACE,
        )
    }

    private fun buildCode128Config(): GEDI_PRNTR_st_BarCodeConfig {
        return GEDI_PRNTR_st_BarCodeConfig(
            GEDI_PRNTR_e_BarCodeType.CODE_128,
            CODE_128_HEIGHT,
            CODE_128_WIDTH,
            CODE_128_WHITE_SPACE,
        )
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
