package com.example.backup

import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
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
 *   "AURABK1\n" | u32 header length | header JSON (UTF-8) | encrypted data
 *
 * The key comes from the user's passphrase with PBKDF2 (random 16-byte salt). The plaintext is a
 * ZIP of named entries. Without the passphrase the file can't be read; a forgotten passphrase
 * cannot be recovered.
 *
 * Format 2 (written now) encrypts the data in pieces, so a backup of any size is written and read
 * without holding it in memory. Each piece of [Header.chunkSize] plaintext bytes is its own
 * AES-256-GCM message, followed by its 16-byte tag. The 12-byte nonce of piece number n is
 * the header's 7 random bytes | n (4 bytes) | 1 if it is the last piece, else 0. Because the
 * number and the "last" mark are part of the nonce, pieces can't be reordered, dropped, repeated,
 * or cut off at the end without decryption failing. The header is the associated data of every
 * piece, so changing it (e.g. lowering the iteration count) also makes decryption fail.
 *
 * Format 1 (older backups, still readable) is one AES-256-GCM message with a 12-byte IV.
 */
object BackupCrypto {
    const val FORMAT = 2
    const val MIN_PASSPHRASE = 8
    private val MAGIC = "AURABK1\n".toByteArray(Charsets.US_ASCII)
    private const val ITERATIONS = 210_000
    private val ACCEPTED_ITERATIONS = 10_000..5_000_000
    private const val MAX_HEADER = 4_096
    private const val TAG_BYTES = 16
    private const val NONCE_PREFIX_BYTES = 7
    const val CHUNK_BYTES = 1024 * 1024
    private val ACCEPTED_CHUNK = 1_024..8 * 1024 * 1024

    /** Largest format-1 file accepted: those are decrypted in memory. */
    const val MAX_FILE_BYTES = 256L * 1024 * 1024

    /** Most data a backup may unpack to, so a crafted file can't fill the phone. */
    const val MAX_UNPACKED_BYTES = 4L * 1024 * 1024 * 1024

    private const val WRONG = "Wrong passphrase, or the backup file is damaged."
    private const val DAMAGED = "This backup file is damaged."

    class BackupException(message: String, cause: Throwable? = null) : Exception(message, cause)

    /** [iv] is the 12-byte IV (format 1) or the 7-byte nonce prefix (format 2). [chunkSize] is 0 in format 1. */
    data class Header(
        val format: Int, val kdf: String, val iterations: Int, val salt: ByteArray, val iv: ByteArray,
        val createdAt: Long, val dbVersion: Int, val chunkSize: Int = 0
    ) {
        fun toJson(): String = JSONObject()
            .put("format", format).put("kdf", kdf).put("iterations", iterations)
            .put("salt", hex(salt)).put("iv", hex(iv)).put("createdAt", createdAt).put("dbVersion", dbVersion)
            .apply { if (format >= 2) put("chunk", chunkSize) }
            .toString()
    }

    /** PBKDF2-HMAC-SHA256 where available (Android 8+ and every JVM), else SHA-1 (Android 7). */
    fun defaultKdf(): String = runCatching { SecretKeyFactory.getInstance(KDF_SHA256); KDF_SHA256 }.getOrDefault(KDF_SHA1)
    const val KDF_SHA256 = "PBKDF2WithHmacSHA256"
    const val KDF_SHA1 = "PBKDF2WithHmacSHA1"

