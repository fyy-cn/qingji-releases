package cn.qingji.app

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.content.edit
import androidx.core.app.NotificationCompat
import cn.qingji.core.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class NotificationHealth(val connected: Boolean = false, val receivedAt: Long = 0, val received: Long = 0,
    val parsed: Long = 0, val lastChannel: String = "", val outcome: String = "尚未收到支持渠道的系统通知",
    val checkedAt: Long = 0, val watchdogAt: Long = 0, val nextRetryAt: Long = 0)

object NotificationMonitor {
    @Volatile private var listener: PaymentNotificationService? = null
    private val handler = Handler(Looper.getMainLooper())
    private var requestedAt: Long? = null
    private var checkedElapsed: Long? = null
    private var retry: Runnable? = null
    private var retryElapsed: Long = 0
    private var attempt = 0
    internal var requestBinding: (ComponentName) -> Unit = NotificationListenerService::requestRebind
    fun hasAccess(context: Context): Boolean = runCatching {
        context.getSystemService(NotificationManager::class.java).isNotificationListenerAccessGranted(ComponentName(context, PaymentNotificationService::class.java))
    }.getOrDefault(false)
    fun attached(service: PaymentNotificationService) = listener === service
    @Synchronized fun connected(service: PaymentNotificationService) {
        listener = service
        requestedAt = null; checkedElapsed = null; attempt = 0
        cancelRetry(service)
        service.getSharedPreferences("diagnostics", 0).edit { putLong("connectedAt", System.currentTimeMillis()); remove("lastError") }
        NotificationRecovery.configure(service)
    }
    @Synchronized fun disconnected(service: PaymentNotificationService, reason: String = "监听断开") {
        // A late callback from an old instance must not disconnect its replacement.
        if (listener != null && listener !== service) return
        listener = null; checkedElapsed = null
        service.getSharedPreferences("diagnostics", 0).edit { putLong("notificationDisconnectedAt", System.currentTimeMillis()) }
        ensureConnected(service.applicationContext, manual = true, reason = reason)
    }
    @Synchronized fun checked(service: PaymentNotificationService) {
        if (listener !== service) return
        checkedElapsed = SystemClock.elapsedRealtime()
        service.getSharedPreferences("diagnostics", 0).edit { putLong("notificationCheckedAt", System.currentTimeMillis()); remove("lastError") }
    }
    @Synchronized fun unavailable(service: PaymentNotificationService) {
        if (listener !== service) return
        listener = null; checkedElapsed = null
        service.getSharedPreferences("diagnostics", 0).edit {
            putLong("notificationDisconnectedAt", System.currentTimeMillis())
            putString("lastError", "通知连接检查失败，正在自动重连")
        }
        ensureConnected(service.applicationContext, manual = true, reason = "连接检查失败")
    }
    private fun cancelRetry(context: Context) {
        retry?.let(handler::removeCallbacks); retry = null; retryElapsed = 0
        context.getSharedPreferences("diagnostics", 0).edit { putLong("notificationNextRetryAt", 0) }
    }
    private fun queueRetry(context: Context, delay: Long) {
        val app = context.applicationContext
        val due = SystemClock.elapsedRealtime() + delay.coerceAtLeast(1000)
        if (retry != null && retryElapsed <= due) return
        retry?.let(handler::removeCallbacks)
        retryElapsed = due
        retry = Runnable {
            synchronized(this) { retry = null; retryElapsed = 0 }
            ensureConnected(app, reason = "断线自动重试")
        }.also { handler.postDelayed(it, delay.coerceAtLeast(1000)) }
        app.getSharedPreferences("diagnostics", 0).edit { putLong("notificationNextRetryAt", System.currentTimeMillis() + delay.coerceAtLeast(1000)) }
    }
    @Synchronized fun ensureConnected(context: Context, manual: Boolean = false, reason: String = "前台检查"): String {
        val component = ComponentName(context, PaymentNotificationService::class.java)
        if (!hasAccess(context)) {
            listener = null; checkedElapsed = null; requestedAt = null; attempt = 0; cancelRetry(context)
            return "请先开启通知使用权"
        }
        val now = SystemClock.elapsedRealtime()
        listener?.let {
            if (manual || checkedElapsed?.let { checked -> now - checked >= 60_000 } != false) it.scanCurrent()
            return if (listener != null) "已请求检查连接并补读当前通知" else "连接异常，已请求自动恢复"
        }
        val minimum = if (manual) 5000L else 10_000L
        requestedAt?.let { last -> if (now - last < minimum) {
            queueRetry(context, minimum - (now - last)); return "已请求连接，等待系统回调"
        } }
        requestedAt = now
        val p = context.getSharedPreferences("diagnostics", 0)
        val success = runCatching { requestBinding(component) }.isSuccess
        attempt++
        p.edit {
            putLong("notificationRebindAt", System.currentTimeMillis())
            putLong("notificationRebindCount", p.getLong("notificationRebindCount", 0) + 1)
            putString("notificationRecoveryReason", reason)
            if (!success) putString("lastError", "系统未接受通知重连请求，将自动重试")
        }
        queueRetry(context, longArrayOf(10_000, 30_000, 60_000, 300_000)[(attempt - 1).coerceAtMost(3)])
        return if (success) "已请求系统连接通知监听，将自动跟进恢复" else "系统未接受通知重连请求，将自动重试"
    }
    fun health(context: Context): NotificationHealth {
        val p = context.getSharedPreferences("diagnostics", 0)
        return NotificationHealth(listener != null && hasAccess(context), p.getLong("notificationReceivedAt", 0), p.getLong("notificationReceived", 0), p.getLong("notificationParsed", 0), p.getString("notificationChannel", "").orEmpty(), p.getString("notificationOutcome", "尚未收到支持渠道的系统通知").orEmpty(), p.getLong("notificationCheckedAt", 0), p.getLong("notificationWatchdogAt", 0), p.getLong("notificationNextRetryAt", 0))
    }
    fun diagnostic(context: Context): String {
        val p = context.getSharedPreferences("diagnostics", 0); val h = health(context)
        val access = context.getSystemService(NotificationManager::class.java).isNotificationListenerAccessGranted(ComponentName(context, PaymentNotificationService::class.java))
        fun version(pkg: String) = runCatching { context.packageManager.getPackageInfo(pkg, 0).versionName }.getOrDefault("未安装")
        val campus = CampusSources.selected(context)
        val recovery = "\n连接检查${h.checkedAt}；后台检查${h.watchdogAt}；下次重试${h.nextRetryAt}\n自动恢复安排${p.getBoolean("notificationRecoveryScheduled", false)}；累计重连${p.getLong("notificationRebindCount", 0)}；触发${p.getString("notificationRecoveryReason", "无")}"
        return "清记${BuildConfig.VERSION_NAME} 通知采集诊断\nAndroid ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}\n${Build.MANUFACTURER} ${Build.MODEL}\n微信${version("com.tencent.mm")}；支付宝${version("com.eg.android.AlipayGphone")}\n校园应用${campus?.label ?: "未选择"}；包名${campus?.packageName ?: "无"}\n通知使用权$access；当前进程监听连接${h.connected}\n最近连接${p.getLong("connectedAt",0)}；断开${p.getLong("notificationDisconnectedAt",0)}；请求重连${p.getLong("notificationRebindAt",0)}\n支持渠道通知回调/补读${h.received}次；已识别候选/补全${h.parsed}次\n最近渠道${h.lastChannel}；时间${h.receivedAt}；结果${h.outcome}\n结果代码${p.getString("notificationCode", "NONE")}；头部字符${p.getInt("notificationHeaderChars",0)}；正文字符${p.getInt("notificationBodyChars",0)}\n可用字段${p.getString("notificationFields", "无")}；最近回调来自${p.getString("notificationOrigin", "无")}\n最近保存结果${p.getString("lastAction", "无")}；错误${p.getString("lastError", "无")}\n诊断不包含通知标题/正文、商家、金额、交易号或聊天内容。" + recovery
    }
    @Synchronized internal fun resetForTests() {
        retry?.let(handler::removeCallbacks); retry = null; retryElapsed = 0
        listener = null; requestedAt = null; checkedElapsed = null; attempt = 0
        requestBinding = NotificationListenerService::requestRebind
    }
}

