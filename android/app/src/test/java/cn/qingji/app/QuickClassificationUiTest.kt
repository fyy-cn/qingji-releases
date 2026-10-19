package cn.qingji.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import cn.qingji.core.*
import com.github.takahirom.roborazzi.captureScreenRoboImage
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = OfflineTestApplication::class, qualifiers = "zh-rCN-w411dp-h891dp-420dpi")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class QuickClassificationUiTest {
    @get:Rule(order = 0) val mainLooper = MainLooperRule()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var repo: LedgerRepository
    @Before fun obtainRepositoryOnMainThread() { repo = (compose.activity.application as QingJiApplication).repository }
    @After fun closeActivityDatabase() {
        val database = org.robolectric.util.ReflectionHelpers.getField<LedgerDatabase>(repo, "db")
        compose.activityRule.scenario.close()
        database.close()
    }
    private fun data() = runBlocking(Dispatchers.IO) { repo.snapshot() }
    private fun waitFor(test: () -> Boolean) {
        try { compose.waitUntil(15000, test) } catch (failure: Throwable) {
            println(compose.onRoot(useUnmergedTree = true).printToString())
            val vm = androidx.lifecycle.ViewModelProvider(compose.activity)[LedgerViewModel::class.java]
            println("UI diagnostic: records=${data().entries.size}; vmRecords=${vm.entries.value.size}; busy=${vm.busy.value}; lifecycle=${compose.activity.lifecycle.currentState}")
            screenshot("debug-quick-failure")
            throw failure
        }
    }
    private fun screenshot(name: String) {
        val directory = System.getProperty("qingji.screenshots") ?: return
        compose.waitForIdle(); val file = File(directory, "$name.png"); file.parentFile?.mkdirs()
        captureScreenRoboImage(file.absolutePath)
    }
    private fun entry(id: String, status: Status = Status.CONFIRMED) = Entry(id = id, amountFen = 1250, occurredAt = System.currentTimeMillis(), channel = Channel.CAMPUS, status = status)
    @Test fun userCreatesSortsEditsAppliesTemplatesAndBatchClassifiesWithoutApprovingPendingBills() {
        val pending = entry("q2", Status.PENDING).copy(amountFen = 0, kind = Kind.UNKNOWN, reason = "金额需核实")
        val known = entry("q3").copy(merchant = "原有商家", note = "原有备注")
        val untouched = entry("q4").copy(category = Category.SHOPPING)
        runBlocking(Dispatchers.IO) { repo.restore(BackupData(entries = listOf(entry("q1"), pending, known, untouched), rules = emptyList(), imports = emptyList())) }
        compose.onNodeWithText("账单", useUnmergedTree = true).performClick()
        waitFor { compose.onAllNodesWithText("快捷归类 · 3 笔待归类").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("快捷归类 · 3 笔待归类").performClick()
        compose.onNodeWithText("待归类 3 笔 · 选择消费分类").assertExists()
        screenshot("10-quick-classification")
        compose.onNodeWithText("管理模板").performClick()
        compose.onNodeWithText("新增模板").performClick()
        compose.onNodeWithText("模板名称").performTextInput("二食堂")
        compose.onNodeWithText("模板商家（选填）").assertDoesNotExist()
        compose.onNodeWithText("模板备注（选填）").performTextInput("二食堂午餐")
        compose.onNodeWithText("保存模板").performClick()
        waitFor { data().quickTemplates.size == 1 }
        compose.onNodeWithText("添加常用模板").performClick()
        waitFor { data().quickTemplates.size == 7 }
        compose.onNodeWithContentDescription("下移模板二食堂").performClick()
        waitFor { data().quickTemplates.single { it.label == "二食堂" }.position == 1 }
        compose.onNodeWithContentDescription("上移模板二食堂").performClick()
        waitFor { data().quickTemplates.single { it.label == "二食堂" }.position == 0 }
        compose.onNodeWithContentDescription("管理模板二食堂").performClick()
        compose.onNodeWithText("编辑模板").performClick()
        compose.onNodeWithText("模板名称").performTextReplacement("二食堂午餐")
        compose.onNodeWithText("保存模板").performClick()
        waitFor { data().quickTemplates.any { it.label == "二食堂午餐" } }
        screenshot("11-scene-templates")
        compose.onNodeWithText("关闭").performClick()
        compose.onNode(hasText("套用场景模板") and hasAnyAncestor(hasTestTag("quick-entry-q1"))).performScrollTo().performClick()
        compose.onNodeWithText("二食堂午餐").performClick()
        waitFor { data().entries.single { it.id == "q1" }.category == Category.FOOD }
        assertEquals("未知商家", data().entries.single { it.id == "q1" }.merchant)
        assertEquals("二食堂午餐", data().entries.single { it.id == "q1" }.note)
        assertEquals(1L, data().quickTemplates.single { it.label == "二食堂午餐" }.useCount)
        compose.onNodeWithText("批量选择").performClick()
        compose.onNodeWithText("全选当前列表").performClick()
        compose.onNodeWithText("已选 2 笔").assertExists()
        compose.onNodeWithText("批量设分类").performClick()
        compose.onNodeWithText("交通").performClick()
        waitFor { data().entries.filter { it.id in setOf("q2", "q3") }.all { it.category == Category.TRANSPORT } }
        assertEquals(pending.copy(category = Category.TRANSPORT, editedFields = setOf("category")), data().entries.single { it.id == "q2" })
        assertEquals(known.copy(category = Category.TRANSPORT, editedFields = setOf("category")), data().entries.single { it.id == "q3" })
        assertEquals(untouched, data().entries.single { it.id == "q4" })
        compose.onNodeWithText("结束多选").performClick()
        compose.onNodeWithText("这一页归类完成了").assertExists()
        screenshot("12-classification-complete")
        compose.onNodeWithText("待确认 1 笔").performClick()
        compose.onNodeWithText("待确认账单").assertExists()
        compose.onNodeWithText("待补充").assertExists()
        compose.onNodeWithText("设置", useUnmergedTree = true).performClick()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("管理场景模板"))
        compose.onNodeWithText("管理场景模板").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
        compose.onNodeWithContentDescription("管理模板二食堂午餐").performClick()
        compose.onNodeWithText("删除模板").performClick()
        compose.onNodeWithText("删除模板“二食堂午餐”？").assertExists()
        compose.onNodeWithText("删除模板").performClick()
        waitFor { data().quickTemplates.none { it.label == "二食堂午餐" } }
        assertEquals("未知商家", data().entries.single { it.id == "q1" }.merchant)
        assertEquals("二食堂午餐", data().entries.single { it.id == "q1" }.note)
        compose.onNodeWithText("关闭").performClick()
        categoryAndNoteFormPreservesLegacyMetadataAndRecordsUsageOnlyAfterSave()
    }
    private fun categoryAndNoteFormPreservesLegacyMetadataAndRecordsUsageOnlyAfterSave() {
        val scene = QuickTemplate(id = "lunch", label = "午餐", category = Category.FOOD, merchant = "旧模板商家", note = "午餐")
        runBlocking(Dispatchers.IO) { repo.restore(BackupData(entries = emptyList(), rules = emptyList(), imports = emptyList(), quickTemplates = listOf(scene))) }
        waitFor { data().quickTemplates.size == 1 }
        compose.onNodeWithText("首页", useUnmergedTree = true).performClick()
        compose.onNodeWithText("记一笔", useUnmergedTree = true).performClick()
        compose.onNodeWithText("金额（元）").performTextInput("19.80")
        waitFor { compose.onAllNodesWithTag("template-lunch").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("template-lunch").performClick()
        compose.onNodeWithText("商家 / 收款方（选填）").assertDoesNotExist()
        compose.onNodeWithText("记住该商家的分类，用于后续交易").assertDoesNotExist()
        compose.onNodeWithText("备注（选填）").assertExists()
        screenshot("13-category-note-editor")
        assertEquals(0L, data().quickTemplates.single().useCount)
        compose.onNodeWithText("保存账单").performClick()
        waitFor { data().entries.size == 1 }
        assertEquals("未知商家", data().entries.single().merchant)
        assertEquals(Category.FOOD, data().entries.single().category); assertEquals(1980L, data().entries.single().amountFen)
        assertEquals(1L, data().quickTemplates.single().useCount)
        val legacy = entry("legacy").copy(merchant = "历史面馆", category = Category.FOOD, note = "原有备注", editedFields = setOf("merchant"))
        runBlocking(Dispatchers.IO) { repo.restore(BackupData(entries = listOf(legacy), rules = emptyList(), imports = emptyList())) }
        compose.onNodeWithText("账单", useUnmergedTree = true).performClick()
        waitFor { compose.onAllNodesWithText("原有备注").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("历史面馆").assertDoesNotExist()
        compose.onNodeWithText("原有备注").performClick()
        compose.onNodeWithText("备注（选填）").performTextReplacement("补充用途")
        compose.onNodeWithText("保存账单").performClick()
        waitFor { data().entries.single().note == "补充用途" }
        assertEquals("历史面馆", data().entries.single().merchant)
        assertEquals(legacy.amountFen, data().entries.single().amountFen)
        assertEquals(legacy.category, data().entries.single().category)
    }
}
