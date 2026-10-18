package cn.qingji.app

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cn.qingji.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LedgerViewModel(application: Application) : AndroidViewModel(application) {
    private val repo = (application as QingJiApplication).repository
    val entries = repo.entries.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val rules = repo.rules.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val imports = repo.imports.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val categories = repo.categories.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), Category.entries)
    val templates = repo.templates.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val busy = MutableStateFlow(false)
    val preview = MutableStateFlow<ImportPreview?>(null)
    val restoredData = MutableStateFlow<BackupData?>(null)
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    private var encryptedExport: ByteArray? = null
    private var csvExport: List<Entry>? = null
    fun task(success: String? = null, work: suspend () -> Unit) {
        if (busy.value) return
        viewModelScope.launch {
            busy.value = true
            try {
                withContext(Dispatchers.IO) { work() }
                if (success != null) messages.emit(success)
            } catch (e: Exception) {
                messages.emit(e.message?.take(200) ?: "操作失败，请重试")
            } finally { busy.value = false }
        }
    }
    fun save(entry: Entry, makeRule: Boolean, templateId: String? = null) = task("账单已保存") { repo.save(entry, makeRule, templateId) }
    fun classify(ids: Set<String>, category: Category) = task("已归类；待确认账单仍需核实") { repo.classify(ids, category = category) }
    fun applyTemplate(ids: Set<String>, templateId: String) = task("已套用模板；待确认账单仍需核实") { repo.classify(ids, templateId = templateId) }
    fun saveTemplate(template: QuickTemplate, onSaved: () -> Unit) = task("模板已保存") { repo.saveTemplate(template); withContext(Dispatchers.Main) { onSaved() } }
    fun deleteTemplate(id: String) = task("模板已删除，账单保持原样") { repo.deleteTemplate(id) }
    fun moveTemplate(id: String, direction: Int) = task { repo.moveTemplate(id, direction) }
    fun addStarterTemplates() = task("已添加可用的常用模板") { repo.addStarterTemplates() }
    fun ignore(entry: Entry) = task("已忽略；再次收到同一交易也不会自动入账") { repo.ignore(entry.id) }
    fun merge(pendingId: String, targetId: String) = task("已合并交易来源") { repo.merge(pendingId, targetId) }
    fun removeRule(rule: MerchantRule) = task("规则已删除") { repo.removeRule(rule.merchant) }
    fun addCategory(name: String) = task("分类已新增") { repo.addCategory(name) }
    fun renameCategory(category: Category, name: String) = task("分类已重命名，历史账单与规则已同步") { repo.renameCategory(category, name) }
    fun deleteCategory(category: Category) = task("分类已删除，相关账单已改为未分类") { repo.deleteCategory(category) }
    fun moveCategory(category: Category, direction: Int) = task { repo.moveCategory(category, direction) }
    fun restoreBuiltInCategories() = task("已恢复内置分类，原账单保持当前分类") { repo.restoreBuiltInCategories() }
    fun prepareCsv(selected: List<Entry>? = null, onReady: () -> Unit) = task {
        val records = selected?.toList() ?: repo.snapshot().entries
        require(records.isNotEmpty()) { "当前没有可导出的账单" }
        csvExport = records
        withContext(Dispatchers.Main) { onReady() }
    }
    fun cancelCsv() { csvExport = null }
    fun writeCsv(uri: Uri) = task("CSV账单已导出") {
        val records = csvExport ?: error("导出已失效，请重试")
        try {
            getApplication<Application>().contentResolver.openOutputStream(uri, "wt")?.use { LedgerCsv.write(records, it) } ?: error("无法写入CSV文件")
        } finally { csvExport = null }
    }
    fun import(uri: Uri) = task {
        val resolver = getApplication<Application>().contentResolver
        val bytes = BillFiles.read(resolver, uri)
        preview.value = repo.preview(sha256(bytes), BillFiles.name(resolver, uri), BillFiles.parse(bytes))
    }
    fun commit() {
        val current = preview.value ?: return
        task("账单导入完成，请检查待确认记录") { repo.commit(current); preview.value = null }
    }
    fun prepareBackup(password: CharArray, onReady: () -> Unit) {
        task {
            try { encryptedExport = BackupCodec.encrypt(repo.snapshot(), password) } finally { password.fill('\u0000') }
            withContext(Dispatchers.Main) { onReady() }
        }
    }
    fun cancelBackup() { encryptedExport = null }
    fun writeBackup(uri: Uri) = task("加密备份已保存，请妥善保管口令") {
        val bytes = encryptedExport ?: error("备份已失效，请重新创建")
        try {
            getApplication<Application>().contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) } ?: error("无法写入备份文件")
        } finally { encryptedExport = null }
    }
    fun readBackup(uri: Uri, password: CharArray) = task {
        try {
            val bytes = BillFiles.read(getApplication<Application>().contentResolver, uri, 26 * 1024 * 1024)
            restoredData.value = BackupCodec.decrypt(bytes, password)
        } finally { password.fill('\u0000') }
    }
    fun restore() {
        val data = restoredData.value ?: return
        task("备份已恢复") { repo.restore(data); restoredData.value = null }
    }
}
