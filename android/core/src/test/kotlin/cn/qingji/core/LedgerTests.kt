package cn.qingji.core

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class LedgerTests {
    private val time = LocalDate.of(2026, 10, 16).atStartOfDay(ledgerZone).toInstant().toEpochMilli()
    private fun entry(amount: Long = 2800, merchant: String = "面馆", txn: String? = null, key: String = "a", kind: Kind = Kind.EXPENSE) = Entry(amountFen = amount, occurredAt = time, merchant = merchant, channel = Channel.WECHAT, transactionId = txn, eventKeys = setOf(key), kind = kind)

    @Test fun moneyHasExactCentsAndRejectsRounding() {
        assertEquals(123456L, parseFen("￥1,234.56元"))
        assertEquals(10L, parseFen("0.1"))
        listOf("1.999", "-1", "0", "1e3", "12,34", "NaN", "999999999999999999").forEach { assertNull(it, parseFen(it)) }
        assertEquals("-0.01", money(-1))
    }
    @Test fun notificationRepostsDoNotDuplicate() {
        val first = LedgerEngine.accept(emptyList(), entry(), emptyList())
        val second = LedgerEngine.accept(first.entries, entry(), emptyList())
        assertEquals(1, second.entries.size)
        assertEquals(2800L, second.entries.single().amountFen)
    }
    @Test fun sameTransactionFromDifferentSourcesMergesAndPreservesHumanEdits() {
        val original = entry(txn = "wx123456").copy(category = Category.HEALTH, note = "人工备注", editedFields = setOf("category", "note"))
        val imported = entry(txn = "wx123456", key = "file:1:2").copy(category = Category.FOOD, note = "导入备注", sources = setOf("账单导入"))
        val result = LedgerEngine.accept(listOf(original), imported, emptyList())
        assertEquals(1, result.entries.size)
        assertEquals(Category.HEALTH, result.entry.category)
        assertEquals("人工备注", result.entry.note)
        assertEquals(setOf("a", "file:1:2"), result.entry.eventKeys)
    }
    @Test fun sameAmountSameMerchantWithoutIdsIsReviewableNotDeleted() {
        val result = LedgerEngine.accept(listOf(entry()), entry(key = "b"), emptyList())
        assertEquals(2, result.entries.size)
        assertEquals(Status.PENDING, result.entry.status)
        assertEquals(1, result.entry.candidateIds.size)
    }
    @Test fun twoDistinctTransactionIdsKeepTwoRealPayments() {
        val result = LedgerEngine.accept(listOf(entry(txn = "1")), entry(txn = "2", key = "b"), emptyList())
        assertEquals(2, result.entries.size)
        assertEquals(Status.CONFIRMED, result.entry.status)
    }
    @Test fun equalAmountsAtDifferentMerchantsAreIndependent() {
        val result = LedgerEngine.accept(listOf(entry()), entry(merchant = "药房", key = "b"), emptyList())
        assertEquals(Status.CONFIRMED, result.entry.status)
        assertEquals(Category.HEALTH, result.entry.category)
    }
    @Test fun differingAmountsOnSameIdentityRequireReview() {
        val result = LedgerEngine.accept(listOf(entry(txn = "x")), entry(amount = 5000, txn = "x", key = "b"), emptyList())
        assertEquals(1, result.entries.size)
        assertEquals(Status.PENDING, result.entry.status)
        assertEquals(2800L, result.entry.amountFen)
    }
    @Test fun differentChannelsDoNotMergeTransactionNumbers() {
        val result = LedgerEngine.accept(listOf(entry(txn = "x")), entry(txn = "x", key = "b").copy(channel = Channel.ALIPAY), emptyList())
        assertEquals(2, result.entries.size)
    }
    @Test fun multiplePartialRefundsDoNotMergeByOriginalOrderNumber() {
        val original = entry(txn = "order")
        val refund1 = entry(amount = 500, key = "r1", kind = Kind.REFUND).copy(relatedTransactionId = "order")
        val refund2 = entry(amount = 800, key = "r2", kind = Kind.REFUND).copy(relatedTransactionId = "order")
        val first = LedgerEngine.accept(listOf(original), refund1, emptyList())
        val second = LedgerEngine.accept(first.entries, refund2, emptyList())
        assertEquals(3, second.entries.size)
        assertEquals(1300L, second.entries.filter { it.kind == Kind.REFUND }.sumOf { it.amountFen })
    }
    @Test fun ignoredNotificationStaysIgnoredWhenReposted() {
        val ignored = entry().copy(status = Status.IGNORED)
        assertEquals(Status.IGNORED, LedgerEngine.accept(listOf(ignored), entry(), emptyList()).entry.status)
    }
    @Test fun merchantRuleWinsOverKeywords() {
        assertEquals(Category.OTHER, Categorizer.category("咖啡店", "午餐", listOf(MerchantRule("咖啡店", Category.OTHER))))
        assertEquals(Category.UNCATEGORIZED, Categorizer.category("张三", "", emptyList()))
    }
}
