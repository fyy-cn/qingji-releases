package cn.qingji.core

data class NotificationParseResult(val entry: Entry?, val code: String, val message: String)

object NotificationParser {
    private val failed = Regex("支付失败|付款失败|交易失败|支付未成功|未支付|待支付|待付款|等待付款|支付中|退款处理中|退款申请|验证码")
    private val amount = "(?<![\\d.,])((?:\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.\\d{1,2})?)(?![\\d.,])"
    private val moneyAfterAction = Regex("(?:实付|支出|支付|付款|消费|扣款|退款|退回|转账|充值|提现|还款|金额|到账|收款)(?:成功|到账|金额|人民币|为|了|[:：\\s])*[¥￥]?\\s*$amount\\s*(?:元)?")
    private val currencyMoney = Regex("[¥￥]\\s*$amount|$amount\\s*元")
    private val actualMoney = Regex("(?:实付|实际支付)\\s*[:：]?\\s*[¥￥]?\\s*$amount")
    fun parse(packageName: String, title: String, text: String, eventKey: String, time: Long, additionalHeaders: List<String> = emptyList(), campusPackage: String? = null): Entry? =
        inspect(packageName, title, text, eventKey, time, additionalHeaders, campusPackage).entry
    fun inspect(packageName: String, title: String, text: String, eventKey: String, time: Long, additionalHeaders: List<String> = emptyList(), campusPackage: String? = null): NotificationParseResult {
        val headers = (listOf(title) + additionalHeaders.take(8)).joinToString("\n") { it.take(500) }
        val channel = PaymentSources.channel(packageName, campusPackage) ?: return NotificationParseResult(null, "UNSUPPORTED_APP", "非支持渠道")
        // Restrict WeChat to payment service notifications, not ordinary chat messages.
        if (channel == Channel.WECHAT && !Regex("微信支付|微信收款|支付凭证").containsMatchIn(headers)) return NotificationParseResult(null, "HEADER_NOT_PAYMENT", "消息头未提供支付服务标识，已按普通消息过滤")
        val raw = "$headers\n$text".trim().take(4000)
        if (failed.containsMatchIn(raw)) return NotificationParseResult(null, "FAILED_OR_PENDING", "失败、未完成交易或验证码，未入账")
        if (channel == Channel.CAMPUS && !Regex("支付成功|消费成功|扣款成功|支出|退款|退回|转账成功|充值成功|提现成功|到账|还款成功").containsMatchIn(raw)) return NotificationParseResult(null, "NOT_PAYMENT", "校园通知未提供交易完成或支出标记")
        val financial = Regex("支付|付款|消费|扣款|退款|转账|收款|还款|提现|充值|支出|到账")
        if (!financial.containsMatchIn(text) && !Regex("支付成功|付款成功|消费成功|扣款成功|退款|转账|收款|还款|提现|充值|到账").containsMatchIn(headers)) return NotificationParseResult(null, "NOT_PAYMENT", "未匹配支付交易内容")
        val transfer = Regex("转账|充值|提现|还款").containsMatchIn(raw)
        val refund = Regex("退款成功|退款到账|已退款|退回.*元").containsMatchIn(raw)
        val income = Regex("收款成功|收款到账|收到.*(?:元|转账)|收入|到账提醒").containsMatchIn(raw) && !refund
        val success = Regex("支付成功|付款成功|消费成功|扣款成功|成功支付|成功付款|支出\\s*[¥￥\\d]|交易成功|退款成功|退款到账|已退款|收款成功|收款到账|转账成功|充值成功|提现成功|还款成功|退回.*元")
            .containsMatchIn(raw)
        val explicit = actualMoney.find(text)
        val actionAmounts = moneyAfterAction.findAll(text).toList()
        val match = explicit ?: actionAmounts.firstOrNull() ?: if (channel == Channel.CAMPUS) null else currencyMoney.find(text)
        val amountString = match?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }
        val fen = amountString?.let(::parseFen) ?: 0L
        if (fen == 0L && Regex("优惠券|领取红包").containsMatchIn(raw)) return NotificationParseResult(null, "PROMOTION", "未提供交易金额的优惠推广，未入账")
        val foreign = Regex("美元|港币|港元|日元|欧元|USD|HKD|JPY|EUR", RegexOption.IGNORE_CASE).find(raw)?.value
        val merchant = Regex("(?:商户(?:名称)?|商家|收款方|交易对方|对方)\\s*[:：]\\s*([^\\n，,；;]+)").find(text)?.groupValues?.get(1)?.trim()
            ?: Regex("(?:向|在)\\s*(.{1,50}?)\\s*(?:支付|付款|消费)").find(text)?.groupValues?.get(1)?.trim()
            ?: "未知商家"
        val kind = when { refund -> Kind.REFUND; transfer -> Kind.TRANSFER; income -> Kind.INCOME; success -> Kind.EXPENSE; else -> Kind.UNKNOWN }
        val problems = buildList {
            if (channel == Channel.CAMPUS) add("校园应用通知，请核实商家、金额和分类后保存")
            if (!success) add("未识别到明确的成功交易标记")
            if (fen == 0L) add("缺少可核对的金额")
            if (Regex("优惠券|领取红包").containsMatchIn(raw) && explicit == null && actionAmounts.isEmpty()) add("包含优惠信息，未能分离明确交易金额，请核实")
            if (foreign != null) add("暂不支持外币，请核对币种")
            if (explicit == null && actionAmounts.mapNotNull { it.groupValues.drop(1).firstOrNull { value -> value.isNotEmpty() }?.let(::parseFen) }.distinct().size > 1) add("通知包含多个交易金额，请核对实际金额")
        }
        val transaction = Regex("(?:交易单号|交易号|订单号|流水号)\\s*[:：]\\s*([A-Za-z0-9_-]{6,80})").find(text)?.groupValues?.get(1)
        val refundId = Regex("退款单号\\s*[:：]\\s*([A-Za-z0-9_-]{6,80})").find(text)?.groupValues?.get(1)
        val entry = Entry(amountFen = fen, occurredAt = time, merchant = merchant,
            currency = foreign?.let { when (it.uppercase()) { "USD", "美元" -> "USD"; "HKD", "港币", "港元" -> "HKD"; "JPY", "日元" -> "JPY"; else -> "EUR" } } ?: "CNY",
            channel = channel, kind = kind, status = if (problems.isEmpty()) Status.CONFIRMED else Status.PENDING,
            transactionId = if (kind == Kind.REFUND) refundId else transaction,
            relatedTransactionId = if (kind == Kind.REFUND) transaction else null,
            sources = setOf("通知"), eventKeys = setOf(eventKey), raw = raw, reason = problems.joinToString("；"))
        return NotificationParseResult(entry, if (entry.status == Status.PENDING) "PENDING" else "PARSED", if (entry.status == Status.PENDING) "已生成待确认记录" else "已识别交易")
    }
}