data class NotificationPart(val title: String, val headers: List<String>, val text: String, val time: Long, val key: String)
data class NotificationEnvelope(val parts: List<NotificationPart>, val fields: String, val summary: Boolean)

object PaymentNotificationReader {
    fun read(sbn: StatusBarNotification, now: Long): NotificationEnvelope {
        val n = sbn.notification; val extras = n.extras
        val fields = listOf(Notification.EXTRA_TITLE to "title", Notification.EXTRA_TITLE_BIG to "bigTitle", Notification.EXTRA_SUB_TEXT to "subText", Notification.EXTRA_CONVERSATION_TITLE to "conversationTitle", Notification.EXTRA_TEXT to "text", Notification.EXTRA_BIG_TEXT to "bigText", Notification.EXTRA_TEXT_LINES to "lines", "android.messages" to "messages")
            .filter { extras.containsKey(it.first) }.joinToString(",") { it.second }
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return NotificationEnvelope(emptyList(), fields, true)
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty().take(500)
        val headers = listOfNotNull(extras.getCharSequence(Notification.EXTRA_TITLE_BIG), extras.getCharSequence(Notification.EXTRA_SUB_TEXT), extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)).map { it.toString().take(500) }
        val time = n.`when`.takeIf { it > 0 && it <= now + 86_400_000 } ?: sbn.postTime
        val style = runCatching { NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n) }.getOrNull()
        val messages = style?.messages.orEmpty().takeLast(20)
        val parts = if (messages.isNotEmpty()) messages.map { message ->
            val text = message.text?.toString().orEmpty().take(8000)
            val sender = message.person?.name?.toString().orEmpty().take(500)
            val at = message.timestamp.takeIf { it > 0 && it <= now + 86_400_000 } ?: time
            NotificationPart(title, headers + sender, text, at, "notification-message:" + sha256("${sbn.packageName}|${sbn.key}|$at|$text"))
        } else {
            val text = listOfNotNull(extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.take(4000), extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.take(8000), extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.take(20)?.joinToString("\n") { it.take(500) }).filter { it.isNotBlank() }.distinct().joinToString("\n").take(8000)
            listOf(NotificationPart(title, headers, text, time, "notification:" + sha256("${sbn.packageName}|${sbn.key}|$time")))
        }
        return NotificationEnvelope(parts, fields, false)
    }
}

