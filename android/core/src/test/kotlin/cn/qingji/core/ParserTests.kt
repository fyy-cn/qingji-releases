package cn.qingji.core

import org.junit.Assert.*
import org.junit.Test

class ParserTests {
    private fun notification(text: String, title: String = "微信支付", pkg: String = "com.tencent.mm") = NotificationParser.parse(pkg, title, text, "event", 1_790_000_000_000)
    @Test fun paymentExtractsActionAmountInsteadOfBalance() {
        val result = notification("支付成功，商户：小小面馆\n支付28.50元，余额999.00元")!!
        assertEquals(2850L, result.amountFen)
        assertEquals("小小面馆", result.merchant)
        assertEquals(Status.CONFIRMED, result.status)
    }
    @Test fun hiddenOrAmbiguousNotificationWaitsForConfirmation() {
        assertEquals(Status.PENDING, notification("你有一笔支付通知")!!.status)
        assertEquals(0L, notification("支付成功，详细信息已隐藏")!!.amountFen)
    }
    @Test fun actualPaidAmountWinsOverOrderPriceAndAmbiguousAmountsNeedReview() {
        assertEquals(8000L, notification("支付成功，订单金额100.00元，实付80.00元")!!.amountFen)
        assertEquals(Status.CONFIRMED, notification("支付成功，订单金额100.00元，实付80.00元")!!.status)
        assertEquals(Status.PENDING, notification("支付成功，订单金额100.00元，支付80.00元")!!.status)
    }
    @Test fun malformedDecimalCannotBeReadAsItsFractionalSuffix() {
        assertEquals(0L, notification("支付成功，金额1.999元")!!.amountFen)
        assertEquals(Status.PENDING, notification("支付成功，金额1,234.567元")!!.status)
    }
    @Test fun ordinaryChatsAndOtherPackagesAreNotCaptured() {
        assertNull(notification("支付成功28元", title = "朋友"))
        assertNull(notification("支付成功28元", pkg = "other.app"))
        assertNull(notification("服务协议更新，请查阅"))
    }
    @Test fun failedPendingAndMarketingNotificationsAreNotExpenses() {
        listOf("支付失败28元", "待付款28元", "支付验证码123456", "支付成功可领优惠券").forEach { assertNull(notification(it)) }
    }
    @Test fun refundAndTransferAreDifferentFromExpenses() {
        assertEquals(Kind.REFUND, notification("退款成功，退款12.34元")!!.kind)
        assertEquals(1234L, notification("退款成功，退款12.34元")!!.amountFen)
        assertEquals(Kind.TRANSFER, notification("还款成功，金额100.00元")!!.kind)
    }
    @Test fun foreignCurrencyNeverSilentlyCountsAsYuan() {
        val result = notification("支付成功，金额28.00美元")!!
        assertEquals(Status.PENDING, result.status)
        assertEquals("USD", result.currency)
    }
    @Test fun alipaySupportUsesTrustedPackage() {
        val result = notification("你已成功付款￥18.80，商家：便利店", "支付宝", "com.eg.android.AlipayGphone")!!
        assertEquals(1880L, result.amountFen)
        assertEquals(Kind.EXPENSE, result.kind)
    }
    @Test fun csvSupportsQuotedCommasEscapedQuotesAndNewlines() {
        val rows = CsvReader.read("a,b,c\r\n1,\"店,铺\",\"第一行\n第二行\"\r\n2,\"说\"\"好\"\"\",x")
        assertEquals("店,铺", rows[1][1])
        assertEquals("第一行\n第二行", rows[1][2])
        assertEquals("说\"好\"", rows[2][1])
    }
    @Test fun gb18030AndUtf8BomDecode() {
        val text = "交易时间,交易对方,金额(元)"
        assertEquals(text, CsvReader.decode(text.toByteArray(charset("GB18030"))))
        assertEquals(text, CsvReader.decode(("\uFEFF" + text).toByteArray()))
    }
    private val header = "交易时间,交易类型,交易对方,商品,收/支,金额(元),当前状态,交易单号"
    private fun bill(vararg lines: String) = BillParser.parse(CsvReader.read("微信支付账单\n$header\n" + lines.joinToString("\n")), "digest", 1_790_000_000_000)
    @Test fun wxImportSeparatesExpensesTransfersAndFailedPayments() {
        val result = bill(
            "2026-10-16 12:00:00,商户消费,面馆,午饭,支出,28.00,支付成功,wx1",
            "2026-10-16 12:01:00,转账,李四,转账,支出,50.00,转账成功,wx2",
            "2026-10-16 12:02:00,商户消费,面馆,午饭,支出,28.00,支付失败,wx3")
        assertEquals(2, result.entries.size)
        assertEquals(Kind.EXPENSE, result.entries[0].kind)
        assertEquals(Kind.TRANSFER, result.entries[1].kind)
        assertEquals(Status.CONFIRMED, result.entries[0].status)
    }
    @Test fun alipayHeaderAliasesParseAndRetainProduct() {
        val rows = CsvReader.read("支付宝交易记录明细查询\n交易创建时间,交易分类,交易对方,商品说明,收/支,金额（元）,交易状态,交易订单号\n2026-10-16 12:00:00,餐饮,咖啡馆,拿铁,支出,19.90,交易成功,ali100")
        val result = BillParser.parse(rows, "d", 1_790_000_000_000)
        assertEquals(Channel.ALIPAY, result.channel)
        assertEquals(1990L, result.entries.single().amountFen)
        assertEquals("拿铁", result.entries.single().note)
    }
    @Test fun refundStatusWithoutDateDoesNotFabricateARefund() {
        val result = bill("2026-10-01 12:00:00,商户消费,商家,商品,支出,100.00,已全额退款,wx1")
        assertEquals(Kind.EXPENSE, result.entries.single().kind)
        assertEquals(Status.PENDING, result.entries.single().status)
    }
    @Test fun refundRowsDontUseOriginalOrderAsRefundIdentity() {
        val result = bill("2026-10-16 12:00:00,退款,商家,退款,收入,10.00,退款成功,wx-original")
        assertEquals(Kind.REFUND, result.entries.single().kind)
        assertNull(result.entries.single().transactionId)
        assertEquals("wx-original", result.entries.single().relatedTransactionId)
    }
    @Test fun malformedRowsAreReportedInsteadOfPartlyCoerced() {
        val result = bill("2026-10-16 12:00:00,商户消费,商家,商品,支出,1.999,支付成功,wx1")
        assertEquals(0, result.entries.size)
        assertEquals(1, result.issues.size)
    }
    @Test fun impossibleCalendarDateIsNotSilentlyNormalized() {
        val result = bill("2026-02-30 12:00:00,商户消费,商家,商品,支出,10.00,支付成功,wx1")
        assertEquals(0, result.entries.size)
        assertEquals(1, result.issues.size)
    }
    @Test(expected = IllegalArgumentException::class) fun missingDirectionFailsSafely() {
        BillParser.parse(listOf(listOf("交易时间", "交易对方", "金额(元)", "交易单号")), "x", 0)
    }
}
