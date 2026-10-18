package cn.qingji.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import java.math.BigDecimal
import java.security.MessageDigest
import java.util.UUID

val ledgerJson = Json { encodeDefaults = true; ignoreUnknownKeys = false }

@Serializable enum class Channel(val label: String) { WECHAT("微信"), ALIPAY("支付宝"), MANUAL("其他 / 现金"), CAMPUS("校园 / 交我办") }

object PaymentSources {
    val builtIns = setOf("com.tencent.mm", "com.eg.android.AlipayGphone")
    fun channel(packageName: String, campusPackage: String? = null): Channel? = when (packageName) {
        "com.tencent.mm" -> Channel.WECHAT
        "com.eg.android.AlipayGphone" -> Channel.ALIPAY
        campusPackage?.takeIf { it.isNotBlank() && it !in builtIns } -> Channel.CAMPUS
        else -> null
    }
}
@Serializable enum class Kind(val label: String) { EXPENSE("支出"), REFUND("退款"), TRANSFER("转账 / 充值 / 还款"), INCOME("收入"), UNKNOWN("待识别") }
@Serializable enum class Status(val label: String) { CONFIRMED("已确认"), PENDING("待确认"), IGNORED("已忽略") }
@ConsistentCopyVisibility
@Serializable(with = CategorySerializer::class)
data class Category private constructor(val name: String, val label: String, val glyph: String) {
    val isCustom: Boolean get() = name.startsWith("CUSTOM:")
    companion object {
        val FOOD = Category("FOOD", "餐饮", "餐")
        val TRANSPORT = Category("TRANSPORT", "交通", "行")
        val SHOPPING = Category("SHOPPING", "购物", "购")
        val HOUSING = Category("HOUSING", "住房", "住")
        val ENTERTAINMENT = Category("ENTERTAINMENT", "娱乐", "乐")
        val HEALTH = Category("HEALTH", "医疗", "医")
        val OTHER = Category("OTHER", "其他", "其")
        val UNCATEGORIZED = Category("UNCATEGORIZED", "未分类", "?")
        val entries = listOf(FOOD, TRANSPORT, SHOPPING, HOUSING, ENTERTAINMENT, HEALTH, OTHER, UNCATEGORIZED)
        fun custom(text: String): Category {
            val label = text.trim()
            require(label.length in 1..20 && label.none { it.isISOControl() }) { "分类名称需要1至20个字，不能包含换行" }
            require(entries.none { it.label.equals(label, true) }) { "此名称与内置分类重复" }
            return Category("CUSTOM:$label", label, "自")
        }
        fun fromName(name: String): Category = entries.firstOrNull { it.name == name }
            ?: if (name.startsWith("CUSTOM:")) custom(name.removePrefix("CUSTOM:")) else error("未知分类：$name")
    }
}

object CategorySerializer : KSerializer<Category> {
    override val descriptor = PrimitiveSerialDescriptor("cn.qingji.core.Category", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: Category) = encoder.encodeString(value.name)
    override fun deserialize(decoder: Decoder): Category = Category.fromName(decoder.decodeString())
}

@Serializable data class Entry(
    val id: String = UUID.randomUUID().toString(),
    val amountFen: Long = 0,
    val currency: String = "CNY",
    val occurredAt: Long,
    val merchant: String = "未知商家",
    val category: Category = Category.UNCATEGORIZED,
    val channel: Channel,
    val kind: Kind = Kind.EXPENSE,
    val status: Status = Status.CONFIRMED,
    val transactionId: String? = null,
    val relatedTransactionId: String? = null,
    val sources: Set<String> = setOf("手动"),
    val eventKeys: Set<String> = emptySet(),
    val note: String = "",
    val raw: String = "",
    val reason: String = "",
    val candidateIds: List<String> = emptyList(),
    val editedFields: Set<String> = emptySet(),
    val createdAt: Long = System.currentTimeMillis()
)

@Serializable data class MerchantRule(val merchant: String, val category: Category)
@Serializable data class ImportReceipt(val digest: String, val name: String, val importedAt: Long)
@Serializable data class CategoryPreference(val category: Category, val position: Int, val hidden: Boolean = false)

object CategoryLayout {
    fun ordered(custom: List<Category>, preferences: List<CategoryPreference>): List<Category> {
        val all = (Category.entries + custom).distinct()
        val settings = preferences.associateBy { it.category }
        return all.withIndex().filter { settings[it.value]?.hidden != true || it.value == Category.UNCATEGORIZED }
            .sortedWith(compareBy({ settings[it.value]?.position ?: (1000 + it.index) }, { it.index })).map { it.value }
    }
}

@Serializable data class BackupData(
    val version: Int = 4,
    val entries: List<Entry>,
    val rules: List<MerchantRule>,
    val imports: List<ImportReceipt>,
    val exportedAt: Long = System.currentTimeMillis(),
    val customCategories: List<Category> = emptyList(),
    val categoryPreferences: List<CategoryPreference> = emptyList()
)

fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
fun sha256(text: String): String = sha256(text.toByteArray(Charsets.UTF_8))
fun normalizeMerchant(text: String): String = text.trim().lowercase().replace(Regex("[\\s·•]"), "")
fun parseFen(text: String): Long? {
    val cleaned = text.trim().removePrefix("¥").removePrefix("￥").removeSuffix("元").trim()
    if (!Regex("(?:\\d+|\\d{1,3}(?:,\\d{3})+)(?:\\.\\d{1,2})?").matches(cleaned)) return null
    return runCatching { BigDecimal(cleaned.replace(",", "")).movePointRight(2).longValueExact() }
        .getOrNull()?.takeIf { it in 1..100_000_000_000_000L }
}
fun money(fen: Long): String = BigDecimal.valueOf(fen, 2).toPlainString()

object Categorizer {
    fun category(merchant: String, description: String, rules: List<MerchantRule>): Category {
        rules.lastOrNull { normalizeMerchant(it.merchant) == normalizeMerchant(merchant) }?.let { return it.category }
        val text = "$merchant $description"
        return listOf(
            Category.FOOD to "餐|饭|面馆|咖啡|奶茶|外卖|食堂|早餐|火锅|美团外卖|饿了么",
            Category.TRANSPORT to "地铁|公交|打车|滴滴|铁路|火车|高铁|停车|加油|出行",
            Category.HOUSING to "房租|物业|水费|电费|燃气|宽带",
            Category.HEALTH to "医院|药房|药店|门诊|医疗|挂号",
            Category.ENTERTAINMENT to "电影|影院|游戏|音乐|演出|门票",
            Category.SHOPPING to "超市|便利店|商场|服装|京东|淘宝|拼多多|书店"
        ).firstOrNull { Regex(it.second).containsMatchIn(text) }?.first ?: Category.UNCATEGORIZED
    }
}

data class IngestResult(val entries: List<Entry>, val action: String, val entry: Entry)

object LedgerEngine {
    fun accept(existing: List<Entry>, incoming: Entry, rules: List<MerchantRule>): IngestResult {
        val candidate = incoming.copy(category = if ("category" in incoming.editedFields) incoming.category
            else Categorizer.category(incoming.merchant, incoming.note, rules))
        val strong = existing.firstOrNull { old ->
            old.eventKeys.intersect(candidate.eventKeys).isNotEmpty() ||
                (candidate.transactionId != null && old.channel == candidate.channel && old.kind == candidate.kind && old.transactionId == candidate.transactionId)
        }
        if (strong != null) {
            val pageUpdate = "页面识别（实验）" in candidate.sources && "reviewed" !in strong.editedFields
            val conflicting = strong.amountFen > 0 && candidate.amountFen > 0 && strong.amountFen != candidate.amountFen && "amount" !in strong.editedFields
            val refundReview = candidate.reason.contains("退款状态") && "reviewed" !in strong.editedFields
            val merged = strong.copy(
                occurredAt = if (pageUpdate && "time" !in strong.editedFields && !candidate.reason.contains("交易时间未读取")) candidate.occurredAt else strong.occurredAt,
                amountFen = if (strong.amountFen == 0L && "amount" !in strong.editedFields) candidate.amountFen else strong.amountFen,
                merchant = if (strong.merchant == "未知商家" && "merchant" !in strong.editedFields) candidate.merchant else strong.merchant,
                transactionId = strong.transactionId ?: candidate.transactionId,
                relatedTransactionId = strong.relatedTransactionId ?: candidate.relatedTransactionId,
                kind = if (strong.kind == Kind.UNKNOWN && "kind" !in strong.editedFields) candidate.kind else strong.kind,
                raw = candidate.raw.ifBlank { strong.raw },
                sources = strong.sources + candidate.sources,
                eventKeys = strong.eventKeys + candidate.eventKeys,
                category = if ("category" in strong.editedFields) strong.category else if (strong.category == Category.UNCATEGORIZED) candidate.category else strong.category,
                status = if ((conflicting || refundReview) && strong.status != Status.IGNORED) Status.PENDING else strong.status,
                reason = if (conflicting) "同一交易出现不同金额，请核对原始账单" else if (refundReview || (pageUpdate && strong.status == Status.PENDING && strong.candidateIds.isEmpty())) candidate.reason else strong.reason
            )
            return IngestResult(existing.map { if (it.id == strong.id) merged else it }, "重复 / 补全", merged)
        }
        val suspects = existing.filter { old ->
            old.status != Status.IGNORED && old.channel == candidate.channel && old.kind == candidate.kind &&
                old.amountFen == candidate.amountFen && candidate.amountFen > 0 &&
                (old.transactionId == null || candidate.transactionId == null) &&
                kotlin.math.abs(old.occurredAt - candidate.occurredAt) <= 120_000 &&
                (normalizeMerchant(old.merchant) == normalizeMerchant(candidate.merchant) || old.merchant == "未知商家" || candidate.merchant == "未知商家")
        }
        val added = if (suspects.isNotEmpty()) candidate.copy(status = Status.PENDING,
            reason = listOf(candidate.reason, "疑似重复，请选择保留新交易或合并已有交易").filter { it.isNotBlank() }.joinToString("；"),
            candidateIds = suspects.map { it.id }) else candidate
        return IngestResult(existing + added, if (added.status == Status.PENDING) "待确认" else "新增", added)
    }
}
