package cn.qingji.core

import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.time.LocalDate

class CategoryCsvTests {
    private fun entry() = Entry(amountFen = 2850, occurredAt = LocalDate.parse("2026-10-16").atStartOfDay(ledgerZone).toInstant().toEpochMilli(), merchant = "测试商家", channel = Channel.WECHAT)
    @Test fun builtInCategorySerializationRemainsCompatibleWithOldLedgerJson() {
        assertEquals("\"FOOD\"", ledgerJson.encodeToString(Category.FOOD))
        assertEquals(Category.FOOD, ledgerJson.decodeFromString<Category>("\"FOOD\""))
        val original = entry().copy(category = Category.FOOD)
        assertEquals(original, ledgerJson.decodeFromString<Entry>(ledgerJson.encodeToString(original)))
    }
    @Test fun customCategoryAndUnusedDefinitionsSurviveEncryptedBackup() {
        val study = Category.custom(" 学习 "); val pet = Category.custom("宠物")
        val data = BackupData(entries = listOf(entry().copy(category = study)), rules = listOf(MerchantRule("测试商家", study)), imports = emptyList(), customCategories = listOf(study, pet))
        assertEquals(data, BackupCodec.decrypt(BackupCodec.encrypt(data, "password1".toCharArray()), "password1".toCharArray()))
    }
    @Test fun versionOneBackupWithoutCustomCategoryFieldStillLoads() {
        val legacy = """{"version":1,"entries":[],"rules":[{"merchant":"面馆","category":"FOOD"}],"imports":[],"exportedAt":1000}"""
        val data = ledgerJson.decodeFromString<BackupData>(legacy)
        BackupCodec.validate(data)
        assertEquals(Category.FOOD, data.rules.single().category)
        assertTrue(data.customCategories.isEmpty())
    }
    @Test fun customCategoryRulesAndReportsKeepUserChosenCategory() {
        val custom = Category.custom("学习")
        val incoming = entry()
        val result = LedgerEngine.accept(emptyList(), incoming, listOf(MerchantRule(incoming.merchant, custom))).entry
        assertEquals(custom, result.category)
        val report = Reports.build(listOf(result), Period.MONTH, LocalDate.parse("2026-10-16"), LocalDate.parse("2026-10-16"))
        assertEquals(2850L, report.categories.single().value)
        assertEquals("学习", report.categories.single().key)
    }
    @Test fun rejectsReservedEmptyControlAndDuplicateBackupCategoryNames() {
        listOf("", "餐饮", "a\nb", "a".repeat(21)).forEach { name -> assertThrows(IllegalArgumentException::class.java) { Category.custom(name) } }
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.validate(BackupData(entries = emptyList(), rules = emptyList(), imports = emptyList(), customCategories = listOf(Category.custom("Coffee"), Category.custom("coffee")))) }
    }
    @Test fun csvContainsUtf8BomChineseCustomCategoryExactAmountAndBeijingTime() {
        val output = ByteArrayOutputStream()
        LedgerCsv.write(listOf(entry().copy(category = Category.custom("学习"))), output)
        assertArrayEquals(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()), output.toByteArray().take(3).toByteArray())
        val csv = output.toString("UTF-8")
        assertTrue(csv.contains("交易时间（北京时间）")); assertTrue(csv.contains("2026-10-16 00:00:00"))
        assertTrue(csv.contains("\"学习\"")); assertTrue(csv.contains("\"28.50\"")); assertTrue(csv.contains("\"已确认\""))
    }
    @Test fun csvPreservesCommasQuotesNewlinesRefundAndStatus() {
        val output = ByteArrayOutputStream()
        LedgerCsv.write(listOf(entry().copy(merchant = "面馆,\"分店\"", note = "第一行\n第二行", kind = Kind.REFUND, status = Status.PENDING)), output)
        val csv = output.toString("UTF-8")
        assertTrue(csv.contains("\"面馆,\"\"分店\"\"\"")); assertTrue(csv.contains("\"第一行\n第二行\""))
        assertTrue(csv.contains("\"退款\"")); assertTrue(csv.contains("\"待确认\""))
    }
    @Test fun csvNeutralizesSpreadsheetFormulasAndPreservesLongTransactionIds() {
        val output = ByteArrayOutputStream()
        LedgerCsv.write(listOf(entry().copy(merchant = "=HYPERLINK(\"https://example.com\")", note = "  +1+1", transactionId = "20261016000000000001")), output)
        val csv = output.toString("UTF-8")
        assertTrue(csv.contains("\"'=HYPERLINK")); assertTrue(csv.contains("\"'  +1+1\"")); assertTrue(csv.contains("\"'20261016000000000001\""))
    }
}
