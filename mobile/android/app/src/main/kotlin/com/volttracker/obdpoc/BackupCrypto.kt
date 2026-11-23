package com.volttracker.obdpoc

import com.volttracker.obdpoc.data.deleteOrLog
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** AES/GCM encrypted backup container used by [DataBackup]. */
object BackupCrypto {
    /**
     * Thrown before decrypting (or encrypting) when the heap has too little room for the work,
     * instead of letting the cipher run the app out of memory. [DataBackup] maps it to a readable
     * status.
     */
    class InsufficientMemoryException(
        message: String,
    ) : IOException(message)

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

    // v4 keeps v3's magic/salt/iteration fields and KDF, replaces the 12-byte IV with an 8-byte
    // nonce prefix, adds a 4-byte segment size, and seals the body as independent GCM segments
    // (BackupSegmentStream) so memory stays at about one segment. v1-v3 were one GCM operation over
    // the whole file, which Android's cipher can only verify by holding the whole file in memory.
    private val ENCRYPTED_BACKUP_MAGIC_V4 =
        ENCRYPTED_BACKUP_MAGIC.copyOf().also { it[6] = '4'.code.toByte() }
    private const val ENCRYPTION_SALT_BYTES = 16
    private const val ENCRYPTION_IV_BYTES = 12
    private const val ENCRYPTION_ITER_FIELD_BYTES = 4
    private const val ENCRYPTION_KEY_BITS = 256

    // Iteration count for backups written from v3 on (the v3 and v4 headers record it). Legacy v1/v2
    // backups were written at LEGACY_PBKDF2_ITERATIONS and decrypt with that count.
    private const val ENCRYPTION_PBKDF2_ITERATIONS = 600_000
    private const val LEGACY_PBKDF2_ITERATIONS = 150_000

    // Sanity bounds on the header-supplied count so a corrupt/hostile header can't request an absurd
    // (DoS-grade) or trivially weak iteration count. The current and legacy counts both sit inside.
    private const val MIN_PBKDF2_ITERATIONS = 50_000
    private const val MAX_PBKDF2_ITERATIONS = 5_000_000
    private const val IO_BUFFER_BYTES = 8192

    // A v1-v3 decrypt holds the whole ciphertext in the platform cipher's buffer, which grows by
    // doubling, and then allocates the plaintext: about three times the file at its peak.
    private const val LEGACY_WORKING_SET_MULTIPLIER = 3L
    private const val UNKNOWN_CONTAINER_BYTES = -1L
    private const val BYTES_PER_MIB = 1024L * 1024L

    // Heap left over for the rest of the app (UI thread, other workers) while a v1-v3 decrypt holds
    // its peak, so a file that only just fits is refused with a readable status instead of taking
    // the whole heap and running another thread out of memory.
    private const val LEGACY_HEAP_RESERVE_BYTES = 16L * BYTES_PER_MIB

    /** Free heap the memory precheck compares against; replaceable so tests can simulate a full heap. */
    @Volatile
    internal var heapHeadroomBytes: () -> Long = ::runtimeHeapHeadroom

    /** Heap a v1-v3 container of [containerBytes] needs to decrypt, including the app's reserve. */
    internal fun legacyDecryptHeapBytes(containerBytes: Long): Long =
        LEGACY_WORKING_SET_MULTIPLIER * containerBytes + LEGACY_HEAP_RESERVE_BYTES

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

