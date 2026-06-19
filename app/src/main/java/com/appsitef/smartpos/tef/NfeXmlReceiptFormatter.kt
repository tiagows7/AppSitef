package com.appsitef.smartpos.tef

import android.util.Log
import com.appsitef.smartpos.sales.network.DatasnapDownloadDecoder
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.nio.charset.Charset
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Converte XML NF-e/NFC-e em cupom térmico — layout espelha impressão Delphi (GPOS/SITEF).
 */
object NfeXmlReceiptFormatter {

    private const val TAG = "NfeXmlReceiptFormatter"
    private const val MODELO_NFE = "55"
    private const val MODELO_NFCE = "65"
    private const val LINE_WIDTH = 50
    private const val CONSULTA_NFCE_RS = "www.sefaz.rs.gov.br/nfce/consulta"

    fun formatForThermalPrinter(
        xmlBytes: ByteArray,
        chaveNota: String = "",
        charset: Charset = Charsets.UTF_8,
    ): NfeThermalReceipt {
        val xml = DatasnapDownloadDecoder.decodeToXmlString(xmlBytes, charset)
        val data = parse(xml)
        enrichFromRegex(xml, data)
        if (data.key.isNullOrBlank() && chaveNota.isNotBlank()) {
            data.key = NfeChaveAccessKey.normalize(chaveNota)
        }
        return buildReceipt(data, chaveNota)
    }

    private fun buildReceipt(data: NfeData, chaveNota: String): NfeThermalReceipt {
        val chave = resolveChave(data, chaveNota)
        val isNfe = resolveIsNfe(data, chaveNota)
        Log.d(
            TAG,
            "buildReceipt isNfe=$isNfe mod=${data.model} chaveModelo=${NfeChaveAccessKey.modeloFromChave(chave)} " +
                "chaveLen=${chave.length}",
        )
        return if (isNfe) buildNfeReceipt(data, chave) else buildNfceReceipt(data, chave)
    }

    private fun resolveChave(data: NfeData, chaveNota: String): String {
        val fromParam = NfeChaveAccessKey.normalize(chaveNota)
        if (NfeChaveAccessKey.isValid(fromParam)) return fromParam
        val fromXml = data.key?.let { NfeChaveAccessKey.normalize(it) }.orEmpty()
        if (NfeChaveAccessKey.isValid(fromXml)) return fromXml
        return fromParam.ifBlank { fromXml }
    }

    /** Prioriza `<mod>` do XML (como ACBr/Delphi), depois chave do servidor. */
    private fun resolveIsNfe(data: NfeData, chaveNota: String): Boolean {
        when (data.model?.trim()) {
            MODELO_NFE -> return true
            MODELO_NFCE -> return false
        }
        val chaveParam = NfeChaveAccessKey.normalize(chaveNota)
        if (NfeChaveAccessKey.isValid(chaveParam)) {
            return NfeChaveAccessKey.isNfe(chaveParam)
        }
        val chaveXml = data.key?.let { NfeChaveAccessKey.normalize(it) }.orEmpty()
        if (NfeChaveAccessKey.isValid(chaveXml)) {
            return NfeChaveAccessKey.isNfe(chaveXml)
        }
        return false
    }

