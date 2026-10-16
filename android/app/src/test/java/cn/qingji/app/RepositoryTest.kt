package cn.qingji.app

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cn.qingji.core.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RepositoryTest {
    private lateinit var db: LedgerDatabase
    private lateinit var repo: LedgerRepository
    private val event = Entry(amountFen = 2800, occurredAt = 1_790_000_000_000, merchant = "面馆", channel = Channel.WECHAT, eventKeys = setOf("notification:test"), sources = setOf("通知"))
    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), LedgerDatabase::class.java).allowMainThreadQueries().build()
        repo = LedgerRepository(db)
    }
    @After fun close() { db.close() }
    @Test fun orderingSurvivesRepositoryRestartRenameAndEncryptedRestore() = runBlocking {
        val study = Category.custom("学习")
        repo.addCategory(study.label)
        repeat(8) { repo.moveCategory(study, -1) }
        repo.renameCategory(study, "进修")
        val backup = repo.snapshot()
        val expected = CategoryLayout.ordered(backup.customCategories, backup.categoryPreferences)
        assertEquals(Category.custom("进修"), expected.first())
        assertEquals(expected, LedgerRepository(db).snapshot().let { CategoryLayout.ordered(it.customCategories, it.categoryPreferences) })
        repo.deleteCategory(Category.custom("进修"))
        repo.restore(BackupCodec.decrypt(BackupCodec.encrypt(backup, "password1".toCharArray()), "password1".toCharArray()))
        assertEquals(expected, repo.snapshot().let { CategoryLayout.ordered(it.customCategories, it.categoryPreferences) })
    }
    @Test fun builtInDeletionStopsNotificationAndImportRecategorizingIntoDeletedCategory() = runBlocking {
        repo.save(event.copy(category = Category.FOOD), true)
        repo.deleteCategory(Category.FOOD)
        assertTrue(repo.snapshot().rules.isEmpty())
        repo.ingest(event.copy(id = "next", occurredAt = event.occurredAt + 600_000, eventKeys = setOf("notification:next")))
        val bill = ParsedBill(Channel.WECHAT, listOf(event.copy(id = "file", occurredAt = event.occurredAt + 1_200_000, eventKeys = setOf("file:d:2"))), emptyList(), 1)
        val preview = repo.preview("d", "账单.csv", bill)
        assertEquals(Category.UNCATEGORIZED, preview.items.single().entry.category)
        repo.commit(preview)
        assertTrue(repo.snapshot().entries.all { it.category == Category.UNCATEGORIZED && it.amountFen == 2800L })
        repo.restoreBuiltInCategories()
        assertTrue(repo.snapshot().categoryPreferences.none { it.hidden })
        assertTrue(repo.snapshot().entries.all { it.category == Category.UNCATEGORIZED })
        repo.ingest(event.copy(id = "after-restore", occurredAt = event.occurredAt + 1_800_000, eventKeys = setOf("notification:restored")))
        assertEquals(Category.FOOD, repo.snapshot().entries.single { it.id == "after-restore" }.category)
    }
    @Test fun deletedCategoryAndSortSurviveBackupWhileUncategorizedIsReserved() = runBlocking {
        repo.moveCategory(Category.TRANSPORT, -1)
        repo.deleteCategory(Category.SHOPPING)
        val before = repo.snapshot()
        assertEquals(Category.TRANSPORT, CategoryLayout.ordered(before.customCategories, before.categoryPreferences).first())
        repo.restoreBuiltInCategories(); repo.restore(before)
        assertEquals(before.categoryPreferences, repo.snapshot().categoryPreferences)
        assertTrue(runCatching { repo.deleteCategory(Category.UNCATEGORIZED) }.isFailure)
        assertEquals(before.categoryPreferences, repo.snapshot().categoryPreferences)
    }
    @Test fun customCategoryRenameUpdatesBillsRulesAndFutureNotificationClassification() = runBlocking {
        val custom = Category.custom("学习")
        repo.save(event.copy(category = custom, editedFields = setOf("category")), true)
        repo.renameCategory(custom, "进修")
        val data = repo.snapshot()
        assertEquals("进修", data.entries.single().category.label)
        assertEquals("进修", data.rules.single().category.label)
        assertEquals(listOf(Category.custom("进修")), data.customCategories)
        repo.ingest(event.copy(id = "future", occurredAt = event.occurredAt + 600_000, eventKeys = setOf("notification:future")))
        assertTrue(repo.snapshot().entries.all { it.category.label == "进修" })
    }
    @Test fun deletingCustomCategoryRetainsBillsAndAmountsAndRemovesOnlyItsRules() = runBlocking {
        val custom = Category.custom("宠物")
        repo.save(event.copy(category = custom), true)
        repo.deleteCategory(custom)
        val data = repo.snapshot()
        assertEquals(2800L, data.entries.single().amountFen)
        assertEquals(Category.UNCATEGORIZED, data.entries.single().category)
        assertTrue(data.rules.isEmpty()); assertTrue(data.customCategories.isEmpty())
    }
    @Test fun unusedCustomCategoriesRestoreAndDuplicateNamesAreRejected() = runBlocking {
        repo.addCategory("Coffee")
        assertTrue(runCatching { repo.addCategory("coffee") }.isFailure)
        val backup = repo.snapshot()
        repo.deleteCategory(Category.custom("Coffee")); repo.restore(backup)
        assertEquals(listOf(Category.custom("Coffee")), repo.snapshot().customCategories)
    }
    @Test fun listenerRacesAreSerializedAndIdempotent() = runBlocking {
        coroutineScope { (1..10).map { async { repo.ingest(event.copy(id = "race:$it")) } }.awaitAll() }
        assertEquals(1, repo.snapshot().entries.size)
    }
    @Test fun importingSameFileTwiceCannotDoubleCount() = runBlocking {
        val bill = ParsedBill(Channel.WECHAT, listOf(event.copy(transactionId = "wx1", eventKeys = setOf("file:d:2"))), emptyList(), 1)
        val preview = repo.preview("digest", "账单.csv", bill)
        assertEquals(1, preview.newCount)
        repo.commit(preview)
        assertTrue(repo.preview("digest", "账单.csv", bill).alreadyImported)
        assertTrue(runCatching { repo.commit(preview) }.isFailure)
        assertEquals(1, repo.snapshot().entries.size)
    }
    @Test fun notificationAndImportCanBeReviewedMergedWithoutLosingUserEdits() = runBlocking {
        repo.ingest(event)
        val original = repo.snapshot().entries.single().copy(category = Category.OTHER, note = "人工备注", editedFields = setOf("category", "note"))
        repo.save(original, true)
        repo.ingest(event.copy(id = "import", transactionId = "wx1", eventKeys = setOf("file:d:2"), sources = setOf("账单导入")))
        assertEquals(2, repo.snapshot().entries.size)
        val pending = repo.snapshot().entries.first { it.status == Status.PENDING }
        repo.merge(pending.id, original.id)
        val snapshot = repo.snapshot()
        assertEquals(1, snapshot.entries.size)
        assertEquals("wx1", snapshot.entries.single().transactionId)
        assertEquals("人工备注", snapshot.entries.single().note)
        assertEquals(Category.OTHER, snapshot.entries.single().category)
        assertEquals(1, snapshot.rules.size)
        assertEquals(2, snapshot.entries.single().eventKeys.size)
    }
    @Test fun restoreReplacesAllThreeTablesAndFailedValidationLeavesThemIntact() = runBlocking {
        repo.ingest(event)
        val newEntry = event.copy(id = "restored")
        val backup = BackupData(entries = listOf(newEntry), rules = listOf(MerchantRule("面馆", Category.OTHER)), imports = listOf(ImportReceipt("restored-file", "账单", 1)))
        repo.restore(backup)
        assertEquals(backup.entries, repo.snapshot().entries)
        assertEquals(backup.rules, repo.snapshot().rules)
        assertEquals(backup.imports, repo.snapshot().imports)
        assertTrue(runCatching { repo.restore(backup.copy(version = 999)) }.isFailure)
        assertEquals(backup.entries, repo.snapshot().entries)
    }
    @Test fun ignoredEventsStayIgnoredAfterRestartedIngest() = runBlocking {
        repo.ingest(event)
        repo.ignore(repo.snapshot().entries.single().id)
        LedgerRepository(db).ingest(event.copy(id = "new-id"))
        assertEquals(Status.IGNORED, repo.snapshot().entries.single().status)
        assertEquals(1, repo.snapshot().entries.size)
    }
}
