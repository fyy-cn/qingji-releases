package cn.qingji.core

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class UpdateTests {
    private val url = "https://example.com/releases/latest.json"
    private val bytes = "an-apk-fixture".toByteArray()
    private val hash = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    private val text get() = """{"packageName":"cn.qingji.app","versionCode":2,"versionName":"1.1.0","minSdk":29,"apkUrl":"https://example.com/releases/app.apk","sha256":"$hash","sizeBytes":${bytes.size},"publishedAt":"2026-10-16","notes":["应用内更新"]}"""
    @Test fun validReleaseAndFutureMetadataFields() {
        assertEquals(2L, Updates.parse(text.dropLast(1) + ",\"future\":true}", url, "cn.qingji.app").versionCode)
    }
    @Test fun rejectsWrongPackageMalformedHashAndUnsupportedSchema() {
        listOf(text.replace("cn.qingji.app", "bad.app"), text.replace(hash, "bad"), text.dropLast(1) + ",\"schema\":2}").forEach {
            assertThrows(IllegalArgumentException::class.java) { Updates.parse(it, url, "cn.qingji.app") }
        }
    }
    @Test fun forbidsInsecureExternalAndCredentialBearingUrls() {
        listOf("http://example.com/a.apk", "https://evil.com/a.apk", "https://example.com.evil/a.apk", "https://user@example.com/a.apk", "https://example.com:444/a.apk", "https://example.com/a.apk#x").forEach {
            assertThrows(IllegalArgumentException::class.java) { Updates.requireSameOrigin(url, it) }
        }
    }
    @Test fun exactPayloadWithProgressPasses() {
        val release = Updates.parse(text, url, "cn.qingji.app")
        val output = ByteArrayOutputStream(); var progress = 0L
        Updates.copyVerified(ByteArrayInputStream(bytes), output, release) { progress = it }
        assertArrayEquals(bytes, output.toByteArray()); assertEquals(bytes.size.toLong(), progress)
    }
    @Test fun rejectsTruncatedOversizedAndTamperedDownloads() {
        val release = Updates.parse(text, url, "cn.qingji.app")
        listOf(bytes.dropLast(1).toByteArray(), bytes + byteArrayOf(0), ByteArray(bytes.size)).forEach {
            assertThrows(IllegalArgumentException::class.java) { Updates.copyVerified(ByteArrayInputStream(it), ByteArrayOutputStream(), release) }
        }
    }
    @Test fun githubPagesManifestMayUseOnlyConfiguredRepositoryReleaseApk() {
        val manifest = "https://fyy-cn.github.io/qingji-releases/releases/latest.json"
        val asset = "https://github.com/fyy-cn/qingji-releases/releases/download/v1.1.1/QingJi-1.1.1-3.apk"
        Updates.requireDownloadUrl(manifest, asset, "fyy-cn/qingji-releases")
        assertEquals(asset, Updates.parse(text.replace("https://example.com/releases/app.apk", asset), manifest, "cn.qingji.app", "fyy-cn/qingji-releases").apkUrl)
    }
    @Test fun githubReleaseCdnIsAllowedOnlyDuringVerifiedAssetRedirects() {
        val manifest = "https://fyy-cn.github.io/qingji-releases/releases/latest.json"
        val cdn = "https://release-assets.githubusercontent.com/github-production-release-asset/123/file?signature=example"
        Updates.requireDownloadUrl(manifest, cdn, "fyy-cn/qingji-releases", isRedirect = true)
        assertThrows(IllegalArgumentException::class.java) { Updates.requireDownloadUrl(manifest, cdn, "fyy-cn/qingji-releases") }
        assertThrows(IllegalArgumentException::class.java) { Updates.requireDownloadUrl(manifest, cdn, "", isRedirect = true) }
    }
    @Test fun githubReleaseRejectsOtherRepositoriesCdnLookalikesAndEncodedTraversal() {
        val manifest = "https://fyy-cn.github.io/qingji-releases/releases/latest.json"
        listOf("https://github.com/other/repo/releases/download/v1/app.apk", "https://github.com/fyy-cn/qingji-releases-evil/releases/download/v1/app.apk",
            "https://release-assets.githubusercontent.com.evil.com/file", "http://release-assets.githubusercontent.com/file",
            "https://github.com/fyy-cn/qingji-releases/releases/download/v1/../app.apk", "https://github.com/fyy-cn/qingji-releases/releases/download/v1/%2e%2e/app.apk",
            "https://github.com/fyy-cn/qingji-releases/releases/download/v1/app.zip").forEach {
            assertThrows(IllegalArgumentException::class.java) { Updates.requireDownloadUrl(manifest, it, "fyy-cn/qingji-releases", isRedirect = true) }
        }
    }
    @Test fun manifestRequestsCannotFollowReleaseCdnRedirects() {
        assertThrows(IllegalArgumentException::class.java) { Updates.requireSameOrigin("https://fyy-cn.github.io/qingji-releases/releases/latest.json", "https://release-assets.githubusercontent.com/file") }
    }
}
