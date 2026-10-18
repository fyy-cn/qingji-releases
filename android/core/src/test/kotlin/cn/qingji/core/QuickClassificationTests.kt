package cn.qingji.core

import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class QuickClassificationTests {
    private val at = LocalDate.parse("2026-10-18").atStartOfDay(ledgerZone).toInstant().toEpochMilli()
    private fun entry() = Entry(id = "bill", amountFen = 1250, occurredAt = at, channel = Channel.CAMPUS, sources = setOf("通知"), eventKeys = setOf("n:1"), transactionId = "CAMPUS123456")
    private val template = QuickTemplate(id = "canteen", label = "二食堂", category = Category.FOOD, merchant = "二食堂", note = "午餐")
    @Test fun categoryOnlyLeavesAllFinancialFieldsAndMerchantUntouched() {
        val old = entry()
        assertEquals(old.copy(category = Category.FOOD, editedFields = setOf("category")), QuickClassification.apply(old, Category.FOOD))
    }
    @Test fun templateFillsMissingMerchantAndNoteAndMarksHumanChoices() {
        val old = entry()
        val next = QuickClassification.apply(old, template.category, template)
        assertEquals(old.copy(category = Category.FOOD, merchant = "二食堂", note = "午餐", editedFields = setOf("category", "merchant", "note")), next)
    }
    @Test fun templatePreservesProvidedMerchantAndHumanNote() {
        val old = entry().copy(merchant = "已有真实商家", note = "人工备注", editedFields = setOf("note"))
        val next = QuickClassification.apply(old, template.category, template)
        assertEquals(old.copy(category = Category.FOOD, editedFields = setOf("note", "category")), next)
    }
    @Test fun categorySelectionNeverApprovesMissingAmountsUnknownTypesOrSuspectedDuplicates() {
        listOf(entry().copy(status = Status.PENDING, amountFen = 0, kind = Kind.UNKNOWN, reason = "金额缺失"),
            entry().copy(status = Status.PENDING, candidateIds = listOf("other"), reason = "疑似重复"),
            entry().copy(status = Status.PENDING, currency = "USD", reason = "币种需核实")).forEach { old ->
            val next = QuickClassification.apply(old, template.category, template)
            assertEquals(old.status, next.status); assertEquals(old.reason, next.reason); assertEquals(old.candidateIds, next.candidateIds)
            assertEquals(old.amountFen, next.amountFen); assertEquals(old.kind, next.kind); assertEquals(old.currency, next.currency)
        }
    }
    @Test fun blankTemplateMerchantDoesNotInventOneFromSceneName() {
        val scene = template.copy(merchant = "")
        assertEquals("未知商家", QuickClassification.apply(entry(), scene.category, scene).merchant)
    }
    @Test fun refundsKeepTypeAndSignAndClassificationDoesNotChangeTotals() {
        val rows = listOf(entry(), entry().copy(id = "refund", amountFen = 200, kind = Kind.REFUND), entry().copy(id = "pending", status = Status.PENDING))
        val range = Reports.fullRange(Period.DAY, LocalDate.parse("2026-10-18"))
        val after = rows.map { QuickClassification.apply(it, Category.FOOD) }
        assertEquals(Reports.total(rows, range), Reports.total(after, range))
        assertEquals(Kind.REFUND, after[1].kind)
    }
    @Test fun classifiedIgnoredTransfersAndIncomeCannotEnterQuickInbox() {
        listOf(entry().copy(category = Category.FOOD), entry().copy(status = Status.IGNORED), entry().copy(kind = Kind.TRANSFER), entry().copy(kind = Kind.INCOME)).forEach {
            assertFalse(QuickClassification.needsCategory(it))
            assertThrows(IllegalArgumentException::class.java) { QuickClassification.apply(it, Category.FOOD) }
        }
    }
    @Test fun humanClassificationSurvivesLaterImportAndNotificationUpdates() {
        val edited = QuickClassification.apply(entry(), Category.TRANSPORT)
        val duplicate = entry().copy(id = "file", merchant = "测试面馆", sources = setOf("账单导入"), eventKeys = setOf("file:1"))
        val merged = LedgerEngine.accept(listOf(edited), duplicate, emptyList())
        assertEquals(1, merged.entries.size); assertEquals(Category.TRANSPORT, merged.entry.category)
    }
    @Test fun templatesAndUsageRoundTripInEncryptedBackupFive() {
        val t = template.copy(category = Category.custom("校园"), lastUsedAt = at, useCount = 3, position = 2)
        val backup = BackupData(entries = listOf(entry()), rules = emptyList(), imports = emptyList(), quickTemplates = listOf(t))
        assertEquals(5, backup.version)
        assertEquals(backup, BackupCodec.decrypt(BackupCodec.encrypt(backup, "password123".toCharArray()), "password123".toCharArray()))
    }
    @Test fun oldFormatFourWithoutTemplatesStillLoads() {
        val oldJson = """{"version":4,"entries":[],"rules":[],"imports":[],"exportedAt":1000}"""
        val old = ledgerJson.decodeFromString<BackupData>(oldJson)
        BackupCodec.validate(old); assertEquals(4, old.version); assertTrue(old.quickTemplates.isEmpty())
    }
    @Test fun malformedDuplicateAndOldVersionTemplateBackupsAreRejected() {
        val backup = BackupData(entries = emptyList(), rules = emptyList(), imports = emptyList(), quickTemplates = listOf(template))
        listOf(backup.copy(quickTemplates = listOf(template, template)), backup.copy(version = 4),
            backup.copy(quickTemplates = listOf(template.copy(label = "bad\nname"))),
            backup.copy(quickTemplates = listOf(template.copy(useCount = -1))),
            backup.copy(quickTemplates = listOf(template.copy(position = -1)))).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) { BackupCodec.validate(invalid) }
        }
    }
    @Test fun starterScenesHaveNoInventedMerchantsAmountsOrHistory() {
        val starters = QuickClassification.starters()
        assertEquals(6, starters.size); assertEquals(6, starters.map { it.id }.toSet().size)
        assertTrue(starters.all { it.merchant.isEmpty() && it.lastUsedAt == 0L && it.useCount == 0L })
        assertEquals((0..5).toList(), starters.map { it.position })
    }
}
