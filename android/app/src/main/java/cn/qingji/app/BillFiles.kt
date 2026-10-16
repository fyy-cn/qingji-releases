package cn.qingji.app

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import cn.qingji.core.*
import org.xmlpull.v1.XmlPullParser
import android.util.Xml
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.zip.ZipInputStream

object BillFiles {
    const val MAX_INPUT = 20 * 1024 * 1024
    private fun InputStream.limited(maxBytes: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (output.size() <= maxBytes) {
            val count = read(buffer, 0, minOf(buffer.size, maxBytes + 1 - output.size()))
            if (count < 0) break
            if (count == 0) break
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
    fun read(resolver: ContentResolver, uri: Uri, maxBytes: Int = MAX_INPUT): ByteArray = resolver.openInputStream(uri)?.use { stream ->
        val result = stream.limited(maxBytes)
        require(result.size <= maxBytes) { "文件超过${maxBytes / 1024 / 1024}MB限制，请分段导出账单" }
        result
    } ?: error("无法打开文件")
    fun name(resolver: ContentResolver, uri: Uri): String = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
        if (it.moveToFirst()) it.getString(0) else null
    } ?: "账单文件"
    fun parse(bytes: ByteArray): ParsedBill {
        val rows = if (bytes.size >= 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) xlsx(bytes)
            else {
                require(!(bytes.size > 4 && String(bytes.take(4).toByteArray()) == "%PDF")) { "不支持PDF，请导出用于个人对账的CSV或XLSX账单" }
                require(!(bytes.size > 2 && bytes[0] == 0xd0.toByte() && bytes[1] == 0xcf.toByte())) { "不支持旧版XLS或加密工作簿，请另存为CSV / XLSX" }
                CsvReader.read(CsvReader.decode(bytes))
            }
        return BillParser.parse(rows, sha256(bytes), System.currentTimeMillis())
    }
    internal fun xlsx(bytes: ByteArray): List<List<String>> {
        val files = mutableMapOf<String, ByteArray>()
        var total = 0
        var fileCount = 0
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                require(++fileCount <= 2000) { "压缩包条目过多" }
                if (!entry.isDirectory) {
                    val content = zip.limited(32 * 1024 * 1024 - total)
                    total += content.size
                    require(total <= 32 * 1024 * 1024) { "工作簿解压后过大，请分段导出" }
                    if (entry.name == "xl/sharedStrings.xml" || entry.name.matches(Regex("xl/worksheets/sheet\\d+\\.xml"))) files[entry.name] = content
                }
                zip.closeEntry()
            }
        }
        require(files.keys.any { it.startsWith("xl/worksheets/") }) { "请选择解压后的XLSX文件；不支持直接导入账单ZIP压缩包" }
        fun parser(content: ByteArray): XmlPullParser {
            val xml = String(content, Charsets.UTF_8)
            require(!xml.contains("<!DOCTYPE", true) && !xml.contains("<!ENTITY", true)) { "工作簿XML无效" }
            return Xml.newPullParser().apply { setInput(xml.reader()) }
        }
        val shared = mutableListOf<String>()
        files["xl/sharedStrings.xml"]?.let { data ->
            val p = parser(data)
            var buffer = StringBuilder()
            while (p.eventType != XmlPullParser.END_DOCUMENT) {
                if (p.eventType == XmlPullParser.START_TAG && p.name == "si") buffer = StringBuilder()
                if (p.eventType == XmlPullParser.START_TAG && p.name == "t") buffer.append(p.nextText())
                if (p.eventType == XmlPullParser.END_TAG && p.name == "si") { require(shared.size < 300_000); shared.add(buffer.toString().take(5000)) }
                p.next()
            }
        }
        val sheets = files.filterKeys { it.startsWith("xl/worksheets/") }.toSortedMap()
        for ((_, data) in sheets) {
            val p = parser(data)
            val rows = mutableListOf<List<String>>()
            var row = mutableListOf<String>()
            var col = 0
            var type = ""
            var value = StringBuilder()
            while (p.eventType != XmlPullParser.END_DOCUMENT) {
                if (p.eventType == XmlPullParser.START_TAG) when (p.name) {
                    "row" -> row = mutableListOf()
                    "c" -> {
                        val ref = p.getAttributeValue(null, "r").orEmpty().takeWhile { it.isLetter() }
                        col = if (ref.isEmpty()) row.size else ref.fold(0) { acc, c -> acc * 26 + (c.uppercaseChar() - 'A' + 1) } - 1
                        require(col in 0..200) { "工作表列数异常" }
                        type = p.getAttributeValue(null, "t").orEmpty(); value = StringBuilder()
                    }
                    "v", "t" -> value.append(p.nextText())
                }
                if (p.eventType == XmlPullParser.END_TAG) when (p.name) {
                    "c" -> {
                        while (row.size <= col) row.add("")
                        row[col] = if (type == "s") shared.getOrNull(value.toString().toIntOrNull() ?: -1) ?: "" else value.toString().take(5000)
                    }
                    "row" -> { require(rows.size < 100_000) { "工作表超过10万行" }; rows.add(row) }
                }
                p.next()
            }
            val headerIndex = rows.indexOfFirst { r -> r.any { it.trim() in listOf("交易时间", "交易创建时间", "付款时间") } && r.any { it.contains("金额") } }
            if (headerIndex >= 0) {
                val timeCol = rows[headerIndex].indexOfFirst { it.trim() in listOf("交易时间", "交易创建时间", "付款时间") }
                return rows.mapIndexed { index, cells ->
                    if (index <= headerIndex) cells else {
                        val serial = cells.getOrNull(timeCol)?.toDoubleOrNull()
                        if (serial != null && serial in 30000.0..100000.0) cells.toMutableList().apply {
                            this[timeCol] = LocalDate.of(1899, 12, 30).atStartOfDay().plusSeconds(kotlin.math.round(serial * 86400).toLong()).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                        } else cells
                    }
                }
            }
        }
        error("XLSX工作表没有支持的交易表头")
    }
}
