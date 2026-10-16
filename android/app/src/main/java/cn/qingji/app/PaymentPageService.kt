package cn.qingji.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.edit
import cn.qingji.core.*
import kotlinx.coroutines.*

data class PaymentPageHealth(val enabled: Boolean = false, val access: Boolean = false, val connected: Boolean = false,
    val lastAt: Long = 0, val channel: String = "", val message: String = "尚未检测", val matched: Long = 0)

object PaymentPageSettings {
    fun enabled(context: Context) = context.getSharedPreferences("payment_page", Context.MODE_PRIVATE).getBoolean("enabled", false)
    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences("payment_page", Context.MODE_PRIVATE).edit { putBoolean("enabled", enabled) }
        context.getSharedPreferences("diagnostics", Context.MODE_PRIVATE).edit { putString("pageMessage", if (enabled) "请在系统授权后重新打开支付结果或交易详情" else "页面识别已关闭") }
    }
    fun health(context: Context): PaymentPageHealth {
        val prefs = context.getSharedPreferences("diagnostics", Context.MODE_PRIVATE)
        val access = context.getSystemService(AccessibilityManager::class.java)
            .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any { info ->
                val service = info.resolveInfo.serviceInfo
                service.packageName == context.packageName && service.name == PaymentPageService::class.java.name
            }
        return PaymentPageHealth(enabled(context), access, prefs.getBoolean("pageConnected", false), prefs.getLong("pageAt", 0),
            prefs.getString("pageChannel", "").orEmpty(), prefs.getString("pageMessage", "尚未检测").orEmpty(), prefs.getLong("pageMatched", 0))
    }
    fun openSettings(context: Context) {
        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    fun diagnostic(context: Context): String {
        val prefs = context.getSharedPreferences("diagnostics", Context.MODE_PRIVATE)
        val health = health(context)
        fun appVersion(pkg: String) = runCatching { context.packageManager.getPackageInfo(pkg, 0).versionName }.getOrDefault("未安装")
        return "清记${BuildConfig.VERSION_NAME} 页面识别诊断\nAndroid ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}\n${Build.MANUFACTURER} ${Build.MODEL}\n微信${appVersion("com.tencent.mm")}；支付宝${appVersion("com.eg.android.AlipayGphone")}\n实验开关${health.enabled}；系统授权${health.access}；服务连接${health.connected && health.access}\n最近渠道${health.channel}；状态${health.message}\n页面类${prefs.getString("pageClass", "未检测")}\n可读节点${prefs.getInt("pageNodes", 0)}；本次已生成${health.matched}条候选\n诊断不包含商家、金额、订单号、完整页面或聊天内容。"
    }
}

