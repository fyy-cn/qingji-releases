package cn.qingji.app

import android.app.Application
import android.app.Notification
import android.content.Context
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.content.edit
import cn.qingji.core.NotificationParser
import cn.qingji.core.sha256
import kotlinx.coroutines.*

open class QingJiApplication : Application(), androidx.work.Configuration.Provider {
    val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val repository: LedgerRepository by lazy { LedgerRepository(LedgerDatabase.open(this)) }
    open val updatesEnabled: Boolean = true
    open val releaseClient: ReleaseClient by lazy { HttpsReleaseClient() }
    override val workManagerConfiguration: androidx.work.Configuration
        get() = androidx.work.Configuration.Builder().build()
    open fun scheduleUpdates() { UpdateSchedule.configure(this) }
    override fun onCreate() { super.onCreate(); scheduleUpdates() }
}

class PaymentNotificationService : NotificationListenerService() {
    override fun onListenerConnected() {
        getSharedPreferences("diagnostics", Context.MODE_PRIVATE).edit { putLong("connectedAt", System.currentTimeMillis()) }
    }
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName !in setOf("com.tencent.mm", "com.eg.android.AlipayGphone")) return
        if (sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val extras = sbn.notification.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = listOfNotNull(extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
            extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString(),
            extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.joinToString("\n"))
            .filter { it.isNotBlank() }.distinct().joinToString("\n")
        val eventTime = sbn.notification.`when`.takeIf { it > 0L && it <= System.currentTimeMillis() + 86_400_000 } ?: sbn.postTime
        val eventKey = "notification:" + sha256("${sbn.packageName}|${sbn.key}|$eventTime")
        val entry = NotificationParser.parse(sbn.packageName, title, text, eventKey, eventTime) ?: return
        val app = application as QingJiApplication
        app.ioScope.launch {
            runCatching { app.repository.ingest(entry) }.onSuccess { action ->
                getSharedPreferences("diagnostics", Context.MODE_PRIVATE).edit {
                    putLong("lastCandidateAt", System.currentTimeMillis())
                    putString("lastAction", action); putString("lastChannel", entry.channel.label)
                    remove("lastError")
                }
            }.onFailure {
                getSharedPreferences("diagnostics", Context.MODE_PRIVATE).edit { putString("lastError", "通知保存失败，请打开应用检查") }
            }
        }
    }
}
