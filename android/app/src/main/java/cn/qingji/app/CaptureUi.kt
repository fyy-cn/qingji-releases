package cn.qingji.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable fun NotificationCapturePanel(health: Diagnostics, onAuthorize: () -> Unit, onReconnect: () -> Unit, onCopy: () -> Unit) {
    val n = health.notification
    Panel {
        SectionTitle("自动记账")
        Text(if (health.access) "通知使用权：已授权" else "通知使用权：未授权", color = if (health.access) Pine else Amber)
        Text(if (health.access && n.connected) "通知监听：已连接" else if (health.access) "通知监听：未连接，正在请求系统恢复" else "通知监听：等待授权", color = if (health.access && n.connected) Pine else Amber)
        Text("最近支持来源通知：${if (n.receivedAt == 0L) "尚未收到" else displayTime(n.receivedAt, "MM-dd HH:mm:ss")}\n${n.lastChannel} ${n.outcome}", style = MaterialTheme.typography.bodySmall, color = Muted)
        Text("回调/补读 ${n.received} 次 · 识别/补全 ${n.parsed} 次", style = MaterialTheme.typography.labelSmall, color = Muted)
        Text("最近连接检查：${if (n.checkedAt == 0L) "尚未完成" else displayTime(n.checkedAt, "MM-dd HH:mm:ss")}\n后台检查：${if (n.watchdogAt == 0L) "等待系统调度" else displayTime(n.watchdogAt, "MM-dd HH:mm:ss")}", style = MaterialTheme.typography.labelSmall, color = Muted)
        if (health.access && n.nextRetryAt > 0) Text("连接恢复中，将继续自动重试", color = Amber, style = MaterialTheme.typography.labelSmall)
        Text("最近候选账单：${if (health.lastAt == 0L) "尚未生成" else displayTime(health.lastAt, "MM-dd HH:mm:ss")} ${health.lastAction}", style = MaterialTheme.typography.labelSmall, color = Muted)
        if (health.error.isNotBlank()) Text(health.error, color = Amber)
        Button(onClick = onAuthorize) { Text(if (health.access) "管理通知授权" else "开启通知记账") }
        if (health.access) OutlinedButton(onClick = onReconnect) { Text("重连并补读当前通知") }
        TextButton(onClick = onCopy) { Text("复制通知诊断") }
        Text("断线后自动重试；后台定期检查连接，不依赖更新检查或网络。系统省电可能延迟恢复。补读仅限仍留在通知栏里的通知，已清除的通知需导入账单补齐。", style = MaterialTheme.typography.labelSmall, color = Muted)
    }
}

@Composable fun CampusSourcePanel(campus: CampusApp?, onSelect: () -> Unit, onRemove: () -> Unit) {
    Panel {
        SectionTitle("校园应用 · 交我办")
        Text(campus?.let { "已选择：${it.label}" } ?: "尚未选择校园应用", style = MaterialTheme.typography.titleSmall)
        Text("选择手机上的“交我办”后，采集该应用的交易通知；开启页面识别时也会尝试提取校园支出详情。校园记录先待确认，渠道单独显示。", style = MaterialTheme.typography.bodySmall, color = Muted)
        Button(onClick = onSelect) { Text(if (campus == null) "选择交我办" else "更换校园应用") }
        if (campus != null) TextButton(onClick = onRemove) { Text("停止采集校园应用") }
        Text("校园卡充值、余额与消费会分别判断；充值不算消费。实际格式需真机验证，普通公告不会按账单保存。", style = MaterialTheme.typography.labelSmall, color = Muted)
    }
}

@Composable fun CampusAppPicker(onDismiss: () -> Unit, onSelect: (CampusApp) -> Unit) {
    val context = LocalContext.current
    val apps by produceState<List<CampusApp>?>(null) { value = withContext(Dispatchers.IO) { runCatching { CampusSources.installed(context) }.getOrDefault(emptyList()) } }
    var query by remember { mutableStateOf("") }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth().padding(16.dp).imePadding().heightIn(max = 700.dp), color = Color.White, shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp)) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("选择手机上的交我办", style = MaterialTheme.typography.titleLarge)
                Text("从已安装应用中选择学校应用，核对名称和包名。选择后仅新增这一应用为采集来源。", style = MaterialTheme.typography.bodySmall, color = Muted)
                OutlinedTextField(query, { query = it }, singleLine = true, label = { Text("搜索应用名称") }, modifier = Modifier.fillMaxWidth())
                if (apps == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                LazyColumn(Modifier.weight(1f, fill = false).heightIn(max = 400.dp)) {
                    val visible = apps.orEmpty().filter { query.isBlank() || it.label.contains(query, true) || it.packageName.contains(query, true) }
                    if (apps != null && visible.isEmpty()) item { Text("没有找到，请确认已安装交我办，或尝试其完整应用名称。", color = Muted) }
                    items(visible, key = { it.packageName }) { app -> Column(Modifier.fillMaxWidth().clickable { onSelect(app) }.padding(vertical = 12.dp)) {
                        Text(app.label, style = MaterialTheme.typography.titleMedium)
                        Text(app.packageName, style = MaterialTheme.typography.labelSmall, color = Muted)
                        HorizontalDivider(Modifier.padding(top = 12.dp))
                    } }
                }
                TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("取消") }
            }
        }
    }
}