    /**
     * Writes an encrypted backup to [out] piece by piece. [writeEntries] adds the entries to the
     * ZIP it is given (with [putEntry]). [out] is flushed but not closed.
     */
    fun encryptTo(
        out: OutputStream, passphrase: CharArray, dbVersion: Int,
        now: Long = System.currentTimeMillis(), kdf: String = defaultKdf(),
        iterations: Int = ITERATIONS, random: SecureRandom = SecureRandom(), chunkSize: Int = CHUNK_BYTES,
        writeEntries: (ZipOutputStream) -> Unit
    ) {
        if (passphrase.size < MIN_PASSPHRASE) throw BackupException("The passphrase needs at least $MIN_PASSPHRASE characters.")
        require(chunkSize in ACCEPTED_CHUNK)
        val salt = ByteArray(16).also(random::nextBytes)
        val prefix = ByteArray(NONCE_PREFIX_BYTES).also(random::nextBytes)
        val header = Header(FORMAT, kdf, iterations, salt, prefix, now, dbVersion, chunkSize)
        val headerBytes = header.toJson().toByteArray(Charsets.UTF_8)
        val key = deriveKey(passphrase, header)
        DataOutputStream(out).apply { write(MAGIC); writeInt(headerBytes.size); write(headerBytes); flush() }
        val pieces = PieceEncryptor(out, key, prefix, headerBytes, chunkSize)
        val zip = ZipOutputStream(pieces)
        writeEntries(zip)
        zip.finish()
        pieces.finish()
        out.flush()
    }

    /** Adds one entry to a backup's ZIP, copying it from [data] without loading it all. */
    fun putEntry(zip: ZipOutputStream, name: String, data: InputStream) {
        zip.putNextEntry(ZipEntry(name))
        data.copyTo(zip)
        zip.closeEntry()
    }

    /** An encrypted backup of small [entries], in memory (settings-sized data and tests). */
    fun encrypt(
        entries: Map<String, ByteArray>, passphrase: CharArray, dbVersion: Int,
        now: Long = System.currentTimeMillis(), kdf: String = defaultKdf(),
        iterations: Int = ITERATIONS, random: SecureRandom = SecureRandom(), chunkSize: Int = CHUNK_BYTES
    ): ByteArray = ByteArrayOutputStream().also { out ->
        encryptTo(out, passphrase, dbVersion, now, kdf, iterations, random, chunkSize) { zip ->
            for ((name, bytes) in entries) putEntry(zip, name, ByteArrayInputStream(bytes))
        }
    }.toByteArray()

    /** Reads the header without the passphrase (to say what's in a file before asking). */
    fun readHeader(file: ByteArray): Header = readHeader(ByteArrayInputStream(file)).first

    /**
     * Decrypts a backup from [input], handing each entry to [onEntry] as a stream (read it before
     * returning). Nothing given to [onEntry] may be relied on until this function returns: only
     * then has the whole file been checked. Throws [BackupException] otherwise.
     */
    fun decryptTo(input: InputStream, passphrase: CharArray, onEntry: (name: String, data: InputStream) -> Unit): Header {
        val (header, headerBytes) = readHeader(input)
        val key = deriveKey(passphrase, header)
        val plain: InputStream = if (header.format == 1) {
            ByteArrayInputStream(decryptFormat1(input, key, header, headerBytes))
        } else {
            PieceDecryptor(input, key, header.iv, headerBytes, header.chunkSize)
        }
        try {
            unzipTo(plain, onEntry)
            // The ZIP reader stops before the end of the data; read the rest so every piece is checked.
            val rest = ByteArray(8192)
            while (plain.read(rest) >= 0) Unit
        } catch (e: BackupException) {
            throw e
        } catch (e: java.io.IOException) {
            throw BackupException(DAMAGED, e)
        }
        return header
    }

    /** Decrypts a small backup held in memory (tests). */
    fun decrypt(file: ByteArray, passphrase: CharArray): Pair<Header, Map<String, ByteArray>> {
        if (file.size > MAX_FILE_BYTES) throw BackupException("This file is too large to be an Aura backup.")
        val result = linkedMapOf<String, ByteArray>()
        val header = decryptTo(ByteArrayInputStream(file), passphrase) { name, data -> result[name] = data.readBytes() }
        return header to result
    }