    private fun buildNfeReceipt(data: NfeData, chave: String): NfeThermalReceipt {
        val linesBeforeBarCode = mutableListOf<String>()
        val linesAfterBarCode = mutableListOf<String>()
        val sep = separator()
        val chaveDigits = chave.filter { it.isDigit() }

        data.emitCnpj?.let { linesBeforeBarCode += "CNPJ: ${digitsOnly(it)}" }
        linesBeforeBarCode += "DANFE Simplificado da NF-e"
        linesBeforeBarCode += "    CHAVE DE ACESSO"
        if (chaveDigits.isNotBlank()) linesBeforeBarCode += chaveDigits

        linesAfterBarCode += "Protocolo de Autorizacao"
        val protocolLine = buildString {
            data.protocolo?.let { append(it) }
            data.dataAutorizacao?.let { auth ->
                if (isNotEmpty()) append(' ')
                append(formatDelphiDateTime(auth))
            }
        }.trim()
        addWrappedLines(linesAfterBarCode, protocolLine)

        linesAfterBarCode += sep
        data.emitFantasy?.let { linesAfterBarCode += it }
        formatEmitFullAddress(data)?.let { addWrappedLines(linesAfterBarCode, it) }
        val emitDocLine = buildString {
            data.emitCnpj?.let { append("CNPJ: ${digitsOnly(it)}") }
            data.emitIe?.let { ie ->
                if (isNotEmpty()) append(' ')
                append("IE: $ie")
            }
        }.trim()
        addWrappedLines(linesAfterBarCode, emitDocLine)

        linesAfterBarCode += sep
        linesAfterBarCode += "    ${finNfeDescription(data.finNfe)}"
        linesAfterBarCode += "    ${tpNfDescription(data.tpNf)}"
        val number = data.number.orEmpty()
        val serie = data.serie.orEmpty()
        if (number.isNotBlank() || serie.isNotBlank()) {
            linesAfterBarCode += "Numero: $number Serie $serie"
        }

        linesAfterBarCode += sep
        linesAfterBarCode += "   DESTINATARIO"
        data.destName?.let { if (it.isNotBlank()) addWrappedLines(linesAfterBarCode, it) }
        formatDestAddress(data)?.let { addWrappedLines(linesAfterBarCode, it) }
        val destDocLine = buildString {
            data.destDocument?.let { append("CNPJ: ${digitsOnly(it)}") }
            data.destIe?.let { ie ->
                if (isNotEmpty()) append(' ')
                append("IE: $ie")
            }
        }.trim()
        addWrappedLines(linesAfterBarCode, destDocLine)

        linesAfterBarCode += sep
        linesAfterBarCode += "Item  Codigo Descricao do Produto"
        linesAfterBarCode += "Cfop Cst Qtd Und V.Unit. (R$) V.Item (R$)"
        linesAfterBarCode += sep

        data.items.forEachIndexed { index, item ->
            val itemNumber = item.itemNumber ?: (index + 1).toString()
            val code = padRight(item.code.orEmpty(), 8)
            val description = padRight(truncate(item.description, 15), 15)
            linesAfterBarCode += "$itemNumber  $code  $description"

            val cfop = item.cfop.orEmpty()
            val cst = padRight(item.icmsCst.orEmpty(), 3)
            val unit = padRight(item.unitTrib ?: item.unit.orEmpty(), 3)
            val qty = padRight(item.quantity?.let { formatDelphiQuantity(it) }.orEmpty(), 8)
            val unitPrice = padRight(item.unitPrice?.let { formatDelphiQuantity(it) }.orEmpty(), 8)
            val total = item.total?.let { formatDelphiMoney(it) }.orEmpty()
            addWrappedLines(linesAfterBarCode, "$cfop  $cst $unit $qty $unitPrice $total".trim())
        }

        linesAfterBarCode += sep
        linesAfterBarCode += "Qtde Total de Items          ${data.items.size}"
        data.valorDesconto?.takeIf { it > 0.0 }?.let {
            linesAfterBarCode += "Desconto                  ${formatDelphiMoney(it)}"
        }
        data.totalValue?.let {
            linesAfterBarCode += "Valor Total               ${formatDelphiMoney(it)}"
        }

        linesAfterBarCode += sep
        data.valorTotTrib?.let {
            linesAfterBarCode += "Valor Aprox. dos Tributos:"
            linesAfterBarCode += "(Lei Federal 12.741/12): ${formatDelphiMoney(it)}"
        }

        linesAfterBarCode += sep
        linesAfterBarCode += "Informacoes Adicionais"
        linesAfterBarCode += sep
        data.infCpl?.let { if (it.isNotBlank()) addWrappedLines(linesAfterBarCode, it) }
        linesAfterBarCode += sep
        linesAfterBarCode += center("Modulo info")

        val barCode = chaveDigits.takeIf { NfeChaveAccessKey.isValid(it) }
        return NfeThermalReceipt(
            text = linesBeforeBarCode.joinToString("\n"),
            accessKeyBarCode = barCode,
            textAfterBarCode = linesAfterBarCode.joinToString("\n"),
            qrCode = null,
        )
    }

