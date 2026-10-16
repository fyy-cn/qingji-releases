package cn.qingji.core

object NotificationParser {
    private val failed = Regex("支付失败|付款失败|交易失败|支付未成功|未支付|待支付|待付款|等待付款|支付中|退款处理中|退款申请|验证码|优惠券|领取红包")
    private val amount = "(?<![\\d.,])((?:\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.\\d{1,2})?)(?![\\d.,])"
    private val moneyAfterAction = Regex("(?:实付|支出|支付|付款|消费|退款|退回|金额|到账|收款)(?:成功|到账|金额|人民币|为|了|[:：\\s])*[¥￥]?\\s*$amount\\s*(?:元)?")
    private val currencyMoney = Regex("[¥￥]\\s*$amount|$amount\\s*元")
    private val actualMoney = Regex("(?:实付|实际支付)\\s*[:：]?\\s*[¥￥]?\\s*$amount")
    fun parse(packageName: String, title: String, text: String, eventKey: String, time: Long): Entry? {
        val channel = when (packageName) {
            "com.tencent.mm" -> Channel.WECHAT
            "com.eg.android.AlipayGphone" -> Channel.ALIPAY
            else -> return null
        }
        // Restrict WeChat to payment service notifications, not ordinary chat messages.
        if (channel == Channel.WECHAT && !Regex("微信支付|微信收款|支付凭证").containsMatchIn(title)) return null
        val raw = "$title\n$text".trim().take(4000)
        if (failed.containsMatchIn(raw)) return null
        val financial = Regex("支付|付款|消费|退款|转账|收款|还款|提现|充值|支出|到账")
        if (!financial.containsMatchIn(text) && !Regex("支付成功|付款成功|消费成功|退款|转账|收款|还款|提现|充值|到账").containsMatchIn(title)) return null
        val transfer = Regex("转账|充值|提现|还款").containsMatchIn(raw)
        val refund = Regex("退款成功|退款到账|已退款|退回.*元").containsMatchIn(raw)
        val income = Regex("收款成功|收款到账|收到.*(?:元|转账)|收入|到账提醒").containsMatchIn(raw) && !refund
        val success = Regex("支付成功|付款成功|消费成功|成功支付|成功付款|支出\\s*[¥￥\\d]|交易成功|退款成功|退款到账|已退款|收款成功|收款到账|转账成功|充值成功|提现成功|还款成功|退回.*元")
            .containsMatchIn(raw)
        val explicit = actualMoney.find(text)
        val actionAmounts = moneyAfterAction.findAll(text).toList()
        val match = explicit ?: actionAmounts.firstOrNull() ?: currencyMoney.find(text)
        val amountString = match?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }
        val fen = amountString?.let(::parseFen) ?: 0L
        val foreign = Regex("美元|港币|港元|日元|欧元|USD|HKD|JPY|EUR", RegexOption.IGNORE_CASE).find(raw)?.value
        val merchant = Regex("(?:商户(?:名称)?|商家|收款方|交易对方|对方)\\s*[:：]\\s*([^\\n，,；;]+)").find(text)?.groupValues?.get(1)?.trim()
            ?: Regex("(?:向|在)\\s*(.{1,50}?)\\s*(?:支付|付款|消费)").find(text)?.groupValues?.get(1)?.trim()
            ?: "未知商家"
        val kind = when { refund -> Kind.REFUND; transfer -> Kind.TRANSFER; income -> Kind.INCOME; success -> Kind.EXPENSE; else -> Kind.UNKNOWN }
        val problems = buildList {
            if (!success) add("未识别到明确的成功交易标记")
            if (fen == 0L) add("缺少可核对的金额")
            if (foreign != null) add("暂不支持外币，请核对币种")
            if (explicit == null && actionAmounts.mapNotNull { it.groupValues.drop(1).firstOrNull { value -> value.isNotEmpty() }?.let(::parseFen) }.distinct().size > 1) add("通知包含多个交易金额，请核对实际金额")
        }
        val transaction = Regex("(?:交易单号|交易号|订单号)\\s*[:：]\\s*([A-Za-z0-9_-]{6,80})").find(text)?.groupValues?.get(1)
        val refundId = Regex("退款单号\\s*[:：]\\s*([A-Za-z0-9_-]{6,80})").find(text)?.groupValues?.get(1)
        return Entry(amountFen = fen, occurredAt = time, merchant = merchant,
            currency = foreign?.let { when (it.uppercase()) { "USD", "美元" -> "USD"; "HKD", "港币", "港元" -> "HKD"; "JPY", "日元" -> "JPY"; else -> "EUR" } } ?: "CNY",
            channel = channel, kind = kind, status = if (problems.isEmpty()) Status.CONFIRMED else Status.PENDING,
            transactionId = if (kind == Kind.REFUND) refundId else transaction,
            relatedTransactionId = if (kind == Kind.REFUND) transaction else null,
            sources = setOf("通知"), eventKeys = setOf(eventKey), raw = raw, reason = problems.joinToString("；"))
    }
}
