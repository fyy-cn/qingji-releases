package cn.qingji.app

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.qingji.core.*
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.abs

@Composable fun HomeScreen(entries: List<Entry>, today: LocalDate, health: Diagnostics, lastImport: ImportReceipt?,
    onAuthorize: () -> Unit, onImport: () -> Unit, onPending: () -> Unit, onEntry: (Entry) -> Unit, onFilter: (EntryFilter) -> Unit, onAnalysis: () -> Unit) {
    val month = remember(entries, today) { Reports.build(entries, Period.MONTH, today, today) }
    val week = Reports.total(entries, Reports.fullRange(Period.WEEK, today))
    val day = Reports.total(entries, Reports.fullRange(Period.DAY, today))
    val pending = entries.count { it.status == Status.PENDING }
    val recent = entries.filter { it.status == Status.CONFIRMED }.sortedByDescending { it.occurredAt }.take(5)
    val last7 = (6L downTo 0L).map { days -> val date = today.minusDays(days); Bucket("${date.dayOfMonth}日", Reports.total(entries, Reports.fullRange(Period.DAY, date)).net) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp, 16.dp, 20.dp, 100.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("清记  /  QINGJI", color = Pine, style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(8.dp))
                    Text("把每一笔，记清楚。", style = MaterialTheme.typography.headlineMedium)
                }
                Text("${today.monthValue}月${today.dayOfMonth}日", color = Muted, style = MaterialTheme.typography.bodySmall)
            }
        }
        item {
            Panel(color = Pine, modifier = Modifier.clickable { onFilter(EntryFilter.range(month.range, "本月消费")) }) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("本月净消费", color = Color.White.copy(alpha = 0.8f), modifier = Modifier.weight(1f))
                    Surface(color = Lime, shape = RoundedCornerShape(30.dp)) { Text("${month.total.count} 笔支出", Modifier.padding(10.dp, 5.dp), color = Pine, style = MaterialTheme.typography.labelSmall) }
                }
                Text(displayMoney(month.total.net), color = Color.White, style = MaterialTheme.typography.headlineLarge, maxLines = 1)
                Text("较上月同期 ${deltaText(month.total.net, month.previousTotal.net)}", color = Lime, style = MaterialTheme.typography.bodySmall)
                HorizontalDivider(color = Color.White.copy(alpha = 0.16f))
                Row(Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f).clickable { onFilter(EntryFilter.range(Reports.fullRange(Period.DAY, today), "今日消费")) }) {
                        Text("今日", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall)
                        Text(displayMoney(day.net), color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Column(Modifier.weight(1f).clickable { onFilter(EntryFilter.range(Reports.fullRange(Period.WEEK, today), "本周消费")) }) {
                        Text("本周", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall)
                        Text(displayMoney(week.net), color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
        if (pending > 0) item {
            Panel(Modifier.clickable(onClick = onPending), color = Color(0xFFFFF0DC)) {
                Text("$pending 笔账单需要确认", color = Amber, style = MaterialTheme.typography.titleMedium)
                Text("待确认记录暂不计入消费，点这里补充信息或处理疑似重复。", color = Amber, style = MaterialTheme.typography.bodySmall)
            }
        }
        item {
            Panel {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (health.access) Icons.Default.NotificationsActive else Icons.Default.NotificationsOff, null, tint = Pine)
                    Spacer(Modifier.width(10.dp))
                    Text(if (health.access && health.notification.connected) "通知记账已连接" else if (health.access) "通知监听待连接" else "开启自动记账", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = if (health.access) onImport else onAuthorize) { Text(if (health.access) "导入补齐" else "去开启") }
                }
                Text(if (health.access && !health.notification.connected) "正在请求系统恢复连接；可到设置重连、补读并查看通知诊断。" else if (health.access) "记录可识别通知，校园应用可在设置中选择，仍需定期核对账单。" else "授权后识别支付通知，校园应用可在设置中选择，也可先导入历史账单。", color = Muted, style = MaterialTheme.typography.bodySmall)
                Text("最近导入：${lastImport?.let { displayTime(it.importedAt) } ?: "尚未导入"} · 统计仅含已确认账单", color = Muted, style = MaterialTheme.typography.labelSmall)
                if (!health.access) OutlinedButton(onClick = onImport) { Text("导入历史账单") }
            }
        }
        item {
            Panel {
                SectionTitle("近7天净消费", "查看分析", onAnalysis)
                BarChart(last7) { index -> onFilter(EntryFilter.range(Reports.fullRange(Period.DAY, today.minusDays(6L - index)), "${today.minusDays(6L - index)} 消费")) }
            }
        }
        item { SectionTitle("最近账单") }
        if (recent.isEmpty()) item { EmptyState("账本从这一笔开始", "点击“记一笔”补记消费，或在设置中导入微信、支付宝账单。") }
        else item { Panel { recent.forEach { EntryItem(it) { onEntry(it) } } } }
    }
}

