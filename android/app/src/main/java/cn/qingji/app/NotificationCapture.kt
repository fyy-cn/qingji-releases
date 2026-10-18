package cn.qingji.app

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
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
    val parsed: Long = 0, val lastChannel: String = "", val outcome: String = "尚未收到支持渠道的系统通知")

object NotificationMonitor {
    @Volatile private var listener: PaymentNotificationService? = null
    private var requestedAt: Long? = null
    fun attached(service: PaymentNotificationService) = listener === service
    fun connected(service: PaymentNotificationService) {
        listener = service
        service.getSharedPreferences("diagnostics", 0).edit { putLong("connectedAt", System.currentTimeMillis()); remove("lastError") }
    }
    fun disconnected(service: PaymentNotificationService) {
        if (listener === service) {
            listener = null
            service.getSharedPreferences("diagnostics", 0).edit { putLong("notificationDisconnectedAt", System.currentTimeMillis()) }
        }
    }
    @Synchronized fun ensureConnected(context: Context, manual: Boolean = false): String {
        val component = ComponentName(context, PaymentNotificationService::class.java)
        if (!context.getSystemService(NotificationManager::class.java).isNotificationListenerAccessGranted(component)) return "请先开启通知使用权"
        listener?.let { if (manual) it.scanCurrent(); return if (manual) "已请求补读通知栏中仍保留的通知" else "通知监听已连接" }
        val now = SystemClock.elapsedRealtime()
        if (requestedAt?.let { now - it < if (manual) 5000 else 60_000 } == true) return "已请求连接，等待系统回调"
        requestedAt = now
        return runCatching {
            NotificationListenerService.requestRebind(component)
            context.getSharedPreferences("diagnostics", 0).edit { putLong("notificationRebindAt", System.currentTimeMillis()); remove("lastError") }
            "已请求系统连接通知监听"
        }.getOrElse {
            context.getSharedPreferences("diagnostics", 0).edit { putString("lastError", "系统未接受通知重连请求，请检查通知使用权") }
            "系统未接受通知重连请求"
        }
    }
    fun health(context: Context): NotificationHealth {
        val p = context.getSharedPreferences("diagnostics", 0)
        return NotificationHealth(listener != null, p.getLong("notificationReceivedAt", 0), p.getLong("notificationReceived", 0), p.getLong("notificationParsed", 0), p.getString("notificationChannel", "").orEmpty(), p.getString("notificationOutcome", "尚未收到支持渠道的系统通知").orEmpty())
    }
    fun diagnostic(context: Context): String {
        val p = context.getSharedPreferences("diagnostics", 0); val h = health(context)
        val access = context.getSystemService(NotificationManager::class.java).isNotificationListenerAccessGranted(ComponentName(context, PaymentNotificationService::class.java))
        fun version(pkg: String) = runCatching { context.packageManager.getPackageInfo(pkg, 0).versionName }.getOrDefault("未安装")
        val campus = CampusSources.selected(context)
        return "清记${BuildConfig.VERSION_NAME} 通知采集诊断\nAndroid ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}\n${Build.MANUFACTURER} ${Build.MODEL}\n微信${version("com.tencent.mm")}；支付宝${version("com.eg.android.AlipayGphone")}\n校园应用${campus?.label ?: "未选择"}；包名${campus?.packageName ?: "无"}\n通知使用权$access；当前进程监听连接${h.connected}\n最近连接${p.getLong("connectedAt",0)}；断开${p.getLong("notificationDisconnectedAt",0)}；请求重连${p.getLong("notificationRebindAt",0)}\n支持渠道通知回调/补读${h.received}次；已识别候选/补全${h.parsed}次\n最近渠道${h.lastChannel}；时间${h.receivedAt}；结果${h.outcome}\n结果代码${p.getString("notificationCode", "NONE")}；头部字符${p.getInt("notificationHeaderChars",0)}；正文字符${p.getInt("notificationBodyChars",0)}\n可用字段${p.getString("notificationFields", "无")}；最近回调来自${p.getString("notificationOrigin", "无")}\n最近保存结果${p.getString("lastAction", "无")}；错误${p.getString("lastError", "无")}\n诊断不包含通知标题/正文、商家、金额、交易号或聊天内容。"
    }
    internal fun resetForTests() { listener = null; requestedAt = null }
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
    override fun onListenerConnected() { NotificationMonitor.connected(this); scanCurrent() }
    override fun onListenerDisconnected() { NotificationMonitor.disconnected(this); NotificationMonitor.ensureConnected(this, manual = true) }
    override fun onDestroy() { NotificationMonitor.disconnected(this); super.onDestroy() }
    fun scanCurrent() {
        if (!NotificationMonitor.attached(this)) return
        (application as QingJiApplication).ioScope.launch {
            if (!NotificationMonitor.attached(this@PaymentNotificationService)) return@launch
            runCatching { activeNotifications?.toList().orEmpty() }.onSuccess { notifications ->
                if (NotificationMonitor.attached(this@PaymentNotificationService)) notifications.filter { it.packageName in CampusSources.packages(this@PaymentNotificationService) }.take(100).forEach { receive(it, true) }
            }.onFailure { getSharedPreferences("diagnostics", 0).edit { putString("lastError", "已连接，但当前通知补读失败，可稍后重试") } }
        }
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
