package cn.qingji.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString
import java.net.URI
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

@Serializable
data class UpdateRelease(
    val schema: Int = 1,
    val packageName: String,
    val versionCode: Long,
    val versionName: String,
    val minSdk: Int,
    val apkUrl: String,
    val sha256: String,
    val sizeBytes: Long,
    val publishedAt: String,
    val notes: List<String>
)

object Updates {
    const val MAX_APK_BYTES = 100L * 1024 * 1024
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(text: String, manifestUrl: String, expectedPackage: String, githubRepository: String = ""): UpdateRelease {
        require(text.toByteArray(Charsets.UTF_8).size <= 64 * 1024) { "版本信息过大" }
        return json.decodeFromString<UpdateRelease>(text).also { release ->
            require(release.schema == 1 && release.packageName == expectedPackage) { "版本信息不属于清记" }
            require(release.versionCode in 1..Int.MAX_VALUE.toLong() && release.versionName.length in 1..50) { "版本号无效" }
            require(release.minSdk in 29..100) { "系统版本要求无效" }
            require(release.sizeBytes in 1..MAX_APK_BYTES && release.sha256.matches(Regex("[0-9a-fA-F]{64}"))) { "安装包校验信息无效" }
            require(release.publishedAt.length in 1..60 && release.notes.size in 1..20 && release.notes.all { it.length in 1..500 }) { "更新说明无效" }
            requireDownloadUrl(manifestUrl, release.apkUrl, githubRepository)
        }
    }

    fun requireSameOrigin(origin: String, target: String) {
        val a = httpsUri(origin); val b = httpsUri(target)
        require(a.host.equals(b.host, true)) { "版本信息必须为同一站点的 HTTPS 地址" }
    }

    private fun httpsUri(address: String): URI = URI(address).also { uri ->
        require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.rawUserInfo == null && uri.fragment == null && (uri.port == -1 || uri.port == 443)) { "更新地址必须使用 HTTPS" }
    }

    /** GitHub assets may redirect to its release CDN; the manifest itself remains same-origin. */
    fun requireDownloadUrl(manifestUrl: String, target: String, githubRepository: String = "", isRedirect: Boolean = false) {
        val origin = httpsUri(manifestUrl); val uri = httpsUri(target)
        if (origin.host.equals(uri.host, true)) return
        require(githubRepository.matches(Regex("[A-Za-z0-9-]+/[A-Za-z0-9._-]+"))) { "安装包来源不在允许列表中" }
        val prefix = "/$githubRepository/releases/download/"
        if (uri.host == "github.com" && uri.rawPath == uri.path && uri.path == uri.normalize().path && uri.path.startsWith(prefix) && uri.query == null) {
            val parts = uri.path.removePrefix(prefix).split('/')
            require(parts.size == 2 && parts.all { it.isNotBlank() && it !in listOf(".", "..") } && parts[1].endsWith(".apk")) { "GitHub 安装包地址无效" }
            return
        }
        require(isRedirect && uri.host == "release-assets.githubusercontent.com") { "安装包来源不在允许列表中" }
    }

    /** Never accepts partial, oversized or corrupted downloads. Caller writes into a temporary file. */
    fun copyVerified(input: InputStream, output: OutputStream, release: UpdateRelease, progress: (Long) -> Unit = {}) {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(32 * 1024)
        var count = 0L
        while (true) {
            val size = input.read(buffer)
            if (size < 0) break
            if (size == 0) continue
            count += size
            require(count <= release.sizeBytes && count <= MAX_APK_BYTES) { "安装包大小超出预期" }
            digest.update(buffer, 0, size)
            output.write(buffer, 0, size)
            progress(count)
        }
        require(count == release.sizeBytes) { "安装包下载不完整，请重试" }
        val hash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        require(hash.equals(release.sha256, true)) { "安装包校验失败，请重新下载" }
    }
}
