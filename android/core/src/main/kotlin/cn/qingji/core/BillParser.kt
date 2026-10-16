package cn.qingji.core

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

data class BillIssue(val row: Int, val message: String)
data class ParsedBill(val channel: Channel, val entries: List<Entry>, val issues: List<BillIssue>, val rowCount: Int)

object CsvReader {
    fun decode(bytes: ByteArray): String {
        if (bytes.size >= 2 && bytes[0] == 0xff.toByte() && bytes[1] == 0xfe.toByte()) return String(bytes, Charsets.UTF_16LE).removePrefix("\uFEFF")
        return runCatching { Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString() }
            .getOrElse { String(bytes, charset("GB18030")) }.removePrefix("\uFEFF")
    }
    fun read(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        fun endCell() { row.add(cell.toString().trim().removeSuffix("\t")); cell.setLength(0) }
        while (i < text.length) {
            val c = text[i]
            when {
                c == '"' && quoted && i + 1 < text.length && text[i + 1] == '"' -> { cell.append('"'); i++ }
                c == '"' && (quoted || cell.isBlank()) -> quoted = !quoted
                c == ',' && !quoted -> endCell()
                (c == '\n' || c == '\r') && !quoted -> {
                    endCell(); rows.add(row); row = mutableListOf()
                    if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                }
                else -> cell.append(c)
            }
            i++
        }
        require(!quoted) { "CSV 引号未闭合，请重新导出文件" }
        if (cell.isNotEmpty() || row.isNotEmpty()) { endCell(); rows.add(row) }
        return rows
    }
}

object BillParser {
    private val zone = ZoneId.of("Asia/Shanghai")
    private fun header(value: String) = value.trim().replace("（", "(").replace("）", ")").replace(" ", "").removeSuffix("\t")
    private val times = listOf("交易时间", "交易创建时间", "付款时间")
    private val amounts = listOf("金额(元)", "金额", "交易金额(元)")
    private val parties = listOf("交易对方", "商户名称", "收款方")
    fun parse(rows: List<List<String>>, digest: String, importedAt: Long): ParsedBill {
        val headerIndex = rows.indexOfFirst { row ->
            val normalized = row.map(::header)
            normalized.any { it in times } && normalized.any { it in amounts } && normalized.any { it in parties }
        }
        require(headerIndex >= 0) { "未找到支持的账单表头。需要交易时间、交易对方、金额和收支字段；不支持 PDF 或旧版 XLS。" }
        val headers = rows[headerIndex].map(::header)
        val introductory = rows.take(headerIndex).flatten().joinToString(" ")
        val channel = when {
            introductory.contains("微信") || "交易单号" in headers -> Channel.WECHAT
            introductory.contains("支付宝") || "交易号" in headers || "交易订单号" in headers -> Channel.ALIPAY
            else -> throw IllegalArgumentException("无法确定微信或支付宝来源，请使用原始导出账单")
        }
        fun find(vararg names: String) = names.firstNotNullOfOrNull { name -> headers.indexOf(header(name)).takeIf { it >= 0 } }
        val timeCol = find(*times.toTypedArray())!!
        val moneyCol = find(*amounts.toTypedArray())!!
        val partyCol = find(*parties.toTypedArray())!!
        val directionCol = find("收/支", "收支", "资金状态", "收支类型")
        require(directionCol != null) { "缺少收/支列，无法安全判断交易方向" }
        val typeCol = find("交易类型", "交易分类")
        val statusCol = find("当前状态", "交易状态")
        val productCol = find("商品", "商品名称", "商品说明")
        val idCol = find("交易单号", "交易号", "交易订单号")
        val refundCol = find("退款单号")
        val entries = mutableListOf<Entry>()
        val issues = mutableListOf<BillIssue>()
        var rowCount = 0
        rows.drop(headerIndex + 1).forEachIndexed { index, row ->
            val rowNumber = headerIndex + index + 2
            fun cell(column: Int?) = column?.let { row.getOrNull(it)?.trim()?.removeSuffix("\t") } ?: ""
            if (row.all { it.isBlank() } || row.firstOrNull()?.startsWith("---") == true) return@forEachIndexed
            val timeText = cell(timeCol)
            if (!Regex("^\\d{4}[-/]\\d{1,2}[-/]\\d{1,2}.*").matches(timeText)) {
                if (cell(moneyCol).isNotBlank()) issues.add(BillIssue(rowNumber, "不是交易数据，已跳过"))
                return@forEachIndexed
            }
            rowCount++
            val time = listOf("uuuu-MM-dd HH:mm:ss", "uuuu/M/d H:mm:ss", "uuuu-MM-dd HH:mm", "uuuu/M/d H:mm")
                .firstNotNullOfOrNull { pattern -> runCatching { LocalDateTime.parse(timeText, DateTimeFormatter.ofPattern(pattern).withResolverStyle(ResolverStyle.STRICT)).atZone(zone).toInstant().toEpochMilli() }.getOrNull() }
            val fen = parseFen(cell(moneyCol))
            if (time == null || fen == null) { issues.add(BillIssue(rowNumber, "时间或金额无效，未导入")); return@forEachIndexed }
            val type = cell(typeCol)
            val status = cell(statusCol)
            val direction = cell(directionCol)
            val product = cell(productCol)
            if (Regex("交易关闭|支付失败|付款失败|已撤销|未支付|等待付款|待付款").containsMatchIn(status)) return@forEachIndexed
            val refund = type.contains("退款") || direction.contains("退款") || (direction.contains("收入") && (product.contains("退款") || status.contains("退款")))
            val transfer = Regex("转账|充值|提现|还款|理财|余额宝|零钱通").containsMatchIn(type)
            val kind = when { refund -> Kind.REFUND; transfer -> Kind.TRANSFER; direction.contains("支出") -> Kind.EXPENSE; direction.contains("收入") -> Kind.INCOME; else -> Kind.UNKNOWN }
            val problems = buildList {
                if (kind == Kind.UNKNOWN) add("无法确定交易方向")
                if (status.isBlank() || !Regex("成功|已支付|已收款|已到账|已退款|退款|支付完成").containsMatchIn(status)) add("交易状态需要核对：${status.ifBlank { "空白" }}")
                if (kind == Kind.EXPENSE && status.contains("退款")) add("该支出含退款状态，请核对并另记实际退款金额与日期")
            }
            val transaction = cell(idCol).takeIf { it.isNotBlank() && it != "/" && it != "-" }
            entries.add(Entry(amountFen = fen, occurredAt = time, merchant = cell(partyCol).ifBlank { "未知商家" },
                channel = channel, kind = kind, status = if (problems.isEmpty()) Status.CONFIRMED else Status.PENDING,
                transactionId = if (refund) cell(refundCol).takeIf { it.isNotBlank() && it != "/" && it != "-" } else transaction,
                relatedTransactionId = if (refund) transaction else null,
                sources = setOf("账单导入"), eventKeys = setOf("file:$digest:$rowNumber"), note = product,
                raw = row.joinToString(" | ").take(4000), reason = problems.joinToString("；"), createdAt = importedAt))
        }
        require(rowCount > 0) { "文件没有可识别的交易数据" }
        return ParsedBill(channel, entries, issues, rowCount)
    }
}
