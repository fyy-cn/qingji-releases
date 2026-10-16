package cn.qingji.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ApplicationProvider
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
import org.xmlpull.v1.XmlPullParser
import cn.qingji.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

class ProbePaymentPageService : PaymentPageService() {
    var page: AccessibilityNodeInfo? = null
    var rootReads = 0
    override fun getRootInActiveWindow(): AccessibilityNodeInfo? { rootReads++; return page }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = OfflineTestApplication::class)
@LooperMode(LooperMode.Mode.PAUSED)
class PaymentPageServiceTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    @Before fun clear() {
        context.getSharedPreferences("payment_page", 0).edit().clear().commit()
        context.getSharedPreferences("diagnostics", 0).edit().clear().commit()
    }
    @After fun cleanup() { clear() }
    @OptIn(ExperimentalCoroutinesApi::class)
    @Suppress("DEPRECATION")
    @Test fun optedInPaymentEventTraversesPageAndStoresOnePendingCandidate() {
        val dispatcher = StandardTestDispatcher()
        Dispatchers.setMain(dispatcher)
        val service = Robolectric.buildService(ProbePaymentPageService::class.java).create().get()
        val repo = (service.application as QingJiApplication).repository
        try {
            runBlocking(Dispatchers.IO) { repo.restore(BackupData(entries = emptyList(), rules = emptyList(), imports = emptyList())) }
            fun node(text: String) = AccessibilityNodeInfo.obtain().apply { this.text = text; packageName = "com.tencent.mm"; isVisibleToUser = true }
            service.page = node("支付成功").also { root ->
                listOf("商户名称", "测试咖啡店", "实付金额", "￥28.50").forEach { Shadows.shadowOf(root).addChild(node(it)) }
            }
            PaymentPageSettings.setEnabled(context, true)
            service.onAccessibilityEvent(AccessibilityEvent.obtain().apply { eventType = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED; packageName = "com.tencent.mm"; className = "com.tencent.mm.plugin.wallet.pay.ui.WalletPayUI" })
            dispatcher.scheduler.advanceTimeBy(500); dispatcher.scheduler.runCurrent()
            var records = emptyList<Entry>()
            val deadline = System.nanoTime() + 5_000_000_000L
            while (records.isEmpty() && System.nanoTime() < deadline) {
                dispatcher.scheduler.runCurrent()
                records = runBlocking(Dispatchers.IO) { repo.snapshot().entries }
                if (records.isEmpty()) Thread.sleep(10)
            }
            val entry = records.single()
            assertEquals(Status.PENDING, entry.status); assertEquals(2850L, entry.amountFen); assertEquals("测试咖啡店", entry.merchant)
            assertTrue(entry.sources.contains("页面识别（实验）")); assertTrue(entry.eventKeys.single().startsWith("page:"))
            assertEquals(1, service.rootReads)
            PaymentPageSettings.setEnabled(context, false)
        } finally { service.onDestroy(); Dispatchers.resetMain() }
    }
    @Test fun experimentIsOptInAndSystemPermissionIsNeverEnabledProgrammatically() {
        assertFalse(PaymentPageSettings.enabled(context)); assertFalse(PaymentPageSettings.health(context).access)
        PaymentPageSettings.setEnabled(context, true)
        assertTrue(PaymentPageSettings.enabled(context)); assertFalse(PaymentPageSettings.health(context).access)
        PaymentPageSettings.openSettings(context)
        val intent = Shadows.shadowOf(context as android.app.Application).nextStartedActivity
        assertEquals(Settings.ACTION_ACCESSIBILITY_SETTINGS, intent.action)
        PaymentPageSettings.setEnabled(context, false)
        assertFalse(PaymentPageSettings.enabled(context))
    }
    @Test fun disabledOtherAppsAndChatWindowsCannotTriggerPageRead() {
        val service = Robolectric.buildService(ProbePaymentPageService::class.java).create().get()
        try {
            val event = AccessibilityEvent.obtain().apply { eventType = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED; packageName = "com.tencent.mm"; className = "com.tencent.mm.plugin.wallet.pay.ui.WalletPayUI" }
            service.onAccessibilityEvent(event)
            PaymentPageSettings.setEnabled(context, true)
            event.packageName = "other.app"; service.onAccessibilityEvent(event)
            event.packageName = "com.tencent.mm"; event.className = "com.tencent.mm.ui.LauncherUI"; service.onAccessibilityEvent(event)
            assertEquals(0, service.rootReads)
        } finally { service.onDestroy() }
    }
    @Suppress("DEPRECATION")
    @Test fun treeReaderSkipsPasswordEditableHiddenSensitiveAndOtherAppNodes() {
        fun node(text: String, packageName: String = "com.tencent.mm") = AccessibilityNodeInfo.obtain().apply {
            this.text = text; this.packageName = packageName; isVisibleToUser = true
        }
        val root = node("支付成功")
        listOf(node("￥28.50"), node("密码秘密").apply { isPassword = true }, node("输入秘密").apply { isEditable = true },
            node("不可见秘密").apply { isVisibleToUser = false }, node("敏感秘密").apply { isAccessibilityDataSensitive = true }, node("其他App秘密", "other.app"))
            .forEach { Shadows.shadowOf(root).addChild(it) }
        val service = Robolectric.buildService(ProbePaymentPageService::class.java).create().get()
        try {
            service.page = root
            assertEquals(listOf("支付成功", "￥28.50"), service.readActivePage("com.tencent.mm"))
        } finally { service.onDestroy() }
    }
    @Test fun serviceManifestAllowsOnlyTwoPackagesAndNoActionsOrScreenshots() {
        val info = context.packageManager.getServiceInfo(ComponentName(context, PaymentPageService::class.java), PackageManager.GET_META_DATA)
        assertEquals("android.permission.BIND_ACCESSIBILITY_SERVICE", info.permission)
        val ns = "http://schemas.android.com/apk/res/android"
        context.resources.getXml(info.metaData.getInt("android.accessibilityservice")).use { xml ->
            while (xml.eventType != XmlPullParser.START_TAG) xml.next()
            assertEquals("com.tencent.mm,com.eg.android.AlipayGphone", xml.getAttributeValue(ns, "packageNames"))
            assertEquals(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, xml.getAttributeIntValue(ns, "accessibilityEventTypes", 0))
            assertTrue(xml.getAttributeBooleanValue(ns, "canRetrieveWindowContent", false))
            assertFalse(xml.getAttributeBooleanValue(ns, "isAccessibilityTool", true))
            assertFalse(xml.getAttributeBooleanValue(ns, "canPerformGestures", false))
            assertFalse(xml.getAttributeBooleanValue(ns, "canTakeScreenshot", false))
        }
    }
    @Test fun diagnosticContainsStatusButNeverReceiptOrRawPageData() {
        context.getSharedPreferences("diagnostics", 0).edit().putString("raw", "秘密商家28.50订单123456").putString("pageClass", "com.tencent.mm.plugin.wallet.TestUI").commit()
        val text = PaymentPageSettings.diagnostic(context)
        assertTrue(text.contains("页面识别诊断")); assertTrue(text.contains("TestUI")); assertFalse(text.contains("秘密商家")); assertFalse(text.contains("28.50"))
    }
}
