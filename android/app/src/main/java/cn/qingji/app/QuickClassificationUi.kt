package cn.qingji.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import cn.qingji.core.*

@Composable fun TemplateChips(templates: List<QuickTemplate>, onSelect: (QuickTemplate) -> Unit) {
    val recent = templates.filter { it.lastUsedAt > 0 }.sortedByDescending { it.lastUsedAt }.take(3)
    if (recent.isNotEmpty()) {
        Text("最近用过", color = Muted, style = MaterialTheme.typography.labelSmall)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { items(recent, key = { it.id }) { t -> SuggestionChip(onClick = { onSelect(t) }, label = { Text(t.label) }, modifier = Modifier.testTag("recent-template-${t.id}")) } }
    }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { items(templates, key = { it.id }) { t -> AssistChip(onClick = { onSelect(t) }, label = { Text(t.label) }, modifier = Modifier.testTag("template-${t.id}")) } }
}

@Composable fun QuickClassificationScreen(entries: List<Entry>, categories: List<Category>, templates: List<QuickTemplate>, busy: Boolean,
    onDismiss: () -> Unit, onEdit: (Entry) -> Unit, onCategory: (Set<String>, Category) -> Unit, onTemplate: (Set<String>, String) -> Unit,
    onManageTemplates: () -> Unit, onViewPending: () -> Unit) {
    val pending = entries.count { it.status == Status.PENDING }
    var channel by remember { mutableStateOf("全部渠道") }
    var choosing by remember { mutableStateOf(false) }
    var selection by remember { mutableStateOf(setOf<String>()) }
    var categoryTargets by remember { mutableStateOf<Set<String>?>(null) }
    var templateTargets by remember { mutableStateOf<Set<String>?>(null) }
    val records = remember(entries, channel) { entries.filter { QuickClassification.needsCategory(it) && (channel == "全部渠道" || it.channel.label == channel) }.sortedByDescending { it.occurredAt } }
    val ids = records.map { it.id }.toSet()
    val usable = categories.filter { it != Category.UNCATEGORIZED }
    LaunchedEffect(ids) { selection = selection.intersect(ids) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onDismiss) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回账单") }
            Text("快捷归类", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = onManageTemplates) { Text("管理模板") }
        }
        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("待归类 ${records.size} 笔 · 选择消费分类", color = Pine, style = MaterialTheme.typography.titleSmall)
            Text("点分类或套用模板即可归类。待确认记录只修改用途，核实后才计入消费。", color = Muted, style = MaterialTheme.typography.bodySmall)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { items(listOf("全部渠道") + Channel.entries.map { it.label }) { label ->
                FilterChip(selected = channel == label, onClick = { channel = label; selection = emptySet() }, label = { Text(label) })
            } }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { choosing = !choosing; selection = emptySet() }, enabled = !busy) { Text(if (choosing) "结束多选" else "批量选择") }
                if (choosing) TextButton(onClick = { selection = if (selection == ids) emptySet() else ids }, enabled = !busy && ids.isNotEmpty()) { Text(if (selection == ids) "清空选择" else "全选当前列表") }
                if (pending > 0 && !choosing) TextButton(onClick = onViewPending) { Text("待确认 $pending 笔") }
            }
            if (choosing) {
                Text("已选 ${selection.size} 笔", style = MaterialTheme.typography.labelLarge, color = Pine)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = !busy && selection.isNotEmpty(), onClick = { categoryTargets = selection.toSet() }) { Text("批量设分类") }
                    OutlinedButton(enabled = !busy && selection.isNotEmpty(), onClick = { templateTargets = selection.toSet() }) { Text("批量套模板") }
                }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        LazyColumn(Modifier.weight(1f).testTag("quick-records"), contentPadding = PaddingValues(20.dp, 12.dp, 20.dp, 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (records.isEmpty()) item { EmptyState("这一页归类完成了", "已归类的记录可在账单中继续编辑；待确认记录仍需核实金额和交易类型。") }
            items(records, key = { it.id }) { entry -> Panel(modifier = Modifier.testTag("quick-entry-${entry.id}")) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (choosing) Checkbox(checked = entry.id in selection, enabled = !busy, onCheckedChange = { checked -> selection = if (checked) selection + entry.id else selection - entry.id }, modifier = Modifier.testTag("choose-${entry.id}"))
                    Column(Modifier.weight(1f)) {
                        Text(if (entry.amountFen > 0) displayMoney(entry.amountFen) else "金额待核实", style = MaterialTheme.typography.headlineSmall)
                        Text("${displayTime(entry.occurredAt)} · ${entry.channel.label}", color = Muted, style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = { onEdit(entry) }) { Text(if (entry.status == Status.PENDING) "去核实" else "详情") }
                }
                if (entry.note.isNotBlank()) Text(entry.note, maxLines = 2, overflow = TextOverflow.Ellipsis, color = Muted, style = MaterialTheme.typography.bodySmall)
                if (entry.status == Status.PENDING) Text("待确认 · 本次归类不会自动确认交易", color = Amber, style = MaterialTheme.typography.labelSmall)
                if (!choosing) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(usable.take(3), key = { it.name }) { category -> AssistChip(enabled = !busy, onClick = { onCategory(setOf(entry.id), category) }, label = { Text(category.label) }) }
                        item { AssistChip(enabled = !busy, onClick = { categoryTargets = setOf(entry.id) }, label = { Text("更多分类") }) }
                    }
                    OutlinedButton(enabled = !busy, onClick = { templateTargets = setOf(entry.id) }) { Text("套用场景模板") }
                }
            } }
        }
    }
    categoryTargets?.let { targets -> ClassificationChoiceDialog("选择消费分类", onDismiss = { categoryTargets = null }) {
        LazyColumn(Modifier.heightIn(max = 420.dp)) { items(usable, key = { it.name }) { category ->
            ListItem(headlineContent = { Text(category.label) }, modifier = Modifier.clickable { categoryTargets = null; onCategory(targets, category) })
        } }
    } }
    templateTargets?.let { targets -> ClassificationChoiceDialog("选择场景模板", onDismiss = { templateTargets = null }) {
        Text("分类会按模板修改；备注仅在空白时补充，已有备注会保留。", color = Muted, style = MaterialTheme.typography.bodySmall)
        val usableTemplates = templates.filter { it.category in usable }
        if (usableTemplates.isEmpty()) {
            Text("还没有可用模板，请先创建模板或为模板选择分类。", color = Muted)
            Button(onClick = { templateTargets = null; onManageTemplates() }) { Text("创建场景模板") }
        } else LazyColumn(Modifier.heightIn(max = 420.dp)) { items(usableTemplates, key = { it.id }) { template ->
            ListItem(headlineContent = { Text(template.label) }, supportingContent = { Text(listOfNotNull(template.category.label, template.note.takeIf { it.isNotBlank() }).joinToString(" · ")) },
                modifier = Modifier.clickable { templateTargets = null; onTemplate(targets, template.id) })
        } }
    } }
}

