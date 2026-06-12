package com.volttracker.obdpoc

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** AES/GCM encrypted backup container used by [DataBackup]. */
object BackupCrypto {
    private val ENCRYPTED_BACKUP_MAGIC =
        byteArrayOf(
            'V'.code.toByte(),
            'T'.code.toByte(),
            'B'.code.toByte(),
            'K'.code.toByte(),
            'E'.code.toByte(),
            'N'.code.toByte(),
            '1'.code.toByte(),
            '\n'.code.toByte(),
        )

    // v2 keeps the 8-byte header length and authenticates magic+salt+IV as GCM AAD. v1 remains
    // readable by skipping AAD during decrypt when the old magic is present.
    private val ENCRYPTED_BACKUP_MAGIC_V2 =
        ENCRYPTED_BACKUP_MAGIC.copyOf().also { it[6] = '2'.code.toByte() }
    private const val ENCRYPTION_SALT_BYTES = 16
    private const val ENCRYPTION_IV_BYTES = 12
    private const val ENCRYPTION_KEY_BITS = 256
    private const val ENCRYPTION_PBKDF2_ITERATIONS = 150_000
    private const val IO_BUFFER_BYTES = 8192

    @JvmStatic
    fun isEncryptedBackup(file: File?): Boolean {
        if (file == null) {
            return false
        }
        val header = ByteArray(ENCRYPTED_BACKUP_MAGIC.size)
        return try {
            FileInputStream(file).use { input ->
                if (input.read(header) != header.size) {
                    return false
                }
                isMagic(header)
            }
        } catch (ex: IOException) {
            false
        }
    }

    @Throws(IOException::class, GeneralSecurityException::class)
    fun encryptFile(
        source: File,
        dest: File,
        passphrase: String,
    ) {
        val salt = ByteArray(ENCRYPTION_SALT_BYTES)
        val iv = ByteArray(ENCRYPTION_IV_BYTES)
        // GCM invariant: a (key, IV) pair must NEVER repeat — reuse leaks plaintext XOR and
        // breaks authentication. The fresh SecureRandom IV per encryption is load-bearing; do not
        // cache, reuse, or derive it deterministically. (The salt is also fresh, so even the same
        // passphrase yields a different key each time.)
        SecureRandom().apply {
            nextBytes(salt)
            nextBytes(iv)
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(passphrase, salt), GCMParameterSpec(128, iv))
        cipher.updateAAD(ENCRYPTED_BACKUP_MAGIC_V2)
        cipher.updateAAD(salt)
        cipher.updateAAD(iv)
        FileInputStream(source).use { input ->
            FileOutputStream(dest).use { fileOut ->
                fileOut.write(ENCRYPTED_BACKUP_MAGIC_V2)
                fileOut.write(salt)
                fileOut.write(iv)
                CipherOutputStream(fileOut, cipher).use { out -> copyStream(input, out) }
            }
        }
    }

    @Throws(IOException::class, GeneralSecurityException::class)
    fun decryptFile(
        source: File,
        dest: File,
        passphrase: String,
        maxPlaintextBytes: Long,
    ) {
        val magic = ByteArray(ENCRYPTED_BACKUP_MAGIC.size)
        val salt = ByteArray(ENCRYPTION_SALT_BYTES)
        val iv = ByteArray(ENCRYPTION_IV_BYTES)
        FileInputStream(source).use { fileIn ->
            if (fileIn.read(magic) != magic.size || !isMagic(magic)) {
                throw IOException("Not an encrypted Volt Tracker backup")
            }
            if (fileIn.read(salt) != salt.size || fileIn.read(iv) != iv.size) {
                throw IOException("Encrypted backup header is truncated")
            }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, deriveKey(passphrase, salt), GCMParameterSpec(128, iv))
            if (magic.contentEquals(ENCRYPTED_BACKUP_MAGIC_V2)) {
                cipher.updateAAD(magic)
                cipher.updateAAD(salt)
                cipher.updateAAD(iv)
            }
            CipherInputStream(fileIn, cipher).use { input ->
                FileOutputStream(dest).use { out ->
                    copyStream(input, out, maxPlaintextBytes)
                    out.fd.sync()
                }
            }
        }
    }

    @Throws(GeneralSecurityException::class)
    private fun deriveKey(
        passphrase: String,
        salt: ByteArray,
    ): SecretKeySpec {
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, ENCRYPTION_PBKDF2_ITERATIONS, ENCRYPTION_KEY_BITS)
        return try {
            val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            SecretKeySpec(key, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    private fun isMagic(header: ByteArray): Boolean =
        header.contentEquals(ENCRYPTED_BACKUP_MAGIC) || header.contentEquals(ENCRYPTED_BACKUP_MAGIC_V2)

    @Throws(IOException::class)
    private fun copyStream(
        input: InputStream,
        out: OutputStream,
    ) {
        copyStream(input, out, Long.MAX_VALUE)
    }

    @Throws(IOException::class)
    private fun copyStream(
        input: InputStream,
        out: OutputStream,
        maxBytes: Long,
    ) {
        val buffer = ByteArray(IO_BUFFER_BYTES)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) {
                break
            }
            total += read.toLong()
            if (total > maxBytes) {
                throw IOException("Restore file exceeds size limit")
            }
            out.write(buffer, 0, read)
        }
    }
}
