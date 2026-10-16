package cn.qingji.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable fun PaymentPagePanel(health: PaymentPageHealth, onEnable: () -> Unit, onDisable: () -> Unit,
    onViewPending: () -> Unit, onCopyDiagnostic: () -> Unit) {
    Panel {
        SectionTitle("支付页面识别（实验）")
        Text(when { !health.enabled -> "实验功能：关闭"; !health.access -> "已同意，等待系统无障碍授权"; else -> "已授权，等待支付页面" }, color = if (health.enabled && health.access) Pine else Muted)
        Text("尝试从微信、支付宝已显示的支付结果或交易详情提取商家、金额和时间，新识别记录先进入待确认。", style = MaterialTheme.typography.bodySmall, color = Muted)
        if (health.enabled) {
            Text("${health.channel} · ${health.message}", style = MaterialTheme.typography.bodySmall)
            if (health.lastAt > 0) Text("最近检测：${displayTime(health.lastAt, "MM-dd HH:mm:ss")}", style = MaterialTheme.typography.labelSmall, color = Muted)
            Text("本次服务已生成 ${health.matched} 次候选或字段补全；重复页面更新不会重复入账。", style = MaterialTheme.typography.labelSmall, color = Muted)
        }
        if (!health.enabled || !health.access) Button(onClick = onEnable) { Text(if (health.enabled) "前往系统授权" else "开启页面识别") }
        if (health.enabled) OutlinedButton(onClick = onDisable) { Text("关闭页面识别") }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onViewPending) { Text("查看待确认账单") }
            TextButton(onClick = onCopyDiagnostic) { Text("复制识别诊断") }
        }
        Text("系统页面读取权限范围较大，需要单独授权。功能默认关闭；模板尚需真机验证，读取不到时继续用通知或账单导入。", style = MaterialTheme.typography.labelSmall, color = Muted)
    }
}

@Composable fun PaymentPageDisclosure(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("启用支付页面识别实验") }, text = {
        Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("接下来会进入系统无障碍设置，需要你手动开启“清记 · 支付页面识别（实验）”。系统授予的页面读取权限范围较大，并非只授权一张支付页面。")
            Text("清记按微信、支付宝的支付页面模板过滤，采用只读方式提取商家、金额、交易时间与交易号。跳过密码、输入框和敏感节点；完整页面只在内存中短暂处理，不保存、不上传。")
            Text("新识别记录先进入待确认，核实后才计入消费。相同交易号只补全已有记录；疑似重复由你决定是否合并。")
            Text("当前是兼容性验证版本，不能保证所有支付页面都能读到；先回到支付App重新打开一笔支出的详情，再返回清记查看结果。可随时在设置中关闭。", color = Muted)
        }
    }, confirmButton = { TextButton(onClick = onConfirm) { Text("同意并前往授权") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("暂不开启") } })
}