@Composable fun LedgerScreen(entries: List<Entry>, filter: EntryFilter?, onClear: () -> Unit, onEntry: (Entry) -> Unit, onExport: (List<Entry>) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var state by rememberSaveable { mutableStateOf("有效记录") }
    var channel by rememberSaveable { mutableStateOf("全部渠道") }
    val selected = remember(entries, filter, query, state, channel) {
        entries.filter { entry ->
            (if (filter != null) filter.matches(entry) else when (state) { "待确认" -> entry.status == Status.PENDING; "已忽略" -> entry.status == Status.IGNORED; else -> entry.status != Status.IGNORED }) &&
                (channel == "全部渠道" || entry.channel.label == channel) &&
                (query.isBlank() || listOf(entry.merchant, entry.note, entry.category.label, entry.transactionId.orEmpty()).any { it.contains(query.trim(), true) })
        }.sortedByDescending { it.occurredAt }
    }
    val grouped = selected.groupBy { displayTime(it.occurredAt, "yyyy-MM-dd") }
    val total = selected.filter { it.status == Status.CONFIRMED && it.currency == "CNY" }.sumOf { when (it.kind) { Kind.EXPENSE -> it.amountFen; Kind.REFUND -> -it.amountFen; else -> 0 } }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp, 16.dp, 20.dp, 100.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("账单", Modifier.weight(1f), style = MaterialTheme.typography.headlineLarge)
            TextButton(onClick = { onExport(selected) }, enabled = selected.isNotEmpty()) { Text("导出CSV") }
        } }
        item { OutlinedTextField(query, { query = it }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), singleLine = true, placeholder = { Text("搜索商家、分类、备注或交易号") }, leadingIcon = { Icon(Icons.Default.Search, "搜索") }) }
        if (filter != null) item {
            Panel(color = Color(0xFFE5EFE6)) {
                Text(filter.title, style = MaterialTheme.typography.titleMedium)
                if (filter.category != null || filter.merchant != null) Text(listOfNotNull(filter.category, filter.merchant).joinToString(" · "), color = Pine)
                TextButton(onClick = onClear) { Text("清除分析筛选") }
            }
        } else item { Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("有效记录", "待确认", "已忽略").forEach { label -> FilterChip(selected = state == label, onClick = { state = label }, label = { Text(label) }) }
        } }
        item { Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (listOf("全部渠道") + Channel.entries.map { it.label }).forEach { label -> FilterChip(selected = channel == label, onClick = { channel = label }, label = { Text(label) }) }
        } }
        item { Text("共 ${selected.size} 笔 · 已确认净消费 ${displayMoney(total)}", color = Muted, style = MaterialTheme.typography.bodySmall) }
        if (selected.isEmpty()) item { EmptyState("这里还没有账单", "可以清除筛选、导入账单，或点击“记一笔”添加。") }
        grouped.forEach { (date, dayEntries) ->
            item(key = "date:$date") { Text(date, color = Muted, style = MaterialTheme.typography.labelLarge) }
            items(dayEntries, key = { it.id }) { entry -> Surface(shape = RoundedCornerShape(18.dp), color = Color.White) { Box(Modifier.padding(horizontal = 12.dp)) { EntryItem(entry) { onEntry(entry) } } } }
        }
    }
}

