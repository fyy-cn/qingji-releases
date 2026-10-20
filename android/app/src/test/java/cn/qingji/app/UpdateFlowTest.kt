package cn.qingji.app

import androidx.test.core.app.ApplicationProvider
import cn.qingji.core.UpdateRelease
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

class TestReleaseClient : ReleaseClient {
    var calls = 0
    var fail = false
    var release = UpdateRelease(packageName = "cn.qingji.app", versionCode = BuildConfig.VERSION_CODE + 1L, versionName = "1.5.0", minSdk = 29,
        apkUrl = "https://github.com/fyy-cn/qingji-releases/releases/download/v1.5.0/test.apk", sha256 = "0".repeat(64), sizeBytes = 1024,
        publishedAt = "2026-10-16", notes = listOf("测试版本：改进消费分析"))
    override suspend fun latest(): UpdateRelease { calls++; if (fail) error("offline"); return release }
    override suspend fun download(release: UpdateRelease, destination: File, progress: (Long) -> Unit) { error("Test downloads are disabled") }
}
class UpdateTestApplication : QingJiApplication() {
    override val notificationRecoveryEnabled = false
    override val releaseClient = TestReleaseClient()
    override fun scheduleUpdates() { /* No background network in unit tests. */ }
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = UpdateTestApplication::class)
class UpdateFlowTest {
    private lateinit var app: UpdateTestApplication
    @Before fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        app = ApplicationProvider.getApplicationContext()
        app.getSharedPreferences("app_updates", 0).edit().clear().commit()
    }
    @After fun teardown() { Dispatchers.resetMain() }

    @Test fun automaticCheckPromptsOnceAndManualCheckCanReopenDismissedRelease() {
        val vm = UpdateViewModel(app)
        vm.resume()
        assertTrue(vm.state.value.showDialog)
        assertEquals("1.5.0", vm.state.value.release?.versionName)
        vm.dismiss()
        val restarted = UpdateViewModel(app)
        restarted.resume()
        assertFalse(restarted.state.value.showDialog)
        restarted.check(true)
        assertTrue(restarted.state.value.showDialog)
    }
    @Test fun offlineCheckDoesNotBlockLedgerOrLeaveCheckBusy() {
        app.releaseClient.fail = true
        val vm = UpdateViewModel(app)
        vm.check()
        assertFalse(vm.state.value.checking)
        assertNull(vm.state.value.release)
        assertTrue(vm.state.value.message.contains("网络"))
        assertFalse(vm.state.value.showDialog)
    }
    @Test fun currentOlderAndIncompatibleReleasesDoNotOfferInstall() {
        val vm = UpdateViewModel(app)
        app.releaseClient.release = app.releaseClient.release.copy(versionCode = BuildConfig.VERSION_CODE.toLong())
        vm.check(); assertNull(vm.state.value.release); assertEquals("当前已是最新版本", vm.state.value.message)
        app.releaseClient.release = app.releaseClient.release.copy(versionCode = BuildConfig.VERSION_CODE - 1L)
        vm.check(); assertNull(vm.state.value.release)
        app.releaseClient.release = app.releaseClient.release.copy(versionCode = BuildConfig.VERSION_CODE + 1L, minSdk = 36)
        vm.check(); assertNull(vm.state.value.release); assertTrue(vm.state.value.message.contains("36"))
    }
}