    private fun buildNfceReceipt(data: NfeData, chave: String): NfeThermalReceipt {
        val lines = mutableListOf<String>()

        data.emitCnpj?.let { lines += "CNPJ: ${digitsOnly(it)}" }
        data.emitFantasy?.let { lines += it }
        val streetLine = listOfNotNull(data.emitStreet, data.emitNumber)
            .joinToString(",")
            .takeIf { it.isNotBlank() }
        streetLine?.let { lines += it }
        val cepFone = buildString {
            data.emitCep?.let { append("Cep: $it") }
            data.emitFone?.let { fone ->
                if (isNotEmpty()) append(' ')
                append("Fone: $fone")
            }
        }.trim()
        if (cepFone.isNotBlank()) lines += cepFone
        data.emitIe?.let { if (it.isNotBlank()) lines += "I.E.: $it" }

        if (data.tpAmb == "2") {
            lines += "   "
            lines += "EMITIDA EM AMBIENTE DE HOMOLOGACAO"
            lines += "SEM VALOR FISCAL"
        }

        lines += "   "
        lines += "Documento Auxiliar da Nota Fiscal"
        lines += "de Consumidor Eletronica"

        lines += "#Codigo  Descricao     Qtde    UN"
        lines += "Valor Unit.    Valor Total"
        data.items.forEach { item ->
            val code = item.code.orEmpty()
            val description = truncate(item.description, 20)
            val qty = item.quantity?.let { formatDelphiQuantity(it) }.orEmpty()
            val unit = item.unit.orEmpty()
            lines += "$code $description $qty $unit".trim()
            val unitPrice = item.unitPrice?.let { formatDelphiMoney(it) }.orEmpty()
            val total = item.total?.let { formatDelphiMoney(it) }.orEmpty()
            if (unitPrice.isNotBlank() || total.isNotBlank()) {
                lines += "$unitPrice    $total".trim()
            }
        }

        lines += "Qtde. total de itens: ${data.items.size}"
        lines += " "

        lines += "Forma de Pagamento"
        if (data.payments.isNotEmpty()) {
            data.payments.forEach { payment ->
                val description = paymentDescription(payment.type)
                val value = payment.value?.let { formatDelphiMoney(it) }.orEmpty()
                lines += "$description  R$ $value"
            }
        } else {
            data.totalValue?.let { lines += "Total  R$ ${formatDelphiMoney(it)}" }
        }
        lines += "   "

        lines += "Consulte pela chave de acesso em"
        lines += data.urlChave?.takeIf { it.isNotBlank() } ?: CONSULTA_NFCE_RS
        if (chave.isNotBlank()) lines += chave.filter { it.isDigit() }
        lines += "    "

        val destDocument = data.destDocument?.trim().orEmpty()
        if (destDocument.isNotBlank()) {
            lines += "Cpf/Cnpj: ${digitsOnly(destDocument)}"
            data.destName?.let { if (it.isNotBlank()) lines += it }
        } else {
            lines += "Consumidor nao identificado"
        }
        data.infCpl?.let { if (it.isNotBlank()) lines += it }

        lines += "    "
        val number = data.number.orEmpty()
        val serie = data.serie.orEmpty()
        if (number.isNotBlank() || serie.isNotBlank()) {
            lines += "NFC-e n. $number serie $serie"
        }
        data.emission?.let { lines += formatDelphiDateTime(it) }
        lines += "    "

        if (!data.protocolo.isNullOrBlank()) {
            lines += "Protoclo de Autorizacao:"
            lines += data.protocolo.orEmpty()
            lines += "    "
        }
        if (!data.dataAutorizacao.isNullOrBlank()) {
            lines += "Data de Autorizacao"
            lines += formatDelphiDateTime(data.dataAutorizacao.orEmpty())
            lines += "    "
        }

        data.valorTotTrib?.let {
            lines += "Valor Aprox. dos Tributos:"
            lines += "(Lei Federal 12.741/12): ${formatDelphiMoney(it)}"
        }

        val qrPayload = data.qrCode?.trim()?.takeIf { it.isNotBlank() }
        return NfeThermalReceipt(text = lines.joinToString("\n"), qrCode = qrPayload)
    }

