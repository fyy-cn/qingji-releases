package cn.qingji.core

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDateTime

class PaymentPageTests {
    private val now = LocalDateTime.parse("2026-10-16T13:00:00").atZone(ledgerZone).toInstant().toEpochMilli()
    private val wx = "com.tencent.mm"
    private val ali = "com.eg.android.AlipayGphone"
    private fun page(vararg extra: String) = listOf("支付成功", "商户名称", "测试咖啡店", "实付金额", "￥28.50") + extra
    @Test fun extractsWeChatSeparateLabelsAndKeepsAllNewRecordsPending() {
        val entry = PaymentPageParser.parse(wx, page("交易时间", "2026-10-16 12:30:00", "微信支付单号", "420000202610160001"), now).entry!!
        assertEquals("测试咖啡店", entry.merchant); assertEquals(2850L, entry.amountFen)
        assertEquals("420000202610160001", entry.transactionId); assertEquals(Status.PENDING, entry.status)
        assertEquals(now - 1_800_000, entry.occurredAt); assertEquals(Kind.EXPENSE, entry.kind)
        assertEquals(Category.FOOD, LedgerEngine.accept(emptyList(), entry, emptyList()).entry.category)
        assertEquals(0L, Reports.total(listOf(entry), Reports.fullRange(Period.DAY, java.time.LocalDate.parse("2026-10-16"))).expense)
    }
    @Test fun extractsAlipayOutgoingDetailAndInlineFields() {
        val entry = PaymentPageParser.parse(ali, listOf("账单详情", "交易状态：交易成功", "收款方：测试超市", "-55.20", "支付宝交易号 20261016000001"), now).entry!!
        assertEquals(Channel.ALIPAY, entry.channel); assertEquals(5520L, entry.amountFen); assertEquals("测试超市", entry.merchant)
        assertEquals("20261016000001", entry.transactionId)
    }
    @Test fun missingMerchantIsFlaggedWithoutInventingOne() {
        val entry = PaymentPageParser.parse(wx, listOf("支付成功", "￥12.00"), now).entry!!
        assertEquals("未知商家", entry.merchant); assertTrue(entry.reason.contains("商家未读取")); assertEquals(Status.PENDING, entry.status)
    }
    @Test fun competingAmountsRemainUnresolvedUnlessAnExplicitPaidFieldExists() {
        val base = listOf("支付成功", "商户名称：测试店", "￥30.00", "￥28.50")
        assertEquals(0L, PaymentPageParser.parse(wx, base, now).entry!!.amountFen)
        assertEquals(2850L, PaymentPageParser.parse(wx, base + listOf("实付金额", "¥", "28.50"), now).entry!!.amountFen)
    }
    @Test fun ignoresFailedPendingRefundTransferIncomeAndForeignCurrency() {
        listOf("支付失败", "待付款", "退款成功", "转账", "充值", "提现", "还款", "收入", "USD", "港币").forEach { text ->
            assertNull(text, PaymentPageParser.parse(wx, page(text), now).entry)
        }
    }
    @Test fun ignoresOrdinaryChatsUnsupportedAppsAndNonOutgoingDetails() {
        assertNull(PaymentPageParser.parse(wx, listOf("今天支付成功了", "￥28.50"), now).entry)
        assertNull(PaymentPageParser.parse("other.app", page(), now).entry)
        assertNull(PaymentPageParser.parse(ali, listOf("交易详情", "交易成功", "商家：测试店", "￥28.50"), now).entry)
    }
    @Test fun savedEvidenceExcludesUnrelatedPageTextAndPaymentInstruments() {
        val entry = PaymentPageParser.parse(wx, page("银行卡6222000012345678", "私人聊天内容不能保存", "推荐商品秘密文本"), now).entry!!
        assertFalse(entry.raw.contains("62220000")); assertFalse(entry.raw.contains("私人聊天")); assertFalse(entry.raw.contains("秘密文本"))
        assertTrue(entry.raw.contains("测试咖啡店")); assertTrue(entry.raw.contains("28.50"))
    }
    @Test fun merchantOrderNumberIsNotUsedAsChannelTransactionId() {
        assertNull(PaymentPageParser.parse(wx, page("商户订单号", "merchant_order_123"), now).entry!!.transactionId)
    }
    @Test fun sessionDeduplicatesUpdatesAndEnrichesMissingMerchantWithSameEventKey() {
        val session = PaymentPageSession()
        val first = PaymentPageParser.parse(wx, listOf("支付成功", "￥28.50"), now).entry!!
        val one = session.accept("window1", first)!!
        assertNull(session.accept("window1", first.copy(id = "new", createdAt = now + 1, occurredAt = now + 10_000)))
        val full = session.accept("window1", PaymentPageParser.parse(wx, page(), now + 20_000).entry!!)!!
        assertEquals(one.eventKeys, full.eventKeys); assertEquals(now, full.occurredAt)
        val merged = LedgerEngine.accept(listOf(one), full, emptyList()).entry
        assertEquals("测试咖啡店", merged.merchant); assertEquals(Status.PENDING, merged.status)
        assertFalse(merged.reason.contains("商家未读取"))
    }
    @Test fun leavingResultAndDifferentTransactionIdsKeepTwoEqualPaymentsSeparate() {
        val session = PaymentPageSession()
        val original = PaymentPageParser.parse(wx, page("交易单号", "wx_order_0001"), now).entry!!
        val one = session.accept("window", original)!!
        val two = session.accept("window", original.copy(id = "second", transactionId = "wx_order_0002"))!!
        assertNotEquals(one.eventKeys, two.eventKeys)
        assertEquals(2, LedgerEngine.accept(listOf(one), two, emptyList()).entries.size)
        session.reset()
        assertNotEquals(two.eventKeys, session.accept("window", original)!!.eventKeys)
    }
    @Test fun pageWithChannelIdEnrichesNotificationAndPreservesHumanEdits() {
        val page = PaymentPageParser.parse(wx, page("交易单号", "wx_order_0001"), now).entry!!
        val old = page.copy(id = "notification", merchant = "未知商家", category = Category.custom("咖啡"), note = "人工备注", editedFields = setOf("category", "note"), sources = setOf("通知"), eventKeys = setOf("notification:1"), status = Status.CONFIRMED)
        val result = LedgerEngine.accept(listOf(old), page, emptyList())
        assertEquals(1, result.entries.size); assertEquals("测试咖啡店", result.entry.merchant)
        assertEquals(Category.custom("咖啡"), result.entry.category); assertEquals("人工备注", result.entry.note)
    }
    @Test fun onlyKnownPaymentWindowsAreEligibleForTreeReading() {
        assertTrue(PaymentPageParser.isPaymentWindow(wx, "com.tencent.mm.plugin.wallet.pay.ui.WalletPayUI"))
        assertTrue(PaymentPageParser.isPaymentWindow(ali, "com.alipay.mobile.bill.detail.BillDetailActivity"))
        assertFalse(PaymentPageParser.isPaymentWindow(wx, "com.tencent.mm.ui.LauncherUI"))
        assertFalse(PaymentPageParser.isPaymentWindow(ali, "com.alipay.mobile.chat.ChatActivity"))
        assertFalse(PaymentPageParser.isPaymentWindow("other", "com.alipay.mobile.pay.PayActivity"))
    }
}