open class PaymentNotificationService : NotificationListenerService() {
    private val lock = Mutex()
    private val mainHandler = Handler(Looper.getMainLooper())
    override fun onListenerConnected() { super.onListenerConnected(); NotificationMonitor.connected(this); scanCurrent() }
    override fun onListenerDisconnected() { NotificationMonitor.disconnected(this); super.onListenerDisconnected() }
    override fun onDestroy() { NotificationMonitor.disconnected(this, "监听服务被回收"); super.onDestroy() }
    fun scanCurrent() {
        if (Looper.myLooper() != Looper.getMainLooper()) { mainHandler.post { scanCurrent() }; return }
        if (!NotificationMonitor.attached(this)) return
        val notifications = runCatching { activeNotifications }.getOrNull()
        if (notifications == null) {
            NotificationMonitor.unavailable(this)
            return
        }
        if (!NotificationMonitor.attached(this)) return
        NotificationMonitor.checked(this)
        notifications.asSequence().filter { it.packageName in CampusSources.packages(this) }.take(100).forEach { receive(it, true) }
    }
    override fun onNotificationPosted(sbn: StatusBarNotification) { receive(sbn, false) }
    private fun receive(sbn: StatusBarNotification, recovered: Boolean) {
        if (sbn.packageName !in CampusSources.packages(this)) return
        val app = application as QingJiApplication
        app.ioScope.launch { lock.withLock {
            val p = getSharedPreferences("diagnostics", 0)
            p.edit {
                putLong("notificationReceivedAt", System.currentTimeMillis()); putLong("notificationReceived", p.getLong("notificationReceived", 0) + 1)
                putString("notificationChannel", PaymentSources.channel(sbn.packageName, CampusSources.packageName(this@PaymentNotificationService))?.label.orEmpty())
                putString("notificationOrigin", if (recovered) "通知栏补读" else "实时回调")
            }
            try {
                val envelope = PaymentNotificationReader.read(sbn, System.currentTimeMillis())
                p.edit { putString("notificationFields", envelope.fields) }
                if (envelope.summary) {
                    p.edit { putString("notificationCode", "GROUP_SUMMARY"); putString("notificationOutcome", "系统分组汇总通知，等待单笔通知"); putInt("notificationHeaderChars", 0); putInt("notificationBodyChars", 0) }
                    return@withLock
                }
                for (part in envelope.parts) {
                    val result = NotificationParser.inspect(sbn.packageName, part.title, part.text, part.key, part.time, part.headers, CampusSources.packageName(this@PaymentNotificationService))
                    p.edit {
                        putInt("notificationHeaderChars", part.title.length + part.headers.sumOf { it.length }); putInt("notificationBodyChars", part.text.length)
                        putString("notificationCode", result.code); putString("notificationOutcome", if (part.text.isBlank()) "系统未提供可读正文；${result.message}" else result.message)
                    }
                    result.entry?.let { entry ->
                        val action = app.repository.ingest(entry)
                        p.edit {
                            putLong("notificationParsed", p.getLong("notificationParsed",0) + 1); putLong("lastCandidateAt", System.currentTimeMillis())
                            putString("lastAction", action); putString("lastChannel", entry.channel.label); remove("lastError")
                        }
                    }
                }
            } catch (_: Exception) { p.edit { putString("notificationCode", "READ_OR_SAVE_ERROR"); putString("notificationOutcome", "通知已收到，但读取或保存失败"); putString("lastError", "通知处理失败，可复制通知诊断反馈") } }
        } }
    }
}
