package cn.qingji.app

import android.app.Notification
import android.app.NotificationManager
import android.app.Person
import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.os.Process
import android.os.Looper
import android.service.notification.StatusBarNotification
import androidx.test.core.app.ApplicationProvider
import cn.qingji.core.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration

class ProbeNotificationService : PaymentNotificationService() {
    var current = emptyArray<StatusBarNotification>()
    var reads = 0
    var nullRead = false
    var failRead = false
    var readOnMain = true
    override fun getActiveNotifications(): Array<StatusBarNotification>? {
        reads++; readOnMain = readOnMain && Looper.myLooper() == Looper.getMainLooper()
        if (failRead) throw SecurityException("synthetic disconnected service")
        return if (nullRead) null else current
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = OfflineTestApplication::class)
@LooperMode(LooperMode.Mode.PAUSED)
class NotificationCaptureTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val repo get() = (context as QingJiApplication).repository
    private val campus = "edu.example.campus"
    private val time = 1792301400000L
    @Before fun setup() {
        NotificationMonitor.resetForTests()
        Shadows.shadowOf(context.getSystemService(NotificationManager::class.java)).setNotificationListenerAccessGranted(ComponentName(context, PaymentNotificationService::class.java), true)
        context.getSharedPreferences("diagnostics", 0).edit().clear().commit()
        context.getSharedPreferences("capture_sources", 0).edit().clear().commit()
        PaymentPageSettings.setEnabled(context, false)
        runBlocking(Dispatchers.IO) { repo.restore(BackupData(entries = emptyList(), rules = emptyList(), imports = emptyList())) }
    }
    @After fun cleanup() { NotificationMonitor.resetForTests(); CampusSources.select(context, null) }
    private fun notification(pkg: String = "com.tencent.mm", title: String = "微信支付", text: String = "支付成功28.50元，商户：测试食堂", id: Int = 1): StatusBarNotification {
        val n = Notification.Builder(context, "test").setContentTitle(title).setContentText(text).setWhen(time).build()
        return StatusBarNotification(pkg, pkg, id, "test", 10001, 1, 0, n, Process.myUserHandle(), time)
    }
    private fun awaitCondition(test: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!test() && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue("异步通知处理未按预期完成", test())
    }
    private fun entries() = runBlocking(Dispatchers.IO) { repo.snapshot().entries }
    private fun installCampus() {
        val app = ApplicationInfo().apply { packageName = campus; enabled = true; nonLocalizedLabel = "交我办" }
        Shadows.shadowOf(context.packageManager).installPackage(PackageInfo().apply { packageName = campus; applicationInfo = app })
    }
    @Test fun reconnectRecoversCurrentNotificationAndRepeatedCallbacksDoNotDuplicate() {
        val service = Robolectric.buildService(ProbeNotificationService::class.java).create().get()
        try {
            val payment = notification(); service.current = arrayOf(payment)
            service.scanCurrent(); assertEquals(0, service.reads)
            service.onListenerConnected()
            awaitCondition { entries().size == 1 }
            assertTrue(NotificationMonitor.health(context).connected)
            service.onNotificationPosted(payment); service.scanCurrent()
            awaitCondition { NotificationMonitor.health(context).parsed >= 3 }
            assertEquals(1, entries().size); assertEquals(2850L, entries().single().amountFen)
            assertFalse(PaymentPageSettings.enabled(context))
        } finally { service.onDestroy() }
        assertFalse(NotificationMonitor.health(context).connected)
    }
    @Test fun filteredNotificationIsDiagnosedWithoutPersistingPrivateMessage() {
        val service = Robolectric.buildService(ProbeNotificationService::class.java).create().get()
        try {
            service.onNotificationPosted(notification(title = "同学", text = "秘密聊天：微信支付成功28.50元，订单123456"))
            awaitCondition { NotificationMonitor.health(context).outcome.contains("普通消息") }
            assertTrue(entries().isEmpty())
            val diagnostic = NotificationMonitor.diagnostic(context)
            assertTrue(diagnostic.contains("HEADER_NOT_PAYMENT")); assertFalse(diagnostic.contains("秘密聊天"))
            assertFalse(diagnostic.contains("28.50")); assertFalse(diagnostic.contains("订单123456"))
        } finally { service.onDestroy() }
    }
    @Test fun messagingStyleUsesPaymentSenderAndRetainsTwoSeparateSameAmountPayments() {
        val n = Notification.Builder(context, "test").setWhen(time).setStyle(Notification.MessagingStyle(Person.Builder().setName("我").build())
            .addMessage("支付成功12.50元，商户：测试一食堂", time, Person.Builder().setName("微信支付").build())
            .addMessage("支付成功12.50元，商户：测试二食堂", time + 600_000, Person.Builder().setName("微信支付").build())).build()
        val sbn = StatusBarNotification("com.tencent.mm", "com.tencent.mm", 2, "test", 10001, 1, 0, n, Process.myUserHandle(), time)
        val parts = PaymentNotificationReader.read(sbn, time + 600_000).parts
        assertEquals(2, parts.size); assertEquals(2, parts.map { it.key }.distinct().size)
        assertTrue(parts.all { NotificationParser.parse("com.tencent.mm", it.title, it.text, it.key, it.time, it.headers) != null })
        val service = Robolectric.buildService(ProbeNotificationService::class.java).create().get()
        try {
            service.onNotificationPosted(sbn); service.onNotificationPosted(sbn)
            awaitCondition { NotificationMonitor.health(context).parsed >= 4 }
            assertEquals(2, entries().size)
        } finally { service.onDestroy() }
    }
    @Test fun groupSummaryDoesNotBecomeAnExtraPayment() {
        val sbn = notification(); sbn.notification.flags = sbn.notification.flags or Notification.FLAG_GROUP_SUMMARY
        assertTrue(PaymentNotificationReader.read(sbn, time).summary)
        assertTrue(PaymentNotificationReader.read(sbn, time).parts.isEmpty())
    }
    @Test fun selectedCampusIsCapturedAndStoppingItKeepsHistory() {
        installCampus(); val service = Robolectric.buildService(ProbeNotificationService::class.java).create().get()
        try {
            val sbn = notification(campus, "交我办", "消费成功12.50元，商户：测试食堂", 4)
            service.onNotificationPosted(sbn); assertEquals(0, NotificationMonitor.health(context).received)
            CampusSources.select(context, campus); assertEquals("交我办", CampusSources.selected(context)!!.label)
            service.onNotificationPosted(sbn)
            awaitCondition { entries().size == 1 }
            assertEquals(Channel.CAMPUS, entries().single().channel); assertEquals(Status.PENDING, entries().single().status)
            CampusSources.select(context, null)
            service.onNotificationPosted(notification(campus, "交我办", "消费成功20.00元", 5))
            assertEquals(1, entries().size); assertFalse(campus in CampusSources.packages(context))
        } finally { service.onDestroy() }
    }
    @Test fun reconnectRequiresSystemPermissionAndDoesNotEnableIt() {
        val manager = context.getSystemService(NotificationManager::class.java)
        val component = ComponentName(context, PaymentNotificationService::class.java)
        Shadows.shadowOf(manager).setNotificationListenerAccessGranted(component, false)
        assertEquals("请先开启通知使用权", NotificationMonitor.ensureConnected(context, true))
        assertFalse(manager.isNotificationListenerAccessGranted(component))
    }

