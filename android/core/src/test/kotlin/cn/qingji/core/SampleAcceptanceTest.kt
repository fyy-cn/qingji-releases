package cn.qingji.core

import java.io.File
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class SampleAcceptanceTest {
    @Test fun shippedSyntheticFilesReconcileToExpectedTotalAndReimportIsIdempotent() {
        val folder = File(System.getProperty("samplesDir"))
        val bills = listOf("wechat-synthetic.csv", "alipay-synthetic.csv").map { name ->
            val bytes = File(folder, name).readBytes()
            BillParser.parse(CsvReader.read(CsvReader.decode(bytes)), sha256(bytes), 1_790_000_000_000)
        }
        val batch = IngestionBatch(emptyList(), emptyList())
        (bills + bills).forEach { bill -> bill.entries.forEach { batch.accept(it) } }
        val ledger = batch.snapshot()
        assertEquals(9, ledger.size)
        assertTrue(ledger.none { it.status == Status.PENDING })
        val today = LocalDate.parse("2026-10-16")
        val report = Reports.build(ledger, Period.MONTH, today, today)
        assertEquals(11530L, report.total.expense)
        assertEquals(2000L, report.total.refund)
        assertEquals(9530L, report.total.net)
        assertEquals(5, report.total.count)
    }
    @Test fun largeImportKeepsDistinctIdsAndFindsANotificationWithoutAnId() {
        val source = Entry(id = "notification", amountFen = 100, occurredAt = 1_790_000_000_000, channel = Channel.WECHAT, merchant = "面馆")
        val batch = IngestionBatch(listOf(source), emptyList())
        repeat(10_000) { index ->
            batch.accept(source.copy(id = "row:$index", occurredAt = source.occurredAt + index * 300_000L, transactionId = "wx:$index"))
        }
        assertEquals(10_001, batch.snapshot().size)
        assertEquals(1, batch.snapshot().count { it.status == Status.PENDING })
        assertEquals(listOf("notification"), batch.snapshot().first { it.status == Status.PENDING }.candidateIds)
    }
}
