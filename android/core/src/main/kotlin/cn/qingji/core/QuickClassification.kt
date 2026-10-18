package cn.qingji.core

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable data class QuickTemplate(
    val id: String = UUID.randomUUID().toString(),
    val label: String,
    val category: Category,
    val merchant: String = "",
    val note: String = "",
    val position: Int = 0,
    val lastUsedAt: Long = 0,
    val useCount: Long = 0
)

object QuickClassification {
    fun needsCategory(entry: Entry) = entry.status != Status.IGNORED && entry.category == Category.UNCATEGORIZED &&
        entry.kind in setOf(Kind.EXPENSE, Kind.REFUND, Kind.UNKNOWN)

    fun validate(template: QuickTemplate) {
        require(template.id.matches(Regex("[A-Za-z0-9_-]{1,100}"))) { "模板编号无效" }
        require(template.label == template.label.trim() && template.label.length in 1..20 && template.label.none { it.isISOControl() }) { "模板名称需要1至20个字" }
        require(template.merchant.length <= 500 && template.note.length <= 4000) { "模板商家最多500字，备注最多4000字" }
        require(template.position in 0..1_000_000 && template.lastUsedAt in 0..4_102_444_800_000L && template.useCount in 0..1_000_000_000L) { "模板顺序或使用记录无效" }
    }

    fun starters(): List<QuickTemplate> = listOf(
        Triple("breakfast", "早餐", Category.FOOD), Triple("lunch", "午餐", Category.FOOD),
        Triple("dinner", "晚餐", Category.FOOD), Triple("canteen", "食堂", Category.FOOD),
        Triple("market", "超市", Category.SHOPPING), Triple("travel", "交通", Category.TRANSPORT)
    ).mapIndexed { index, (id, label, category) -> QuickTemplate(id = "starter_$id", label = label, category = category, note = label, position = index) }

    /** Classification never approves a payment or changes its amount, time, type or source. */
    fun apply(entry: Entry, category: Category, template: QuickTemplate? = null): Entry {
        require(needsCategory(entry)) { "账单状态或分类已变更，请刷新后重试" }
        require(category != Category.UNCATEGORIZED) { "请选择具体分类" }
        template?.let { validate(it); require(it.category == category) { "模板分类已变更" } }
        val merchant = if (entry.merchant.isBlank() || entry.merchant == "未知商家") template?.merchant?.trim()?.takeIf { it.isNotBlank() } ?: entry.merchant else entry.merchant
        val note = if (entry.note.isBlank()) template?.note?.trim().orEmpty() else entry.note
        return entry.copy(category = category, merchant = merchant, note = note,
            editedFields = entry.editedFields + buildSet {
                add("category")
                if (merchant != entry.merchant) add("merchant")
                if (note != entry.note) add("note")
            })
    }
}
