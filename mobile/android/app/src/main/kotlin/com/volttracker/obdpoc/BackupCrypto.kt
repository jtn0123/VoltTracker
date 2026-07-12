package com.volttracker.obdpoc

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
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

    // v3 raises the KDF cost (OWASP 2023 ~600k vs the 2021 150k floor) and, since a higher iteration
    // count would otherwise make older backups undecryptable, stores the count it was derived with in
    // a 4-byte big-endian header field after the IV. Decrypt reads the count from the v3 header and
    // falls back to the 150k legacy count for v1/v2, so EVERY previously-written backup still decrypts.
    // The count is authenticated alongside magic+salt+IV as GCM AAD so it can't be tampered down.
    private val ENCRYPTED_BACKUP_MAGIC_V3 =
        ENCRYPTED_BACKUP_MAGIC.copyOf().also { it[6] = '3'.code.toByte() }
    private const val ENCRYPTION_SALT_BYTES = 16
    private const val ENCRYPTION_IV_BYTES = 12
    private const val ENCRYPTION_ITER_FIELD_BYTES = 4
    private const val ENCRYPTION_KEY_BITS = 256

    // Iteration count for backups written from this version on (v3 header records it). Legacy v1/v2
    // backups were written at LEGACY_PBKDF2_ITERATIONS and decrypt with that count.
    private const val ENCRYPTION_PBKDF2_ITERATIONS = 600_000
    private const val LEGACY_PBKDF2_ITERATIONS = 150_000

    // Sanity bounds on the header-supplied count so a corrupt/hostile header can't request an absurd
    // (DoS-grade) or trivially weak iteration count. The current and legacy counts both sit inside.
    private const val MIN_PBKDF2_ITERATIONS = 50_000
    private const val MAX_PBKDF2_ITERATIONS = 5_000_000
    private const val IO_BUFFER_BYTES = 8192

    @JvmStatic
    fun isEncryptedBackup(file: File?): Boolean {
        if (file == null) {
            return false
        }
        return try {
            FileInputStream(file).use { input -> isEncryptedBackupStream(input) }
        } catch (ex: IOException) {
            false
        }
    }

    /**
     * Stream form of the magic-header sniff, split out so tests can drive it with short-read
     * streams. Uses [readExactly] because a single `read(buf)` filling the whole buffer is not
     * guaranteed by the [InputStream] contract — short reads without EOF are legal.
     */
    @Throws(IOException::class)
    internal fun isEncryptedBackupStream(input: InputStream): Boolean {
        val header = ByteArray(ENCRYPTED_BACKUP_MAGIC.size)
        return readExactly(input, header) && isMagic(header)
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
        val iterations = ENCRYPTION_PBKDF2_ITERATIONS
        val iterBytes = encodeIterations(iterations)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(passphrase, salt, iterations), GCMParameterSpec(128, iv))
        cipher.updateAAD(ENCRYPTED_BACKUP_MAGIC_V3)
        cipher.updateAAD(salt)
        cipher.updateAAD(iv)
        cipher.updateAAD(iterBytes)
        FileInputStream(source).use { input ->
            FileOutputStream(dest).use { fileOut ->
                fileOut.write(ENCRYPTED_BACKUP_MAGIC_V3)
                fileOut.write(salt)
                fileOut.write(iv)
                fileOut.write(iterBytes)
                CipherOutputStream(fileOut, cipher).use { out -> copyStream(input, out) }
            }
        }
    }

    /** Which form of the passphrase authenticated an encrypted container. */
    enum class PassphraseForm {
        /** The passphrase exactly as the user typed it (edge whitespace included). */
        EXACT,

        /** The trimmed form — the key older backups were written with before trimming was removed. */
        LEGACY_TRIMMED,
    }

    /**
     * Decrypts like [decryptFile], but with a one-shot compatibility retry: passphrases used to be
     * trimmed before key derivation, so a backup encrypted back then by a user who typed edge
     * whitespace was actually keyed on the TRIMMED form. Now that the passphrase is used exactly as
     * typed, that same input would fail GCM authentication against the old backup — so if the exact
     * form fails authentication and the passphrase carries trimmable whitespace, retry once with
     * the trimmed form. Returns which form unlocked the container so the caller can log it. Format
     * errors (bad magic, truncated header, oversize plaintext) are NOT retried — only a failed
     * authentication ([GeneralSecurityException]) can mean "wrong key".
     */
    @Throws(IOException::class, GeneralSecurityException::class)
    fun decryptFileWithTrimFallback(
        source: File,
        dest: File,
        passphrase: String,
        maxPlaintextBytes: Long,
    ): PassphraseForm {
        try {
            decryptFile(source, dest, passphrase, maxPlaintextBytes)
            return PassphraseForm.EXACT
        } catch (ex: GeneralSecurityException) {
            val trimmed = passphrase.trim()
            if (trimmed == passphrase || trimmed.isEmpty()) {
                throw ex
            }
            decryptFile(source, dest, trimmed, maxPlaintextBytes)
            return PassphraseForm.LEGACY_TRIMMED
        }
    }

    @Throws(IOException::class, GeneralSecurityException::class)
    fun decryptFile(
        source: File,
        dest: File,
        passphrase: String,
        maxPlaintextBytes: Long,
    ) {
        FileInputStream(source).use { fileIn -> decryptStream(fileIn, dest, passphrase, maxPlaintextBytes) }
    }

    /**
     * Stream form of [decryptFile], split out so tests can drive it with short-read streams. All
     * fixed-size header fields are read with [readExactly] — a single `read(buf)` filling the
     * whole buffer is not guaranteed by the [InputStream] contract (short reads without EOF are
     * legal), so treating `read(buf) != buf.size` as truncation could reject a valid backup.
     */
    @Throws(IOException::class, GeneralSecurityException::class)
    internal fun decryptStream(
        fileIn: InputStream,
        dest: File,
        passphrase: String,
        maxPlaintextBytes: Long,
    ) {
        val magic = ByteArray(ENCRYPTED_BACKUP_MAGIC.size)
        val salt = ByteArray(ENCRYPTION_SALT_BYTES)
        val iv = ByteArray(ENCRYPTION_IV_BYTES)
        if (!readExactly(fileIn, magic) || !isMagic(magic)) {
            throw IOException("Not an encrypted Volt Tracker backup")
        }
        if (!readExactly(fileIn, salt) || !readExactly(fileIn, iv)) {
            throw IOException("Encrypted backup header is truncated")
        }
        val isV3 = magic.contentEquals(ENCRYPTED_BACKUP_MAGIC_V3)
        // v3 stores the KDF iteration count in a 4-byte field after the IV; v1/v2 predate the
        // field and used the legacy count. Read it here so the key derives with the SAME count
        // the file was written with — otherwise the GCM tag (and the key) would never match.
        var iterBytes: ByteArray? = null
        val iterations: Int =
            if (isV3) {
                val bytes = ByteArray(ENCRYPTION_ITER_FIELD_BYTES)
                if (!readExactly(fileIn, bytes)) {
                    throw IOException("Encrypted backup header is truncated")
                }
                iterBytes = bytes
                decodeIterations(bytes)
            } else {
                LEGACY_PBKDF2_ITERATIONS
            }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, deriveKey(passphrase, salt, iterations), GCMParameterSpec(128, iv))
        // v2 and v3 authenticate the header as AAD (v3 additionally binds the iteration count so
        // it can't be tampered down); v1 wrote no AAD, so skip it for the oldest magic.
        if (magic.contentEquals(ENCRYPTED_BACKUP_MAGIC_V2) || isV3) {
            cipher.updateAAD(magic)
            cipher.updateAAD(salt)
            cipher.updateAAD(iv)
            if (iterBytes != null) {
                cipher.updateAAD(iterBytes)
            }
        }
        // Authenticate-then-trust instead of using a CipherInputStream: some Android
        // CipherInputStream implementations swallow the end-of-stream AEADBadTagException (they
        // return EOF rather than throwing), so a wrong passphrase / tampered container would
        // "succeed" and stream unauthenticated plaintext to disk — silently defeating GCM's
        // integrity guarantee and never firing DECRYPT_FAILED. writeAuthenticatedPlaintext
        // streams the plaintext out (never buffering the whole restore in RAM) but verifies the
        // trailing GCM tag via doFinal() and deletes dest on any failure, so no unauthenticated
        // or partial plaintext ever survives; DataBackup maps the thrown exception to
        // DECRYPT_FAILED.
        writeAuthenticatedPlaintext(cipher, fileIn, dest, maxPlaintextBytes)
    }

    /**
     * Fills [buffer] completely, looping over short reads (the readFully contract, mirroring
     * RestoreValidator's DataInputStream use). Returns false when EOF arrives before the buffer
     * is full — the only condition that actually means the header is truncated.
     */
    @Throws(IOException::class)
    internal fun readExactly(
        input: InputStream,
        buffer: ByteArray,
    ): Boolean {
        var offset = 0
        while (offset < buffer.size) {
            val read = input.read(buffer, offset, buffer.size - offset)
            if (read < 0) {
                return false
            }
            offset += read
        }
        return true
    }

    /**
     * Streams GCM plaintext from [source] to [dest] as it decrypts — the whole restore is never held
     * in RAM — then verifies the trailing authentication tag with [Cipher.doFinal] before the output
     * is trusted. On ANY failure (a bad tag from a wrong passphrase / tampered container, or an
     * oversize stream) [dest] is deleted so no unauthenticated or partial plaintext is left for the
     * caller. Output is capped at [maxPlaintextBytes].
     */
    @Throws(IOException::class, GeneralSecurityException::class)
    private fun writeAuthenticatedPlaintext(
        cipher: Cipher,
        source: InputStream,
        dest: File,
        maxPlaintextBytes: Long,
    ) {
        var succeeded = false
        try {
            FileOutputStream(dest).use { out ->
                val buffer = ByteArray(IO_BUFFER_BYTES)
                var written = 0L
                while (true) {
                    val read = source.read(buffer)
                    if (read < 0) break
                    val chunk = cipher.update(buffer, 0, read) ?: continue
                    written += chunk.size
                    if (written > maxPlaintextBytes) {
                        throw IOException("Restore file exceeds size limit")
                    }
                    out.write(chunk)
                }
                // doFinal verifies the GCM tag over the whole stream and throws on a mismatch.
                val finalBlock = cipher.doFinal()
                written += finalBlock.size
                if (written > maxPlaintextBytes) {
                    throw IOException("Restore file exceeds size limit")
                }
                out.write(finalBlock)
                out.fd.sync()
            }
            succeeded = true
        } finally {
            if (!succeeded) dest.delete()
        }
    }

    @Throws(GeneralSecurityException::class)
    private fun deriveKey(
        passphrase: String,
        salt: ByteArray,
        iterations: Int,
    ): SecretKeySpec {
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, iterations, ENCRYPTION_KEY_BITS)
        return try {
            val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            SecretKeySpec(key, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    /** Big-endian 4-byte encoding of the KDF iteration count for the v3 header. */
    private fun encodeIterations(iterations: Int): ByteArray =
        ByteBuffer.allocate(ENCRYPTION_ITER_FIELD_BYTES).putInt(iterations).array()

    @Throws(IOException::class)
    private fun decodeIterations(bytes: ByteArray): Int {
        val value = ByteBuffer.wrap(bytes).int
        if (value < MIN_PBKDF2_ITERATIONS || value > MAX_PBKDF2_ITERATIONS) {
            throw IOException("Encrypted backup header has an out-of-range iteration count")
        }
        return value
    }

    private fun isMagic(header: ByteArray): Boolean =
        header.contentEquals(ENCRYPTED_BACKUP_MAGIC) ||
            header.contentEquals(ENCRYPTED_BACKUP_MAGIC_V2) ||
            header.contentEquals(ENCRYPTED_BACKUP_MAGIC_V3)

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