/** User opt-in, package/window gated, no gestures/screenshot/credential fields, local structured output only. */
open class PaymentPageService : AccessibilityService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var pending: Job? = null
    private val session = PaymentPageSession()
    private var windowClass = ""
    private var generation = 0
    private var currentWindow = -1
    private val changed = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "enabled" && !PaymentPageSettings.enabled(this)) {
            pending?.cancel(); session.reset(); disableSelf()
        }
    }
    override fun onServiceConnected() {
        getSharedPreferences("payment_page", MODE_PRIVATE).registerOnSharedPreferenceChangeListener(changed)
        getSharedPreferences("diagnostics", MODE_PRIVATE).edit {
            putBoolean("pageConnected", true); putLong("pageMatched", 0)
            putString("pageMessage", "服务已连接，请重新打开支付结果或交易详情")
        }
        if (!PaymentPageSettings.enabled(this)) disableSelf()
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !PaymentPageSettings.enabled(this)) return
        val pkg = event.packageName?.toString().orEmpty()
        if (pkg !in PaymentPageParser.packages || event.eventType !in setOf(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)) return
        val className = event.className?.toString().orEmpty()
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && (className.startsWith("com.tencent.mm.") || className.startsWith("com.alipay."))) {
            if (windowClass != className || currentWindow != event.windowId) { generation++; session.reset(); pending?.cancel() }
            windowClass = className; currentWindow = event.windowId
        }
        if (pending?.isActive == true) return
        if (!PaymentPageParser.isPaymentWindow(pkg, windowClass)) {
            session.reset()
            diagnostic(pkg, "尚未适配此页面；请重新打开支付结果或交易详情", 0)
            return
        }
        val expectedGeneration = generation
        val token = "$pkg:$generation:${event.windowId}"
        pending = scope.launch {
            delay(450)
            try {
                val snapshot = withContext(Dispatchers.IO) { readActivePage(pkg) }
                if (!PaymentPageSettings.enabled(this@PaymentPageService) || expectedGeneration != generation) return@launch
                if (snapshot == null) { session.reset(); diagnostic(pkg, "页面文字不可读取或页面已切换", 0); return@launch }
                val result = PaymentPageParser.parse(pkg, snapshot, System.currentTimeMillis())
                val entry = result.entry
                if (entry == null) session.reset()
                else session.accept(token, entry)?.let { candidate ->
                    withContext(Dispatchers.IO) {
                        ensureActive()
                        if (PaymentPageSettings.enabled(this@PaymentPageService)) (application as QingJiApplication).repository.ingest(candidate)
                    }
                    getSharedPreferences("diagnostics", MODE_PRIVATE).edit { putLong("pageMatched", getSharedPreferences("diagnostics", MODE_PRIVATE).getLong("pageMatched", 0) + 1) }
                }
                diagnostic(pkg, result.message, snapshot.size)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { diagnostic(pkg, "本次读取或保存失败，可稍后重新打开详情", 0) }
        }
    }
    private fun diagnostic(pkg: String, message: String, nodes: Int) {
        val prefs = getSharedPreferences("diagnostics", MODE_PRIVATE)
        if (prefs.getString("pageMessage", "") == message && prefs.getString("pageClass", "") == windowClass && System.currentTimeMillis() - prefs.getLong("pageAt", 0) < 5000) return
        prefs.edit {
            putLong("pageAt", System.currentTimeMillis()); putString("pageChannel", if (pkg == "com.tencent.mm") "微信" else "支付宝")
            putString("pageClass", windowClass.take(180)); putString("pageMessage", message); putInt("pageNodes", nodes)
        }
    }
    @Suppress("DEPRECATION")
    internal fun readActivePage(expectedPackage: String): List<String>? {
        val root = rootInActiveWindow ?: return null
        val result = mutableListOf<String>()
        var count = 0
        var characters = 0
        val deadline = android.os.SystemClock.uptimeMillis() + 700
        fun visit(node: AccessibilityNodeInfo) {
            if (++count > 250 || characters >= 12_000 || android.os.SystemClock.uptimeMillis() > deadline) return
            if (node.packageName?.toString() != expectedPackage || !node.isVisibleToUser || node.isPassword || node.isEditable || (Build.VERSION.SDK_INT >= 34 && node.isAccessibilityDataSensitive)) return
            listOfNotNull(node.text, node.contentDescription).map { it.toString().trim().take(500) }.filter { it.isNotBlank() }.distinct().forEach {
                if (characters + it.length <= 12_000) { result.add(it); characters += it.length }
            }
            for (index in 0 until node.childCount.coerceAtMost(100)) {
                if (count >= 250 || characters >= 12_000 || android.os.SystemClock.uptimeMillis() > deadline) break
                node.getChild(index)?.let { child -> try { visit(child) } finally { child.recycle() } }
            }
        }
        return try { visit(root); result } finally { root.recycle() }
    }
    override fun onInterrupt() { pending?.cancel(); session.reset() }
    override fun onDestroy() {
        getSharedPreferences("payment_page", MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(changed)
        scope.cancel()
        getSharedPreferences("diagnostics", MODE_PRIVATE).edit { putBoolean("pageConnected", false) }
        super.onDestroy()
    }
}
