package cn.qingji.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.qingji.core.*
import java.time.Instant
import java.time.format.DateTimeFormatter
import kotlin.math.abs

fun displayTime(time: Long, pattern: String = "MM-dd HH:mm") = Instant.ofEpochMilli(time).atZone(ledgerZone).format(DateTimeFormatter.ofPattern(pattern))
fun displayMoney(fen: Long) = "¥${money(fen)}"
fun deltaText(current: Long, previous: Long): String = if (previous == 0L) {
    if (current == 0L) "暂无变化" else "上期为零，差额 ${displayMoney(current)}"
} else {
    val percentage = (current - previous).toDouble() / abs(previous).toDouble() * 100
    "${if (percentage >= 0) "+" else ""}${"%.1f".format(java.util.Locale.CHINA, percentage)}% · ${displayMoney(current - previous)}"
}

@Composable fun Panel(modifier: Modifier = Modifier, color: Color = Color.White, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), color = color) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}
@Composable fun SectionTitle(title: String, trailing: String? = null, onClick: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        if (trailing != null) TextButton(onClick = { onClick?.invoke() }, enabled = onClick != null) { Text(trailing) }
    }
}
@Composable fun EmptyState(title: String, detail: String) {
    Panel {
        Box(Modifier.size(48.dp).clip(CircleShape).background(Lime), contentAlignment = Alignment.Center) { Text("记", color = Pine, fontSize = 22.sp) }
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(detail, color = Muted, style = MaterialTheme.typography.bodyMedium)
    }
}
@Composable fun EntryItem(entry: Entry, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(vertical = 12.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(if (entry.status == Status.PENDING) Color(0xFFFFEED8) else Color(0xFFEDF3E8)), contentAlignment = Alignment.Center) {
            Text(if (entry.kind == Kind.REFUND) "退" else entry.category.glyph, color = if (entry.status == Status.PENDING) Amber else Pine, fontWeight = FontWeight.SemiBold)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(entry.merchant, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
            Text("${entry.channel.label} · ${entry.category.label} · ${displayTime(entry.occurredAt, "HH:mm")}", style = MaterialTheme.typography.bodySmall, color = Muted)
            if (entry.status != Status.CONFIRMED) Text(entry.status.label, style = MaterialTheme.typography.labelSmall, color = Amber)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(if (entry.amountFen == 0L) "待补充" else "${if (entry.kind == Kind.REFUND || entry.kind == Kind.INCOME) "+" else ""}${displayMoney(entry.amountFen)}", fontWeight = FontWeight.SemiBold, color = if (entry.kind == Kind.REFUND) Pine else Ink)
            if (entry.kind !in listOf(Kind.EXPENSE, Kind.REFUND)) Text(entry.kind.label, style = MaterialTheme.typography.labelSmall, color = Muted)
        }
    }
}

@Composable fun BarChart(buckets: List<Bucket>, onBucket: (Int) -> Unit = {}) {
    if (buckets.isEmpty()) return
    val maximum = buckets.maxOf { abs(it.value) }.coerceAtLeast(1L)
    Row(Modifier.fillMaxWidth().height(125.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        buckets.forEachIndexed { index, bucket ->
            Column(Modifier.weight(1f).fillMaxHeight().clickable { onBucket(index) }
                .semantics { contentDescription = "${bucket.label}，净消费${money(bucket.value)}元" },
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                Box(Modifier.fillMaxWidth().height((92 * abs(bucket.value).toDouble() / maximum).coerceAtLeast(3.0).dp)
                    .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                    .background(if (bucket.value < 0) Color(0xFFB9CC87) else Pine))
                Spacer(Modifier.height(6.dp))
                Text(if (buckets.size <= 7 || index == 0 || index == buckets.lastIndex || index % (buckets.size / 4).coerceAtLeast(1) == 0) bucket.label else "", style = MaterialTheme.typography.labelSmall, fontSize = 9.sp, maxLines = 1, color = Muted)
            }
        }
    }
}

@Composable fun BreakdownItem(item: Breakdown, max: Long, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(item.key, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(displayMoney(item.value), fontWeight = FontWeight.SemiBold)
            Icon(Icons.Default.ChevronRight, contentDescription = "查看账单", tint = Muted, modifier = Modifier.size(18.dp))
        }
        Box(Modifier.fillMaxWidth().height(5.dp).clip(CircleShape).background(Cream)) {
            Box(Modifier.fillMaxWidth((abs(item.value).toFloat() / max.coerceAtLeast(1)).coerceIn(0.01f, 1f)).fillMaxHeight().background(if (item.value < 0) Color(0xFFB9CC87) else Pine))
        }
    }
}
