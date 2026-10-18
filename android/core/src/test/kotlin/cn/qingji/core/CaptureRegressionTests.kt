package cn.qingji.core

import org.junit.Assert.*
import org.junit.Test

class CaptureRegressionTests {
    private val campus = "edu.example.campus"
    private val now = 1792301400000L
    @Test fun wechatPaymentSenderInSubtitleIsAcceptedButOrdinaryChatBodyIsNot() {
        val receipt = NotificationParser.inspect("com.tencent.mm", "微信", "支付成功28.50元，商户：测试食堂", "n:1", now, listOf("微信支付"))
        assertEquals("PARSED", receipt.code); assertEquals(2850L, receipt.entry!!.amountFen)
        val chat = NotificationParser.inspect("com.tencent.mm", "同学", "微信支付成功28.50元", "n:2", now)
        assertEquals("HEADER_NOT_PAYMENT", chat.code); assertNull(chat.entry)
    }
    @Test fun successfulReceiptContainingCouponPromotionStillRecordsActualPayment() {
        val entry = NotificationParser.parse("com.tencent.mm", "微信支付", "支付成功28.50元，商户：测试店。领取优惠券", "n:3", now)!!
        assertEquals(Status.CONFIRMED, entry.status); assertEquals(2850L, entry.amountFen)
        assertNull(NotificationParser.parse("com.tencent.mm", "微信支付", "支付优惠券，点击领取红包", "n:4", now))
        assertEquals(Status.PENDING, NotificationParser.parse("com.tencent.mm", "微信支付", "支付成功即可领取20元优惠券", "n:5", now)!!.status)
    }
    @Test fun campusIsExplicitlySelectedAndNewConsumptionAlwaysWaitsForReview() {
        val text = "消费成功12.50元，商户：测试食堂，余额88.00元，流水号：CAMPUS123456"
        assertNull(NotificationParser.parse(campus, "交我办", text, "c:1", now))
        assertNull(NotificationParser.parse("edu.other.app", "交我办", text, "c:1", now, campusPackage = campus))
        val entry = NotificationParser.parse(campus, "交我办", text, "c:1", now, campusPackage = campus)!!
        assertEquals(Channel.CAMPUS, entry.channel); assertEquals(1250L, entry.amountFen)
        assertEquals(Status.PENDING, entry.status); assertEquals("CAMPUS123456", entry.transactionId)
        assertEquals(800L, NotificationParser.parse(campus, "交我办", "扣款成功8.00元", "c:6", now, campusPackage = campus)!!.amountFen)
    }
    @Test fun schoolBalanceAndAnnouncementsAreExcludedAndRechargeIsTransfer() {
        assertNull(NotificationParser.parse(campus, "交我办", "校园卡余额不足20.00元，请及时充值", "c:2", now, campusPackage = campus))
        assertNull(NotificationParser.parse(campus, "交我办", "食堂消费指南：套餐12元", "c:3", now, campusPackage = campus))
        val recharge = NotificationParser.parse(campus, "交我办", "充值成功50.00元，余额88.00元", "c:4", now, campusPackage = campus)!!
        assertEquals(5000L, recharge.amountFen); assertEquals(Kind.TRANSFER, recharge.kind)
        val missing = NotificationParser.parse(campus, "交我办", "消费成功，余额88.00元", "c:5", now, campusPackage = campus)!!
        assertEquals(0L, missing.amountFen); assertEquals(Status.PENDING, missing.status)
    }
    @Test fun reportedWeChatWebViewReachesParserWithoutAcceptingOrdinaryWebContent() {
        assertTrue(PaymentPageParser.isPaymentWindow("com.tencent.mm", "com.tencent.mm.plugin.webview.ui.tools.MMWebViewUI"))
        assertNull(PaymentPageParser.parse("com.tencent.mm", listOf("公众号文章", "餐饮推荐", "28.50元"), now).entry)
    }
    @Test fun campusDetailUsesConsumptionFieldAndNeverTreatsBalanceAsAmount() {
        val lines = listOf("消费详情", "交易类型", "消费", "消费地点", "测试一食堂", "消费金额", "12.50", "余额", "88.00", "流水号", "CAMPUS123456")
        assertNull(PaymentPageParser.parse(campus, lines, now).entry)
        val entry = PaymentPageParser.parse(campus, lines, now, campus).entry!!
        assertEquals(Channel.CAMPUS, entry.channel); assertEquals(1250L, entry.amountFen)
        assertEquals("测试一食堂", entry.merchant); assertTrue(entry.reason.contains("未读取到明确成功状态"))
        val noAmount = PaymentPageParser.parse(campus, listOf("消费成功", "消费地点：测试食堂", "余额", "88.00"), now, campus).entry!!
        assertEquals(0L, noAmount.amountFen)
        assertNull(PaymentPageParser.parse(campus, lines + "充值成功", now, campus).entry)
        assertNull(PaymentPageParser.parse(campus, listOf("校园卡", "余额", "88.00"), now, campus).entry)
    }
    @Test fun backupRoundTripPreservesCampusChannelAndStillReadsVersionThree() {
        val entry = Entry(amountFen = 1250, occurredAt = now, merchant = "测试食堂", channel = Channel.CAMPUS, status = Status.PENDING)
        val data = BackupData(entries = listOf(entry), rules = emptyList(), imports = emptyList())
        assertEquals(4, data.version)
        assertEquals(data, BackupCodec.decrypt(BackupCodec.encrypt(data, "password123".toCharArray()), "password123".toCharArray()))
        val legacy = data.copy(version = 3, entries = listOf(entry.copy(channel = Channel.WECHAT)))
        assertEquals(legacy, BackupCodec.decrypt(BackupCodec.encrypt(legacy, "password123".toCharArray()), "password123".toCharArray()))
    }
}
