package cn.qingji.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import cn.qingji.core.*
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

@Composable fun <T> ChoiceField(label: String, selected: T, options: List<T>, name: (T) -> String, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(12.dp)) {
            Column(Modifier.weight(1f)) { Text(label, style = MaterialTheme.typography.labelSmall, color = Muted); Text(name(selected)) }
            Icon(Icons.Default.ExpandMore, "选择$label")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option -> DropdownMenuItem(text = { Text(name(option)) }, onClick = { onSelect(option); expanded = false }) }
        }
    }
}

@Composable fun EntryEditor(seed: Entry?, allEntries: List<Entry>, onDismiss: () -> Unit, onSave: (Entry, Boolean, String?) -> Unit,
    onIgnore: (Entry) -> Unit, onMerge: (String, String) -> Unit, categories: List<Category> = Category.entries, templates: List<QuickTemplate> = emptyList()) {
    var amount by remember(seed?.id) { mutableStateOf(seed?.amountFen?.takeIf { it > 0 }?.let(::money) ?: "") }
    var merchant by remember(seed?.id) { mutableStateOf(seed?.merchant?.takeUnless { it == "未知商家" } ?: "") }
    var category by remember(seed?.id) { mutableStateOf(seed?.category ?: Category.UNCATEGORIZED) }
    var kind by remember(seed?.id) { mutableStateOf(seed?.kind?.takeUnless { it == Kind.UNKNOWN } ?: Kind.EXPENSE) }
    var channel by remember(seed?.id) { mutableStateOf(seed?.channel ?: Channel.MANUAL) }
    var note by remember(seed?.id) { mutableStateOf(seed?.note ?: "") }
    val initialTime = remember(seed?.id) { Instant.ofEpochMilli(seed?.occurredAt ?: System.currentTimeMillis()).atZone(ledgerZone) }
    var date by remember(seed?.id) { mutableStateOf(initialTime.toLocalDate().toString()) }
    var time by remember(seed?.id) { mutableStateOf(initialTime.format(DateTimeFormatter.ofPattern("HH:mm:ss"))) }
    var makeRule by remember { mutableStateOf(false) }
    var showRaw by remember { mutableStateOf(false) }
    var yuanConfirmed by remember { mutableStateOf(seed == null || seed.currency == "CNY") }
    var error by remember { mutableStateOf("") }
    var appliedTemplateId by remember(seed?.id) { mutableStateOf<String?>(null) }
    var addCategory by remember { mutableStateOf(false) }
    fun save() {
        val fen = parseFen(amount)
        if (fen == null) { error = "请填写大于0且最多两位小数的金额"; return }
        val localDate = runCatching { LocalDate.parse(date, DateTimeFormatter.ISO_LOCAL_DATE.withResolverStyle(ResolverStyle.STRICT)) }.getOrNull()
        val localTime = runCatching { LocalTime.parse(time) }.getOrNull()
        if (localDate == null || localDate.year !in 2000..2100 || localTime == null) { error = "请输入有效日期和时间，例如2026-10-16、12:30:00"; return }
        if (merchant.length > 500 || note.length > 4000) { error = "商家最多500字，备注最多4000字"; return }
        if (!yuanConfirmed) { error = "请先核对人民币金额"; return }
        val at = localDate.atTime(localTime).atZone(ledgerZone).toInstant().toEpochMilli()
        val base = seed ?: Entry(occurredAt = at, channel = channel)
        val newMerchant = merchant.trim().ifBlank { "未知商家" }
        val edited = buildSet {
            addAll(base.editedFields)
            if (seed == null || fen != base.amountFen) add("amount")
            if (seed == null || newMerchant != base.merchant) add("merchant")
            if (seed == null || category != base.category) add("category")
            if (seed == null || note != base.note) add("note")
            if (seed == null || kind != base.kind) add("kind")
            if (seed == null || at != base.occurredAt) add("time")
        }
        onSave(base.copy(amountFen = fen, currency = "CNY", occurredAt = at, merchant = newMerchant, category = category, kind = kind, channel = channel,
            note = note, status = Status.CONFIRMED, editedFields = edited, reason = "", candidateIds = emptyList()), makeRule, appliedTemplateId)
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth().padding(12.dp).imePadding().heightIn(max = 760.dp), shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp), color = Color.White) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (seed == null) "记一笔" else if (seed.status == Status.PENDING) "确认账单" else "编辑账单", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "关闭") }
                }
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (seed?.reason?.isNotBlank() == true) Text(seed.reason, color = Amber, style = MaterialTheme.typography.bodyMedium)
                    val usableTemplates = templates.filter { it.category in categories && it.category != Category.UNCATEGORIZED }
                    if (usableTemplates.isNotEmpty()) {
                        Text("快捷场景", style = MaterialTheme.typography.labelLarge, color = Pine)
                        TemplateChips(usableTemplates) { template ->
                            category = template.category
                            if (merchant.isBlank()) merchant = template.merchant
                            if (note.isBlank()) note = template.note
                            appliedTemplateId = template.id
                        }
                    }
                    if (seed != null && seed.currency != "CNY") {
                        Text("原币种：${seed.currency}。暂不提供汇率换算，请核对实际人民币支出后填写。", color = Amber)
                        Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(yuanConfirmed, { yuanConfirmed = it }); Text("金额已核对为人民币", style = MaterialTheme.typography.bodySmall) }
                    }
                    OutlinedTextField(amount, { amount = it; error = "" }, label = { Text("金额（元）") }, prefix = { Text("¥ ") }, singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ChoiceField("交易类型", kind, Kind.entries.filter { it != Kind.UNKNOWN }, { it.label }, { kind = it }, Modifier.weight(1f))
                        ChoiceField("支付渠道", channel, Channel.entries, { it.label }, { channel = it }, Modifier.weight(1f))
                    }
                    OutlinedTextField(merchant, { merchant = it }, label = { Text("商家 / 收款方（选填）") }, supportingText = { Text("可以留空，先把消费分好类。") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    ChoiceField("消费分类", category, (categories + category).distinct(), { it.label }, { category = it })
                    TextButton(onClick = { addCategory = true }, enabled = categories.count { it.isCustom } < 100) { Text("新增自定义分类") }
                    if (addCategory) CategoryNameFields(null, categories + category, onCancel = { addCategory = false }, onSave = { name -> category = Category.custom(name); addCategory = false })
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(date, { date = it }, label = { Text("日期") }, singleLine = true, modifier = Modifier.weight(1.2f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii))
                        OutlinedTextField(time, { time = it }, label = { Text("时间") }, singleLine = true, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii))
                    }
                    Text("时间均按北京时间记录；退款填写实际到账时间。", color = Muted, style = MaterialTheme.typography.labelSmall)
                    OutlinedTextField(note, { note = it }, label = { Text("备注") }, modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 4)
                    Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(makeRule, { makeRule = it }); Text("记住该商家的分类，用于后续交易", style = MaterialTheme.typography.bodySmall) }
                    if (kind == Kind.TRANSFER || kind == Kind.INCOME) Text("此类型默认不计入消费统计。", color = Muted, style = MaterialTheme.typography.bodySmall)
                    if (seed != null) {
                        Text("来源：${seed.sources.joinToString("、")}\n交易号：${seed.transactionId ?: "未提供"}", color = Muted, style = MaterialTheme.typography.labelSmall)
                        if (seed.raw.isNotBlank()) {
                            TextButton(onClick = { showRaw = !showRaw }) { Text(if (showRaw) "收起识别依据" else "查看识别依据") }
                            if (showRaw) Text(seed.raw, color = Muted, style = MaterialTheme.typography.bodySmall)
                        }
                        seed.candidateIds.mapNotNull { id -> allEntries.firstOrNull { it.id == id && it.status != Status.IGNORED } }.forEach { candidate ->
                            OutlinedButton(onClick = { onMerge(seed.id, candidate.id) }, modifier = Modifier.fillMaxWidth()) {
                                Text("合并至 ${candidate.merchant} ${displayMoney(candidate.amountFen)}\n${displayTime(candidate.occurredAt)}")
                            }
                        }
                    }
                    if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (seed != null && seed.status != Status.IGNORED) TextButton(onClick = { onIgnore(seed) }) { Text("忽略") }
                    Button(onClick = ::save, modifier = Modifier.weight(1f)) { Text(if (seed?.candidateIds?.isNotEmpty() == true) "确认保留为独立交易" else "保存账单") }
                }
            }
        }
    }
}

