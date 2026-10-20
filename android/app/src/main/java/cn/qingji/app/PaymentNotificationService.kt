package cn.qingji.app

import android.app.Application
import kotlinx.coroutines.*

open class QingJiApplication : Application(), androidx.work.Configuration.Provider {
    val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val repository: LedgerRepository by lazy { LedgerRepository(LedgerDatabase.open(this)) }
    open val updatesEnabled: Boolean = true
    open val notificationRecoveryEnabled: Boolean = true
    open val releaseClient: ReleaseClient by lazy { HttpsReleaseClient() }
    override val workManagerConfiguration: androidx.work.Configuration
        get() = androidx.work.Configuration.Builder().build()
    open fun scheduleUpdates() { UpdateSchedule.configure(this) }
    open fun scheduleNotificationRecovery() {
        if (notificationRecoveryEnabled) NotificationRecovery.wake(this, "进程启动")
    }
    override fun onCreate() { super.onCreate(); scheduleUpdates(); scheduleNotificationRecovery() }
}