    private fun readHeader(source: InputStream): Pair<Header, ByteArray> {
        try {
            val input = DataInputStream(source)
            val magic = ByteArray(MAGIC.size)
            input.readFully(magic)
            if (!magic.contentEquals(MAGIC)) throw BackupException("This isn't an Aura backup file.")
            val length = input.readInt()
            if (length !in 2..MAX_HEADER) throw BackupException(DAMAGED)
            val headerBytes = ByteArray(length).also(input::readFully)
            val json = JSONObject(String(headerBytes, Charsets.UTF_8))
            val format = json.getInt("format")
            if (format > FORMAT) throw BackupException("This backup was made by a newer version of Aura. Update Aura first.")
            if (format < 1) throw BackupException(DAMAGED)
            val header = Header(
                format = format,
                kdf = json.getString("kdf"),
                iterations = json.getInt("iterations"),
                salt = unhex(json.getString("salt")),
                iv = unhex(json.getString("iv")),
                createdAt = json.getLong("createdAt"),
                dbVersion = json.getInt("dbVersion"),
                chunkSize = if (format >= 2) json.getInt("chunk") else 0
            )
            if (header.kdf != KDF_SHA256 && header.kdf != KDF_SHA1) throw BackupException(DAMAGED)
            val ivBytes = if (format == 1) 12 else NONCE_PREFIX_BYTES
            if (header.iterations !in ACCEPTED_ITERATIONS || header.salt.size != 16 || header.iv.size != ivBytes) {
                throw BackupException(DAMAGED)
            }
            if (format >= 2 && header.chunkSize !in ACCEPTED_CHUNK) throw BackupException(DAMAGED)
            return header to headerBytes
        } catch (e: BackupException) {
            throw e
        } catch (e: Exception) {
            throw BackupException("This isn't a readable Aura backup file.", e)
        }
    }