@Composable fun CategoryNameFields(seed: Category?, existing: List<Category>, onCancel: () -> Unit, onSave: (String) -> Unit) {
    var name by remember(seed?.name) { mutableStateOf(seed?.label.orEmpty()) }
    var error by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(name, { name = it; error = "" }, label = { Text("分类名称") }, singleLine = true, supportingText = { Text("1至20个字，例如宠物、学习、咖啡") })
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        Row {
        TextButton(onClick = {
        val next = runCatching { Category.custom(name) }.getOrElse { error = it.message.orEmpty(); return@TextButton }
        if (existing.any { it != seed && it.label.equals(next.label, true) }) { error = "分类名称已存在"; return@TextButton }
        onSave(next.label)
        }) { Text("保存分类") }
        TextButton(onClick = onCancel) { Text("取消") }
        }
    }
}

@Composable fun CategoryManagerDialog(categories: List<Category>, entries: List<Entry>, rules: List<MerchantRule>, busy: Boolean,
    onDismiss: () -> Unit, onAdd: (String) -> Unit, onRename: (Category, String) -> Unit, onDelete: (Category) -> Unit,
    onMove: (Category, Int) -> Unit, onRestoreBuiltIns: () -> Unit) {
    var showName by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Category?>(null) }
    var deleting by remember { mutableStateOf<Category?>(null) }
    val counts = remember(entries) { entries.groupingBy { it.category }.eachCount() }
    val ruleCounts = remember(rules) { rules.groupingBy { it.category }.eachCount() }
    if (deleting == null) Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth().padding(12.dp).imePadding().heightIn(max = 740.dp), shape = RoundedCornerShape(24.dp), color = Color.White) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("消费分类", style = MaterialTheme.typography.titleLarge)
                        Text("${categories.size}个分类 · 按你的习惯排列", style = MaterialTheme.typography.bodySmall, color = Muted)
                    }
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "关闭分类管理") }
                }
                Text("用上下箭头调整顺序，记账时同步。除“未分类”外均可删除。", color = Muted, style = MaterialTheme.typography.bodySmall)
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (showName) CategoryNameFields(editing, categories, onCancel = { showName = false }, onSave = { name ->
                        val old = editing; if (old == null) onAdd(name) else onRename(old, name); showName = false
                    })
                    categories.forEachIndexed { index, category -> key(category.name) {
                        var menu by remember { mutableStateOf(false) }
                        Row(Modifier.fillMaxWidth().background(Cream, RoundedCornerShape(16.dp)).padding(start = 12.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Surface(color = Lime, shape = RoundedCornerShape(12.dp), modifier = Modifier.size(36.dp)) {
                                Box(contentAlignment = Alignment.Center) { Text(if (category.isCustom) category.label.take(1) else category.glyph, color = Pine, style = MaterialTheme.typography.titleMedium) }
                            }
                            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                                Text(category.label, style = MaterialTheme.typography.titleSmall)
                                Text("${counts[category] ?: 0}笔账单 · ${ruleCounts[category] ?: 0}条规则", color = Muted, style = MaterialTheme.typography.labelSmall)
                            }
                            IconButton(enabled = !busy && index > 0, onClick = { onMove(category, -1) }) { Icon(Icons.Default.ArrowUpward, "上移${category.label}", Modifier.size(18.dp)) }
                            IconButton(enabled = !busy && index < categories.lastIndex, onClick = { onMove(category, 1) }) { Icon(Icons.Default.ArrowDownward, "下移${category.label}", Modifier.size(18.dp)) }
                            Box {
                                IconButton(enabled = !busy && category != Category.UNCATEGORIZED, onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "管理${category.label}", Modifier.size(20.dp)) }
                                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                    if (category.isCustom) DropdownMenuItem(text = { Text("重命名") }, onClick = { menu = false; editing = category; showName = true })
                                    DropdownMenuItem(text = { Text("删除分类", color = MaterialTheme.colorScheme.error) }, onClick = { menu = false; deleting = category })
                                }
                            }
                        }
                    } }
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(enabled = !busy, onClick = onRestoreBuiltIns) { Text("恢复内置") }
                    Spacer(Modifier.weight(1f))
                    TextButton(enabled = !busy && categories.count { it.isCustom } < 100, onClick = { editing = null; showName = true }) { Text("新增分类") }
                    TextButton(onClick = onDismiss) { Text("完成") }
                }
            }
        }
    }
    deleting?.let { category -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("删除“${category.label}”？") },
        text = { Text("${counts[category] ?: 0}笔相关账单会改为“未分类”，${ruleCounts[category] ?: 0}条商家规则会被移除。账单和金额会保留，使用此分类的场景模板需重新选分类。" + if (!category.isCustom) "\n\n之后可用“恢复内置”重新添加此分类，原账单不会自动改回。" else "") },
        confirmButton = { TextButton(onClick = { onDelete(category); deleting = null }, enabled = !busy) { Text("删除分类") } },
        dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } }) }
}

