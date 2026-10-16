package cn.qingji.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.FileProvider
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.*
import cn.qingji.core.UpdateRelease
import cn.qingji.core.Updates
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

interface ReleaseClient {
    suspend fun latest(): UpdateRelease
    suspend fun download(release: UpdateRelease, destination: File, progress: (Long) -> Unit)
}

class HttpsReleaseClient : ReleaseClient {
    private fun connection(address: String, apk: Boolean = false): HttpURLConnection {
        if (apk) Updates.requireDownloadUrl(BuildConfig.UPDATE_URL, address, BuildConfig.GITHUB_REPOSITORY)
        else Updates.requireSameOrigin(BuildConfig.UPDATE_URL, address)
        var target = address
        repeat(4) {
            val connection = URL(target).openConnection() as HttpURLConnection
            connection.connectTimeout = 15_000; connection.readTimeout = 30_000
            connection.instanceFollowRedirects = false; connection.useCaches = false
            connection.setRequestProperty("Cache-Control", "no-cache")
            connection.setRequestProperty("Accept-Encoding", "identity")
            val code = connection.responseCode
            if (code in listOf(301, 302, 303, 307, 308)) {
                val location = connection.getHeaderField("Location")
                connection.disconnect()
                require(!location.isNullOrBlank()) { "更新服务重定向无效" }
                target = URL(URL(target), location).toString()
                if (apk) Updates.requireDownloadUrl(BuildConfig.UPDATE_URL, target, BuildConfig.GITHUB_REPOSITORY, isRedirect = true)
                else Updates.requireSameOrigin(BuildConfig.UPDATE_URL, target)
            } else {
                if (code != 200) { connection.disconnect(); error("更新服务暂不可用（HTTP $code）") }
                return connection
            }
        }
        error("更新服务重定向过多")
    }

    override suspend fun latest(): UpdateRelease = withContext(Dispatchers.IO) {
        val connection = connection(BuildConfig.UPDATE_URL)
        try {
            val bytes = connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(4096)
                while (true) {
                    ensureActive()
                    val size = input.read(buffer); if (size < 0) break
                    require(output.size() + size <= 64 * 1024) { "版本信息过大" }
                    output.write(buffer, 0, size)
                }
                output.toByteArray()
            }
            Updates.parse(bytes.toString(Charsets.UTF_8), BuildConfig.UPDATE_URL, BuildConfig.APPLICATION_ID, BuildConfig.GITHUB_REPOSITORY)
        } finally { connection.disconnect() }
    }

    override suspend fun download(release: UpdateRelease, destination: File, progress: (Long) -> Unit) = withContext(Dispatchers.IO) {
        val connection = connection(release.apkUrl, apk = true)
        try {
            val length = connection.contentLengthLong
            require(length == -1L || length == release.sizeBytes) { "服务器安装包大小与版本信息不一致" }
            connection.inputStream.use { input -> destination.outputStream().use { output ->
                Updates.copyVerified(input, output, release) { count -> ensureActive(); progress(count) }
            } }
        } finally { connection.disconnect() }
    }
}

class UpdatePreferences(context: Context) {
    private val preferences = context.getSharedPreferences("app_updates", Context.MODE_PRIVATE)
    var background: Boolean
        get() = preferences.getBoolean("background", true)
        set(value) { preferences.edit { putBoolean("background", value) } }
    var lastCheck: Long
        get() = preferences.getLong("lastCheck", 0)
        set(value) { preferences.edit { putLong("lastCheck", value) } }
    var dismissedVersion: Long
        get() = preferences.getLong("dismissedVersion", 0)
        set(value) { preferences.edit { putLong("dismissedVersion", value) } }
    var notifiedVersion: Long
        get() = preferences.getLong("notifiedVersion", 0)
        set(value) { preferences.edit { putLong("notifiedVersion", value) } }
}

object UpdateSchedule {
    const val NAME = "qingji.daily-update-check"
    const val CHANNEL = "qingji.app-updates"
    const val NOTIFICATION_ID = 1100

