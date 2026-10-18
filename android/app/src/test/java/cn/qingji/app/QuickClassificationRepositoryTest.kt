package cn.qingji.app

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cn.qingji.core.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = OfflineTestApplication::class)
class QuickClassificationRepositoryTest {
    private lateinit var db: LedgerDatabase
    private lateinit var repo: LedgerRepository
    private val template = QuickTemplate(id = "canteen", label = "二食堂", category = Category.FOOD, merchant = "二食堂", note = "午餐")
    private fun entry(id: String, status: Status = Status.CONFIRMED) = Entry(id = id, amountFen = 1250, occurredAt = 1792301400000, channel = Channel.CAMPUS, status = status)
    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), LedgerDatabase::class.java).allowMainThreadQueries().build()
        repo = LedgerRepository(db)
    }
    @After fun close() { db.close() }
    @Test fun templateCrudOrderingAndDuplicateLabelsAreStored() = runBlocking {
        repo.saveTemplate(template); repo.saveTemplate(template.copy(id = "market", label = "校内超市", category = Category.SHOPPING))
        assertTrue(runCatching { repo.saveTemplate(template.copy(id = "duplicate")) }.isFailure)
        repo.moveTemplate("market", -1)
        assertEquals(listOf("market", "canteen"), LedgerRepository(db).snapshot().quickTemplates.map { it.id })
        repo.saveTemplate(template.copy(label = "二食堂午饭"))
        assertEquals("二食堂午饭", repo.snapshot().quickTemplates.last().label)
        repo.deleteTemplate("market"); assertEquals(1, repo.snapshot().quickTemplates.size)
    }
    @Test fun batchCategoryPreservesPendingStatusAndAllMoney() = runBlocking {
        val confirmed = entry("a"); val pending = entry("b", Status.PENDING).copy(amountFen = 0, kind = Kind.UNKNOWN, reason = "金额缺失", candidateIds = listOf("a"))
        repo.restore(BackupData(entries = listOf(confirmed, pending), rules = emptyList(), imports = emptyList()))
        repo.classify(setOf("a", "b"), category = Category.FOOD)
        val rows = repo.snapshot().entries.associateBy { it.id }
        assertEquals(confirmed.copy(category = Category.FOOD, editedFields = setOf("category")), rows["a"])
        assertEquals(pending.copy(category = Category.FOOD, editedFields = setOf("category")), rows["b"])
    }
    @Test fun changedBillRollsBackWholeBatchInsteadOfOverwritingNewCategory() = runBlocking {
        repo.restore(BackupData(entries = listOf(entry("a"), entry("b").copy(category = Category.SHOPPING)), rules = emptyList(), imports = emptyList()))
        assertTrue(runCatching { repo.classify(setOf("a", "b"), category = Category.FOOD) }.isFailure)
        assertEquals(Category.UNCATEGORIZED, repo.snapshot().entries.first { it.id == "a" }.category)
        assertTrue(runCatching { repo.classify(setOf("a", "missing"), category = Category.FOOD) }.isFailure)
        assertEquals(Category.UNCATEGORIZED, repo.snapshot().entries.first { it.id == "a" }.category)
    }
    @Test fun templateApplicationFillsOnlyEmptyFieldsAndUsageSurvivesEditing() = runBlocking {
        repo.restore(BackupData(entries = listOf(entry("a"), entry("b").copy(merchant = "实际商家", note = "人工备注")), rules = emptyList(), imports = emptyList()))
        repo.saveTemplate(template); repo.classify(setOf("a", "b"), templateId = template.id)
        val rows = repo.snapshot().entries.associateBy { it.id }
        assertEquals("二食堂", rows["a"]!!.merchant); assertEquals("午餐", rows["a"]!!.note)
        assertEquals("实际商家", rows["b"]!!.merchant); assertEquals("人工备注", rows["b"]!!.note)
        val used = repo.snapshot().quickTemplates.single(); assertEquals(1L, used.useCount); assertTrue(used.lastUsedAt > 0)
        repo.saveTemplate(template.copy(label = "食堂午餐"))
        assertEquals(used.useCount, repo.snapshot().quickTemplates.single().useCount)
        assertEquals(used.lastUsedAt, repo.snapshot().quickTemplates.single().lastUsedAt)
        repo.deleteTemplate(template.id); assertEquals(2, repo.snapshot().entries.size)
    }
    @Test fun categoryRenameAndDeletionUpdateTemplatesAndPreventDeletedCategoryUse() = runBlocking {
        val custom = Category.custom("校园")
        repo.addCategory(custom.label); repo.saveTemplate(template.copy(category = custom))
        repo.renameCategory(custom, "校内")
        assertEquals("校内", repo.snapshot().quickTemplates.single().category.label)
        repo.deleteCategory(Category.custom("校内"))
        assertEquals(Category.UNCATEGORIZED, repo.snapshot().quickTemplates.single().category)
        repo.ingest(entry("a"))
        assertTrue(runCatching { repo.classify(setOf("a"), templateId = template.id) }.isFailure)
        assertEquals(Category.UNCATEGORIZED, repo.snapshot().entries.single().category)
    }
    @Test fun backupRestoresTemplatesAndIntentionalEmptyInventoryWhileOldBackupGetsStarters() = runBlocking {
        repo.saveTemplate(template); val data = repo.snapshot()
        val restored = BackupCodec.decrypt(BackupCodec.encrypt(data, "password123".toCharArray()), "password123".toCharArray())
        repo.deleteTemplate(template.id); repo.restore(restored)
        assertEquals(data.quickTemplates, repo.snapshot().quickTemplates)
        repo.restore(data.copy(quickTemplates = emptyList()))
        assertTrue(repo.snapshot().quickTemplates.isEmpty())
        repo.restore(data.copy(version = 4, quickTemplates = emptyList()))
        assertEquals(6, repo.snapshot().quickTemplates.size)
        repo.deleteCategory(Category.FOOD); repo.addStarterTemplates()
        assertTrue(repo.snapshot().quickTemplates.none { it.category == Category.FOOD })
    }
    @Test fun applyingClassificationDoesNotChangeRulesOrInventMerchantRules() = runBlocking {
        repo.restore(BackupData(entries = listOf(entry("a")), rules = emptyList(), imports = emptyList()))
        repo.saveTemplate(template); repo.classify(setOf("a"), templateId = template.id)
        assertTrue(repo.snapshot().rules.isEmpty())
        val saved = repo.snapshot().entries.single()
        repo.ingest(saved.copy(id = "import", category = Category.OTHER, editedFields = emptySet(), sources = setOf("账单导入"), eventKeys = setOf("file:1"), transactionId = "CAMPUS123456"))
        assertEquals(Category.FOOD, repo.snapshot().entries.first { it.id == "a" }.category)
    }
}
