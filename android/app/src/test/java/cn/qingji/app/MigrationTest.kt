package cn.qingji.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cn.qingji.core.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = OfflineTestApplication::class)
class MigrationTest {
    @Test fun databaseOneMigratesWithoutLosingExistingBillsRulesOrImportReceipts() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "migration-${System.nanoTime()}.db"
        val path = context.getDatabasePath(name).apply { parentFile!!.mkdirs() }
        val entry = Entry(id = "legacy", amountFen = 1250, occurredAt = 1_790_000_000_000, merchant = "面馆", category = Category.FOOD, channel = Channel.WECHAT)
        SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
            db.execSQL("CREATE TABLE entries (id TEXT NOT NULL PRIMARY KEY, occurredAt INTEGER NOT NULL, payload TEXT NOT NULL)")
            db.execSQL("CREATE INDEX index_entries_occurredAt ON entries(occurredAt)")
            db.execSQL("CREATE TABLE merchant_rules (merchant TEXT NOT NULL PRIMARY KEY, payload TEXT NOT NULL)")
            db.execSQL("CREATE TABLE imports (digest TEXT NOT NULL PRIMARY KEY, payload TEXT NOT NULL)")
            db.execSQL("INSERT INTO entries VALUES (?, ?, ?)", arrayOf<Any>(entry.id, entry.occurredAt, ledgerJson.encodeToString(entry)))
            db.execSQL("INSERT INTO merchant_rules VALUES (?, ?)", arrayOf("面馆", ledgerJson.encodeToString(MerchantRule("面馆", Category.FOOD))))
            db.execSQL("INSERT INTO imports VALUES (?, ?)", arrayOf("digest", ledgerJson.encodeToString(ImportReceipt("digest", "旧账单.csv", 1000))))
            db.version = 1
        }
        val database = Room.databaseBuilder(context, LedgerDatabase::class.java, name).addMigrations(LedgerDatabase.MIGRATION_1_2, LedgerDatabase.MIGRATION_2_3).build()
        try {
            val repo = LedgerRepository(database)
            val data = repo.snapshot()
            assertEquals(entry, data.entries.single()); assertEquals(Category.FOOD, data.rules.single().category); assertEquals("旧账单.csv", data.imports.single().name)
            repo.addCategory("学习")
            assertEquals("学习", repo.snapshot().customCategories.single().label)
        } finally { database.close(); context.deleteDatabase(name) }
    }
    @Test fun databaseTwoPreservesCustomCategoryAndCanPersistNewOrderAndDeletion() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "migration-v2-${System.nanoTime()}.db"
        val path = context.getDatabasePath(name).apply { parentFile!!.mkdirs() }
        val entry = Entry(id = "v2", amountFen = 2800, occurredAt = 1_790_000_000_000, merchant = "课程", category = Category.custom("学习"), channel = Channel.MANUAL)
        SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
            db.execSQL("CREATE TABLE entries (id TEXT NOT NULL PRIMARY KEY, occurredAt INTEGER NOT NULL, payload TEXT NOT NULL)")
            db.execSQL("CREATE INDEX index_entries_occurredAt ON entries(occurredAt)")
            db.execSQL("CREATE TABLE merchant_rules (merchant TEXT NOT NULL PRIMARY KEY, payload TEXT NOT NULL)")
            db.execSQL("CREATE TABLE imports (digest TEXT NOT NULL PRIMARY KEY, payload TEXT NOT NULL)")
            db.execSQL("CREATE TABLE custom_categories (name TEXT NOT NULL PRIMARY KEY)")
            db.execSQL("INSERT INTO custom_categories VALUES ('学习')")
            db.execSQL("INSERT INTO entries VALUES (?, ?, ?)", arrayOf<Any>(entry.id, entry.occurredAt, ledgerJson.encodeToString(entry)))
            db.version = 2
        }
        val database = Room.databaseBuilder(context, LedgerDatabase::class.java, name).addMigrations(LedgerDatabase.MIGRATION_2_3).build()
        try {
            val repo = LedgerRepository(database)
            assertEquals(entry, repo.snapshot().entries.single())
            repo.moveCategory(Category.custom("学习"), -1)
            repo.deleteCategory(Category.FOOD)
            val data = repo.snapshot()
            assertEquals(entry, data.entries.single())
            assertTrue(data.categoryPreferences.any { it.category == Category.FOOD && it.hidden })
            assertEquals(7, data.categoryPreferences.single { it.category == Category.custom("学习") }.position)
        } finally { database.close(); context.deleteDatabase(name) }
    }
}
