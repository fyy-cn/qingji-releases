package cn.qingji.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.edit
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

object NotificationRecovery {
    const val NAME = "qingji.notification-connection-check"
    internal fun request() = PeriodicWorkRequestBuilder<NotificationRecoveryWorker>(15, TimeUnit.MINUTES)
        .setInitialDelay(15, TimeUnit.MINUTES).build()

    fun configure(context: Context) {
        if (!(context.applicationContext as QingJiApplication).notificationRecoveryEnabled) return
        val p = context.getSharedPreferences("diagnostics", 0)
        runCatching {
            val work = WorkManager.getInstance(context)
            if (NotificationMonitor.hasAccess(context)) {
                work.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request())
                p.edit { putBoolean("notificationRecoveryScheduled", true) }
            } else {
                work.cancelUniqueWork(NAME)
                p.edit { putBoolean("notificationRecoveryScheduled", false) }
            }
        }.onFailure { p.edit { putBoolean("notificationRecoveryScheduled", false); putString("lastError", "后台连接检查未能安排，请重新打开清记") } }
    }

    fun wake(context: Context, reason: String) {
        configure(context)
        NotificationMonitor.ensureConnected(context, reason = reason)
    }
}

class NotificationRecoveryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        applicationContext.getSharedPreferences("diagnostics", 0).edit { putLong("notificationWatchdogAt", System.currentTimeMillis()) }
        NotificationMonitor.ensureConnected(applicationContext, reason = "后台连接检查")
        return Result.success()
    }
}

class NotificationRecoveryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> NotificationRecovery.wake(context, "手机重启")
            Intent.ACTION_MY_PACKAGE_REPLACED -> NotificationRecovery.wake(context, "覆盖更新")
        }
    }
}