@Composable private fun ClassificationChoiceDialog(title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth().padding(16.dp).imePadding().heightIn(max = 700.dp), shape = RoundedCornerShape(24.dp), color = Color.White) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                content()
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text("关闭") }
            }
        }
    }
}

@Composable fun TemplateManagerDialog(templates: List<QuickTemplate>, categories: List<Category>, busy: Boolean, onDismiss: () -> Unit,
    onSave: (QuickTemplate, () -> Unit) -> Unit, onDelete: (String) -> Unit, onMove: (String, Int) -> Unit, onStarters: () -> Unit) {
    var editing by remember { mutableStateOf<QuickTemplate?>(null) }
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<QuickTemplate?>(null) }
    if (creating || editing != null) {
        TemplateEditorDialog(editing, templates, categories, busy, onDismiss = { creating = false; editing = null }, onSave = { template -> onSave(template) { creating = false; editing = null } })
    } else ClassificationChoiceDialog("场景模板", onDismiss) {
        Text("选择分类，按需填写备注。设置一次，以后点一下就能归类。", color = Muted, style = MaterialTheme.typography.bodySmall)
        LazyColumn(Modifier.weight(1f, fill = false).heightIn(max = 420.dp)) {
            if (templates.isEmpty()) item { Text("还没有模板，可新增自己的场景，或添加早餐、午餐等常用模板。", color = Muted) }
            items(templates, key = { it.id }) { template ->
                var menu by remember { mutableStateOf(false) }
                val index = templates.indexOfFirst { it.id == template.id }
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(template.label, style = MaterialTheme.typography.titleMedium)
                        Text(if (template.category == Category.UNCATEGORIZED) "需重新选择分类" else template.category.label, color = if (template.category == Category.UNCATEGORIZED) Amber else Muted, style = MaterialTheme.typography.bodySmall)
                        if (template.note.isNotBlank()) Text(template.note, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
                    }
                    IconButton(enabled = !busy && index > 0, onClick = { onMove(template.id, -1) }) { Icon(Icons.Default.ArrowUpward, "上移模板${template.label}", Modifier.size(18.dp)) }
                    IconButton(enabled = !busy && index < templates.lastIndex, onClick = { onMove(template.id, 1) }) { Icon(Icons.Default.ArrowDownward, "下移模板${template.label}", Modifier.size(18.dp)) }
                    Box {
                        IconButton(enabled = !busy, onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "管理模板${template.label}") }
                        DropdownMenu(menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("编辑模板") }, onClick = { menu = false; editing = template })
                            DropdownMenuItem(text = { Text("删除模板") }, onClick = { menu = false; deleting = template })
                        }
                    }
                }
                HorizontalDivider()
            }
        }
        Row(Modifier.fillMaxWidth()) {
            TextButton(enabled = !busy && templates.size < 100, onClick = onStarters) { Text("添加常用模板") }
            Spacer(Modifier.weight(1f))
            Button(enabled = !busy && templates.size < 100, onClick = { creating = true }) { Text("新增模板") }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    }
    deleting?.let { template -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("删除模板“${template.label}”？") }, text = { Text("已套用过模板的账单会保留。") },
        confirmButton = { TextButton(enabled = !busy, onClick = { onDelete(template.id); deleting = null }) { Text("删除模板") } },
        dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } }) }
}