    /**
     * Writes [source] to [dest] as a v4 container: the header, then the plaintext sealed in
     * [segmentBytes] segments, so memory stays at about one segment however large the database is.
     * On any failure [dest] is deleted, and a file cut short (a killed process) has no final
     * segment, so it can never decrypt as a complete backup.
     */
    @Throws(IOException::class, GeneralSecurityException::class)
    fun encryptFileSegmented(
        source: File,
        dest: File,
        passphrase: String,
        segmentBytes: Int = BackupSegmentStream.DEFAULT_SEGMENT_BYTES,
    ) {
        val segmentSize = BackupSegmentStream.encodeSegmentSize(segmentBytes)
        requireHeapHeadroom(BackupSegmentStream.workingSetBytes(segmentBytes))
        val salt = ByteArray(ENCRYPTION_SALT_BYTES)
        val noncePrefix = ByteArray(BackupSegmentStream.NONCE_PREFIX_BYTES)
        // GCM invariant: a (key, nonce) pair must NEVER repeat — reuse leaks plaintext XOR and
        // breaks authentication. The fresh SecureRandom salt (so a fresh key) and nonce prefix per
        // encryption are load-bearing; do not cache, reuse, or derive them deterministically. Within
        // a file each segment's nonce carries its own index, so no two segments share one.
        SecureRandom().apply {
            nextBytes(salt)
            nextBytes(noncePrefix)
        }
        val iterations = ENCRYPTION_PBKDF2_ITERATIONS
        val header =
            ENCRYPTED_BACKUP_MAGIC_V4 + salt + noncePrefix + encodeIterations(iterations) + segmentSize
        val aead = GcmSegmentAead(deriveKey(passphrase, salt, iterations))
        var succeeded = false
        try {
            FileInputStream(source).use { input ->
                FileOutputStream(dest).use { out ->
                    out.write(header)
                    BackupSegmentStream.encrypt(input, out, header, noncePrefix, segmentBytes, aead)
                    out.fd.sync()
                }
            }
            succeeded = true
        } finally {
            if (!succeeded) dest.deleteOrLog()
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
            // The first attempt already passed the memory precheck, and its cipher buffers are now
            // garbage that still counts as used until the runtime collects it, so a second precheck
            // would refuse a restore that fits. Skip it: the retry's own allocations let the runtime
            // collect that garbage, exactly as the first attempt's would have.
            FileInputStream(source).use { fileIn ->
                decryptStream(fileIn, dest, trimmed, maxPlaintextBytes, UNKNOWN_CONTAINER_BYTES)
            }
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
        FileInputStream(source).use { fileIn ->
            decryptStream(fileIn, dest, passphrase, maxPlaintextBytes, source.length())
        }
    }

    /**
     * Stream form of [decryptFile], split out so tests can drive it with short-read streams. All
     * fixed-size header fields are read with [readExactly] — a single `read(buf)` filling the
     * whole buffer is not guaranteed by the [InputStream] contract (short reads without EOF are
     * legal), so treating `read(buf) != buf.size` as truncation could reject a valid backup.
     *
     * [containerBytes] is the encrypted file's size, used by the memory precheck for v1-v3
     * containers; pass [UNKNOWN_CONTAINER_BYTES] when it isn't known to skip that precheck.
     */
    @Throws(IOException::class, GeneralSecurityException::class)
    internal fun decryptStream(
        fileIn: InputStream,
        dest: File,
        passphrase: String,
        maxPlaintextBytes: Long,
        containerBytes: Long = UNKNOWN_CONTAINER_BYTES,
    ) {
        val magic = ByteArray(ENCRYPTED_BACKUP_MAGIC.size)
        if (!readExactly(fileIn, magic) || !isMagic(magic)) {
            throw IOException("Not an encrypted Volt Tracker backup")
        }
        if (magic.contentEquals(ENCRYPTED_BACKUP_MAGIC_V4)) {
            decryptSegmentedStream(fileIn, magic, dest, passphrase, maxPlaintextBytes)
            return
        }
        val salt = ByteArray(ENCRYPTION_SALT_BYTES)
        val iv = ByteArray(ENCRYPTION_IV_BYTES)
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
        // One GCM operation over the whole file: the platform cipher holds all of it until doFinal.
        // Fail with a readable error before deriving the key rather than run out of memory.
        if (containerBytes != UNKNOWN_CONTAINER_BYTES) {
            requireHeapHeadroom(legacyDecryptHeapBytes(containerBytes))
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
        // verifies the trailing GCM tag via doFinal() and deletes dest on any failure, so no
        // unauthenticated or partial plaintext ever survives; DataBackup maps the thrown exception
        // to DECRYPT_FAILED. This is NOT a bounded-memory stream: Android's GCM cipher returns
        // nothing from update() and buffers the whole ciphertext until doFinal(), which is why
        // the precheck above exists and why new backups are written as v4 segments.
        writeAuthenticatedPlaintext(cipher, fileIn, dest, maxPlaintextBytes)
    }

    /** Reads the rest of a v4 header after [magic] and opens its segments into [dest]. */
    @Throws(IOException::class, GeneralSecurityException::class)
    private fun decryptSegmentedStream(
        fileIn: InputStream,
        magic: ByteArray,
        dest: File,
        passphrase: String,
        maxPlaintextBytes: Long,
    ) {
        val salt = ByteArray(ENCRYPTION_SALT_BYTES)
        val noncePrefix = ByteArray(BackupSegmentStream.NONCE_PREFIX_BYTES)
        val iterBytes = ByteArray(ENCRYPTION_ITER_FIELD_BYTES)
        val segmentSize = ByteArray(BackupSegmentStream.SEGMENT_SIZE_FIELD_BYTES)
        if (
            !readExactly(fileIn, salt) ||
            !readExactly(fileIn, noncePrefix) ||
            !readExactly(fileIn, iterBytes) ||
            !readExactly(fileIn, segmentSize)
        ) {
            throw IOException("Encrypted backup header is truncated")
        }
        val iterations = decodeIterations(iterBytes)
        val segmentBytes = BackupSegmentStream.decodeSegmentSize(segmentSize)
        requireHeapHeadroom(BackupSegmentStream.workingSetBytes(segmentBytes))
        val header = magic + salt + noncePrefix + iterBytes + segmentSize
        val aead = GcmSegmentAead(deriveKey(passphrase, salt, iterations))
        var succeeded = false
        try {
            FileOutputStream(dest).use { out ->
                BackupSegmentStream.decrypt(fileIn, out, header, noncePrefix, segmentBytes, aead, maxPlaintextBytes)
                out.fd.sync()
            }
            succeeded = true
        } finally {
            if (!succeeded) dest.deleteOrLog()
        }
    }

    /**
     * Throws [InsufficientMemoryException] when [neededBytes] exceeds the free heap, so a backup
     * too large for this phone fails with a readable status instead of an OutOfMemoryError.
     */
    @Throws(InsufficientMemoryException::class)
    private fun requireHeapHeadroom(neededBytes: Long) {
        val available = heapHeadroomBytes()
        if (neededBytes > available) {
            throw InsufficientMemoryException(
                "Encrypted backup needs about ${neededBytes / BYTES_PER_MIB} MiB of free memory; " +
                    "${available / BYTES_PER_MIB} MiB is available",
            )
        }
    }

    private fun runtimeHeapHeadroom(): Long {
        val runtime = Runtime.getRuntime()
        return runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory())
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
     * Decrypts a v1-v3 GCM body from [source] to [dest], verifying the trailing authentication tag
     * with [Cipher.doFinal] before the output is trusted. Android's GCM cipher buffers every update()
     * and releases the plaintext only from doFinal(), so the whole file is held in memory here (the
     * caller's heap precheck guards that). On ANY failure (a bad tag from a wrong passphrase /
     * tampered container, or an oversize stream) [dest] is deleted so no unauthenticated or partial
     * plaintext is left for the caller. Output is capped at [maxPlaintextBytes].
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
            if (!succeeded) dest.deleteOrLog()
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
            header.contentEquals(ENCRYPTED_BACKUP_MAGIC_V3) ||
            header.contentEquals(ENCRYPTED_BACKUP_MAGIC_V4)
}
