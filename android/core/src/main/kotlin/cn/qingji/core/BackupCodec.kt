package cn.qingji.core

import kotlinx.serialization.encodeToString
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object BackupCodec {
    private val magic = "QINGJI01".toByteArray(Charsets.US_ASCII)
    private const val iterations = 210_000
    private const val maxBytes = 25 * 1024 * 1024
    private fun key(password: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, iterations, 256)
        return try { SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES") } finally { spec.clearPassword() }
    }
    fun validate(data: BackupData) {
        require(data.version in 1..4) { "备份版本不受支持" }
        require(data.entries.size <= 100_000 && data.entries.map { it.id }.toSet().size == data.entries.size) { "账单数量或主键无效" }
        require(data.entries.all { it.amountFen in 0..100_000_000_000_000L && it.occurredAt in 0..4_102_444_800_000L && it.merchant.length <= 500 && it.note.length <= 4000 && it.raw.length <= 4000 && (it.status != Status.CONFIRMED || (it.amountFen > 0 && it.kind != Kind.UNKNOWN)) }) { "备份包含无效交易" }
        require(data.rules.size <= 20_000 && data.imports.size <= 20_000) { "备份数据超出限制" }
        require(data.customCategories.all { it.isCustom }) { "自定义分类无效" }
        val categories = (data.customCategories + data.entries.map { it.category } + data.rules.map { it.category }).filter { it.isCustom }.distinct()
        require(categories.size <= 100 && categories.map { it.label.lowercase() }.distinct().size == categories.size) { "自定义分类过多或名称重复" }
        require(data.categoryPreferences.size <= 108 && data.categoryPreferences.map { it.category }.distinct().size == data.categoryPreferences.size) { "分类排序重复或超出限制" }
        require(data.categoryPreferences.all { it.position in 0..1_000_000 && (!it.category.isCustom || (it.category in categories && !it.hidden)) && (it.category != Category.UNCATEGORIZED || !it.hidden) }) { "分类排序或删除状态无效" }
    }
    fun encrypt(data: BackupData, password: CharArray): ByteArray {
        require(password.size >= 8) { "备份口令至少8位" }
        validate(data)
        val plaintext = ledgerJson.encodeToString(data).toByteArray(Charsets.UTF_8)
        require(plaintext.size <= maxBytes) { "备份超过25MB限制" }
        val random = SecureRandom()
        val salt = ByteArray(16).also(random::nextBytes)
        val nonce = ByteArray(12).also(random::nextBytes)
        val header = magic + salt + nonce
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(password, salt), GCMParameterSpec(128, nonce))
        cipher.updateAAD(header)
        return header + cipher.doFinal(plaintext)
    }
    fun decrypt(bytes: ByteArray, password: CharArray): BackupData {
        require(bytes.size in 52..(maxBytes + 52)) { "备份文件大小无效" }
        require(bytes.take(8).toByteArray().contentEquals(magic)) { "不是清记加密备份" }
        val salt = bytes.copyOfRange(8, 24)
        val nonce = bytes.copyOfRange(24, 36)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(password, salt), GCMParameterSpec(128, nonce))
        cipher.updateAAD(bytes.copyOfRange(0, 36))
        val decrypted = try { cipher.doFinal(bytes.copyOfRange(36, bytes.size)) } catch (_: Exception) { throw IllegalArgumentException("口令错误或备份文件已损坏") }
        return ledgerJson.decodeFromString<BackupData>(String(decrypted, Charsets.UTF_8)).also(::validate)
    }
}