    private fun parse(xml: String): NfeData {
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = true
        val parser = factory.newPullParser()
        parser.setInput(xml.reader())

        val data = NfeData()
        val textStack = ArrayDeque<String>()
        val detItems = mutableListOf<NfeItem>()
        var currentDet: NfeItem? = null
        var currentPayment: NfePayment? = null
        var insideProd = false
        var insideDetIcms = false

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    val tag = parser.name
                    when (tag) {
                        "infNFe" -> data.key = extractChaveFromId(parser.getAttributeValue(null, "Id"))
                        "det" -> {
                            currentDet = NfeItem(itemNumber = parser.getAttributeValue(null, "nItem"))
                            insideProd = false
                            insideDetIcms = false
                        }
                        "prod" -> if (currentDet != null) insideProd = true
                        "detPag" -> currentPayment = NfePayment()
                        "ICMS" -> if (currentDet != null && textStack.contains("imposto")) {
                            insideDetIcms = true
                        }
                    }
                    textStack.addLast(tag)
                }

                XmlPullParser.TEXT -> {
                    val value = parser.text?.trim().orEmpty()
                    if (value.isEmpty() || textStack.isEmpty()) {
                        event = parser.next()
                        continue
                    }
                    val tag = textStack.last()
                    val inIcmsTot = textStack.contains("ICMSTot")
                    val inInfProt = textStack.contains("infProt")
                    val inDetPag = textStack.contains("detPag")

                    when (tag) {
                        "xFant" -> if (textStack.contains("emit") && data.emitFantasy.isNullOrBlank()) {
                            data.emitFantasy = value
                        }
                        "xNome" -> when {
                            textStack.contains("emit") && data.emitName.isNullOrBlank() -> data.emitName = value
                            textStack.contains("dest") && data.destName.isNullOrBlank() -> data.destName = value
                        }
                        "IE" -> when {
                            textStack.contains("emit") && data.emitIe.isNullOrBlank() -> data.emitIe = value
                            textStack.contains("dest") && data.destIe.isNullOrBlank() -> data.destIe = value
                        }
                        "CNPJ", "CPF" -> when {
                            textStack.contains("emit") && data.emitCnpj.isNullOrBlank() -> data.emitCnpj = value
                            textStack.contains("dest") && data.destDocument.isNullOrBlank() -> data.destDocument = value
                        }
                        "xLgr" -> when {
                            textStack.contains("enderEmit") && data.emitStreet.isNullOrBlank() -> data.emitStreet = value
                            textStack.contains("enderDest") && data.destStreet.isNullOrBlank() -> data.destStreet = value
                        }
                        "nro" -> when {
                            textStack.contains("enderEmit") && data.emitNumber.isNullOrBlank() -> data.emitNumber = value
                            textStack.contains("enderDest") && data.destNumber.isNullOrBlank() -> data.destNumber = value
                        }
                        "xBairro" -> when {
                            textStack.contains("enderEmit") && data.emitDistrict.isNullOrBlank() -> data.emitDistrict = value
                            textStack.contains("enderDest") && data.destDistrict.isNullOrBlank() -> data.destDistrict = value
                        }
                        "xMun" -> when {
                            textStack.contains("enderEmit") && data.emitCity.isNullOrBlank() -> data.emitCity = value
                            textStack.contains("enderDest") && data.destCity.isNullOrBlank() -> data.destCity = value
                        }
                        "UF" -> when {
                            textStack.contains("enderEmit") && data.emitUf.isNullOrBlank() -> data.emitUf = value
                            textStack.contains("enderDest") && data.destUf.isNullOrBlank() -> data.destUf = value
                        }
                        "CEP" -> if (textStack.contains("enderEmit") && data.emitCep.isNullOrBlank()) {
                            data.emitCep = value
                        }
                        "fone" -> if (textStack.contains("enderEmit") && data.emitFone.isNullOrBlank()) {
                            data.emitFone = value
                        }
                        "mod" -> if (textStack.contains("ide") && data.model.isNullOrBlank()) data.model = value
                        "serie" -> if (data.serie.isNullOrBlank()) data.serie = value
                        "tpAmb" -> if (data.tpAmb.isNullOrBlank()) data.tpAmb = value
                        "finNFe" -> if (data.finNfe.isNullOrBlank()) data.finNfe = value
                        "tpNF" -> if (data.tpNf.isNullOrBlank()) data.tpNf = value
                        "nNF" -> if (data.number.isNullOrBlank()) data.number = value
                        "dhEmi", "dEmi" -> if (data.emission.isNullOrBlank()) data.emission = value
                        "qrCode" -> if (data.qrCode.isNullOrBlank()) data.qrCode = value
                        "urlChave" -> if (data.urlChave.isNullOrBlank()) data.urlChave = value
                        "infCpl" -> if (textStack.contains("infAdic") && data.infCpl.isNullOrBlank()) {
                            data.infCpl = value
                        }
                        "nProt" -> if (inInfProt && data.protocolo.isNullOrBlank()) data.protocolo = value
                        "dhRecbto" -> if (inInfProt && data.dataAutorizacao.isNullOrBlank()) {
                            data.dataAutorizacao = value
                        }
                        "vNF" -> if (inIcmsTot && data.totalValue == null) data.totalValue = value.toDoubleOrNull()
                        "vDesc" -> if (inIcmsTot && data.valorDesconto == null) {
                            data.valorDesconto = value.toDoubleOrNull()
                        }
                        "vTotTrib" -> if (inIcmsTot && data.valorTotTrib == null) {
                            data.valorTotTrib = value.toDoubleOrNull()
                        }
                        "tPag" -> if (inDetPag && currentPayment != null) currentPayment.type = value
                        "vPag" -> if (inDetPag && currentPayment != null) {
                            currentPayment.value = value.toDoubleOrNull()
                        }
                        "cProd" -> if (insideProd && currentDet != null) currentDet.code = value
                        "xProd" -> if (insideProd && currentDet != null) currentDet.description = value
                        "CFOP" -> if (insideProd && currentDet != null) currentDet.cfop = value
                        "qCom" -> if (insideProd && currentDet != null) currentDet.quantity = value.toDoubleOrNull()
                        "uCom" -> if (insideProd && currentDet != null) currentDet.unit = value
                        "uTrib" -> if (insideProd && currentDet != null) currentDet.unitTrib = value
                        "vUnCom" -> if (insideProd && currentDet != null) currentDet.unitPrice = value.toDoubleOrNull()
                        "vProd" -> if (insideProd && currentDet != null) currentDet.total = value.toDoubleOrNull()
                        "CST", "CSOSN" -> if (insideDetIcms && currentDet != null && currentDet.icmsCst.isNullOrBlank()) {
                            currentDet.icmsCst = value
                        }
                    }
                }

