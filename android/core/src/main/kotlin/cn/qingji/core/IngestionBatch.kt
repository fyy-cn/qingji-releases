package cn.qingji.core

data class BatchAcceptance(val action: String, val entry: Entry)

/** Index only possible matches during file imports; reuse the same review and merge rules. */
class IngestionBatch(initial: List<Entry>, private val rules: List<MerchantRule>) {
    private val entries = linkedMapOf<String, Entry>()
    private val events = mutableMapOf<String, String>()
    private val transactions = mutableMapOf<String, String>()
    private val byAmount = mutableMapOf<String, MutableSet<String>>()
    private val withoutTransaction = mutableMapOf<String, MutableSet<String>>()
    init { initial.forEach(::store) }
    private fun transaction(entry: Entry) = entry.transactionId?.let { "${entry.channel}|${entry.kind}|$it" }
    private fun amount(entry: Entry) = "${entry.channel}|${entry.kind}|${entry.amountFen}"
    private fun store(entry: Entry) {
        entries[entry.id]?.let { old ->
            old.eventKeys.forEach { if (events[it] == old.id) events.remove(it) }
            transaction(old)?.let { if (transactions[it] == old.id) transactions.remove(it) }
            byAmount[amount(old)]?.remove(old.id)
            withoutTransaction[amount(old)]?.remove(old.id)
        }
        entries[entry.id] = entry
        entry.eventKeys.forEach { events[it] = entry.id }
        transaction(entry)?.let { transactions[it] = entry.id }
        byAmount.getOrPut(amount(entry)) { linkedSetOf() }.add(entry.id)
        if (entry.transactionId == null) withoutTransaction.getOrPut(amount(entry)) { linkedSetOf() }.add(entry.id)
    }
    fun accept(incoming: Entry): BatchAcceptance {
        val strong = incoming.eventKeys.firstNotNullOfOrNull { events[it] } ?: transaction(incoming)?.let { transactions[it] }
        val relevant = if (strong != null) listOfNotNull(entries[strong]) else {
            val index = if (incoming.transactionId == null) byAmount else withoutTransaction
            index[amount(incoming)].orEmpty().mapNotNull { entries[it] }.filter { kotlin.math.abs(it.occurredAt - incoming.occurredAt) <= 120_000 }
        }
        val result = LedgerEngine.accept(relevant, incoming, rules)
        store(result.entry)
        return BatchAcceptance(result.action, result.entry)
    }
    fun snapshot(): List<Entry> = entries.values.toList()
}