    @Test fun disconnectRetriesAndConnectionCancelsFurtherAttempts() {
        var requests = 0; NotificationMonitor.requestBinding = { requests++ }
        val service = Robolectric.buildService(ProbeNotificationService::class.java).create().get()
        service.onListenerConnected(); service.onListenerDisconnected()
        assertEquals(1, requests); assertFalse(NotificationMonitor.health(context).connected)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10)); assertEquals(2, requests)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(30)); assertEquals(3, requests)
        service.onListenerConnected()
        assertTrue(NotificationMonitor.health(context).connected); assertEquals(0L, NotificationMonitor.health(context).nextRetryAt)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(20)); assertEquals(3, requests)
        service.onDestroy()
    }
    @Test fun destructionWithoutDisconnectCallbackStillRecovers() {
        var requests = 0; NotificationMonitor.requestBinding = { requests++ }
        val service = Robolectric.buildService(ProbeNotificationService::class.java).create().get()
        service.onListenerConnected(); service.onDestroy()
        assertFalse(NotificationMonitor.health(context).connected); assertEquals(1, requests)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10)); assertEquals(2, requests)
    }
    @Test fun nullReadInvalidatesStaleConnectionAndRebinds() {
        var requests = 0; NotificationMonitor.requestBinding = { requests++ }
        val service = Robolectric.buildService(ProbeNotificationService::class.java).create().get()
        service.onListenerConnected(); service.nullRead = true
        NotificationMonitor.ensureConnected(context, manual = true)
        assertFalse(NotificationMonitor.health(context).connected); assertEquals(1, requests)
        service.nullRead = false; service.onListenerConnected()
        assertTrue(NotificationMonitor.health(context).connected); assertTrue(NotificationMonitor.health(context).checkedAt > 0)
        service.onDestroy()
    }
    @Test fun failedReadDoesNotKeepFalseConnectedState() {
        var requests = 0; NotificationMonitor.requestBinding = { requests++ }
        val service = Robolectric.buildService(ProbeNotificationService::class.java).create().get()
        service.onListenerConnected(); service.failRead = true; service.scanCurrent()
        assertFalse(NotificationMonitor.health(context).connected); assertEquals(1, requests)
        assertFalse(NotificationMonitor.diagnostic(context).contains("synthetic disconnected service")); service.onDestroy()
    }
    @Test fun revocationCancelsRetryAndDoesNotGrantPermission() {
        var requests = 0; NotificationMonitor.requestBinding = { requests++ }
        NotificationMonitor.ensureConnected(context); assertEquals(1, requests)
        val manager = context.getSystemService(NotificationManager::class.java)
        val component = ComponentName(context, PaymentNotificationService::class.java)
        Shadows.shadowOf(manager).setNotificationListenerAccessGranted(component, false)
        NotificationMonitor.ensureConnected(context)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(20))
        assertEquals(1, requests); assertFalse(manager.isNotificationListenerAccessGranted(component))
        assertEquals(0L, NotificationMonitor.health(context).nextRetryAt)
    }
    @Test fun quietHealthyConnectionIsNotForciblyRebound() {
        var requests = 0; NotificationMonitor.requestBinding = { requests++ }
        val service = Robolectric.buildService(ProbeNotificationService::class.java).create().get()
        service.onListenerConnected(); Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofHours(2))
        NotificationMonitor.ensureConnected(context)
        assertTrue(service.reads >= 2); assertEquals(0, requests); assertTrue(NotificationMonitor.health(context).connected)
        service.onDestroy()
    }
    @Test fun oldServiceCannotDetachReplacement() {
        var requests = 0; NotificationMonitor.requestBinding = { requests++ }
        val old = Robolectric.buildService(ProbeNotificationService::class.java).create().get()
        val replacement = Robolectric.buildService(ProbeNotificationService::class.java).create().get()
        old.onListenerConnected(); replacement.onListenerConnected(); old.onListenerDisconnected(); old.onDestroy()
        assertTrue(NotificationMonitor.attached(replacement)); assertEquals(0, requests); replacement.onDestroy()
    }
    @Test fun failedRequestRetriesWithoutFloodingBinder() {
        var requests = 0; NotificationMonitor.requestBinding = { requests++; throw IllegalStateException("synthetic binder failure") }
        repeat(10) { NotificationMonitor.ensureConnected(context) }; assertEquals(1, requests)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10)); assertEquals(2, requests)
        assertFalse(NotificationMonitor.diagnostic(context).contains("synthetic binder failure"))
    }
    @Test fun backgroundProbeReadsServiceOnMainThread() {
        val service = Robolectric.buildService(ProbeNotificationService::class.java).create().get()
        service.onListenerConnected(); runBlocking(Dispatchers.IO) { service.scanCurrent() }
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertEquals(2, service.reads); assertTrue(service.readOnMain); service.onDestroy()
    }
}