    fun configure(context: Context) {
        val app = context.applicationContext as QingJiApplication
        if (!app.updatesEnabled) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "清记版本更新", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "清记有新版本时提醒，每个版本提醒一次"
        })
        val work = WorkManager.getInstance(context)
        if (!UpdatePreferences(context).background) { work.cancelUniqueWork(NAME); return }
        val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(24, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresBatteryNotLow(true).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
            .build()
        work.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun notificationsAllowed(context: Context): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled() &&
        context.getSystemService(NotificationManager::class.java).getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
}

class UpdateCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as QingJiApplication
        val preferences = UpdatePreferences(app)
        if (!app.updatesEnabled || !preferences.background) return Result.success()
        return try {
            val release = app.releaseClient.latest()
            preferences.lastCheck = System.currentTimeMillis()
            if (release.versionCode > BuildConfig.VERSION_CODE && release.minSdk <= Build.VERSION.SDK_INT &&
                release.versionCode > preferences.notifiedVersion && UpdateSchedule.notificationsAllowed(app)) {
                val intent = Intent(app, MainActivity::class.java).setAction("cn.qingji.app.CHECK_UPDATE")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                val pending = PendingIntent.getActivity(app, 1100, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                val notification = NotificationCompat.Builder(app, UpdateSchedule.CHANNEL)
                    .setSmallIcon(R.drawable.ic_update_notification).setContentTitle("清记 ${release.versionName} 可以更新了")
                    .setContentText(release.notes.firstOrNull() ?: "点击查看更新说明")
                    .setContentIntent(pending).setAutoCancel(true).build()
                // Channel/runtime authorization can change while the network request is running.
                try { NotificationManagerCompat.from(app).notify(UpdateSchedule.NOTIFICATION_ID, notification); preferences.notifiedVersion = release.versionCode }
                catch (_: SecurityException) { /* The foreground update entry remains available. */ }
            }
            Result.success()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { if (runAttemptCount < 3) Result.retry() else Result.failure() }
    }
}

object ApkInstaller {
    @Suppress("DEPRECATION")
    fun verify(context: Context, file: File, release: UpdateRelease) {
        require(file.isFile && file.length() == release.sizeBytes) { "已下载的安装包已失效，请重新下载" }
        val sink = object : java.io.OutputStream() { override fun write(value: Int) {} ; override fun write(buffer: ByteArray, offset: Int, length: Int) {} }
        file.inputStream().use { Updates.copyVerified(it, sink, release) }
        val pm = context.packageManager
        val archive = pm.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES) ?: error("安装包无法读取")
        val installed = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        require(archive.packageName == context.packageName && archive.longVersionCode == release.versionCode && archive.longVersionCode > installed.longVersionCode) { "安装包名称或版本不匹配" }
        require((archive.applicationInfo?.minSdkVersion ?: Int.MAX_VALUE) <= Build.VERSION.SDK_INT) { "当前系统不支持此版本" }
        fun signatures(info: PackageInfo): Set<String> = info.signingInfo?.apkContentsSigners.orEmpty().map {
            MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString("") { byte -> "%02x".format(byte.toInt() and 255) }
        }.toSet()
        val current = signatures(installed)
        require(current.isNotEmpty() && signatures(archive) == current) { "安装包签名不一致，已停止安装" }
    }

    fun launch(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        intent.clipData = android.content.ClipData.newRawUri("清记安装包", uri)
        context.startActivity(intent)
    }
}

data class UpdateUiState(
    val checking: Boolean = false,
    val downloading: Boolean = false,
    val downloadedBytes: Long = 0,
    val release: UpdateRelease? = null,
    val showDialog: Boolean = false,
    val lastCheck: Long = 0,
    val background: Boolean = true,
    val notificationsAllowed: Boolean = false,
    val ready: Boolean = false,
    val message: String = "尚未检查版本"
)

class UpdateViewModel(application: android.app.Application) : AndroidViewModel(application) {
    private val app = application as QingJiApplication
    private val preferences = UpdatePreferences(app)
    val state = MutableStateFlow(UpdateUiState(lastCheck = preferences.lastCheck, background = preferences.background))
    private var downloadJob: Job? = null
    private var readyFile: File? = null
    private var awaitingInstallPermission = false
    private var lastAttempt = 0L
    private var requestedDialog = false