    private fun decryptFormat1(input: InputStream, key: SecretKeySpec, header: Header, headerBytes: ByteArray): ByteArray {
        val ciphertext = readAtMost(input, MAX_FILE_BYTES)
        if (ciphertext.size < TAG_BYTES) throw BackupException(DAMAGED)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, header.iv))
            cipher.updateAAD(headerBytes)
            return cipher.doFinal(ciphertext)
        } catch (e: GeneralSecurityException) {
            throw BackupException(WRONG, e)
        }
    }

    private fun readAtMost(input: InputStream, max: Long): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
            if (out.size() > max) throw BackupException("This file is too large to be an Aura backup.")
        }
        return out.toByteArray()
    }

    /** One old-style (format 1) backup, kept only so tests can prove old files still open. */
    internal fun encryptFormat1(
        entries: Map<String, ByteArray>, passphrase: CharArray, dbVersion: Int,
        now: Long = System.currentTimeMillis(), iterations: Int = ITERATIONS, random: SecureRandom = SecureRandom()
    ): ByteArray {
        val salt = ByteArray(16).also(random::nextBytes)
        val iv = ByteArray(12).also(random::nextBytes)
        val header = Header(1, defaultKdf(), iterations, salt, iv, now, dbVersion)
        val headerBytes = header.toJson().toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(passphrase, header), GCMParameterSpec(128, iv))
        cipher.updateAAD(headerBytes)
        val ciphertext = cipher.doFinal(zip(entries))
        return ByteArrayOutputStream().use { out ->
            DataOutputStream(out).apply { write(MAGIC); writeInt(headerBytes.size); write(headerBytes); write(ciphertext) }
            out.toByteArray()
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

    private fun nonce(prefix: ByteArray, number: Long, last: Boolean): ByteArray {
        val nonce = ByteArray(12)
        prefix.copyInto(nonce)
        nonce[7] = (number ushr 24).toByte()
        nonce[8] = (number ushr 16).toByte()
        nonce[9] = (number ushr 8).toByte()
        nonce[10] = number.toByte()
        nonce[11] = if (last) 1 else 0
        return nonce
    }

    /** Encrypts what is written to it in pieces of [chunkSize]. Call [finish] once at the end. */
    private class PieceEncryptor(
        private val out: OutputStream, private val key: SecretKeySpec, private val prefix: ByteArray,
        private val aad: ByteArray, chunkSize: Int
    ) : OutputStream() {
        private val buffer = ByteArray(chunkSize)
        private var filled = 0
        private var number = 0L
        private var finished = false

        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            check(!finished)
            var from = off
            var left = len
            while (left > 0) {
                // A full buffer is only sent once more data arrives, so the final piece is known to be final.
                if (filled == buffer.size) emit(last = false)
                val n = minOf(left, buffer.size - filled)
                System.arraycopy(b, from, buffer, filled, n)
                filled += n; from += n; left -= n
            }
        }

        fun finish() {
            if (finished) return
            emit(last = true)
            finished = true
        }

        private fun emit(last: Boolean) {
            if (number > 0xFFFF_FFFFL) throw BackupException("There is too much data for one backup.")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce(prefix, number, last)))
            cipher.updateAAD(aad)
            out.write(cipher.doFinal(buffer, 0, filled))
            number++
            filled = 0
        }
    }

    /** Reads pieces written by [PieceEncryptor]; only checked plaintext is ever returned. */
    private class PieceDecryptor(
        private val input: InputStream, private val key: SecretKeySpec, private val prefix: ByteArray,
        private val aad: ByteArray, chunkSize: Int
    ) : InputStream() {
        private val sealed = ByteArray(chunkSize + TAG_BYTES)
        private var plain = ByteArray(0)
        private var position = 0
        private var number = 0L
        private var ahead = NOT_READ
        private var done = false

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            while (position == plain.size) if (!next()) return -1
            val n = minOf(len, plain.size - position)
            System.arraycopy(plain, position, b, off, n)
            position += n
            return n
        }

        private fun next(): Boolean {
            if (done) return false
            var got = 0
            if (ahead >= 0) { sealed[0] = ahead.toByte(); got = 1 }
            while (got < sealed.size) {
                val n = input.read(sealed, got, sealed.size - got)
                if (n < 0) break
                got += n
            }
            // A piece is the last one when nothing follows it.
            ahead = if (got == sealed.size) input.read() else -1
            val last = ahead < 0
            if (got < TAG_BYTES || number > 0xFFFF_FFFFL) throw BackupException(if (number == 0L) DAMAGED else WRONG)
            plain = try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, nonce(prefix, number, last)))
                cipher.updateAAD(aad)
                cipher.doFinal(sealed, 0, got)
            } catch (e: GeneralSecurityException) {
                throw BackupException(WRONG, e)
            }
            position = 0
            number++
            done = last
            return true
        }

        private companion object { const val NOT_READ = -2 }
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
    private fun unzipTo(plain: InputStream, onEntry: (String, InputStream) -> Unit) {
        val seen = hashSetOf<String>()
        val zip = ZipInputStream(plain)
        val counted = Limited(zip, MAX_UNPACKED_BYTES)
        while (true) {
            val entry = zip.nextEntry ?: break
            val name = entry.name
            if (!Regex("[A-Za-z0-9_.-]{1,80}").matches(name) || name.startsWith(".") || !seen.add(name)) {
                throw BackupException(DAMAGED)
            }
            onEntry(name, counted)
            // Skip whatever the caller left unread, still counting it.
            val rest = ByteArray(8192)
            while (counted.read(rest) >= 0) Unit
        }
    }

    fun unzip(bytes: ByteArray): Map<String, ByteArray> {
        val result = linkedMapOf<String, ByteArray>()
        try {
            unzipTo(ByteArrayInputStream(bytes)) { name, data -> result[name] = data.readBytes() }
        } catch (e: EOFException) {
            throw BackupException(DAMAGED, e)
        }
        return result
    }

    /** Passes reads through, refusing to go past [max] bytes in total. Closing it does nothing. */
    private class Limited(private val source: InputStream, private val max: Long) : InputStream() {
        private var total = 0L
        override fun read(): Int = source.read().also { if (it >= 0) count(1) }
        override fun read(b: ByteArray, off: Int, len: Int): Int = source.read(b, off, len).also { if (it > 0) count(it) }
        override fun close() = Unit
        private fun count(n: Int) {
            total += n
            if (total > max) throw BackupException("This backup file is too large.")
        }
    }

    // Hex, not java.util.Base64 (Android 8+ only; minSdk is 24).
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun unhex(s: String): ByteArray {
        require(s.length % 2 == 0 && s.all { it in '0'..'9' || it in 'a'..'f' })
        return ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}