@Composable fun AnalysisScreen(entries: List<Entry>, today: LocalDate, onFilter: (EntryFilter) -> Unit) {
    var period by rememberSaveable { mutableStateOf(Period.MONTH) }
    var anchorText by rememberSaveable { mutableStateOf(today.toString()) }
    val anchor = LocalDate.parse(anchorText)
    val report = remember(entries, period, anchor, today) { Reports.build(entries, period, anchor, today) }
    val grossCategories = entries.filter { it.status == Status.CONFIRMED && it.currency == "CNY" && it.kind == Kind.EXPENSE && report.range.contains(it.occurredAt) }
        .groupBy { it.category.label }.map { (label, list) -> Breakdown(label, list.sumOf { it.amountFen }, 0) }.sortedByDescending { it.value }
    val next = Reports.shift(period, anchor, 1)
    fun bucket(index: Int) {
        when (period) {
            Period.DAY -> {
                val start = report.range.start.atStartOfDay(ledgerZone).plusHours(index.toLong()).toInstant().toEpochMilli()
                onFilter(EntryFilter("${report.range.start} ${index}时", start, start + 3_600_000))
            }
            Period.YEAR -> { val month = report.range.start.withMonth(index + 1); onFilter(EntryFilter.range(Reports.fullRange(Period.MONTH, month), "${month.year}年${month.monthValue}月")) }
            else -> { val date = report.range.start.plusDays(index.toLong()); onFilter(EntryFilter.range(Reports.fullRange(Period.DAY, date), "$date 消费")) }
        }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp, 16.dp, 20.dp, 28.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item { Text("消费分析", style = MaterialTheme.typography.headlineLarge) }
        item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Period.entries.forEach { p -> FilterChip(selected = period == p, onClick = { period = p }, label = { Box(Modifier.width(44.dp), contentAlignment = Alignment.Center) { Text(p.label) } }) }
        } }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { anchorText = Reports.shift(period, anchor, -1).toString() }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "上一个周期") }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(report.range.label, style = MaterialTheme.typography.labelLarge)
                    TextButton(onClick = { anchorText = today.toString() }) { Text("回到本期") }
                }
                IconButton(onClick = { anchorText = next.toString() }, enabled = Reports.fullRange(period, next).start <= Reports.fullRange(period, today).start) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "下一个周期") }
            }
        }
        item {
            Panel {
                Text("净消费", color = Muted)
                Text(displayMoney(report.total.net), style = MaterialTheme.typography.headlineLarge, modifier = Modifier.clickable { onFilter(EntryFilter.range(report.range)) })
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column { Text("支出", color = Muted, style = MaterialTheme.typography.bodySmall); Text(displayMoney(report.total.expense), fontWeight = FontWeight.SemiBold) }
                    Column { Text("退款", color = Muted, style = MaterialTheme.typography.bodySmall); Text(displayMoney(report.total.refund), color = Pine, fontWeight = FontWeight.SemiBold) }
                    Column { Text("日均净消费", color = Muted, style = MaterialTheme.typography.bodySmall); Text(displayMoney(report.total.net / report.days), fontWeight = FontWeight.SemiBold) }
                }
                HorizontalDivider()
                Text("较上期 ${deltaText(report.total.net, report.previousTotal.net)}", color = Pine)
                Text("对比 ${report.previous.label}", color = Muted, style = MaterialTheme.typography.bodySmall)
                Text("同比 ${deltaText(report.total.net, report.yearAgoTotal.net)}", color = Muted, style = MaterialTheme.typography.bodySmall)
                Text("未结束周期按相同进度比较；仅统计已确认的人民币消费。", color = Muted, style = MaterialTheme.typography.labelSmall)
            }
        }
        item { Panel { SectionTitle("消费趋势"); BarChart(report.buckets, ::bucket); Text("点击柱形查看账单 · 退款按到账日抵减", color = Muted, style = MaterialTheme.typography.labelSmall) } }
        item {
            Panel {
                SectionTitle("消费去向 · 支出占比")
                if (grossCategories.isEmpty()) Text("本期暂无已确认支出", color = Muted)
                grossCategories.forEach { item ->
                    val percentage = if (report.total.expense == 0L) 0.0 else item.value.toDouble() * 100 / report.total.expense
                    BreakdownItem(item.copy(key = "${item.key} · ${"%.1f".format(java.util.Locale.CHINA, percentage)}%"), report.total.expense) { onFilter(EntryFilter.range(report.range, "${item.key}消费", category = item.key)) }
                }
            }
        }
        item {
            Panel {
                SectionTitle("变化来自哪里")
                Text("分类净消费较上期增减（含退款）", color = Muted, style = MaterialTheme.typography.bodySmall)
                val changes = report.categories.filter { it.delta != 0L }.sortedByDescending { abs(it.delta) }.take(5)
                if (changes.isEmpty()) Text("暂无可比较的变化", color = Muted)
                changes.forEach { item -> Row(Modifier.fillMaxWidth().clickable { onFilter(EntryFilter.range(report.range, "${item.key}本期明细", category = item.key)) }.padding(vertical = 8.dp)) {
                    Text(item.key, Modifier.weight(1f)); Text("${if (item.delta > 0) "+" else ""}${displayMoney(item.delta)}", color = if (item.delta > 0) Amber else Pine, fontWeight = FontWeight.SemiBold)
                } }
            }
        }
        item {
            Panel {
                SectionTitle("商家净消费排名")
                if (report.merchants.isEmpty()) Text("本期暂无商家记录", color = Muted)
                report.merchants.take(10).forEach { item ->
                    BreakdownItem(item, report.merchants.maxOfOrNull { abs(it.value) } ?: 1) { onFilter(EntryFilter.range(report.range, "商家明细", merchant = item.key)) }
                    Text("较上期 ${if (item.delta > 0) "+" else ""}${displayMoney(item.delta)}", color = Muted, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable fun SettingsScreen(health: Diagnostics, rules: List<MerchantRule>, imports: List<ImportReceipt>,
    onAuthorize: () -> Unit, onImport: () -> Unit, onBackup: () -> Unit, onRestore: () -> Unit,
    onRemoveRule: (MerchantRule) -> Unit, onReconnect: () -> Unit, onAppSettings: () -> Unit,
    updateState: UpdateUiState, onCheckUpdate: () -> Unit, onBackgroundUpdate: (Boolean) -> Unit, onOpenUpdate: () -> Unit,
    categoryCount: Int, onManageCategories: () -> Unit, onExportAll: () -> Unit,
    onEnablePage: () -> Unit, onDisablePage: () -> Unit, onViewPending: () -> Unit, onCopyPageDiagnostic: () -> Unit,
    onSelectCampus: () -> Unit, onRemoveCampus: () -> Unit, onCopyNotificationDiagnostic: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp, 16.dp, 20.dp, 28.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item { Text("设置", style = MaterialTheme.typography.headlineLarge) }
        item { Panel(color = Color(0xFFE5EFE6)) {
            Text("你的账本，留在你的手机里。", style = MaterialTheme.typography.titleMedium)
            Text("无需登录 · 本地识别 · 联网仅用于检查和下载更新", color = Pine, style = MaterialTheme.typography.bodySmall)
        } }
        item { NotificationCapturePanel(health, onAuthorize, onReconnect, onCopyNotificationDiagnostic) }
        item { CampusSourcePanel(health.campus, onSelectCampus, onRemoveCampus) }
        item { PaymentPagePanel(health.page, onEnablePage, onDisablePage, onViewPending, onCopyPageDiagnostic) }
        item { UpdatePanel(updateState, onCheckUpdate, onBackgroundUpdate, onOpenUpdate) }
        if (Build.MANUFACTURER.equals("Xiaomi", true) || Build.BRAND.equals("Redmi", true)) item { Panel {
            SectionTitle("小米手机后台设置")
            Text("若锁屏后漏记：在系统设置中搜索“自启动”，允许清记后台自启动；再检查应用信息中的省电或后台限制。菜单随澎湃OS版本变化，以本机显示为准。", color = Muted, style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = onAppSettings) { Text("打开清记应用信息") }
            Text("微信、支付宝本身也需能正常发布支付通知。建议锁屏与重启后各做一次实际验证。", color = Muted, style = MaterialTheme.typography.labelSmall)
        } }
        item { Panel {
            SectionTitle("导入与备份")
            Text("支持带标准表头的微信 / 支付宝 CSV，以及 XLSX。账单ZIP需先解压；不支持PDF、旧版XLS或加密工作簿。导入前会预览。", color = Muted, style = MaterialTheme.typography.bodySmall)
            Button(onClick = onImport) { Icon(Icons.Default.FileUpload, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("导入账单") }
            OutlinedButton(onClick = onExportAll) { Text("导出全部账单（CSV）") }
            Text("也可在账单页搜索、筛选后导出当前结果。CSV用于表格查看，完整恢复请用加密备份。", color = Muted, style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = onBackup) { Text("创建加密备份") }
            OutlinedButton(onClick = onRestore) { Text("从备份恢复") }
            Text("备份使用独立口令加密；忘记口令无法恢复。卸载应用会删除本机账本，请先保存备份到安全位置。", color = Muted, style = MaterialTheme.typography.bodySmall)
        } }
        item { Panel {
            SectionTitle("消费分类")
            Text("$categoryCount 个可用分类 · 支持排序与删除", color = Muted, style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = onManageCategories) { Text("管理消费分类") }
        } }
        item { SectionTitle("商家分类规则 · ${rules.size} 条") }
        if (rules.isEmpty()) item { Panel { Text("修改账单时勾选“记住该商家的分类”，后续交易将优先使用你的规则。", color = Muted, style = MaterialTheme.typography.bodySmall) } }
        items(rules, key = { normalizeMerchant(it.merchant) }) { rule -> Panel {
            Row(verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(rule.merchant); Text(rule.category.label, color = Pine, style = MaterialTheme.typography.bodySmall) }; TextButton(onClick = { onRemoveRule(rule) }) { Text("删除规则") } }
        } }
        if (imports.isNotEmpty()) item { Panel {
            SectionTitle("最近导入")
            imports.sortedByDescending { it.importedAt }.take(5).forEach { receipt -> Column { Text(receipt.name, style = MaterialTheme.typography.bodyMedium); Text(displayTime(receipt.importedAt, "yyyy-MM-dd HH:mm"), color = Muted, style = MaterialTheme.typography.labelSmall) } }
        } }
        item { Panel {
            SectionTitle("真机诊断信息")
            Text("${Build.MANUFACTURER} ${Build.MODEL}\nAndroid ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}\n微信 ${health.wechat}\n支付宝 ${health.alipay}", color = Muted, style = MaterialTheme.typography.bodySmall)
            Text("验证方法：进行日常消费后检查通知和账单，连续7天导入对账。未发布通知、历史交易与现金消费无法通过通知补齐。", color = Muted, style = MaterialTheme.typography.bodySmall)
            Text("清记 ${BuildConfig.VERSION_NAME} · 人民币 · 北京时间 · 周一为周起点", color = Muted, style = MaterialTheme.typography.labelSmall)
        } }
    }
}
