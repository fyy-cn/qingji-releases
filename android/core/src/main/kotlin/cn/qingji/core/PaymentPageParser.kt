package cn.qingji.core

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.UUID

data class PaymentPageResult(val entry: Entry?, val message: String)

/** Conservative, text-only experiment. Inputs are transient; raw pages are never returned. */
object PaymentPageParser {
    val packages = setOf("com.tencent.mm", "com.eg.android.AlipayGphone")
    fun isPaymentWindow(packageName: String, className: String): Boolean = when (packageName) {
        "com.tencent.mm" -> className.startsWith("com.tencent.mm.plugin.") &&
            Regex("\\.plugin\\.(?:wallet[^.]*|wxpay[^.]*|remittance)\\.", RegexOption.IGNORE_CASE).containsMatchIn(className)
        "com.eg.android.AlipayGphone" -> className.startsWith("com.alipay.") &&
            Regex("\\.(?:pay|bill|msp|trade|wallet|nebula|nebulax)\\.", RegexOption.IGNORE_CASE).containsMatchIn(className)
        else -> false
    }
    private val headers = setOf("支付成功", "付款成功", "交易详情", "账单详情", "支付详情", "付款详情", "微信支付凭证")
    private val merchantLabels = listOf("商户名称", "商家名称", "收款商户", "收款方", "交易对方", "对方名称", "商家", "收款人")
    private val paidLabels = listOf("实付金额", "实际付款", "实际支付", "实付", "付款金额", "支付金额")
    private val allLabels = (merchantLabels + paidLabels + listOf("金额", "交易时间", "付款时间", "支付时间", "交易单号", "交易号", "微信支付单号", "支付宝交易号", "支付方式", "付款方式", "交易状态", "商品", "商品说明", "创建时间", "商户单号", "商户订单号")).toSet()
    private val moneyLine = Regex("^[¥￥]?\\s*-?\\s*((?:\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.\\d{1,2})?)\\s*元?$")
    private fun moneyValue(value: String): Long? = moneyLine.matchEntire(value.trim())?.groupValues?.get(1)?.let(::parseFen)
    private fun field(lines: List<String>, labels: List<String>): String? {
        labels.forEach { label ->
            lines.forEachIndexed { index, line ->
                if (line == label || line == "$label：" || line == "$label:") {
                    val next = lines.getOrNull(index + 1)?.trim().orEmpty()
                    if (next.isNotBlank() && next !in allLabels && next !in headers) {
                        return if (next in listOf("¥", "￥")) next + lines.getOrNull(index + 2).orEmpty() else next
                    }
                }
                Regex("^${Regex.escape(label)}(?:\\s*[:：]\\s*|\\s+)(.+)$").matchEntire(line)?.let { return it.groupValues[1].trim() }
            }
        }
        return null
    }
    fun parse(packageName: String, texts: List<String>, observedAt: Long): PaymentPageResult {
        if (packageName !in packages) return PaymentPageResult(null, "非支持渠道")
        val lines = texts.take(200).flatMap { it.take(500).lines() }.map { it.trim() }.filter { it.isNotBlank() }.take(250)
        if (lines.take(12).none { it in headers }) return PaymentPageResult(null, "未匹配支付结果或交易详情模板")
        val text = lines.joinToString("\n")
        if (Regex("支付失败|付款失败|交易失败|待付款|等待付款|支付中|未支付|退款|退回|转账|充值|提现|还款|收款成功|收入").containsMatchIn(text))
            return PaymentPageResult(null, "本实验仅识别成功支出；其他类型请用通知或账单导入")
        val success = lines.any { it in setOf("支付成功", "付款成功", "交易成功", "交易状态：交易成功", "交易状态:交易成功") } || field(lines, listOf("交易状态")) == "交易成功"
        val outgoing = lines.any { it in setOf("支付成功", "付款成功", "支出") } || lines.any { it.startsWith("支出：") || it.startsWith("支出:") } ||
            lines.any { Regex("^[¥￥]?\\s*-\\s*\\d[\\d,.]*\\s*元?$").matches(it) && moneyValue(it) != null }
        if (!success || !outgoing) return PaymentPageResult(null, "页面未同时提供成功状态与支出依据")
        if (Regex("美元|港币|港元|日元|欧元|USD|HKD|JPY|EUR|\\$").containsMatchIn(text)) return PaymentPageResult(null, "本实验暂只识别人民币支出")
        val explicit = field(lines, paidLabels)?.let(::moneyValue)
        val amounts = lines.mapNotNull(::moneyValue).distinct()
        val amount = explicit ?: field(lines, listOf("金额"))?.let(::moneyValue) ?: amounts.singleOrNull() ?: 0L
        val rawMerchant = field(lines, merchantLabels)
        val merchant = rawMerchant?.takeIf { value -> value.length <= 100 && value !in allLabels && value !in headers && moneyValue(value) == null && !Regex("^\\d{10,}$").matches(value) } ?: "未知商家"
        if (amount == 0L && merchant == "未知商家") return PaymentPageResult(null, "已匹配支付页面，但金额与商家字段尚未读到")
        val timeText = field(lines, listOf("交易时间", "付款时间", "支付时间"))
        val at = timeText?.let { value -> runCatching {
            LocalDateTime.parse(value.replace('/', '-'), DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss").withResolverStyle(ResolverStyle.STRICT)).atZone(ledgerZone).toInstant().toEpochMilli()
        }.getOrNull() }?.takeIf { it in 946_656_000_000L..(observedAt + 86_400_000L) }
        val transaction = field(lines, listOf("微信支付单号", "支付宝交易号", "交易单号", "交易号"))?.takeIf { Regex("[A-Za-z0-9_-]{6,80}").matches(it) }
        val reason = buildList {
            add("支付页面识别实验，请核实后保存")
            if (amount == 0L) add("金额缺失或存在歧义")
            if (merchant == "未知商家") add("商家未读取")
            if (at == null) add("交易时间未读取，暂用识别时间")
        }.joinToString("；")
        val entry = Entry(amountFen = amount, occurredAt = at ?: observedAt, merchant = merchant,
            channel = if (packageName == "com.tencent.mm") Channel.WECHAT else Channel.ALIPAY,
            kind = Kind.EXPENSE, status = Status.PENDING, transactionId = transaction, sources = setOf("页面识别（实验）"),
            raw = "页面提取字段（未保存完整页面）\n金额：${if (amount > 0) money(amount) else "未读取"}\n商家：$merchant\n交易时间：${timeText?.takeIf { at != null } ?: "未读取"}\n交易号：${transaction ?: "未读取"}", reason = reason)
        return PaymentPageResult(entry, "已读取${if (amount > 0) "金额" else "部分字段"}、${if (merchant != "未知商家") "商家" else "商家待补充"}，请到待确认核实")
    }
}

/** Repeated tree updates enrich one visible result; leaving or changing a transaction starts another. */
class PaymentPageSession {
    private var window = ""
    private var previous: Entry? = null
    private var key = ""
    fun reset() { window = ""; previous = null; key = "" }
    fun accept(windowToken: String, incoming: Entry): Entry? {
        val old = previous
        val changedTransaction = old != null && (old.channel != incoming.channel ||
            (old.transactionId != null && incoming.transactionId != null && old.transactionId != incoming.transactionId) ||
            (old.amountFen > 0 && incoming.amountFen > 0 && old.amountFen != incoming.amountFen) ||
            (old.merchant != "未知商家" && incoming.merchant != "未知商家" && old.merchant != incoming.merchant))
        if (window != windowToken || old == null || changedTransaction) {
            window = windowToken; key = "page:" + UUID.randomUUID(); previous = null
        }
        val candidate = incoming.copy(eventKeys = setOf(key), occurredAt = if (incoming.reason.contains("交易时间未读取")) previous?.occurredAt ?: incoming.occurredAt else incoming.occurredAt)
        val existing = previous
        if (existing != null && existing.copy(id = candidate.id, createdAt = candidate.createdAt) == candidate) return null
        previous = candidate
        return candidate
    }
}