                XmlPullParser.END_TAG -> {
                    when (parser.name) {
                        "det" -> {
                            currentDet?.let { detItems.add(it) }
                            currentDet = null
                            insideProd = false
                            insideDetIcms = false
                        }
                        "prod" -> insideProd = false
                        "ICMS" -> insideDetIcms = false
                        "detPag" -> {
                            currentPayment?.let { payment ->
                                if (payment.type != null || payment.value != null) {
                                    data.payments += payment
                                }
                            }
                            currentPayment = null
                        }
                    }
                    if (textStack.isNotEmpty()) textStack.removeLast()
                }
            }
            event = parser.next()
        }

        data.items.addAll(detItems.filter { it.description.isNotBlank() || !it.code.isNullOrBlank() })
        if (data.key.isNullOrBlank()) {
            data.key = Regex("""Id\s*=\s*['"]?NFe(\d{44})""").find(xml)?.groupValues?.getOrNull(1)
        }
        return data
    }

    private fun enrichFromRegex(xml: String, data: NfeData) {
        fun firstMatch(pattern: String): String? =
            Regex(pattern, RegexOption.IGNORE_CASE).find(xml)?.groupValues?.getOrNull(1)?.trim()

        if (data.qrCode.isNullOrBlank()) data.qrCode = firstMatch("""<qrCode>([^<]+)</qrCode>""")
        if (data.urlChave.isNullOrBlank()) data.urlChave = firstMatch("""<urlChave>([^<]+)</urlChave>""")
        if (data.protocolo.isNullOrBlank()) data.protocolo = firstMatch("""<nProt>([^<]+)</nProt>""")
        if (data.dataAutorizacao.isNullOrBlank()) {
            data.dataAutorizacao = firstMatch("""<dhRecbto>([^<]+)</dhRecbto>""")
        }
        if (data.valorTotTrib == null) {
            data.valorTotTrib = firstMatch("""<ICMSTot>[\s\S]*?<vTotTrib>([^<]+)</vTotTrib>""")
                ?.toDoubleOrNull()
        }
        if (data.valorDesconto == null) {
            data.valorDesconto = firstMatch("""<ICMSTot>[\s\S]*?<vDesc>([^<]+)</vDesc>""")
                ?.toDoubleOrNull()
        }
        if (data.emitCep.isNullOrBlank()) {
            data.emitCep = firstMatch("""<enderEmit>[\s\S]*?<CEP>([^<]+)</CEP>""")
        }
        if (data.emitFone.isNullOrBlank()) {
            data.emitFone = firstMatch("""<enderEmit>[\s\S]*?<fone>([^<]+)</fone>""")
        }
        if (data.emitDistrict.isNullOrBlank()) {
            data.emitDistrict = firstMatch("""<enderEmit>[\s\S]*?<xBairro>([^<]+)</xBairro>""")
        }
        if (data.emitCity.isNullOrBlank()) {
            data.emitCity = firstMatch("""<enderEmit>[\s\S]*?<xMun>([^<]+)</xMun>""")
        }
        if (data.emitUf.isNullOrBlank()) {
            data.emitUf = firstMatch("""<enderEmit>[\s\S]*?<UF>([^<]+)</UF>""")
        }
        if (data.destIe.isNullOrBlank()) {
            data.destIe = firstMatch("""<dest>[\s\S]*?<IE>([^<]+)</IE>""")
        }
        if (data.destStreet.isNullOrBlank()) {
            data.destStreet = firstMatch("""<enderDest>[\s\S]*?<xLgr>([^<]+)</xLgr>""")
        }
        if (data.destNumber.isNullOrBlank()) {
            data.destNumber = firstMatch("""<enderDest>[\s\S]*?<nro>([^<]+)</nro>""")
        }
        if (data.destDistrict.isNullOrBlank()) {
            data.destDistrict = firstMatch("""<enderDest>[\s\S]*?<xBairro>([^<]+)</xBairro>""")
        }
        if (data.destCity.isNullOrBlank()) {
            data.destCity = firstMatch("""<enderDest>[\s\S]*?<xMun>([^<]+)</xMun>""")
        }
        if (data.destUf.isNullOrBlank()) {
            data.destUf = firstMatch("""<enderDest>[\s\S]*?<UF>([^<]+)</UF>""")
        }
        if (data.model.isNullOrBlank()) {
            data.model = firstMatch("""<ide>[\s\S]*?<mod>([^<]+)</mod>""")
        }
        if (data.finNfe.isNullOrBlank()) data.finNfe = firstMatch("""<finNFe>([^<]+)</finNFe>""")
        if (data.tpNf.isNullOrBlank()) data.tpNf = firstMatch("""<tpNF>([^<]+)</tpNF>""")
        if (data.infCpl.isNullOrBlank()) {
            data.infCpl = firstMatch("""<infCpl>([^<]+)</infCpl>""")
        }
        if (data.payments.isEmpty()) {
            Regex(
                """<detPag>[\s\S]*?<tPag>([^<]+)</tPag>[\s\S]*?<vPag>([^<]+)</vPag>""",
                RegexOption.IGNORE_CASE,
            ).findAll(xml).forEach { match ->
                data.payments += NfePayment(
                    type = match.groupValues[1].trim(),
                    value = match.groupValues[2].trim().toDoubleOrNull(),
                )
            }
        }
        if (data.items.any { it.icmsCst.isNullOrBlank() }) {
            val detBlocks = Regex("""<det[\s\S]*?</det>""", RegexOption.IGNORE_CASE).findAll(xml).toList()
            data.items.forEachIndexed { index, item ->
                if (!item.icmsCst.isNullOrBlank()) return@forEachIndexed
                val block = detBlocks.getOrNull(index)?.value ?: return@forEachIndexed
                item.icmsCst = Regex("""<(?:CST|CSOSN)>([^<]+)</(?:CST|CSOSN)>""", RegexOption.IGNORE_CASE)
                    .find(block)?.groupValues?.getOrNull(1)
            }
        }
    }

    private fun formatEmitFullAddress(data: NfeData): String? {
        val parts = listOfNotNull(
            data.emitStreet,
            data.emitNumber,
            data.emitDistrict,
            listOfNotNull(data.emitCity, data.emitUf).joinToString("/").takeIf { it.isNotBlank() },
        )
        return parts.joinToString(",").takeIf { it.isNotBlank() }
    }

    private fun formatDestAddress(data: NfeData): String? {
        val parts = listOfNotNull(
            data.destStreet,
            data.destNumber,
            data.destDistrict,
            listOfNotNull(data.destCity, data.destUf).joinToString("/").takeIf { it.isNotBlank() },
        )
        return parts.joinToString(",").takeIf { it.isNotBlank() }
    }

    private fun finNfeDescription(finNfe: String?): String = when (finNfe) {
        "2" -> "EMISSAO COMPLEMENTAR"
        "3" -> "EMISSAO DE AJUSTE"
        "4" -> "DEVOLUCAO DE MERCADORIA"
        else -> "EMISSAO NORMAL"
    }

    private fun tpNfDescription(tpNf: String?): String = when (tpNf) {
        "0" -> "0 - ENTRADA"
        else -> "1 - SAIDA"
    }

    private fun extractChaveFromId(id: String?): String? {
        if (id.isNullOrBlank()) return null
        val normalized = id.removePrefix("NFe")
        val digits = normalized.filter { it.isDigit() }
        return digits.take(44).takeIf { it.length == 44 }
    }

    private fun paymentDescription(tPag: String?): String = when (tPag) {
        "04" -> "Cartao Debito"
        "03" -> "Cartao Credito"
        "01" -> "Dinheiro"
        else -> "PIX"
    }

    private fun separator(): String = "-".repeat(LINE_WIDTH)

    private fun addWrappedLines(lines: MutableList<String>, text: String) {
        if (text.isBlank()) return
        wrapToWidth(text, LINE_WIDTH).forEach { lines += it }
    }

    private fun wrapToWidth(text: String, maxWidth: Int): List<String> {
        if (text.length <= maxWidth) return listOf(text)
        val result = mutableListOf<String>()
        var remaining = text.trim()
        while (remaining.isNotEmpty()) {
            if (remaining.length <= maxWidth) {
                result += remaining
                break
            }
            var breakAt = remaining.lastIndexOf(',', maxWidth.coerceAtMost(remaining.length - 1))
            if (breakAt <= 0) breakAt = remaining.lastIndexOf(' ', maxWidth)
            if (breakAt <= 0) breakAt = maxWidth
            result += remaining.substring(0, breakAt).trimEnd(',', ' ')
            remaining = remaining.substring(breakAt).trimStart(',', ' ')
        }
        return result
    }

    private fun center(text: String): String {
        if (text.length >= LINE_WIDTH) return text
        val pad = (LINE_WIDTH - text.length) / 2
        return " ".repeat(pad.coerceAtLeast(0)) + text
    }

    private fun padRight(text: String, width: Int): String {
        return if (text.length >= width) text.substring(0, width) else text + " ".repeat(width - text.length)
    }

    private fun truncate(text: String, maxLength: Int): String {
        return if (text.length <= maxLength) text else text.substring(0, maxLength)
    }

    private fun digitsOnly(raw: String): String = raw.filter { it.isDigit() }

    private fun formatDelphiMoney(value: Double): String {
        return String.format(Locale("pt", "BR"), "%,.2f", value)
    }

    private fun formatDelphiQuantity(value: Double): String {
        return String.format(Locale("pt", "BR"), "%,.3f", value)
    }

    private fun formatDelphiDateTime(raw: String): String {
        val patterns = listOf(
            "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd'T'HH:mm:ss",
            "yyyy-MM-dd",
            "dd/MM/yyyy HH:mm:ss",
            "dd/MM/yyyy",
        )
        val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale("pt", "BR"))
        val timeFormat = SimpleDateFormat("HH:mm:ss", Locale("pt", "BR"))
        patterns.forEach { pattern ->
            runCatching {
                val input = SimpleDateFormat(pattern, Locale.US)
                input.parse(raw)?.let { parsed ->
                    return "${dateFormat.format(parsed)}  ${timeFormat.format(parsed)}"
                }
            }
        }
        return raw
    }

    private data class NfeData(
        var model: String? = null,
        var key: String? = null,
        var qrCode: String? = null,
        var urlChave: String? = null,
        var emitFantasy: String? = null,
        var emitName: String? = null,
        var emitCnpj: String? = null,
        var emitIe: String? = null,
        var emitStreet: String? = null,
        var emitNumber: String? = null,
        var emitDistrict: String? = null,
        var emitCity: String? = null,
        var emitUf: String? = null,
        var emitCep: String? = null,
        var emitFone: String? = null,
        var destName: String? = null,
        var destDocument: String? = null,
        var destIe: String? = null,
        var destStreet: String? = null,
        var destNumber: String? = null,
        var destDistrict: String? = null,
        var destCity: String? = null,
        var destUf: String? = null,
        var serie: String? = null,
        var tpAmb: String? = null,
        var finNfe: String? = null,
        var tpNf: String? = null,
        var number: String? = null,
        var emission: String? = null,
        var infCpl: String? = null,
        var totalValue: Double? = null,
        var valorDesconto: Double? = null,
        var valorTotTrib: Double? = null,
        var protocolo: String? = null,
        var dataAutorizacao: String? = null,
        val payments: MutableList<NfePayment> = mutableListOf(),
        val items: MutableList<NfeItem> = mutableListOf(),
    )

    private data class NfePayment(
        var type: String? = null,
        var value: Double? = null,
    )

    private data class NfeItem(
        var itemNumber: String? = null,
        var code: String? = null,
        var description: String = "",
        var cfop: String? = null,
        var icmsCst: String? = null,
        var quantity: Double? = null,
        var unit: String? = null,
        var unitTrib: String? = null,
        var unitPrice: Double? = null,
        var total: Double? = null,
    )
}
