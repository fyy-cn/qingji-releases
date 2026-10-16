package cn.qingji.app

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import cn.qingji.core.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.encodeToString

@Entity(tableName = "entries", indices = [Index("occurredAt")])
data class EntryRow(@PrimaryKey val id: String, val occurredAt: Long, val payload: String)
@Entity(tableName = "merchant_rules")
data class RuleRow(@PrimaryKey val merchant: String, val payload: String)
@Entity(tableName = "imports")
data class ImportRow(@PrimaryKey val digest: String, val payload: String)
@Entity(tableName = "custom_categories")
data class CategoryRow(@PrimaryKey val name: String)
@Entity(tableName = "category_preferences")
data class CategoryPreferenceRow(@PrimaryKey val categoryName: String, val position: Int, val hidden: Boolean)

@Dao interface LedgerDao {
    @Query("SELECT * FROM entries ORDER BY occurredAt DESC") fun watchEntries(): Flow<List<EntryRow>>
    @Query("SELECT * FROM merchant_rules ORDER BY merchant") fun watchRules(): Flow<List<RuleRow>>
    @Query("SELECT * FROM imports") fun watchImports(): Flow<List<ImportRow>>
    @Query("SELECT * FROM custom_categories ORDER BY name") fun watchCategories(): Flow<List<CategoryRow>>
    @Query("SELECT * FROM custom_categories") suspend fun categories(): List<CategoryRow>
    @Upsert suspend fun putCategory(row: CategoryRow)
    @Query("DELETE FROM custom_categories WHERE name = :name") suspend fun removeCategory(name: String)
    @Query("DELETE FROM custom_categories") suspend fun clearCategories()
    @Query("SELECT * FROM category_preferences ORDER BY position, categoryName") fun watchCategoryPreferences(): Flow<List<CategoryPreferenceRow>>
    @Query("SELECT * FROM category_preferences ORDER BY position, categoryName") suspend fun categoryPreferences(): List<CategoryPreferenceRow>
    @Upsert suspend fun putCategoryPreferences(rows: List<CategoryPreferenceRow>)
    @Query("DELETE FROM category_preferences WHERE categoryName = :name") suspend fun removeCategoryPreference(name: String)
    @Query("DELETE FROM category_preferences") suspend fun clearCategoryPreferences()
    @Query("SELECT * FROM entries") suspend fun entries(): List<EntryRow>
    @Query("SELECT * FROM merchant_rules") suspend fun rules(): List<RuleRow>
    @Query("SELECT * FROM imports") suspend fun imports(): List<ImportRow>
    @Upsert suspend fun putEntries(rows: List<EntryRow>)
    @Upsert suspend fun putRule(row: RuleRow)
    @Upsert suspend fun putImport(row: ImportRow)
    @Query("DELETE FROM entries WHERE id = :id") suspend fun removeEntry(id: String)
    @Query("DELETE FROM merchant_rules WHERE merchant = :merchant") suspend fun removeRule(merchant: String)
    @Query("DELETE FROM entries") suspend fun clearEntries()
    @Query("DELETE FROM merchant_rules") suspend fun clearRules()
    @Query("DELETE FROM imports") suspend fun clearImports()
}

@Database(entities = [EntryRow::class, RuleRow::class, ImportRow::class, CategoryRow::class, CategoryPreferenceRow::class], version = 3, exportSchema = true)
abstract class LedgerDatabase : RoomDatabase() {
    abstract fun dao(): LedgerDao
    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `custom_categories` (`name` TEXT NOT NULL, PRIMARY KEY(`name`))")
            }
        }
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `category_preferences` (`categoryName` TEXT NOT NULL, `position` INTEGER NOT NULL, `hidden` INTEGER NOT NULL, PRIMARY KEY(`categoryName`))")
            }
        }
        fun open(context: Context) = Room.databaseBuilder(context, LedgerDatabase::class.java, "qingji.db").addMigrations(MIGRATION_1_2, MIGRATION_2_3).build()
    }
}

data class PreviewItem(val entry: Entry, val action: String)
data class ImportPreview(val digest: String, val name: String, val bill: ParsedBill, val items: List<PreviewItem>, val alreadyImported: Boolean) {
    val newCount get() = items.count { it.action == "新增" }
    val pendingCount get() = items.count { it.action == "待确认" }
    val duplicateCount get() = items.count { it.action == "重复 / 补全" }
}

