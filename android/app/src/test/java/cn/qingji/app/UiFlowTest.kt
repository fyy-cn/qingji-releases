package cn.qingji.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import java.io.File
import cn.qingji.core.BackupData
import cn.qingji.core.UpdateRelease
import cn.qingji.core.Entry
import cn.qingji.core.Channel
import cn.qingji.core.Category
import android.app.Activity
import android.content.Intent
import android.net.Uri
import org.robolectric.Shadows
import java.io.ByteArrayOutputStream
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.After
import org.junit.Test
import org.junit.Assert.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = OfflineTestApplication::class, qualifiers = "zh-rCN-w411dp-h891dp-420dpi")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UiFlowTest {
    @get:Rule(order = 0) val mainLooper = MainLooperRule()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    @After fun closeActivityDatabase() {
        val repo = (compose.activity.application as QingJiApplication).repository
        val database = org.robolectric.util.ReflectionHelpers.getField<LedgerDatabase>(repo, "db")
        compose.activityRule.scenario.close()
        database.close()
    }

    private fun screenshot(name: String) {
        val directory = System.getProperty("qingji.screenshots") ?: return
        compose.waitForIdle()
        val file = File(directory, "$name.png")
        file.parentFile?.mkdirs()
        if (name in setOf("06-categories", "07-page-recognition-disclosure", "08-campus-picker")) captureScreenRoboImage(file.absolutePath)
        else compose.activity.window.decorView.captureRoboImage(file.absolutePath)
    }

    @Test fun userCanRecordExpenseAndNavigateToLedgerAnalysisAndSettingsWithoutNotificationAccess() {
        val repo = (compose.activity.application as QingJiApplication).repository
        runBlocking(Dispatchers.IO) { repo.restore(BackupData(entries = emptyList(), rules = emptyList(), imports = emptyList())) }
        screenshot("01-home")
        compose.onNodeWithText("记一笔", useUnmergedTree = true).performClick()
        compose.onNodeWithText("金额（元）").performTextInput("28.50")
        compose.onNodeWithText("商家 / 收款方（选填）").performTextInput("测试面馆")
        compose.onNodeWithText("新增自定义分类").performClick()
        compose.onNodeWithText("分类名称").performScrollTo().performTextInput("学习")
        compose.onNodeWithText("保存分类").performScrollTo().performClick()
        compose.onNodeWithText("学习").assertExists()
        compose.onNodeWithText("保存账单").performClick()
        compose.waitUntil(15000) { runBlocking(Dispatchers.IO) { repo.snapshot().entries.any { it.merchant == "测试面馆" && it.amountFen == 2850L } } }
        compose.waitForIdle()
        compose.onNodeWithText("账单", useUnmergedTree = true).performClick()
        compose.waitUntil(15000) { compose.onAllNodesWithText("测试面馆").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("测试面馆").assertIsDisplayed()
        assertTrue(runBlocking(Dispatchers.IO) { repo.snapshot().entries.first { it.merchant == "测试面馆" }.category.isCustom })
        screenshot("02-ledger")
        val excluded = Entry(amountFen = 1000, occurredAt = System.currentTimeMillis(), merchant = "不应导出的商家", channel = Channel.MANUAL)
        runBlocking(Dispatchers.IO) { repo.save(excluded, false) }
        compose.onNodeWithText("搜索商家、分类、备注或交易号").performTextInput("测试面馆")
        compose.onNodeWithText("导出CSV").performClick()
        val activity = Shadows.shadowOf(compose.activity)
        compose.waitUntil(15000) {
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            activity.peekNextStartedActivityForResult() != null
        }
        val request = activity.nextStartedActivityForResult.intent
        org.junit.Assert.assertEquals(Intent.ACTION_CREATE_DOCUMENT, request.action)
        org.junit.Assert.assertEquals("text/csv", request.type)
        val destination = Uri.parse("content://qingji-test/export.csv")
        val csv = ByteArrayOutputStream()
        Shadows.shadowOf(compose.activity.contentResolver).registerOutputStream(destination, csv)
        compose.runOnUiThread { activity.receiveResult(request, Activity.RESULT_OK, Intent().setData(destination)) }
        compose.waitUntil(15000) {
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            csv.toString("UTF-8").contains("测试面馆")
        }
        assertTrue(csv.toString("UTF-8").contains("学习"))
        assertTrue(!csv.toString("UTF-8").contains("不应导出的商家"))
        runBlocking(Dispatchers.IO) { repo.ignore(excluded.id) }
        compose.onNode(hasSetTextAction()).performTextClearance()
        compose.onNodeWithText("分析", useUnmergedTree = true).performClick()
        compose.onNodeWithText("消费分析").assertIsDisplayed()
        assertTrue(compose.onAllNodesWithText("¥28.50").fetchSemanticsNodes().isNotEmpty())
        screenshot("03-analysis")
        compose.onNodeWithText("设置", useUnmergedTree = true).performClick()
        compose.onNodeWithText("你的账本，留在你的手机里。").assertIsDisplayed()
        compose.onNodeWithText("通知使用权：未授权").assertIsDisplayed()
        screenshot("04-settings")
        val campusInfo = android.content.pm.ApplicationInfo().apply { packageName = "edu.example.campus"; enabled = true; nonLocalizedLabel = "交我办（测试）" }
        val packageInfo = android.content.pm.PackageInfo().apply { packageName = campusInfo.packageName; applicationInfo = campusInfo }
        val pm = Shadows.shadowOf(compose.activity.packageManager)
        pm.installPackage(packageInfo)
        val launcher = android.content.pm.ResolveInfo().apply { activityInfo = android.content.pm.ActivityInfo().apply { packageName = campusInfo.packageName; name = "edu.example.campus.MainActivity"; enabled = true; applicationInfo = campusInfo } }
        pm.addResolveInfoForIntent(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), launcher)
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("选择交我办"))
        compose.onNodeWithText("选择交我办").performClick()
        compose.onNodeWithText("搜索应用名称").performTextInput("交我办")
        compose.waitUntil(15000) { compose.onAllNodesWithText("交我办（测试）").fetchSemanticsNodes().isNotEmpty() }
        screenshot("08-campus-picker")
        compose.onNodeWithText("交我办（测试）").performClick()
        compose.waitUntil(15000) { CampusSources.packageName(compose.activity) == campusInfo.packageName }
        compose.onNodeWithText("已选择：交我办（测试）").assertExists()
        screenshot("09-campus-settings")
        // A paused Robolectric snackbar can cover the bottom row; invoke its accessibility action.
        compose.onNodeWithText("停止采集校园应用").performScrollTo().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
        compose.waitUntil(15000) { CampusSources.packageName(compose.activity) == null }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("开启页面识别"))
        compose.onNodeWithText("实验功能：关闭").assertExists()
        compose.onNodeWithText("开启页面识别").performClick()
        compose.onNodeWithText("启用支付页面识别实验").assertIsDisplayed()
        screenshot("07-page-recognition-disclosure")
        compose.onNodeWithText("暂不开启").performClick()
        assertTrue(!PaymentPageSettings.enabled(compose.activity))
        runBlocking(Dispatchers.IO) { repeat(8) { repo.moveCategory(Category.custom("学习"), -1) } }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("管理消费分类"))
        // Trigger the same accessibility action when the paused snackbar covers this row.
        compose.onNodeWithText("管理消费分类").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
        compose.onNodeWithText("学习").assertExists()
        compose.onNodeWithContentDescription("下移学习").performClick()
        compose.waitUntil(15000) { runBlocking(Dispatchers.IO) { repo.snapshot().categoryPreferences.single { it.category.label == "学习" }.position == 1 } }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("上移学习").performClick()
        compose.waitUntil(15000) { runBlocking(Dispatchers.IO) { repo.snapshot().categoryPreferences.single { it.category.label == "学习" }.position == 0 } }
        compose.onNodeWithContentDescription("管理学习").performClick()
        compose.onNodeWithText("重命名").performClick()
        compose.onNodeWithText("分类名称").performScrollTo().performTextReplacement("进修")
        compose.onNodeWithText("保存分类").performScrollTo().performClick()
        compose.waitUntil(15000) { runBlocking(Dispatchers.IO) { repo.snapshot().customCategories.any { it.label == "进修" } } }
        compose.waitUntil(15000) { compose.onAllNodesWithText("进修").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("进修").assertExists()
        compose.onNodeWithContentDescription("管理购物").performScrollTo().performClick()
        compose.onNodeWithText("删除分类").performClick()
        compose.onNodeWithText("删除“购物”？").assertExists()
        compose.onNodeWithText("删除分类").performClick()
        compose.waitUntil(15000) { runBlocking(Dispatchers.IO) { repo.snapshot().categoryPreferences.any { it.category == Category.SHOPPING && it.hidden } } }
        compose.onNodeWithText("购物").assertDoesNotExist()
        screenshot("06-categories")
        compose.onNodeWithText("完成").performClick()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("应用更新"))
        compose.onNodeWithText("应用更新").assertIsDisplayed()
        compose.onNodeWithText("检查更新").assertExists()
        screenshot("05-update-settings")
        val updater = ViewModelProvider(compose.activity)[UpdateViewModel::class.java]
        compose.runOnUiThread {
            updater.state.value = UpdateUiState(release = UpdateRelease(packageName = "cn.qingji.app", versionCode = BuildConfig.VERSION_CODE + 1L, versionName = "1.5.0", minSdk = 29,
                apkUrl = "https://github.com/fyy-cn/qingji-releases/releases/download/v1.5.0/test.apk", sha256 = "0".repeat(64), sizeBytes = 1024,
                publishedAt = "2026-10-16", notes = listOf("测试更新说明")), showDialog = true, message = "发现新版本 1.5.0")
        }
        compose.onNodeWithText("清记 1.5.0").assertIsDisplayed()
        compose.onNodeWithText("下载并更新").assertIsDisplayed()
        compose.onNodeWithText("稍后再说").performClick()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("查看新版"))
        compose.onNodeWithText("查看新版").performClick()
        compose.onNodeWithText("清记 1.5.0").assertIsDisplayed()
        compose.onNodeWithText("稍后再说").performClick()
    }
}

class OfflineTestApplication : QingJiApplication() { override val updatesEnabled = false }
