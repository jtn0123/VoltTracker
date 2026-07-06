package com.volttracker.obdpoc

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile
import java.security.GeneralSecurityException
import java.security.SecureRandom

/**
 * Direct unit coverage for [BackupCrypto] — the AES-256-GCM backup container. The end-to-end
 * [BackupRoundTripTest] exercises BackupCrypto only through [DataBackup] and only asserts the
 * wrong-passphrase path; this suite drives [BackupCrypto.encryptFile]/[BackupCrypto.decryptFile]
 * directly and pins the security-relevant failure branches that nothing else covers:
 *
 *  - a non-trivial binary payload round-trips byte-for-byte,
 *  - a single flipped ciphertext byte (GCM auth-tag failure) must NOT yield the original plaintext,
 *  - a truncated container must not surface partial/garbage plaintext as the real backup,
 *  - the magic-header sniff distinguishes encrypted containers from plaintext/garbage.
 *
 * [BackupCrypto] is a pure `javax.crypto` object with no `android.*` calls, so this is a plain
 * JVM JUnit4 test — no Robolectric runner needed.
 */
class BackupCryptoTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val passphrase = "correct horse battery staple"

    // The v3 magic that encryptFile now writes: bytes "VTBKEN3\n". 8-byte magic, then 16-byte salt,
    // then 12-byte IV, then the 4-byte big-endian KDF iteration count, then GCM ciphertext (incl. the
    // 16-byte auth tag). v2 (no iter field) and v1 (no AAD) remain readable on decrypt.
    private fun magicWithVersion(version: Char): ByteArray =
        byteArrayOf(
            'V'.code.toByte(),
            'T'.code.toByte(),
            'B'.code.toByte(),
            'K'.code.toByte(),
            'E'.code.toByte(),
            'N'.code.toByte(),
            version.code.toByte(),
            '\n'.code.toByte(),
        )

    private val magicV3 = magicWithVersion('3')

    // magic + salt + IV + 4-byte iteration field for the v3 container encryptFile writes.
    private val headerBytes = magicV3.size + 16 + 12 + 4

    private fun randomBytes(n: Int): ByteArray {
        val b = ByteArray(n)
        SecureRandom().nextBytes(b)
        return b
    }

    private fun writeFile(
        name: String,
        bytes: ByteArray,
    ): File {
        val f = tmp.newFile(name)
        f.writeBytes(bytes)
        return f
    }

    /**
     * Round-trip: a non-trivial binary payload (random bytes, larger than the 8 KiB IO buffer so
     * the GCM stream spans several blocks) survives encrypt -> decrypt byte-for-byte.
     */
    @Test
    fun encryptThenDecryptRoundTripsBinaryPayloadByteForByte() {
        val payload = randomBytes(20_000)
        val source = writeFile("plain.bin", payload)
        val encrypted = tmp.newFile("encrypted.vtdb")
        val decrypted = tmp.newFile("decrypted.bin")

        BackupCrypto.encryptFile(source, encrypted, passphrase)

        // Sanity: the on-disk container is genuinely encrypted, not a copy of the plaintext.
        assertTrue("encrypted output should exist", encrypted.exists())
        assertTrue(
            "encrypted output should be recognized as a backup container",
            BackupCrypto.isEncryptedBackup(encrypted),
        )
        assertFalse("ciphertext must not equal the plaintext", encrypted.readBytes().contentEquals(payload))

        BackupCrypto.decryptFile(encrypted, decrypted, passphrase, Long.MAX_VALUE)

        assertArrayEquals("decrypted bytes must match the original payload exactly", payload, decrypted.readBytes())
    }

    /**
     * Tampered ciphertext: flip exactly one byte in the middle of the GCM ciphertext region (past
     * the magic/salt/IV header). GCM authentication must catch this — decrypt must NOT produce the
     * original plaintext. SunJCE's CipherInputStream throws on the bad tag; the contract we pin is
     * "either it throws, or the output is not the original plaintext" — never a silent correct read.
     */
    @Test
    fun tamperedCiphertextFailsAuthenticationAndDoesNotReturnPlaintext() {
        val payload = randomBytes(4_096)
        val source = writeFile("plain.bin", payload)
        val encrypted = tmp.newFile("encrypted.vtdb")
        val decrypted = tmp.newFile("decrypted.bin")

        BackupCrypto.encryptFile(source, encrypted, passphrase)

        // Flip a single bit of one ciphertext byte squarely in the middle of the file, well past
        // the header and well before the trailing auth tag.
        val len = encrypted.length()
        val flipAt = (headerBytes + (len - headerBytes) / 2)
        RandomAccessFile(encrypted, "rw").use { raf ->
            raf.seek(flipAt)
            val orig = raf.readByte()
            raf.seek(flipAt)
            raf.writeByte((orig.toInt() xor 0x01))
        }
        assertTrue("flip should land inside the ciphertext, not the header", flipAt > headerBytes)

        var threw = false
        try {
            BackupCrypto.decryptFile(encrypted, decrypted, passphrase, Long.MAX_VALUE)
        } catch (ex: Exception) {
            // Expected: GCM auth-tag mismatch surfaces (GeneralSecurityException / AEADBadTagException
            // possibly wrapped in an IOException by CipherInputStream).
            threw = true
        }

        if (!threw) {
            // If no exception escaped, the decrypt MUST NOT have reproduced the real plaintext.
            assertFalse(
                "tampered ciphertext must never decrypt back to the original plaintext",
                decrypted.readBytes().contentEquals(payload),
            )
        }
    }

    /**
     * Truncated ciphertext: drop the trailing bytes (including the GCM auth tag). Decryption must
     * fail cleanly — it must not surface the leading, unauthenticated plaintext block as a valid
     * partial backup. Pinned contract: either it throws, or the output is not a correct prefix of
     * the original plaintext.
     */
    @Test
    fun truncatedCiphertextFailsCleanlyWithoutLeakingPartialPlaintext() {
        val payload = randomBytes(8_192)
        val source = writeFile("plain.bin", payload)
        val encrypted = tmp.newFile("encrypted.vtdb")
        val decrypted = tmp.newFile("decrypted.bin")

        BackupCrypto.encryptFile(source, encrypted, passphrase)

        // Lop off the last 32 bytes — that takes out the whole 16-byte GCM tag plus a chunk of
        // ciphertext, so authentication cannot succeed.
        val full = encrypted.readBytes()
        assertTrue("encrypted container should be larger than the bytes we drop", full.size > 32)
        val truncated = full.copyOf(full.size - 32)
        encrypted.writeBytes(truncated)

        var threw = false
        try {
            BackupCrypto.decryptFile(encrypted, decrypted, passphrase, Long.MAX_VALUE)
        } catch (ex: Exception) {
            threw = true
        }

        if (!threw) {
            val out = decrypted.readBytes()
            // No exception means we must not have produced the full original payload, and whatever
            // bytes landed must not be presentable as a correct prefix of the real plaintext.
            assertFalse(
                "truncated container must not decrypt to the full original payload",
                out.contentEquals(payload),
            )
            if (out.isNotEmpty()) {
                val prefix = payload.copyOf(out.size)
                assertFalse(
                    "truncated container must not leak a correct plaintext prefix",
                    out.contentEquals(prefix),
                )
            }
        }
    }

    /**
     * Wrong passphrase decrypts to the wrong key, so the GCM tag fails. Asserted directly at the
     * BackupCrypto level here (BackupRoundTripTest only covers it via DataBackup.stageRestoreFile).
     */
    @Test
    fun wrongPassphraseFailsAndDoesNotReturnPlaintext() {
        val payload = randomBytes(2_048)
        val source = writeFile("plain.bin", payload)
        val encrypted = tmp.newFile("encrypted.vtdb")
        val decrypted = tmp.newFile("decrypted.bin")

        BackupCrypto.encryptFile(source, encrypted, passphrase)

        var threw = false
        try {
            BackupCrypto.decryptFile(encrypted, decrypted, "totally different passphrase", Long.MAX_VALUE)
        } catch (ex: Exception) {
            threw = true
        }

        if (!threw) {
            assertFalse(
                "a wrong passphrase must never decrypt back to the original plaintext",
                decrypted.readBytes().contentEquals(payload),
            )
        }
    }

    /**
     * Hardened contract for the buffer-and-doFinal decrypt: a tampered ciphertext must now THROW a
     * GCM authentication failure (AEADBadTagException, a GeneralSecurityException) instead of a
     * CipherInputStream possibly swallowing it at EOF — and no plaintext may be written. This is the
     * branch DataBackup maps to DECRYPT_FAILED, so the "either it throws or output isn't plaintext"
     * soft contract above is tightened here to "it must throw and write nothing".
     */
    @Test
    fun tamperedCiphertextThrowsAuthenticationFailureAndWritesNoPlaintext() {
        val payload = randomBytes(4_096)
        val source = writeFile("plain.bin", payload)
        val encrypted = tmp.newFile("encrypted.vtdb")
        val decrypted = tmp.newFile("decrypted.bin")

        BackupCrypto.encryptFile(source, encrypted, passphrase)

        val len = encrypted.length()
        val flipAt = headerBytes + (len - headerBytes) / 2
        RandomAccessFile(encrypted, "rw").use { raf ->
            raf.seek(flipAt)
            val orig = raf.readByte()
            raf.seek(flipAt)
            raf.writeByte(orig.toInt() xor 0x01)
        }

        try {
            BackupCrypto.decryptFile(encrypted, decrypted, passphrase, Long.MAX_VALUE)
            fail("a tampered GCM container must throw an authentication failure, not silently succeed")
        } catch (ex: GeneralSecurityException) {
            // Expected: doFinal surfaces the AEADBadTagException instead of returning EOF.
        }
        assertEquals("no plaintext may be written when authentication fails", 0L, decrypted.length())
    }

    /**
     * A wrong passphrase derives the wrong key, so the GCM tag can never verify. The fix guarantees
     * this throws (and writes nothing) rather than a swallowed-tag silent success.
     */
    @Test
    fun wrongPassphraseThrowsAuthenticationFailureAndWritesNoPlaintext() {
        val payload = randomBytes(2_048)
        val source = writeFile("plain.bin", payload)
        val encrypted = tmp.newFile("encrypted.vtdb")
        val decrypted = tmp.newFile("decrypted.bin")

        BackupCrypto.encryptFile(source, encrypted, passphrase)

        try {
            BackupCrypto.decryptFile(encrypted, decrypted, "totally different passphrase", Long.MAX_VALUE)
            fail("a wrong passphrase must throw an authentication failure, not silently succeed")
        } catch (ex: GeneralSecurityException) {
            // Expected: the wrong key yields a GCM tag mismatch at doFinal.
        }
        assertEquals("no plaintext may be written for a wrong passphrase", 0L, decrypted.length())
    }

    /**
     * Magic-header sniff: true for a real encrypted container, false for a plaintext JSON file, a
     * blob of random non-magic bytes, a too-short file, and a null file.
     */
    @Test
    fun isEncryptedBackupDetectsContainerAndRejectsNonContainers() {
        val source = writeFile("plain.bin", randomBytes(1_024))
        val encrypted = tmp.newFile("encrypted.vtdb")
        BackupCrypto.encryptFile(source, encrypted, passphrase)

        assertTrue("real encrypted container should be detected", BackupCrypto.isEncryptedBackup(encrypted))

        val json = writeFile("backup.json", "{\"sessions\":[],\"version\":7}".toByteArray(Charsets.UTF_8))
        assertFalse("plaintext JSON must not be mistaken for an encrypted backup", BackupCrypto.isEncryptedBackup(json))

        // Random bytes whose first 8 bytes are not the magic.
        val garbageBytes = randomBytes(4_096)
        // Guarantee the header differs from the magic regardless of the random draw.
        garbageBytes[0] = ('X'.code.toByte())
        garbageBytes[1] = ('Y'.code.toByte())
        garbageBytes[2] = ('Z'.code.toByte())
        val garbage = writeFile("garbage.bin", garbageBytes)
        assertFalse("random non-magic bytes must not be detected as a backup", BackupCrypto.isEncryptedBackup(garbage))

        val tooShort = writeFile("short.bin", byteArrayOf(magicV3[0], magicV3[1], magicV3[2]))
        assertFalse(
            "a file shorter than the magic header must not be detected",
            BackupCrypto.isEncryptedBackup(tooShort),
        )

        assertFalse("null file must report false", BackupCrypto.isEncryptedBackup(null))
    }

    /**
     * Guard the encrypt header layout this suite reasons about (magic + salt + IV + iter field). If
     * the on-disk format ever changes, the tamper/truncate offset math above would silently weaken,
     * so anchor it. encryptFile writes the v3 container now (raised KDF cost, recorded count).
     */
    @Test
    fun encryptedContainerStartsWithV3MagicAndExpectedHeaderSize() {
        val source = writeFile("plain.bin", randomBytes(64))
        val encrypted = tmp.newFile("encrypted.vtdb")
        BackupCrypto.encryptFile(source, encrypted, passphrase)

        val bytes = encrypted.readBytes()
        if (bytes.size < magicV3.size) {
            fail("encrypted container is smaller than the magic header")
        }
        assertArrayEquals(
            "encryptFile must write the v3 magic header",
            magicV3,
            bytes.copyOf(magicV3.size),
        )
        // The recorded iteration count (4 big-endian bytes after magic+salt+IV) is the new 600k cost.
        val iterOffset = magicV3.size + 16 + 12
        val recorded =
            ((bytes[iterOffset].toInt() and 0xFF) shl 24) or
                ((bytes[iterOffset + 1].toInt() and 0xFF) shl 16) or
                ((bytes[iterOffset + 2].toInt() and 0xFF) shl 8) or
                (bytes[iterOffset + 3].toInt() and 0xFF)
        assertEquals("v3 records the 600k OWASP-current iteration count", 600_000, recorded)
        assertNotNull(encrypted)
        // 64-byte payload + GCM tag means the container must comfortably exceed the header.
        assertTrue("container must be larger than magic+salt+IV+iter header", bytes.size > headerBytes)
    }

    /**
     * v3 round-trip at the raised 600k cost: a payload encrypted by the current code (which writes
     * v3 with the recorded count) decrypts back byte-for-byte. The container is recognized and is not
     * a copy of the plaintext.
     */
    @Test
    fun v3ContainerRoundTripsAtRaisedIterationCount() {
        val payload = randomBytes(12_000)
        val source = writeFile("plain.bin", payload)
        val encrypted = tmp.newFile("encrypted.vtdb")
        val decrypted = tmp.newFile("decrypted.bin")

        BackupCrypto.encryptFile(source, encrypted, passphrase)
        assertTrue(BackupCrypto.isEncryptedBackup(encrypted))
        // Confirm it really is the v3 magic, i.e. the raised-cost path.
        assertArrayEquals(magicV3, encrypted.readBytes().copyOf(magicV3.size))

        BackupCrypto.decryptFile(encrypted, decrypted, passphrase, Long.MAX_VALUE)
        assertArrayEquals("v3 backup must round-trip byte-for-byte", payload, decrypted.readBytes())
    }

    /**
     * Backward compatibility: a backup written in the OLD v2 format (no iteration field, 150k count)
     * must still decrypt. We synthesize a genuine v2 container the same way the prior version did —
     * magic V2 + salt + IV, AES/GCM with the key derived at the legacy 150k count, AAD =
     * magic+salt+IV (no iter field) — then assert BackupCrypto reads it back at 150k.
     */
    @Test
    fun legacyV2ContainerStillDecryptsAtLegacyIterationCount() {
        val payload = randomBytes(5_000)
        val encrypted = writeLegacyV2Container(payload, passphrase)
        val decrypted = tmp.newFile("decrypted.bin")

        assertTrue("v2 container is still recognized as a backup", BackupCrypto.isEncryptedBackup(encrypted))
        BackupCrypto.decryptFile(encrypted, decrypted, passphrase, Long.MAX_VALUE)
        assertArrayEquals("an old v2 backup must still decrypt", payload, decrypted.readBytes())
    }

    /**
     * Backward compatibility for the OLDEST format: a v1 backup wrote NO GCM AAD and used the legacy
     * 150k count. The decrypt path must skip AAD for the v1 magic, so a genuine v1 container still
     * round-trips. This is the only test exercising the no-AAD branch — a regression there would
     * silently brick the earliest users' backups.
     */
    @Test
    fun legacyV1ContainerStillDecryptsWithoutAad() {
        val payload = randomBytes(3_000)
        val encrypted = writeLegacyV1Container(payload, passphrase)
        val decrypted = tmp.newFile("decrypted.bin")

        assertTrue("v1 container is still recognized as a backup", BackupCrypto.isEncryptedBackup(encrypted))
        BackupCrypto.decryptFile(encrypted, decrypted, passphrase, Long.MAX_VALUE)
        assertArrayEquals("an old v1 backup must still decrypt", payload, decrypted.readBytes())
    }

    /**
     * A corrupt/hostile v3 header that requests an absurd iteration count (here 0x7FFFFFFF, far above
     * MAX_PBKDF2_ITERATIONS) must be rejected outright before any key derivation — otherwise opening a
     * malicious backup could pin the CPU for minutes (a KDF-cost DoS). Pins the decodeIterations bound.
     */
    @Test
    fun v3HeaderWithOutOfRangeIterationCountIsRejected() {
        val payload = randomBytes(512)
        val source = writeFile("plain.bin", payload)
        val encrypted = tmp.newFile("encrypted.vtdb")
        BackupCrypto.encryptFile(source, encrypted, passphrase)

        // Overwrite the 4-byte big-endian iteration field (after magic+salt+IV) with a count just
        // ABOVE the 5,000,000 cap. Deliberately NOT a huge value like 0x7FFFFFFF: if the upper-bound
        // check ever regressed past key derivation, deriving at ~5M iterations is bounded (a second
        // or two) whereas ~2.1B would hang CI. Either way it must be rejected before any derivation.
        val tooManyIterations = 5_000_001
        val iterBytes =
            byteArrayOf(
                (tooManyIterations ushr 24).toByte(),
                (tooManyIterations ushr 16).toByte(),
                (tooManyIterations ushr 8).toByte(),
                tooManyIterations.toByte(),
            )
        val iterOffset = (magicV3.size + 16 + 12).toLong()
        RandomAccessFile(encrypted, "rw").use { raf ->
            raf.seek(iterOffset)
            raf.write(iterBytes)
        }

        val decrypted = tmp.newFile("decrypted.bin")
        try {
            BackupCrypto.decryptFile(encrypted, decrypted, passphrase, Long.MAX_VALUE)
            fail("an out-of-range iteration count must be rejected")
        } catch (ex: java.io.IOException) {
            assertTrue(
                "rejection should name the out-of-range iteration count",
                ex.message?.contains("iteration count") == true,
            )
        }
    }

    /** Builds a real v2 container (legacy 150k KDF, magic+salt+IV AAD) the way the prior code did. */
    private fun writeLegacyV2Container(
        payload: ByteArray,
        pass: String,
    ): File {
        val magicV2 = magicWithVersion('2')
        val salt = randomBytes(16)
        val iv = randomBytes(12)
        val keyBytes =
            javax.crypto.SecretKeyFactory
                .getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(javax.crypto.spec.PBEKeySpec(pass.toCharArray(), salt, 150_000, 256))
                .encoded
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            javax.crypto.Cipher.ENCRYPT_MODE,
            javax.crypto.spec.SecretKeySpec(keyBytes, "AES"),
            javax.crypto.spec.GCMParameterSpec(128, iv),
        )
        cipher.updateAAD(magicV2)
        cipher.updateAAD(salt)
        cipher.updateAAD(iv)
        val ct = cipher.doFinal(payload)
        val f = tmp.newFile("legacy-v2.vtdb")
        f.outputStream().use { out ->
            out.write(magicV2)
            out.write(salt)
            out.write(iv)
            out.write(ct)
        }
        return f
    }

    /** Builds a real v1 container (legacy 150k KDF, NO AAD) the way the oldest code did. */
    private fun writeLegacyV1Container(
        payload: ByteArray,
        pass: String,
    ): File {
        val magicV1 = magicWithVersion('1')
        val salt = randomBytes(16)
        val iv = randomBytes(12)
        val keyBytes =
            javax.crypto.SecretKeyFactory
                .getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(javax.crypto.spec.PBEKeySpec(pass.toCharArray(), salt, 150_000, 256))
                .encoded
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            javax.crypto.Cipher.ENCRYPT_MODE,
            javax.crypto.spec.SecretKeySpec(keyBytes, "AES"),
            javax.crypto.spec.GCMParameterSpec(128, iv),
        )
        // v1 wrote no AAD — that is exactly the legacy decrypt branch under test.
        val ct = cipher.doFinal(payload)
        val f = tmp.newFile("legacy-v1.vtdb")
        f.outputStream().use { out ->
            out.write(magicV1)
            out.write(salt)
            out.write(iv)
            out.write(ct)
        }
        return f
    }
}
