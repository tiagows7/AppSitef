package com.appsitef.smartpos.tef

import android.app.Activity
import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.util.Log
import br.com.gertec.gedi.GEDI
import br.com.gertec.gedi.enums.GEDI_PRNTR_e_Alignment
import br.com.gertec.gedi.enums.GEDI_PRNTR_e_BarCodeType
import br.com.gertec.gedi.enums.GEDI_PRNTR_e_Status
import br.com.gertec.gedi.enums.GEDI_e_Ret
import br.com.gertec.gedi.exceptions.GediException
import br.com.gertec.gedi.interfaces.IPRNTR
import br.com.gertec.gedi.structs.GEDI_PRNTR_st_BarCodeConfig
import br.com.gertec.gedi.structs.GEDI_PRNTR_st_PictureConfig
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
    /** Espaço extra entre linhas do comprovante TEF (pixels). 1 = mais compacto. */
    private const val FISCAL_LINE_SPACE = 1
    /** Colunas úteis com mono 17 na GPOS (~384 dots) — evita pontilhado em 2 linhas. */
    private const val FISCAL_MAX_LINE_CHARS = 38
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
    private const val CODE_128_HEIGHT = 70
    private const val CODE_128_WIDTH = 360
    private const val CODE_128_BITMAP_WIDTH = 360
    private const val CODE_128_BITMAP_HEIGHT = 90
    private const val CODE_128_WHITE_SPACE = 2
    /** Bitmap QR (px) — ARGB_8888, igual BitmapToJBitmap do Delphi. */
    private const val QR_BITMAP_SIZE_PX = 240
    private const val QR_PRINT_SIZE = 220
    private const val QR_NATIVE_SIZE = 200
    private const val QR_FEED_BLANK_LINES = 80

    @Synchronized
    fun printNfeCupom(context: Context, receipt: NfeThermalReceipt) {
        if (receipt.text.isBlank() &&
            receipt.textAfterBarCode.isNullOrBlank() &&
            receipt.qrCode.isNullOrBlank() &&
            receipt.accessKeyBarCode.isNullOrBlank()
        ) {
            Log.w(TAG, "printNfeCupom — cupom vazio após parse do XML")
            throw IllegalStateException("Cupom fiscal vazio — XML sem conteúdo imprimível.")
        }

        val host = if (context is Activity) context else context.applicationContext
        GertecPinpadBootstrap.ensureGediReady(host)
        val printer = GEDI.getInstance(host).prntr

        // Folga extra após vias TEF — impressora GPOS ainda pode estar ocupada.
        Thread.sleep(PRE_PRINT_DELAY_MS + 600L)
        waitForPrinterReady(printer)

        val beforeLines = expandCupomLines(receipt.text)
        val afterLines = expandCupomLines(receipt.textAfterBarCode.orEmpty())
        val hasBarcode = !receipt.accessKeyBarCode.isNullOrBlank()
        val hasQr = !receipt.qrCode.isNullOrBlank()
        val totalTextLines = beforeLines.size + afterLines.size

        Log.d(
            TAG,
            "printNfeCupom start before=${beforeLines.size} after=${afterLines.size} " +
                "barcode=$hasBarcode qr=$hasQr",
        )
        if (totalTextLines == 0 && !hasBarcode && !hasQr) {
            throw IllegalStateException("Cupom fiscal sem linhas imprimíveis.")
        }

        // Espelha printReceipt: lotes com Init/Output (buffer GEDI não segura cupom inteiro).
        val config = buildFiscalStringConfig()
        val qrPayload = receipt.qrCode?.let { normalizeQrPayload(it) }.orEmpty()
        val printQr = qrPayload.isNotBlank()

        // NFC-e sem QR: falha antes de gastar papel no texto.
        if (!printQr &&
            receipt.accessKeyBarCode.isNullOrBlank() &&
            receipt.text.contains("Documento Auxiliar", ignoreCase = true)
        ) {
            throw IllegalStateException("QR Code da NFC-e ausente no XML — cupom incompleto.")
        }

        // NFC-e com QR: sessão sticky só se o cupom for curto (NFC-e típica).
        if (printQr && afterLines.isEmpty() && !hasBarcode && beforeLines.size <= 60) {
            try {
                printNfceCupomSingleSession(printer, config, beforeLines, qrPayload)
                Log.d(TAG, "printNfeCupom ok single-session lines=$totalTextLines qrLen=${qrPayload.length}")
                return
            } catch (error: Throwable) {
                Log.w(TAG, "printNfeCupom single-session falhou — fallback lotes", error)
                waitForPrinterReady(printer)
                Thread.sleep(500L)
            }
        }

        // NF-e: NÃO meter o cupom inteiro num único Init — DANFE tem muitas linhas e o
        // buffer GEDI descarta tudo. Texto em lotes + CODE_128 em job curto com contexto.
        val barPayload = receipt.accessKeyBarCode?.trim().orEmpty()

        if (beforeLines.isNotEmpty()) {
            val batches = beforeLines.chunked(LINES_PER_BATCH)
            batches.forEachIndexed { index, batch ->
                val isLastOverall = index == batches.lastIndex &&
                    afterLines.isEmpty() &&
                    barPayload.isEmpty() &&
                    !printQr
                printBatch(printer, config, batch, feedAfter = isLastOverall)
            }
        }

        if (barPayload.isNotEmpty()) {
            printNfeAccessKeyBarCode(printer, config, barPayload)
        }

        if (afterLines.isNotEmpty()) {
            val batches = afterLines.chunked(LINES_PER_BATCH)
            batches.forEachIndexed { index, batch ->
                val isLast = index == batches.lastIndex && !printQr
                printBatch(printer, config, batch, feedAfter = isLast)
            }
        }

        if (printQr) {
            printNfceQrCode(printer, qrPayload)
        }

        if (beforeLines.isEmpty() && afterLines.isEmpty() && barPayload.isNotEmpty() && !printQr) {
            printFeedOnly(printer)
        }

        Log.d(
            TAG,
            "printNfeCupom ok lines=$totalTextLines barcode=${barPayload.isNotEmpty()} qr=$printQr " +
                "qrLen=${qrPayload.length}",
        )
    }

    /**
     * CODE_128 da chave NF-e — job curto: bitmap ARGB (confiável) + fallback DrawBarCode nativo.
     */
    private fun printNfeAccessKeyBarCode(
        printer: IPRNTR,
        config: GEDI_PRNTR_st_StringConfig,
        payload: String,
    ) {
        val digits = payload.filter { it.isDigit() }
        if (digits.isBlank()) {
            throw IllegalStateException("Chave da NF-e vazia para código de barras.")
        }
        Log.d(TAG, "printNfeAccessKeyBarCode len=${digits.length}")

        // 1) Bitmap CODE_128 + DrawPictureExt (mesmo caminho que funcionou no QR NFC-e).
        try {
            waitForPrinterReady(printer)
            Thread.sleep(300L)
            var initialized = false
            try {
                printer.Init()
                initialized = true
                printer.DrawStringExt(config, " ")
                val bitmap = QrCodeBitmapEncoder.encodeCode128(
                    content = digits,
                    widthPx = CODE_128_BITMAP_WIDTH,
                    heightPx = CODE_128_BITMAP_HEIGHT,
                ) ?: throw IllegalStateException("Falha ao gerar bitmap CODE_128.")
                val pictureConfig = GEDI_PRNTR_st_PictureConfig().apply {
                    alignment = GEDI_PRNTR_e_Alignment.CENTER
                    offset = 0
                    width = CODE_128_WIDTH
                    height = CODE_128_HEIGHT
                }
                try {
                    pictureConfig.javaClass.getField("color").setBoolean(pictureConfig, false)
                } catch (_: Throwable) {
                }
                printer.DrawPictureExt(pictureConfig, bitmap)
                printer.DrawBlankLine(12)
                outputWithRetry(printer)
                Log.d(TAG, "printNfeAccessKeyBarCode DrawPictureExt OK")
                return
            } catch (error: Throwable) {
                flushPrinter(printer, initialized)
                throw error
            }
        } catch (error: Throwable) {
            Log.w(TAG, "printNfeAccessKeyBarCode bitmap falhou — DrawBarCode", error)
        }

        // 2) Nativo CODE_128.
        printBarCodeBlock(printer, buildCode128Config(), digits)
    }

    /**
     * Uma sessão: Init → todo o texto → QR → feed → Output (padrão Delphi sticky Init).
     */
    private fun printNfceCupomSingleSession(
        printer: IPRNTR,
        config: GEDI_PRNTR_st_StringConfig,
        lines: List<String>,
        qrPayload: String,
    ) {
        var initialized = false
        try {
            waitForPrinterReady(printer)
            Thread.sleep(300L)
            printer.Init()
            initialized = true
            lines.forEach { line ->
                printer.DrawStringExt(config, line)
            }
            printer.DrawBlankLine(8)
            drawNfceQrInCurrentSession(printer, qrPayload)
            printer.DrawBlankLine(QR_FEED_BLANK_LINES)
            outputWithRetry(printer)
        } catch (error: Throwable) {
            flushPrinter(printer, initialized)
            throw mapPrintError(error)
        }
    }

    /**
     * QR em job próprio — bitmap ARGB + DrawPictureExt (caminho VERO).
     * DrawBarCode nativo só como fallback: URL SEFAZ longa costuma “ok” sem imprimir.
     */
    private fun printNfceQrCode(printer: IPRNTR, rawPayload: String) {
        val payload = normalizeQrPayload(rawPayload)
        if (payload.isBlank()) {
            throw IllegalStateException("QR Code NFC-e vazio após normalização.")
        }
        Log.d(TAG, "printNfceQrCode payloadLen=${payload.length} preview=${payload.take(96)}")

        // 1) Bitmap ARGB_8888 (PrintImage / BitmapToJBitmap Delphi).
        try {
            waitForPrinterReady(printer)
            Thread.sleep(300L)
            var initialized = false
            try {
                printer.Init()
                initialized = true
                drawNfceQrPictureArgb(printer, payload)
                printer.DrawBlankLine(QR_FEED_BLANK_LINES)
                outputWithRetry(printer)
                Log.d(TAG, "printNfceQrCode DrawPictureExt OK")
                return
            } catch (error: Throwable) {
                flushPrinter(printer, initialized)
                throw error
            }
        } catch (error: Throwable) {
            Log.w(TAG, "printNfceQrCode DrawPictureExt falhou — tentando DrawBarCode", error)
        }

        // 2) Nativo QR_CODE.
        var initialized = false
        try {
            waitForPrinterReady(printer)
            Thread.sleep(400L)
            printer.Init()
            initialized = true
            printer.DrawBarCode(buildQrCodeConfig(compact = false), payload)
            printer.DrawBlankLine(QR_FEED_BLANK_LINES)
            outputWithRetry(printer)
            Log.d(TAG, "printNfceQrCode DrawBarCode OK")
        } catch (error: Throwable) {
            Log.e(TAG, "printNfceQrCode", error)
            flushPrinter(printer, initialized)
            throw mapPrintError(error)
        }
    }

    /** Desenha QR na sessão já aberta — só bitmap ARGB (não confiar em DrawBarCode silencioso). */
    private fun drawNfceQrInCurrentSession(printer: IPRNTR, payload: String) {
        drawNfceQrPictureArgb(printer, payload)
        Log.d(TAG, "drawNfceQrInCurrentSession DrawPictureExt OK")
    }

    private fun drawNfceQrPictureArgb(printer: IPRNTR, payload: String) {
        val bitmap = QrCodeBitmapEncoder.encode(payload, QR_BITMAP_SIZE_PX)
            ?: throw IllegalStateException("Falha ao gerar bitmap do QR NFC-e.")
        val pictureConfig = GEDI_PRNTR_st_PictureConfig().apply {
            alignment = GEDI_PRNTR_e_Alignment.CENTER
            offset = 0
            width = QR_PRINT_SIZE
            height = QR_PRINT_SIZE
        }
        // GPOS720 tem `color`; GPOS700 não — seta via reflection se existir.
        try {
            pictureConfig.javaClass.getField("color").setBoolean(pictureConfig, false)
        } catch (_: Throwable) {
        }
        printer.DrawPictureExt(pictureConfig, bitmap)
    }

    private fun normalizeQrPayload(raw: String): String {
        return raw.trim()
            .replace("\r", "")
            .replace("\n", "")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#38;", "&")
            .trim()
    }

    private fun expandCupomLines(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        return TefReceiptFormatter.formatCupom(text)
            .split("\n")
            .flatMap { expandPrintableLines(it, FISCAL_MAX_LINE_CHARS, stripSimuladoPrefixes = true) }
            .map { it.ifBlank { " " } }
    }

    private fun printBarCodeBlock(
        printer: IPRNTR,
        config: GEDI_PRNTR_st_BarCodeConfig,
        payload: String,
        feedAfter: Boolean = false,
    ) {
        var initialized = false
        try {
            waitForPrinterReady(printer)
            printer.Init()
            initialized = true
            Log.d(TAG, "printBarCode type=${config.barCodeType} len=${payload.length}")
            printer.DrawBarCode(config, payload)
            if (feedAfter) {
                printer.DrawBlankLine(NFE_CUPOM_FEED_BLANK_LINES)
            } else {
                printer.DrawBlankLine(16)
            }
            outputWithRetry(printer)
        } catch (error: Throwable) {
            Log.e(TAG, "printBarCodeBlock", error)
            flushPrinter(printer, initialized)
            throw mapPrintError(error)
        }
    }

    private fun printFeedOnly(printer: IPRNTR) {
        var initialized = false
        try {
            waitForPrinterReady(printer)
            printer.Init()
            initialized = true
            printer.DrawBlankLine(NFE_CUPOM_FEED_BLANK_LINES)
            outputWithRetry(printer)
        } catch (error: Throwable) {
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
        val lines = expandCupomLines(text)
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

        // Homologação: o SiTef já manda SI/MU/LA/DO à esquerda + faixas ....S..I..M..U..L..A..D..O....
        // Não remover nem reincrustar laterais — imprimir como veio (igual ao comprovante oficial).
        val homologacao = TefPreferences.isSitefHomologacao(context)
        val stripSimulado = !homologacao

        val formatted = TefReceiptFormatter.formatCupom(text)
        val lines = formatted
            .split("\n")
            .flatMap { expandPrintableLines(it, FISCAL_MAX_LINE_CHARS, stripSimulado) }

        Log.d(TAG, "printReceipt lines=${lines.size} homologacao=$homologacao stripSimulado=$stripSimulado")
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
            val codeName = error.errorCode?.name.orEmpty()
            val mapped = when {
                error.errorCode == GEDI_e_Ret.PRNTR_OUT_OF_PAPER ||
                    codeName.contains("OUT_OF_PAPER") ->
                    "Impressora sem papel. Verifique o rolo térmico."
                error.errorCode == GEDI_e_Ret.PRNTR_NOT_READY ||
                    codeName.contains("NOT_READY") ->
                    "Impressora ocupada. Aguarde e tente novamente."
                // GPOS720 tem PRNTR_OVERHEAT; GPOS700 (payment antigo) não — usa o nome.
                codeName.contains("OVERHEAT") ->
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

    private fun expandPrintableLines(
        raw: String,
        maxLineChars: Int = FISCAL_MAX_LINE_CHARS,
        stripSimuladoPrefixes: Boolean = true,
    ): List<String> {
        // Faixas centrais do layout oficial de homologação: não quebrar/reformatar.
        if (!stripSimuladoPrefixes &&
            (TefReceiptFormatter.isSimuladoBannerLine(raw) ||
                raw.contains("REIMPRESSAO", ignoreCase = true) ||
                raw.contains("=========="))
        ) {
            return listOf(raw.trimEnd().ifBlank { " " })
        }

        val adjusted = TefReceiptFormatter.adjustLine(raw, stripSimuladoPrefixes).ifBlank { " " }
        if (adjusted.length <= maxLineChars) return listOf(adjusted)

        val lines = mutableListOf<String>()
        var remaining = adjusted
        var isFirstWrapped = true
        val simuladoPrefix = if (!stripSimuladoPrefixes &&
            TefReceiptFormatter.startsWithSimuladoPrefix(adjusted)
        ) {
            adjusted.take(2)
        } else {
            null
        }
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
            // Continuação de linha longa: mantém indentação do prefixo SI/MU/LA/DO.
            if (simuladoPrefix != null && !isFirstWrapped && remaining.isNotEmpty()) {
                remaining = "  $remaining"
            }
            isFirstWrapped = false
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

    /** Layout cupom fiscal — Delphi GPOS: MONOSPACE, LEFT, lineSpace baixo. */
    private fun buildFiscalStringConfig(): GEDI_PRNTR_st_StringConfig {
        val paint = Paint().apply {
            textSize = 17f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
            textAlign = Paint.Align.LEFT
            isAntiAlias = true
        }
        return GEDI_PRNTR_st_StringConfig(paint, 1, 0)
    }

    private fun buildQrCodeConfig(compact: Boolean = false): GEDI_PRNTR_st_BarCodeConfig {
        val size = if (compact) FISCAL_QR_CODE_HEIGHT else QR_NATIVE_SIZE
        return GEDI_PRNTR_st_BarCodeConfig(
            GEDI_PRNTR_e_BarCodeType.QR_CODE,
            size,
            size,
        ).also {
            try {
                val field = it.javaClass.getField("white_Space")
                field.setInt(it, QR_CODE_WHITE_SPACE)
            } catch (_: Throwable) {
                // GPOS700: campo inexistente.
            }
        }
    }

    private fun buildCode128Config(): GEDI_PRNTR_st_BarCodeConfig {
        return GEDI_PRNTR_st_BarCodeConfig(
            GEDI_PRNTR_e_BarCodeType.CODE_128,
            CODE_128_HEIGHT,
            CODE_128_WIDTH,
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
