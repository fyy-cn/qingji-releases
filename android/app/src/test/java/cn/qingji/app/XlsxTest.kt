package cn.qingji.app

import cn.qingji.core.*
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class XlsxTest {
    private fun zip(vararg entries: Pair<String, String>): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { out -> entries.forEach { (name, content) -> out.putNextEntry(ZipEntry(name)); out.write(content.toByteArray()); out.closeEntry() } }
        return bytes.toByteArray()
    }
    @Test fun inlineAndSharedStringsCanBeReadAndImported() {
        val headers = listOf("交易时间", "交易类型", "交易对方", "商品", "收/支", "金额(元)", "当前状态", "交易单号")
        val cells = headers.mapIndexed { index, text -> "<c r='${'A' + index}1' t='inlineStr'><is><t>$text</t></is></c>" }.joinToString("")
        val data = "<c r='A2' t='inlineStr'><is><t>2026-10-16 12:00:00</t></is></c><c r='B2' t='inlineStr'><is><t>商户消费</t></is></c><c r='C2' t='s'><v>0</v></c><c r='E2' t='inlineStr'><is><t>支出</t></is></c><c r='F2'><v>28.50</v></c><c r='G2' t='inlineStr'><is><t>支付成功</t></is></c><c r='H2' t='inlineStr'><is><t>wx123</t></is></c>"
        val bytes = zip("xl/sharedStrings.xml" to "<sst><si><t>面馆</t></si></sst>", "xl/worksheets/sheet1.xml" to "<worksheet><sheetData><row>$cells</row><row>$data</row></sheetData></worksheet>")
        val result = BillFiles.parse(bytes)
        assertEquals(2850L, result.entries.single().amountFen)
        assertEquals("面馆", result.entries.single().merchant)
        assertEquals(Status.CONFIRMED, result.entries.single().status)
    }
    @Test(expected = IllegalArgumentException::class) fun genericZipIsRejected() {
        BillFiles.parse(zip("bill.csv" to "hello"))
    }
    @Test(expected = IllegalArgumentException::class) fun externalEntityXmlIsRejected() {
        BillFiles.parse(zip("xl/worksheets/sheet1.xml" to "<!DOCTYPE x [<!ENTITY xxe SYSTEM 'file:///unavailable'>]><worksheet/>"))
    }
}
