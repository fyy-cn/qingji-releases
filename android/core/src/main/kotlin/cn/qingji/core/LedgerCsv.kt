package cn.qingji.core

import java.io.OutputStream
import java.time.Instant
import java.time.format.DateTimeFormatter

object LedgerCsv {
    private val time = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ledgerZone)
    private fun text(value: String): String {
        val formula = value.trimStart().firstOrNull() in listOf('=', '+', '-', '@') || value.firstOrNull() in listOf('\t', '\r', '\n')
        val numericId = value.matches(Regex("\\d{15,}|0\\d+"))
        val safe = if (formula || numericId) "'$value" else value
        return "\"" + safe.replace("\"", "\"\"") + "\""
    }
    fun write(entries: List<Entry>, output: OutputStream) {
        output.write(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))
        val writer = output.bufferedWriter(Charsets.UTF_8)
        val header = listOf("记录ID", "交易时间（北京时间）", "消费分类", "交易类型", "金额（元）", "币种", "支付渠道", "确认状态", "交易号", "关联交易号", "备注", "来源", "创建时间（北京时间）")
        writer.write(header.joinToString(",", transform = ::text) + "\r\n")
        entries.sortedByDescending { it.occurredAt }.forEach { entry ->
            val values = listOf(entry.id, time.format(Instant.ofEpochMilli(entry.occurredAt)), entry.category.label, entry.kind.label,
                money(entry.amountFen), entry.currency, entry.channel.label, entry.status.label, entry.transactionId.orEmpty(), entry.relatedTransactionId.orEmpty(),
                entry.note, entry.sources.sorted().joinToString(" / "), time.format(Instant.ofEpochMilli(entry.createdAt)))
            writer.write(values.joinToString(",", transform = ::text) + "\r\n")
        }
        writer.flush()
    }
}
