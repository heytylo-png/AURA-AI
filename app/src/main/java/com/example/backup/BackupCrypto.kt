package com.example.backup

import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Aura's encrypted backup file format (".aura"). No Android types, so it is unit-tested on the JVM.
 *
 *   "AURABK1\n" | u32 header length | header JSON (UTF-8) | AES-256-GCM ciphertext
 *
 * Salt and IV are stored as hex. The key comes from the user's passphrase with PBKDF2 (random 16-byte salt). The header is the
 * GCM associated data, so changing it (e.g. lowering the iteration count) makes decryption fail.
 * The plaintext is a ZIP of named entries. Without the passphrase the file can't be read; a
 * forgotten passphrase cannot be recovered.
 */
object BackupCrypto {
    const val FORMAT = 1
    const val MIN_PASSPHRASE = 8
    private val MAGIC = "AURABK1\n".toByteArray(Charsets.US_ASCII)
    private const val ITERATIONS = 210_000
    private val ACCEPTED_ITERATIONS = 10_000..5_000_000
    private const val MAX_HEADER = 4_096
    const val MAX_FILE_BYTES = 256L * 1024 * 1024

    class BackupException(message: String, cause: Throwable? = null) : Exception(message, cause)

    data class Header(val format: Int, val kdf: String, val iterations: Int, val salt: ByteArray, val iv: ByteArray, val createdAt: Long, val dbVersion: Int) {
        fun toJson(): String = JSONObject()
            .put("format", format).put("kdf", kdf).put("iterations", iterations)
            .put("salt", b64(salt)).put("iv", b64(iv)).put("createdAt", createdAt).put("dbVersion", dbVersion)
            .toString()
    }

    /** PBKDF2-HMAC-SHA256 where available (Android 8+ and every JVM), else SHA-1 (Android 7). */
    fun defaultKdf(): String = runCatching { SecretKeyFactory.getInstance(KDF_SHA256); KDF_SHA256 }.getOrDefault(KDF_SHA1)
    const val KDF_SHA256 = "PBKDF2WithHmacSHA256"
    const val KDF_SHA1 = "PBKDF2WithHmacSHA1"

    fun encrypt(
        entries: Map<String, ByteArray>, passphrase: CharArray, dbVersion: Int,
        now: Long = System.currentTimeMillis(), kdf: String = defaultKdf(),
        iterations: Int = ITERATIONS, random: SecureRandom = SecureRandom()
    ): ByteArray {
        if (passphrase.size < MIN_PASSPHRASE) throw BackupException("The passphrase needs at least $MIN_PASSPHRASE characters.")
        val salt = ByteArray(16).also(random::nextBytes)
        val iv = ByteArray(12).also(random::nextBytes)
        val header = Header(FORMAT, kdf, iterations, salt, iv, now, dbVersion)
        val headerBytes = header.toJson().toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(passphrase, header), GCMParameterSpec(128, iv))
        cipher.updateAAD(headerBytes)
        val ciphertext = cipher.doFinal(zip(entries))
        return ByteArrayOutputStream().use { out ->
            DataOutputStream(out).apply {
                write(MAGIC); writeInt(headerBytes.size); write(headerBytes); write(ciphertext)
            }
            out.toByteArray()
        }
    }

    /** Reads the header without the passphrase (to say what's in a file before asking). */
    fun readHeader(file: ByteArray): Header = split(file).first

    fun decrypt(file: ByteArray, passphrase: CharArray): Pair<Header, Map<String, ByteArray>> {
        val (header, headerBytes, ciphertext) = split(file).let { Triple(it.first, it.second, it.third) }
        val plain = try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, deriveKey(passphrase, header), GCMParameterSpec(128, header.iv))
            cipher.updateAAD(headerBytes)
            cipher.doFinal(ciphertext)
        } catch (e: GeneralSecurityException) {
            throw BackupException("Wrong passphrase, or the backup file is damaged.", e)
        }
        return header to unzip(plain)
    }

    private fun split(file: ByteArray): Triple<Header, ByteArray, ByteArray> {
        try {
            if (file.size > MAX_FILE_BYTES) throw BackupException("This file is too large to be an Aura backup.")
            val input = DataInputStream(ByteArrayInputStream(file))
            val magic = ByteArray(MAGIC.size)
            input.readFully(magic)
            if (!magic.contentEquals(MAGIC)) throw BackupException("This isn't an Aura backup file.")
            val length = input.readInt()
            if (length !in 2..MAX_HEADER) throw BackupException("This backup file is damaged.")
            val headerBytes = ByteArray(length).also(input::readFully)
            val json = JSONObject(String(headerBytes, Charsets.UTF_8))
            val header = Header(
                format = json.getInt("format"),
                kdf = json.getString("kdf"),
                iterations = json.getInt("iterations"),
                salt = unb64(json.getString("salt")),
                iv = unb64(json.getString("iv")),
                createdAt = json.getLong("createdAt"),
                dbVersion = json.getInt("dbVersion")
            )
            if (header.format != FORMAT) throw BackupException("This backup was made by a newer version of Aura. Update Aura first.")
            if (header.kdf != KDF_SHA256 && header.kdf != KDF_SHA1) throw BackupException("This backup file is damaged.")
            if (header.iterations !in ACCEPTED_ITERATIONS || header.salt.size != 16 || header.iv.size != 12) {
                throw BackupException("This backup file is damaged.")
            }
            val ciphertext = file.copyOfRange(MAGIC.size + 4 + length, file.size)
            if (ciphertext.size < 16) throw BackupException("This backup file is damaged.")
            return Triple(header, headerBytes, ciphertext)
        } catch (e: BackupException) {
            throw e
        } catch (e: Exception) {
            throw BackupException("This isn't a readable Aura backup file.", e)
        }
    }

    private fun deriveKey(passphrase: CharArray, header: Header): SecretKeySpec {
        val spec = PBEKeySpec(passphrase, header.salt, header.iterations, 256)
        try {
            return SecretKeySpec(SecretKeyFactory.getInstance(header.kdf).generateSecret(spec).encoded, "AES")
        } catch (e: GeneralSecurityException) {
            throw BackupException("This phone can't open this backup (${header.kdf}).", e)
        } finally {
            spec.clearPassword()
        }
    }

    fun zip(entries: Map<String, ByteArray>): ByteArray = ByteArrayOutputStream().use { out ->
        ZipOutputStream(out).use { zip ->
            for ((name, bytes) in entries) {
                zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
            }
        }
        out.toByteArray()
    }

    /** Only plain names (no paths) are accepted, so a crafted file can't write outside its folder. */
    fun unzip(bytes: ByteArray): Map<String, ByteArray> {
        val result = linkedMapOf<String, ByteArray>()
        var total = 0L
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name
                if (!Regex("[A-Za-z0-9_.-]{1,80}").matches(name) || name.startsWith(".")) throw BackupException("This backup file is damaged.")
                val data = zip.readBytes()
                total += data.size
                if (total > MAX_FILE_BYTES * 2) throw BackupException("This backup file is too large.")
                result[name] = data
            }
        }
        return result
    }

    // Hex, not java.util.Base64 (Android 8+ only; minSdk is 24).
    private fun b64(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun unb64(s: String): ByteArray {
        require(s.length % 2 == 0 && s.all { it in '0'..'9' || it in 'a'..'f' })
        return ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}
