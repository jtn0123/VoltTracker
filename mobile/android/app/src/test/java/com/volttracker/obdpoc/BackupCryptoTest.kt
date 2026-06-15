package com.volttracker.obdpoc

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile
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

    // The v2 magic that encryptFile writes: bytes "VTBKEN2\n". 8-byte header, then 16-byte salt,
    // then 12-byte IV, then GCM ciphertext (incl. the 16-byte auth tag).
    private val magicV2 =
        byteArrayOf(
            'V'.code.toByte(),
            'T'.code.toByte(),
            'B'.code.toByte(),
            'K'.code.toByte(),
            'E'.code.toByte(),
            'N'.code.toByte(),
            '2'.code.toByte(),
            '\n'.code.toByte(),
        )
    private val headerBytes = magicV2.size + 16 + 12 // magic + salt + IV

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

        val tooShort = writeFile("short.bin", byteArrayOf(magicV2[0], magicV2[1], magicV2[2]))
        assertFalse(
            "a file shorter than the magic header must not be detected",
            BackupCrypto.isEncryptedBackup(tooShort),
        )

        assertFalse("null file must report false", BackupCrypto.isEncryptedBackup(null))
    }

    /**
     * Guard the encrypt header layout this suite reasons about (magic + salt + IV). If the on-disk
     * format ever changes, the tamper/truncate offset math above would silently weaken, so anchor it.
     */
    @Test
    fun encryptedContainerStartsWithV2MagicAndExpectedHeaderSize() {
        val source = writeFile("plain.bin", randomBytes(64))
        val encrypted = tmp.newFile("encrypted.vtdb")
        BackupCrypto.encryptFile(source, encrypted, passphrase)

        val bytes = encrypted.readBytes()
        if (bytes.size < magicV2.size) {
            fail("encrypted container is smaller than the magic header")
        }
        assertArrayEquals(
            "encryptFile must write the v2 magic header",
            magicV2,
            bytes.copyOf(magicV2.size),
        )
        assertNotNull(encrypted)
        // 64-byte payload + GCM tag means the container must comfortably exceed the header.
        assertTrue("container must be larger than magic+salt+IV header", bytes.size > headerBytes)
    }
}