@Composable fun ImportDialog(preview: ImportPreview, onDismiss: () -> Unit, onCommit: () -> Unit, busy: Boolean) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("账单导入预览") }, text = {
        Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(preview.name, style = MaterialTheme.typography.titleMedium)
            Text("${preview.bill.channel.label} · 检查 ${preview.bill.rowCount} 行交易")
            if (preview.alreadyImported) Text("此文件已经导入，不会重复记账。", color = Pine)
            Text("新增 ${preview.newCount} 笔\n待确认 ${preview.pendingCount} 笔\n重复 / 补全 ${preview.duplicateCount} 笔\n异常 / 跳过 ${preview.bill.issues.size} 行", color = Pine)
            Text("待确认记录不计入消费；重复交易只合并来源和补充缺失信息，保留人工分类与备注。", color = Muted, style = MaterialTheme.typography.bodySmall)
            preview.items.take(50).forEach { item -> Column {
                Text("${item.action} · ${item.entry.merchant} · ${displayMoney(item.entry.amountFen)}", style = MaterialTheme.typography.bodySmall)
                Text("${item.entry.kind.label} · ${displayTime(item.entry.occurredAt)}", color = Muted, style = MaterialTheme.typography.labelSmall)
                if (item.entry.reason.isNotBlank()) Text(item.entry.reason, color = Amber, style = MaterialTheme.typography.labelSmall)
            } }
            if (preview.items.size > 50) Text("仅展示前50笔预览；确认后处理全部可识别记录。", color = Muted, style = MaterialTheme.typography.bodySmall)
            preview.bill.issues.take(20).forEach { Text("第${it.row}行：${it.message}", color = Amber, style = MaterialTheme.typography.bodySmall) }
        }
    }, confirmButton = { TextButton(onClick = onCommit, enabled = !preview.alreadyImported && !busy && preview.bill.entries.isNotEmpty()) { Text("确认导入") } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("关闭") } })
}

@Composable fun PasswordDialog(creating: Boolean, onDismiss: () -> Unit, onConfirm: (CharArray) -> Unit) {
    var password by remember { mutableStateOf("") }
    var repeat by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (creating) "设置备份口令" else "输入备份口令") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (creating) "至少8位。口令不会保存，忘记后无法恢复备份。" else "验证成功后将显示备份摘要，再确认是否替换本机数据。", style = MaterialTheme.typography.bodySmall, color = Muted)
            OutlinedTextField(password, { password = it }, label = { Text("备份口令") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
            if (creating) OutlinedTextField(repeat, { repeat = it }, label = { Text("再次输入口令") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = { TextButton(onClick = {
        if (password.length < 8) error = "口令至少8位"
        else if (creating && password != repeat) error = "两次口令不一致"
        else { val chars = password.toCharArray(); password = ""; repeat = ""; onConfirm(chars) }
    }) { Text(if (creating) "选择保存位置" else "验证备份") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}