    fun resume() {
        state.update { it.copy(notificationsAllowed = UpdateSchedule.notificationsAllowed(app)) }
        if (awaitingInstallPermission && app.packageManager.canRequestPackageInstalls()) { awaitingInstallPermission = false; install() }
        if (!state.value.ready) check(false)
    }

    fun check(manual: Boolean = true) {
        if (!app.updatesEnabled || state.value.downloading) return
        if (state.value.checking) { if (manual) requestedDialog = true; return }
        val now = System.currentTimeMillis()
        if (!manual && now - lastAttempt < 60_000) return
        lastAttempt = now
        viewModelScope.launch {
            state.update { it.copy(checking = true, message = "正在检查新版…") }
            try {
                val release = app.releaseClient.latest()
                preferences.lastCheck = System.currentTimeMillis()
                val newer = release.versionCode > BuildConfig.VERSION_CODE
                val compatible = release.minSdk <= Build.VERSION.SDK_INT
                state.update { old -> old.copy(checking = false, release = release.takeIf { newer && compatible }, lastCheck = preferences.lastCheck,
                    ready = old.ready && old.release?.versionCode == release.versionCode,
                    showDialog = old.showDialog || (newer && compatible && (manual || requestedDialog || preferences.dismissedVersion != release.versionCode)),
                    message = if (!newer) "当前已是最新版本" else if (!compatible) "新版需要 Android API ${release.minSdk} 或以上" else "发现新版本 ${release.versionName}") }
                requestedDialog = false
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { state.update { it.copy(checking = false, message = "暂时无法检查更新，请检查网络后重试") } }
        }
    }

    fun openRelease() { if (state.value.release != null) state.update { it.copy(showDialog = true) } else check() }
    fun dismiss() {
        if (state.value.downloading) return
        preferences.dismissedVersion = state.value.release?.versionCode ?: 0
        state.update { it.copy(showDialog = false) }
    }
    fun background(enabled: Boolean) {
        preferences.background = enabled
        UpdateSchedule.configure(app)
        state.update { it.copy(background = enabled, notificationsAllowed = UpdateSchedule.notificationsAllowed(app)) }
    }

    fun download() {
        val release = state.value.release ?: return
        if (state.value.downloading) return
        downloadJob = viewModelScope.launch {
            var temporary: File? = null
            state.update { it.copy(downloading = true, downloadedBytes = 0, message = "正在下载…", ready = false) }
            try {
                val file = withContext(Dispatchers.IO) {
                    val directory = File(app.cacheDir, "updates").apply { mkdirs() }
                    directory.listFiles()?.forEach { it.delete() }
                    val part = File(directory, "qingji-${release.versionCode}.part"); temporary = part
                    app.releaseClient.download(release, part) { count -> state.update { it.copy(downloadedBytes = count) } }
                    ApkInstaller.verify(app, part, release)
                    val complete = File(directory, "qingji-${release.versionCode}.apk")
                    require(part.renameTo(complete)) { "无法保存安装包，请重试" }
                    complete
                }
                readyFile = file
                state.update { it.copy(downloading = false, ready = true, message = "下载完成，已验证安装包") }
                install()
            } catch (cancelled: CancellationException) {
                temporary?.delete(); state.update { it.copy(downloading = false, message = "下载已取消") }; throw cancelled
            } catch (error: Exception) {
                temporary?.delete(); state.update { it.copy(downloading = false, message = error.message ?: "下载失败，请重试") }
            }
        }
    }
    fun cancelDownload() { downloadJob?.cancel() }

    fun install() {
        val file = readyFile ?: return
        val release = state.value.release ?: return
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { ApkInstaller.verify(app, file, release) }
                if (!app.packageManager.canRequestPackageInstalls()) {
                    awaitingInstallPermission = true
                    state.update { it.copy(message = "请允许清记安装更新，返回后继续；账本会保留") }
                    app.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${app.packageName}".toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } else { ApkInstaller.launch(app, file); state.update { it.copy(message = "请在系统安装界面确认更新；账本会保留") } }
            } catch (error: Exception) { state.update { it.copy(message = error.message ?: "无法打开安装界面，请重试") } }
        }
    }
}