@Composable private fun TemplateEditorDialog(seed: QuickTemplate?, templates: List<QuickTemplate>, categories: List<Category>, busy: Boolean,
    onDismiss: () -> Unit, onSave: (QuickTemplate) -> Unit) {
    var label by remember { mutableStateOf(seed?.label.orEmpty()) }
    var note by remember { mutableStateOf(seed?.note.orEmpty()) }
    val usable = categories.filter { it != Category.UNCATEGORIZED }
    var category by remember { mutableStateOf(seed?.category?.takeIf { it in usable } ?: usable.firstOrNull() ?: Category.UNCATEGORIZED) }
    var error by remember { mutableStateOf("") }
    ClassificationChoiceDialog(if (seed == null) "新增场景模板" else "编辑场景模板", onDismiss) {
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(label, { label = it; error = "" }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("模板名称") }, placeholder = { Text("例如：二食堂") })
            ChoiceField("模板分类", category, usable, { it.label }, { category = it })
            OutlinedTextField(note, { note = it }, modifier = Modifier.fillMaxWidth(), label = { Text("模板备注（选填）") }, maxLines = 3)
            Text("套用模板会设置分类，只补充空白备注；金额、时间和已有备注会保留。", style = MaterialTheme.typography.labelSmall, color = Muted)
            if (error.isNotBlank()) Text(error, color = Amber)
        }
        Button(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = {
            val candidate = (seed ?: QuickTemplate(label = label.trim(), category = category)).copy(label = label.trim(), note = note.trim(), category = category)
            runCatching {
                QuickClassification.validate(candidate)
                require(candidate.category in usable) { "请选择具体分类" }
                require(templates.none { it.id != candidate.id && it.label.equals(candidate.label, true) }) { "模板名称已存在" }
            }.onSuccess { onSave(candidate) }.onFailure { error = it.message ?: "请检查模板内容" }
        }) { Text(if (busy) "正在保存…" else "保存模板") }
    }
}
