package cn.qingji.core

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class ReportBackupTests {
    private fun entry(date: String, amount: Long, kind: Kind = Kind.EXPENSE, status: Status = Status.CONFIRMED) = Entry(amountFen = amount, occurredAt = LocalDate.parse(date).atStartOfDay(ledgerZone).toInstant().toEpochMilli(), merchant = "面馆", category = Category.FOOD, channel = Channel.WECHAT, kind = kind, status = status)
    @Test fun monthToDateUsesSameCalendarProgress() {
        val today = LocalDate.parse("2026-10-16")
        val report = Reports.build(listOf(entry("2026-09-16", 100), entry("2026-09-17", 500), entry("2026-10-16", 200)), Period.MONTH, today, today)
        assertEquals(LocalDate.parse("2026-09-17"), report.previous.endExclusive)
        assertEquals(100L, report.previousTotal.net)
        assertEquals(200L, report.total.net)
        assertEquals(100L, report.categories.single().delta)
    }
    @Test fun historicalMonthUsesWholeMonthEvenWhenDifferentLengths() {
        val report = Reports.build(emptyList(), Period.MONTH, LocalDate.parse("2026-02-10"), LocalDate.parse("2026-10-16"))
        assertEquals(LocalDate.parse("2026-03-01"), report.range.endExclusive)
        assertEquals(DateRange(LocalDate.parse("2026-01-01"), LocalDate.parse("2026-02-01")), report.previous)
    }
    @Test fun shortPreviousMonthIsCapped() {
        val date = LocalDate.parse("2024-03-31")
        val report = Reports.build(emptyList(), Period.MONTH, date, date)
        assertEquals(LocalDate.parse("2024-03-01"), report.previous.endExclusive)
    }
    @Test fun weekStartsMondayAndCrossesYear() {
        assertEquals(DateRange(LocalDate.parse("2025-12-29"), LocalDate.parse("2026-01-05")), Reports.fullRange(Period.WEEK, LocalDate.parse("2026-01-01")))
    }
    @Test fun yearToDateAcrossLeapYearUsesSameCalendarDate() {
        val day = LocalDate.parse("2024-10-16")
        val report = Reports.build(emptyList(), Period.YEAR, day, day)
        assertEquals(LocalDate.parse("2023-10-17"), report.previous.endExclusive)
    }
    @Test fun refundsUseRefundDateAndExcludedRecordsDontAffectSpend() {
        val entries = listOf(entry("2026-09-30", 10000), entry("2026-10-01", 2000, Kind.REFUND), entry("2026-10-02", 3000, Kind.REFUND),
            entry("2026-10-02", 50000, Kind.TRANSFER), entry("2026-10-02", 9999, status = Status.PENDING), entry("2026-10-02", 8888, status = Status.IGNORED))
        val report = Reports.build(entries, Period.MONTH, LocalDate.parse("2026-10-16"), LocalDate.parse("2026-10-16"))
        assertEquals(-5000L, report.total.net)
        assertEquals(5000L, report.total.refund)
        assertEquals(0, report.total.count)
    }
    @Test fun merchantRankingOrdersNetSpendBeforeLargeRefunds() {
        val date = LocalDate.parse("2026-10-16")
        val report = Reports.build(listOf(entry("2026-10-16", 2800).copy(merchant = "消费商家"), entry("2026-10-16", 10000, Kind.REFUND).copy(merchant = "退款商家")), Period.DAY, date, date)
        assertEquals(listOf("消费商家", "退款商家"), report.merchants.map { it.key })
    }
    @Test fun backupRoundTripIncludesRulesImportsAndEditedFlags() {
        val data = BackupData(entries = listOf(entry("2026-10-16", 100).copy(note = "中文备注", editedFields = setOf("category"), eventKeys = setOf("n:1"))),
            rules = listOf(MerchantRule("面馆", Category.OTHER)), imports = listOf(ImportReceipt("abc", "账单.csv", 123)))
        val encrypted = BackupCodec.encrypt(data, "test-password".toCharArray())
        assertEquals(data, BackupCodec.decrypt(encrypted, "test-password".toCharArray()))
        assertFalse(String(encrypted, Charsets.ISO_8859_1).contains("test-password"))
    }
    @Test(expected = IllegalArgumentException::class) fun wrongBackupPasswordFails() {
        val bytes = BackupCodec.encrypt(BackupData(entries = emptyList(), rules = emptyList(), imports = emptyList()), "password1".toCharArray())
        BackupCodec.decrypt(bytes, "password2".toCharArray())
    }
    @Test(expected = IllegalArgumentException::class) fun tamperedBackupFailsAuthentication() {
        val bytes = BackupCodec.encrypt(BackupData(entries = emptyList(), rules = emptyList(), imports = emptyList()), "password1".toCharArray())
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        BackupCodec.decrypt(bytes, "password1".toCharArray())
    }
    @Test(expected = IllegalArgumentException::class) fun shortBackupPasswordRejected() {
        BackupCodec.encrypt(BackupData(entries = emptyList(), rules = emptyList(), imports = emptyList()), "short".toCharArray())
    }
    @Test(expected = IllegalArgumentException::class) fun duplicateBackupEntryIdsRejected() {
        val e = entry("2026-10-16", 100)
        BackupCodec.validate(BackupData(entries = listOf(e, e), rules = emptyList(), imports = emptyList()))
    }
}