class LedgerRepository(private val db: LedgerDatabase) {
    private val dao = db.dao()
    val entries = dao.watchEntries().map { rows -> rows.map { ledgerJson.decodeFromString<Entry>(it.payload) } }.flowOn(Dispatchers.IO)
    val rules = dao.watchRules().map { rows -> rows.map { ledgerJson.decodeFromString<MerchantRule>(it.payload) } }.flowOn(Dispatchers.IO)
    val imports = dao.watchImports().map { rows -> rows.map { ledgerJson.decodeFromString<ImportReceipt>(it.payload) } }.flowOn(Dispatchers.IO)
    val categories = combine(dao.watchCategories(), dao.watchCategoryPreferences()) { rows, preferences ->
        CategoryLayout.ordered(rows.map { Category.custom(it.name) }, preferences.map { CategoryPreference(Category.fromName(it.categoryName), it.position, it.hidden) })
    }.flowOn(Dispatchers.IO)
    private suspend fun preferences() = dao.categoryPreferences().map { CategoryPreference(Category.fromName(it.categoryName), it.position, it.hidden) }
    private suspend fun available() = CategoryLayout.ordered(dao.categories().map { Category.custom(it.name) }, preferences())
    private suspend fun storeOrder(categories: List<Category>) = dao.putCategoryPreferences(categories.mapIndexed { index, category -> CategoryPreferenceRow(category.name, index, false) })
    suspend fun moveCategory(category: Category, direction: Int) = db.withTransaction {
        require(direction == -1 || direction == 1)
        val ordered = available().toMutableList()
        val index = ordered.indexOf(category)
        if (index >= 0 && index + direction in ordered.indices) {
            java.util.Collections.swap(ordered, index, index + direction)
            storeOrder(ordered)
        }
    }
    suspend fun restoreBuiltInCategories() = db.withTransaction {
        storeOrder((available() + Category.entries).distinct())
    }
    private suspend fun hiddenCategories() = dao.categoryPreferences().filter { it.hidden }.map { it.categoryName }.toSet()
    private fun withoutDeletedCategory(entry: Entry, hidden: Set<String>): Entry =
        if (entry.category.name in hidden) entry.copy(category = Category.UNCATEGORIZED) else entry
    private fun row(entry: Entry) = EntryRow(entry.id, entry.occurredAt, ledgerJson.encodeToString(entry))
    private suspend fun all() = dao.entries().map { ledgerJson.decodeFromString<Entry>(it.payload) }
    private suspend fun allRules() = dao.rules().map { ledgerJson.decodeFromString<MerchantRule>(it.payload) }
    private suspend fun register(category: Category): Category {
        if (!category.isCustom) return category
        val defined = dao.categories()
        defined.firstOrNull { it.name.equals(category.label, true) }?.let { return Category.custom(it.name) }
        require(defined.size < 100) { "最多支持100个自定义分类" }
        val ordered = available()
        dao.putCategory(CategoryRow(category.label))
        storeOrder(ordered + category)
        return category
    }
    suspend fun addCategory(name: String) = db.withTransaction {
        val category = Category.custom(name)
        require(dao.categories().none { it.name.equals(category.label, true) }) { "分类名称已存在" }
        register(category)
    }
    suspend fun renameCategory(old: Category, name: String) = db.withTransaction {
        require(old.isCustom) { "内置分类不能重命名" }
        val next = Category.custom(name)
        require(dao.categories().any { it.name == old.label }) { "分类不存在" }
        require(dao.categories().none { it.name != old.label && it.name.equals(next.label, true) }) { "分类名称已存在" }
        val changed = all().filter { it.category == old }.map { it.copy(category = next, editedFields = it.editedFields + "category") }
        dao.putEntries(changed.map(::row))
        allRules().filter { it.category == old }.forEach { rule -> dao.putRule(RuleRow(normalizeMerchant(rule.merchant), ledgerJson.encodeToString(rule.copy(category = next)))) }
        val ordered = available().map { if (it == old) next else it }
        dao.removeCategory(old.label); dao.putCategory(CategoryRow(next.label))
        dao.removeCategoryPreference(old.name); storeOrder(ordered)
    }
    suspend fun deleteCategory(category: Category) = db.withTransaction {
        require(category != Category.UNCATEGORIZED) { "未分类用于接收未识别账单，需要保留" }
        dao.putEntries(all().filter { it.category == category }.map { row(it.copy(category = Category.UNCATEGORIZED, editedFields = it.editedFields + "category")) })
        allRules().filter { it.category == category }.forEach { dao.removeRule(normalizeMerchant(it.merchant)) }
        if (category.isCustom) { dao.removeCategory(category.label); dao.removeCategoryPreference(category.name) }
        else dao.putCategoryPreferences(listOf(CategoryPreferenceRow(category.name, 1_000_000, true)))
    }
    suspend fun ingest(entry: Entry): String = db.withTransaction {
        val result = LedgerEngine.accept(all(), entry, allRules())
        val accepted = withoutDeletedCategory(result.entry, hiddenCategories())
        dao.putEntries(listOf(row(accepted.copy(category = register(accepted.category)))))
        result.action
    }
    suspend fun preview(digest: String, name: String, bill: ParsedBill): ImportPreview = db.withTransaction {
        val already = dao.imports().any { it.digest == digest }
        val batch = IngestionBatch(all(), allRules())
        val hidden = hiddenCategories()
        val items = bill.entries.map { entry ->
            val result = batch.accept(entry)
            PreviewItem(withoutDeletedCategory(result.entry, hidden), result.action)
        }
        ImportPreview(digest, name, bill, items, already)
    }
    suspend fun commit(preview: ImportPreview) = db.withTransaction {
        require(dao.imports().none { it.digest == preview.digest }) { "此文件已经导入，无需重复操作" }
        val batch = IngestionBatch(all(), allRules())
        val changed = linkedMapOf<String, Entry>()
        val hidden = hiddenCategories()
        preview.bill.entries.forEach { entry ->
            val result = batch.accept(entry)
            changed[result.entry.id] = withoutDeletedCategory(result.entry, hidden)
        }
        dao.putEntries(changed.values.map(::row))
        val receipt = ImportReceipt(preview.digest, preview.name, System.currentTimeMillis())
        dao.putImport(ImportRow(receipt.digest, ledgerJson.encodeToString(receipt)))
    }
    suspend fun save(entry: Entry, makeRule: Boolean) = db.withTransaction {
        require(entry.amountFen > 0 && entry.currency == "CNY" && entry.kind != Kind.UNKNOWN) { "请填写有效的人民币金额与交易类型" }
        require(entry.merchant.isNotBlank() && entry.merchant.length <= 500 && entry.note.length <= 4000) { "商家或备注长度无效" }
        val old = all().firstOrNull { it.id == entry.id }
        // Event keys may be enriched by the listener while the editor is open.
        val accepted = withoutDeletedCategory(entry, hiddenCategories())
        val saved = accepted.copy(category = register(accepted.category), eventKeys = entry.eventKeys + (old?.eventKeys ?: emptySet()), sources = entry.sources + (old?.sources ?: emptySet()), status = Status.CONFIRMED, reason = "", candidateIds = emptyList(), editedFields = entry.editedFields + "reviewed")
        dao.putEntries(listOf(row(saved)))
        if (makeRule && entry.merchant != "未知商家") {
            val rule = MerchantRule(entry.merchant.trim(), saved.category)
            dao.putRule(RuleRow(normalizeMerchant(rule.merchant), ledgerJson.encodeToString(rule)))
        }
    }
    suspend fun ignore(id: String) = db.withTransaction {
        all().firstOrNull { it.id == id }?.let { dao.putEntries(listOf(row(it.copy(status = Status.IGNORED, reason = "用户忽略")))) }
    }
    suspend fun merge(pendingId: String, targetId: String) = db.withTransaction {
        val state = all()
        val pending = state.first { it.id == pendingId }
        val target = state.firstOrNull { it.id == targetId } ?: error("原交易不存在")
        require(target.status != Status.IGNORED && pending.channel == target.channel && pending.currency == target.currency && pending.amountFen == target.amountFen && pending.kind == target.kind) { "两笔交易信息不同，请先核对，不可直接合并" }
        require(pending.transactionId == null || target.transactionId == null || pending.transactionId == target.transactionId) { "交易号不同，应分别保留" }
        dao.putEntries(listOf(row(target.copy(
            transactionId = target.transactionId ?: pending.transactionId,
            relatedTransactionId = target.relatedTransactionId ?: pending.relatedTransactionId,
            merchant = if (target.merchant == "未知商家" && "merchant" !in target.editedFields) pending.merchant else target.merchant,
            category = if (target.category == Category.UNCATEGORIZED && "category" !in target.editedFields) pending.category else target.category,
            eventKeys = target.eventKeys + pending.eventKeys, sources = target.sources + pending.sources
        ))))
        dao.removeEntry(pendingId)
    }
    suspend fun removeRule(merchant: String) = dao.removeRule(normalizeMerchant(merchant))
    suspend fun snapshot(): BackupData = db.withTransaction {
        BackupData(entries = all(), rules = allRules(), imports = dao.imports().map { ledgerJson.decodeFromString<ImportReceipt>(it.payload) }, customCategories = dao.categories().map { Category.custom(it.name) }, categoryPreferences = preferences())
    }
    suspend fun restore(data: BackupData) = db.withTransaction {
        BackupCodec.validate(data)
        dao.clearEntries(); dao.clearRules(); dao.clearImports(); dao.clearCategories(); dao.clearCategoryPreferences()
        (data.customCategories + data.entries.map { it.category } + data.rules.map { it.category }).filter { it.isCustom }.distinct().forEach { register(it) }
        dao.clearCategoryPreferences()
        dao.putCategoryPreferences(data.categoryPreferences.map { CategoryPreferenceRow(it.category.name, it.position, it.hidden) })
        dao.putEntries(data.entries.map(::row))
        data.rules.forEach { dao.putRule(RuleRow(normalizeMerchant(it.merchant), ledgerJson.encodeToString(it))) }
        data.imports.forEach { dao.putImport(ImportRow(it.digest, ledgerJson.encodeToString(it))) }
    }
}
