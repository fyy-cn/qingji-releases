package cn.qingji.app

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.core.net.toUri
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.qingji.core.*
import java.time.LocalDate

data class EntryFilter(val title: String, val start: Long? = null, val end: Long? = null, val category: String? = null, val pendingOnly: Boolean = false) {
    fun matches(entry: Entry): Boolean {
        if (pendingOnly) return entry.status == Status.PENDING
        return entry.status == Status.CONFIRMED && entry.currency == "CNY" && entry.kind in listOf(Kind.EXPENSE, Kind.REFUND) &&
            (start == null || entry.occurredAt >= start) && (end == null || entry.occurredAt < end) &&
            (category == null || entry.category.label == category)
    }
    companion object {
        fun range(range: DateRange, title: String = range.label, category: String? = null) = EntryFilter(title,
            range.start.atStartOfDay(ledgerZone).toInstant().toEpochMilli(), range.endExclusive.atStartOfDay(ledgerZone).toInstant().toEpochMilli(), category)
    }
}
data class Diagnostics(val access: Boolean = false, val connectedAt: Long = 0, val lastAt: Long = 0, val lastAction: String = "", val channel: String = "", val error: String = "", val wechat: String = "未安装", val alipay: String = "未安装", val page: PaymentPageHealth = PaymentPageHealth(), val notification: NotificationHealth = NotificationHealth(), val campus: CampusApp? = null)
fun diagnostics(context: Context): Diagnostics {
    val prefs = context.getSharedPreferences("diagnostics", Context.MODE_PRIVATE)
    fun version(pkg: String) = runCatching { context.packageManager.getPackageInfo(pkg, 0).versionName ?: "未知版本" }.getOrDefault("未安装")
    return Diagnostics(context.getSystemService(NotificationManager::class.java).isNotificationListenerAccessGranted(ComponentName(context, PaymentNotificationService::class.java)),
        prefs.getLong("connectedAt", 0), prefs.getLong("lastCandidateAt", 0), prefs.getString("lastAction", "").orEmpty(), prefs.getString("lastChannel", "").orEmpty(), prefs.getString("lastError", "").orEmpty(),
        version("com.tencent.mm"), version("com.eg.android.AlipayGphone"), PaymentPageSettings.health(context), NotificationMonitor.health(context), CampusSources.selected(context))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun QingJiApp(vm: LedgerViewModel = viewModel(), updater: UpdateViewModel = viewModel(), updateRequest: Int = 0) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val entries by vm.entries.collectAsStateWithLifecycle()
    val imports by vm.imports.collectAsStateWithLifecycle()
    val categories by vm.categories.collectAsStateWithLifecycle()
    val templates by vm.templates.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val preview by vm.preview.collectAsStateWithLifecycle()
    val restored by vm.restoredData.collectAsStateWithLifecycle()
    val updateState by updater.state.collectAsStateWithLifecycle()
    var selected by rememberSaveable { mutableIntStateOf(0) }
    var filter by remember { mutableStateOf<EntryFilter?>(null) }
    var editing by remember { mutableStateOf<Entry?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    var showDisclosure by remember { mutableStateOf(false) }
    var showPageDisclosure by remember { mutableStateOf(false) }
    var showCampusPicker by remember { mutableStateOf(false) }
    var showCategories by remember { mutableStateOf(false) }
    var showTemplates by remember { mutableStateOf(false) }
    var showQuickClassification by rememberSaveable { mutableStateOf(false) }
    var backupMode by remember { mutableStateOf<String?>(null) }
    var restoreUri by remember { mutableStateOf<Uri?>(null) }
    var health by remember { mutableStateOf(diagnostics(context)) }
    var today by remember { mutableStateOf(LocalDate.now(ledgerZone)) }
    val snackbar = remember { SnackbarHostState() }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) { NotificationRecovery.wake(context, "返回前台"); health = diagnostics(context); today = LocalDate.now(ledgerZone); updater.resume() } }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    DisposableEffect(context) {
        val preferences = context.getSharedPreferences("diagnostics", Context.MODE_PRIVATE)
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> health = diagnostics(context) }
        val sources = context.getSharedPreferences("capture_sources", Context.MODE_PRIVATE)
        preferences.registerOnSharedPreferenceChangeListener(listener); sources.registerOnSharedPreferenceChangeListener(listener)
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener); sources.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    LaunchedEffect(context) { NotificationRecovery.wake(context, "打开应用"); health = diagnostics(context) }
    LaunchedEffect(vm) { vm.messages.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(updateRequest) { if (updateRequest > 0) updater.check(true) else updater.resume() }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::import) }
    val backupLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri -> if (uri != null) vm.writeBackup(uri) else vm.cancelBackup() }
    val csvLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri -> if (uri != null) vm.writeCsv(uri) else vm.cancelCsv() }
    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) { restoreUri = uri; backupMode = "restore" } }
    fun importFile() { importLauncher.launch(arrayOf("*/*")) }
    fun openFilter(next: EntryFilter) { filter = next; selected = 1; showQuickClassification = false }
    fun quickClassify() { selected = 1; showQuickClassification = true }
    fun edit(entry: Entry?) { editing = entry; showEditor = true }
    BackHandler(enabled = showQuickClassification && !showEditor && !showTemplates) { showQuickClassification = false; filter = null }

    Box(Modifier.fillMaxSize()) {
        Scaffold(containerColor = Cream, snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                NavigationBar(containerColor = Cream, tonalElevation = 0.dp) {
                    listOf("首页" to Icons.Default.Home, "账单" to Icons.AutoMirrored.Filled.ReceiptLong, "分析" to Icons.Default.BarChart, "设置" to Icons.Default.Tune).forEachIndexed { index, (label, icon) ->
                        NavigationBarItem(selected = selected == index, onClick = { selected = index; showQuickClassification = false; if (index == 1) filter = null },
                            icon = { Icon(icon, contentDescription = label) }, label = { Text(label) },
                            colors = NavigationBarItemDefaults.colors(indicatorColor = Lime, selectedIconColor = Pine, selectedTextColor = Pine))
                    }
                }
            },
            floatingActionButton = {
                if (selected <= 1 && !showQuickClassification) ExtendedFloatingActionButton(onClick = { edit(null) }, icon = { Icon(Icons.Default.Add, "补记") }, text = { Text("记一笔") }, containerColor = Pine, contentColor = androidx.compose.ui.graphics.Color.White)
            }
        ) { padding ->
            Box(Modifier.padding(padding).fillMaxSize()) {
                if (showQuickClassification) QuickClassificationScreen(entries, categories, templates, busy,
                    onDismiss = { showQuickClassification = false; filter = null }, onEdit = { edit(it) }, onCategory = vm::classify, onTemplate = vm::applyTemplate,
                    onManageTemplates = { showTemplates = true }, onViewPending = { openFilter(EntryFilter("待确认账单", pendingOnly = true)) })
                else when (selected) {
                    0 -> HomeScreen(entries, today, health, imports.maxByOrNull { it.importedAt }, onAuthorize = { showDisclosure = true }, onImport = ::importFile,
                        onPending = { openFilter(EntryFilter("待确认账单", pendingOnly = true)) }, onEntry = { edit(it) }, onFilter = ::openFilter, onAnalysis = { selected = 2 }, onQuickClassify = ::quickClassify)
                    1 -> LedgerScreen(entries, filter, onClear = { filter = null }, onEntry = { edit(it) }, onExport = { records -> vm.prepareCsv(records) { csvLauncher.launch("清记账单-$today.csv") } }, onQuickClassify = ::quickClassify)
                    2 -> AnalysisScreen(entries, today, onFilter = ::openFilter)
                    3 -> SettingsScreen(health, imports, onAuthorize = { showDisclosure = true }, onImport = ::importFile,
                        onBackup = { backupMode = "backup" }, onRestore = { restoreLauncher.launch(arrayOf("*/*")) },
                        onAppSettings = { runCatching { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri())) }.onFailure { vm.messages.tryEmit("请长按清记图标打开应用信息") } },
                        onReconnect = { vm.messages.tryEmit(NotificationMonitor.ensureConnected(context, manual = true)); health = diagnostics(context) },
                        updateState = updateState, onCheckUpdate = { updater.check() }, onBackgroundUpdate = updater::background, onOpenUpdate = updater::openRelease,
                        categoryCount = categories.size, onManageCategories = { showCategories = true }, onExportAll = { vm.prepareCsv { csvLauncher.launch("清记全部账单-$today.csv") } },
                        templateCount = templates.size, onManageTemplates = { showTemplates = true }, onQuickClassify = ::quickClassify,
                        onSelectCampus = { showCampusPicker = true }, onRemoveCampus = { CampusSources.select(context, null); health = diagnostics(context); vm.messages.tryEmit("已停止采集校园应用，历史账单保留") },
                        onCopyNotificationDiagnostic = {
                            context.getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(android.content.ClipData.newPlainText("清记通知诊断", NotificationMonitor.diagnostic(context)))
                            vm.messages.tryEmit("通知诊断已复制，不包含原始通知或交易字段")
                        },
                        onEnablePage = { showPageDisclosure = true }, onDisablePage = { PaymentPageSettings.setEnabled(context, false); health = diagnostics(context) },
                        onViewPending = { openFilter(EntryFilter("待确认账单", pendingOnly = true)) }, onCopyPageDiagnostic = {
                            context.getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(android.content.ClipData.newPlainText("清记页面识别诊断", PaymentPageSettings.diagnostic(context)))
                            vm.messages.tryEmit("识别诊断已复制，不包含交易字段或完整页面")
                        })
                }
            }
        }
        if (busy) Box(Modifier.fillMaxSize().background(Ink.copy(alpha = 0.15f)).clickable(enabled = true, onClick = {}), contentAlignment = Alignment.Center) {
            Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp), color = androidx.compose.ui.graphics.Color.White) {
                Row(Modifier.padding(24.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(24.dp)); Text("正在处理…") }
            }
        }
    }
    if (showCampusPicker) CampusAppPicker(onDismiss = { showCampusPicker = false }, onSelect = { app ->
        showCampusPicker = false
        runCatching { CampusSources.select(context, app.packageName); NotificationMonitor.ensureConnected(context, manual = true); health = diagnostics(context) }
            .onSuccess { vm.messages.tryEmit("已选择${app.label}，校园交易先进入待确认") }.onFailure { vm.messages.tryEmit(it.message ?: "选择失败，请重试") }
    })
    AppUpdateDialog(updateState, updater::dismiss, updater::download, updater::cancelDownload, updater::install)
    if (showPageDisclosure) PaymentPageDisclosure(onDismiss = { showPageDisclosure = false }, onConfirm = {
        showPageDisclosure = false; PaymentPageSettings.setEnabled(context, true); health = diagnostics(context)
        runCatching { PaymentPageSettings.openSettings(context) }.onFailure { vm.messages.tryEmit("请在系统设置中搜索“无障碍”，开启清记的支付页面识别") }
    })
    if (showEditor) EntryEditor(editing, entries, onDismiss = { showEditor = false }, onSave = { entry, rule, templateId -> vm.save(entry, rule, templateId); showEditor = false },
        onIgnore = { vm.ignore(it); showEditor = false }, onMerge = { id, target -> vm.merge(id, target); showEditor = false }, categories = categories, templates = templates)
    if (showTemplates) TemplateManagerDialog(templates, categories, busy, onDismiss = { showTemplates = false }, onSave = vm::saveTemplate, onDelete = vm::deleteTemplate, onMove = vm::moveTemplate, onStarters = vm::addStarterTemplates)
    if (showCategories) CategoryManagerDialog(categories, entries, busy, onDismiss = { showCategories = false }, onAdd = vm::addCategory, onRename = vm::renameCategory, onDelete = vm::deleteCategory, onMove = vm::moveCategory, onRestoreBuiltIns = vm::restoreBuiltInCategories)
    preview?.let { ImportDialog(it, onDismiss = { if (!busy) vm.preview.value = null }, onCommit = vm::commit, busy = busy) }
    if (showDisclosure) AlertDialog(onDismissRequest = { showDisclosure = false }, title = { Text("开启支付通知记账") },
        text = { Text("系统通知使用权允许应用访问通知。清记只处理微信、支付宝及你选择的校园应用的交易候选通知，数据保存在本机，不上传。\n\n识别不到或未收到的通知需要核对补齐。前往系统设置后，请开启“清记 · 支付通知记账”。") },
        confirmButton = { TextButton(onClick = {
            showDisclosure = false
            runCatching { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }.onFailure { vm.messages.tryEmit("无法打开系统设置，请在设置中搜索“通知使用权”") }
        }) { Text("前往系统设置") } }, dismissButton = { TextButton(onClick = { showDisclosure = false }) { Text("暂不开启") } })
    if (backupMode != null) PasswordDialog(backupMode == "backup", onDismiss = { backupMode = null; restoreUri = null }, onConfirm = { password ->
        val mode = backupMode
        backupMode = null
        if (mode == "backup") vm.prepareBackup(password) { backupLauncher.launch("清记备份-$today.qjb") }
        else restoreUri?.let { vm.readBackup(it, password) }
        restoreUri = null
    })
    restored?.let { data -> AlertDialog(onDismissRequest = { vm.restoredData.value = null }, title = { Text("恢复备份") },
        text = { Text("备份包含 ${data.entries.size} 笔记录、${data.quickTemplates.size} 个场景模板。\n备份时间：${displayTime(data.exportedAt, "yyyy-MM-dd HH:mm")}\n\n恢复将替换本机现有账单、分类与模板。建议先备份当前数据。") },
        confirmButton = { TextButton(onClick = vm::restore, enabled = !busy) { Text("替换并恢复") } }, dismissButton = { TextButton(onClick = { vm.restoredData.value = null }) { Text("取消") } }) }
}
