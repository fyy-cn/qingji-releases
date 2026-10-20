package cn.qingji.app

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

class RecoveryTestApplication : QingJiApplication() {
    override val updatesEnabled = false
    override fun scheduleNotificationRecovery() { /* Configure explicitly in each test. */ }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = RecoveryTestApplication::class)
@LooperMode(LooperMode.Mode.PAUSED)
class NotificationRecoveryTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private var requests = 0
    @Before fun setup() {
        NotificationMonitor.resetForTests(); NotificationMonitor.requestBinding = { requests++ }
        context.getSharedPreferences("diagnostics", 0).edit().clear().commit()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        permission(true)
    }
    @After fun cleanup() {
        NotificationMonitor.resetForTests(); WorkManager.getInstance(context).cancelAllWork().result.get()
    }
    private fun permission(granted: Boolean) = Shadows.shadowOf(context.getSystemService(NotificationManager::class.java))
        .setNotificationListenerAccessGranted(ComponentName(context, PaymentNotificationService::class.java), granted)
    private fun scheduled() = WorkManager.getInstance(context).getWorkInfosForUniqueWork(NotificationRecovery.NAME).get()
    @Test fun recoveryIsUniqueAndIndependentOfUpdateNetworkOrBatterySetting() {
        NotificationRecovery.configure(context); NotificationRecovery.configure(context)
        val jobs = scheduled(); assertEquals(1, jobs.size); assertFalse((context as QingJiApplication).updatesEnabled)
        assertEquals(NetworkType.NOT_REQUIRED, jobs.single().constraints.requiredNetworkType)
        assertFalse(jobs.single().constraints.requiresBatteryNotLow())
    }
    @Test fun revocationCancelsScheduledJobAndDoesNotStartRecovery() {
        NotificationRecovery.configure(context); permission(false); NotificationRecovery.wake(context, "test")
        assertTrue(scheduled().all { it.state == WorkInfo.State.CANCELLED }); assertEquals(0, requests)
    }
    @Test fun workerRestoresMissingListenerAfterProcessStateIsLost() = runBlocking {
        val worker = TestListenableWorkerBuilder<NotificationRecoveryWorker>(context).build()
        assertEquals(ListenableWorker.Result.success(), worker.doWork()); assertEquals(1, requests)
        assertTrue(NotificationMonitor.health(context).watchdogAt > 0); assertFalse(NotificationMonitor.health(context).connected)
    }
    @Test fun workerDoesNotRequestBindingWithoutPermission() = runBlocking {
        permission(false)
        val worker = TestListenableWorkerBuilder<NotificationRecoveryWorker>(context).build()
        assertEquals(ListenableWorker.Result.success(), worker.doWork()); assertEquals(0, requests)
    }
    @Test fun bootAndUpgradeWakeRecoveryButUnrelatedBroadcastDoesNot() {
        val receiver = NotificationRecoveryReceiver()
        receiver.onReceive(context, Intent("unrelated")); assertEquals(0, requests)
        receiver.onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED)); assertEquals(1, requests)
        NotificationMonitor.resetForTests(); NotificationMonitor.requestBinding = { requests++ }
        receiver.onReceive(context, Intent(Intent.ACTION_MY_PACKAGE_REPLACED)); assertEquals(2, requests)
        assertEquals(1, scheduled().size)
    }
}
