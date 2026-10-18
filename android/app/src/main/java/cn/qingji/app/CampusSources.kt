package cn.qingji.app

import android.content.Context
import android.content.Intent
import androidx.core.content.edit
import cn.qingji.core.PaymentSources

data class CampusApp(val packageName: String, val label: String)

object CampusSources {
    fun packageName(context: Context): String? = context.getSharedPreferences("capture_sources", Context.MODE_PRIVATE).getString("campusPackage", null)?.takeIf { it.isNotBlank() }
    fun selected(context: Context): CampusApp? = packageName(context)?.let { pkg ->
        runCatching { context.packageManager.getApplicationInfo(pkg, 0).let { CampusApp(pkg, context.packageManager.getApplicationLabel(it).toString()) } }.getOrElse { CampusApp(pkg, "校园应用（未安装）") }
    }
    fun packages(context: Context) = PaymentSources.builtIns + listOfNotNull(packageName(context))
    fun select(context: Context, pkg: String?) {
        if (pkg != null) {
            require(pkg != context.packageName && pkg !in PaymentSources.builtIns && Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+").matches(pkg)) { "请选择安装的校园应用" }
            require(context.packageManager.getApplicationInfo(pkg, 0).enabled) { "此应用不可用" }
        }
        context.getSharedPreferences("capture_sources", Context.MODE_PRIVATE).edit { if (pkg == null) remove("campusPackage") else putString("campusPackage", pkg) }
    }
    fun installed(context: Context): List<CampusApp> = context.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        .mapNotNull { item -> item.activityInfo?.takeIf { it.enabled && it.applicationInfo.enabled }?.let { CampusApp(it.packageName, context.packageManager.getApplicationLabel(it.applicationInfo).toString()) } }
        .filter { it.packageName != context.packageName && it.packageName !in PaymentSources.builtIns }.distinctBy { it.packageName }
        .sortedWith(compareBy<CampusApp> { !it.label.contains("交我办") }.thenBy { it.label })
}
