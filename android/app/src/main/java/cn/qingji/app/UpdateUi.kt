package cn.qingji.app

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri

@Composable fun UpdatePanel(state: UpdateUiState, onCheck: () -> Unit, onBackground: (Boolean) -> Unit, onOpen: () -> Unit) {
    val context = LocalContext.current
    fun notificationSettings() { runCatching { context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)) } }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        onBackground(state.background)
        if (!granted) notificationSettings()
    }
    Panel {
        SectionTitle("应用更新")
        Text("当前版本 ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleMedium)
        Text(state.message, color = if (state.release != null) Pine else Muted, style = MaterialTheme.typography.bodySmall)
        if (state.lastCheck > 0) Text("最近检查：${displayTime(state.lastCheck, "MM-dd HH:mm")}", color = Muted, style = MaterialTheme.typography.labelSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onCheck, enabled = !state.checking && !state.downloading) { Text(if (state.checking) "检查中…" else "检查更新") }
            if (state.release != null) Button(onClick = onOpen) { Text("查看新版") }
        }
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text("后台定期检查"); Text("每天检查一次，仅下载版本信息", color = Muted, style = MaterialTheme.typography.bodySmall) }
            Switch(checked = state.background, onCheckedChange = onBackground)
        }
        if (state.background && !state.notificationsAllowed) OutlinedButton(onClick = {
            if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                permission.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else notificationSettings()
        }) { Text("允许新版通知提醒") }
        Text(if (state.notificationsAllowed) "新版通知提醒已允许" else "通知未允许：仍可打开清记检查新版", color = Muted, style = MaterialTheme.typography.labelSmall)
        Text("后台时间受系统省电与网络影响。安装包在你确认后下载，安装时由系统确认，保留账本。", color = Muted, style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, BuildConfig.DOWNLOAD_PAGE.toUri())) } }) { Text("打开统一下载页") }
    }
}

@Composable fun AppUpdateDialog(state: UpdateUiState, onDismiss: () -> Unit, onDownload: () -> Unit, onCancel: () -> Unit, onInstall: () -> Unit) {
    val release = state.release ?: return
    if (!state.showDialog) return
    AlertDialog(onDismissRequest = { if (!state.downloading) onDismiss() },
        title = { Text("清记 ${release.versionName}") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("新版已发布 · ${"%.1f".format(java.util.Locale.CHINA, release.sizeBytes / 1024.0 / 1024)} MB", color = Pine)
            release.notes.forEach { Text("• $it") }
            if (state.downloading) {
                LinearProgressIndicator(progress = { (state.downloadedBytes.toFloat() / release.sizeBytes).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                Text("已下载 ${state.downloadedBytes * 100 / release.sizeBytes}%", style = MaterialTheme.typography.bodySmall)
            }
            Text(state.message, color = Muted, style = MaterialTheme.typography.bodySmall)
            Text("覆盖安装会保留账本。请在系统安装界面确认更新。", color = Muted, style = MaterialTheme.typography.labelSmall)
        } },
        confirmButton = { if (!state.downloading) TextButton(onClick = if (state.ready) onInstall else onDownload) { Text(if (state.ready) "继续安装" else "下载并更新") } },
        dismissButton = { TextButton(onClick = if (state.downloading) onCancel else onDismiss) { Text(if (state.downloading) "取消下载" else "稍后再说") } })
}
