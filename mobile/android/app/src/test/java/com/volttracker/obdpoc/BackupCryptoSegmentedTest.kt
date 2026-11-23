package com.volttracker.obdpoc

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.util.Random

/**
 * File-level coverage for the v4 encrypted backup container written by
 * [BackupCrypto.encryptFileSegmented]: header layout, round trips, tampering, wrong passphrase,
 * cleanup of a failed write, and the heap precheck that guards older single-shot containers.
 */
class BackupCryptoSegmentedTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val passphrase = "synthetic passphrase"
    private val segment = 1024
    private val tag = BackupSegmentStream.TAG_BYTES

    // magic 8 + salt 16 + nonce prefix 8 + iterations 4 + segment size 4
    private val headerBytes = 40
    private val originalHeadroom = BackupCrypto.heapHeadroomBytes

    @After
    fun restoreHeadroom() {
        BackupCrypto.heapHeadroomBytes = originalHeadroom
        Thread.interrupted()
    }

    /** Returns at most one byte per read without reaching EOF early, as the InputStream contract allows. */
    private class TrickleInputStream(
        private val delegate: InputStream,
    ) : InputStream() {
        override fun read(): Int = delegate.read()

        override fun read(
            b: ByteArray,
            off: Int,
            len: Int,
        ): Int = delegate.read(b, off, if (len > 0) 1 else 0)
    }

    private fun payload(size: Int): ByteArray = ByteArray(size).also { Random(size.toLong() + 7).nextBytes(it) }

    private fun encrypt(
        plain: ByteArray,
        segmentBytes: Int = segment,
        pass: String = passphrase,
    ): File {
        val source = tmp.newFile().apply { writeBytes(plain) }
        val dest = File(tmp.root, "v4-${System.nanoTime()}.vtdb")
        BackupCrypto.encryptFileSegmented(source, dest, pass, segmentBytes)
        return dest
    }

    private fun decrypt(
        container: File,
        pass: String = passphrase,
    ): ByteArray {
        val out = File(tmp.root, "plain-${System.nanoTime()}.db")
        BackupCrypto.decryptFile(container, out, pass, Long.MAX_VALUE)
        return out.readBytes()
    }

    private fun assertFailsClosed(
        what: String,
        container: File,
    ) {
        val out = File(tmp.root, "out-${System.nanoTime()}.db")
        try {
            BackupCrypto.decryptFile(container, out, passphrase, Long.MAX_VALUE)
            fail("$what must not decrypt")
        } catch (ex: GeneralSecurityException) {
            // Expected: authentication failure.
        } catch (ex: IOException) {
            // Expected: format failure (truncated, out-of-range field).
        }
        assertFalse("$what must leave no plaintext behind", out.exists())
    }

    private fun body(container: File): List<ByteArray> =
        container
            .readBytes()
            .drop(headerBytes)
            .chunked(segment + tag)
            .map { it.toByteArray() }

    private fun withBody(
        container: File,
        parts: List<ByteArray>,
    ): File {
        val header = container.readBytes().copyOf(headerBytes)
        return File(tmp.root, "edited-${System.nanoTime()}.vtdb").apply {
            writeBytes(parts.fold(header) { acc, part -> acc + part })
        }
    }

    @Test
    fun writesTheV4HeaderWithCurrentKdfCostAndSegmentSize() {
        val container = encrypt(payload(10), segmentBytes = BackupSegmentStream.DEFAULT_SEGMENT_BYTES)
        val bytes = container.readBytes()
        assertArrayEquals("VTBKEN4\n".toByteArray(Charsets.US_ASCII), bytes.copyOf(8))
        assertEquals(600_000, ByteBuffer.wrap(bytes, 32, 4).int)
        assertEquals(BackupSegmentStream.DEFAULT_SEGMENT_BYTES, ByteBuffer.wrap(bytes, 36, 4).int)
        assertEquals(headerBytes + 10 + tag, bytes.size)
        assertTrue(BackupCrypto.isEncryptedBackup(container))
    }

    @Test
    fun roundTripsEmptyOneSegmentOneByteOverAndManySegments() {
        for (size in listOf(0, segment, segment + 1, 23 * segment + 17)) {
            val plain = payload(size)
            val container = encrypt(plain)
            val segments = maxOf(1, (size + segment - 1) / segment)
            assertEquals("$size bytes", headerBytes + size + segments * tag, container.length().toInt())
            assertArrayEquals("$size bytes must round-trip", plain, decrypt(container))
        }
    }

    @Test
    fun roundTripsAtTheDefaultSegmentSize() {
        val plain = payload(BackupSegmentStream.DEFAULT_SEGMENT_BYTES + 1)
        assertArrayEquals(plain, decrypt(encrypt(plain, segmentBytes = BackupSegmentStream.DEFAULT_SEGMENT_BYTES)))
    }

    @Test
    fun wrongPassphraseFailsAndLeavesNoPlaintext() {
        val container = encrypt(payload(3 * segment))
        val out = File(tmp.root, "wrong.db")
        try {
            BackupCrypto.decryptFile(container, out, "a different passphrase", Long.MAX_VALUE)
            fail("a wrong passphrase must not decrypt")
        } catch (ex: GeneralSecurityException) {
            // Expected.
        }
        assertFalse(out.exists())
    }

    @Test
    fun trimFallbackStillUnlocksATrimKeyedV4Container() {
        val plain = payload(2 * segment)
        val container = encrypt(plain, pass = "correct horse")
        val out = File(tmp.root, "trimmed.db")
        val form = BackupCrypto.decryptFileWithTrimFallback(container, out, "  correct horse ", Long.MAX_VALUE)
        assertEquals(BackupCrypto.PassphraseForm.LEGACY_TRIMMED, form)
        assertArrayEquals(plain, out.readBytes())
    }

    @Test
    fun everyTamperedOrRearrangedFileFailsClosed() {
        val container = encrypt(payload(3 * segment + 9))
        val parts = body(container)
        assertEquals(4, parts.size)

        val flipped =
            container.readBytes().also {
                it[headerBytes + segment + 5] =
                    (it[headerBytes + segment + 5] + 1).toByte()
            }
        assertFailsClosed("a flipped ciphertext byte", File(tmp.root, "flip.vtdb").apply { writeBytes(flipped) })
        assertFailsClosed(
            "truncated mid-segment",
            File(tmp.root, "cut.vtdb").apply {
                writeBytes(
                    container.readBytes().copyOf(
                        container.length().toInt() - 4,
                    ),
                )
            },
        )
        assertFailsClosed(
            "header only",
            File(tmp.root, "hdr.vtdb").apply {
                writeBytes(container.readBytes().copyOf(headerBytes))
            },
        )
        assertFailsClosed("reordered", withBody(container, listOf(parts[1], parts[0], parts[2], parts[3])))
        assertFailsClosed("duplicated", withBody(container, listOf(parts[0], parts[0], parts[1], parts[2], parts[3])))
        assertFailsClosed("final segment dropped", withBody(container, parts.dropLast(1)))
        assertFailsClosed("extra segment appended", withBody(container, parts + parts.last()))

        val exact = encrypt(payload(2 * segment))
        assertFailsClosed("full final segment dropped", withBody(exact, body(exact).dropLast(1)))
    }

    @Test
    fun segmentFromAnotherFileWithTheSamePassphraseFailsClosed() {
        val plain = payload(3 * segment)
        val first = encrypt(plain)
        val second = encrypt(plain)
        val mixed = body(first).toMutableList().also { it[1] = body(second)[1] }
        assertFailsClosed("a segment swapped in from another backup", withBody(first, mixed))
    }

    @Test
    fun tamperedHeaderFieldsFailClosed() {
        val container = encrypt(payload(2 * segment + 1))

        fun edited(
            name: String,
            offset: Int,
            value: Int,
        ) = File(tmp.root, name).apply {
            val bytes = container.readBytes()
            ByteBuffer.wrap(bytes).putInt(offset, value)
            writeBytes(bytes)
        }
        assertFailsClosed("a lowered iteration count", edited("iter.vtdb", 32, 50_000))
        assertFailsClosed("an out-of-range iteration count", edited("iter-big.vtdb", 32, 5_000_001))
        assertFailsClosed("a changed segment size", edited("seg.vtdb", 36, 2 * segment))
        assertFailsClosed("an out-of-range segment size", edited("seg-big.vtdb", 36, Int.MAX_VALUE))
        val prefixFlipped = container.readBytes().also { it[24] = (it[24] + 1).toByte() }
        assertFailsClosed("a changed nonce prefix", File(tmp.root, "nonce.vtdb").apply { writeBytes(prefixFlipped) })
        val saltFlipped = container.readBytes().also { it[8] = (it[8] + 1).toByte() }
        assertFailsClosed("a changed salt", File(tmp.root, "salt.vtdb").apply { writeBytes(saltFlipped) })
    }

    /**
     * Rewriting the version byte must not let a v4 file be read as an older format, or a v3 file as
     * v4. The default segment size is used so that, read as v3, the segment-size field lands where
     * v3 keeps its iteration count and is still in range: the key derives and the tag check is what
     * fails.
     */
    @Test
    fun rewrittenVersionByteFailsClosed() {
        val v4 = encrypt(payload(2 * segment + 1), segmentBytes = BackupSegmentStream.DEFAULT_SEGMENT_BYTES)

        fun withVersion(
            container: File,
            version: Char,
        ) = File(tmp.root, "as-v$version-${System.nanoTime()}.vtdb").apply {
            writeBytes(container.readBytes().also { it[6] = version.code.toByte() })
        }
        for (older in listOf('1', '2', '3')) {
            assertFailsClosed("a v4 file relabelled as v$older", withVersion(v4, older))
        }

        val v3Source = tmp.newFile().apply { writeBytes(payload(3 * segment)) }
        val v3 = File(tmp.root, "real-v3.vtdb")
        writeLegacyV3Backup(v3Source, v3, passphrase)
        assertFailsClosed("a v3 file relabelled as v4", withVersion(v3, '4'))
    }

    /** A v4 container opens over a stream that returns one byte per read, header and body alike. */
    @Test
    fun decryptStreamSurvivesShortReadsAcrossHeaderAndSegments() {
        val plain = payload(5 * segment + 3)
        val container = encrypt(plain)
        val out = File(tmp.root, "trickle.db")

        container.inputStream().use { input ->
            BackupCrypto.decryptStream(TrickleInputStream(input), out, passphrase, Long.MAX_VALUE)
        }

        assertArrayEquals("short reads must not end a segment early or truncate the backup", plain, out.readBytes())
    }

    @Test
    fun interruptedEncryptionLeavesNoFile() {
        val source = tmp.newFile().apply { writeBytes(payload(4 * segment)) }
        val dest = File(tmp.root, "interrupted.vtdb")
        Thread.currentThread().interrupt()
        try {
            BackupCrypto.encryptFileSegmented(source, dest, passphrase, segment)
            fail("an interrupted encryption must throw")
        } catch (ex: InterruptedIOException) {
            // Expected.
        } finally {
            Thread.interrupted()
        }
        assertFalse("a failed write must not leave a container behind", dest.exists())
    }

    @Test
    fun encryptFailsWithReadableErrorWhenTheHeapIsFull() {
        BackupCrypto.heapHeadroomBytes = { 0L }
        val source = tmp.newFile().apply { writeBytes(payload(10)) }
        val dest = File(tmp.root, "no-room.vtdb")
        try {
            BackupCrypto.encryptFileSegmented(source, dest, passphrase)
            fail("encryption must refuse to start without heap headroom")
        } catch (ex: BackupCrypto.InsufficientMemoryException) {
            assertTrue(ex.message!!.contains("free memory"))
        }
        assertFalse(dest.exists())
    }

    /**
     * A v4 container needs heap for one segment, not the whole file: a 3 MiB backup still opens
     * with 5 MiB of headroom, while a v3 container of the same size, which the platform cipher
     * must buffer whole, is refused by the precheck before it can run out of memory.
     */
    @Test
    fun v4NeedsOneSegmentOfHeadroomWhileV3IsGatedOnItsWholeSize() {
        val plain = payload(3 * BackupSegmentStream.DEFAULT_SEGMENT_BYTES)
        val v4 = encrypt(plain, segmentBytes = BackupSegmentStream.DEFAULT_SEGMENT_BYTES)
        val v3Source = tmp.newFile().apply { writeBytes(plain) }
        val v3 = File(tmp.root, "legacy.vtdb")
        writeLegacyV3Backup(v3Source, v3, passphrase)

        BackupCrypto.heapHeadroomBytes = { 5L * 1024 * 1024 }
        assertArrayEquals(plain, decrypt(v4))
        val out = File(tmp.root, "legacy-out.db")
        try {
            BackupCrypto.decryptFile(v3, out, passphrase, Long.MAX_VALUE)
            fail("a v3 container larger than the heap allows must be refused")
        } catch (ex: BackupCrypto.InsufficientMemoryException) {
            assertTrue(ex.message!!.contains("MiB"))
        }
        assertFalse(out.exists())

        BackupCrypto.heapHeadroomBytes = { 3L * v3.length() }
        try {
            BackupCrypto.decryptFile(v3, out, passphrase, Long.MAX_VALUE)
            fail("a v3 container that would leave the rest of the app no heap must be refused")
        } catch (ex: BackupCrypto.InsufficientMemoryException) {
            // Expected: three times the file fits, but not with the reserve on top.
        }

        BackupCrypto.heapHeadroomBytes = { BackupCrypto.legacyDecryptHeapBytes(v3.length()) }
        assertArrayEquals("with enough headroom the v3 container still restores", plain, decrypt(v3))
    }

    /**
     * Headroom counts uncollected garbage as used. A wrong first attempt (here the exact passphrase
     * against a backup keyed on the trimmed one) leaves its cipher buffers behind as garbage, so the
     * trimmed retry must not run the precheck again and refuse a restore that fits: the precheck
     * runs once per call, before the first attempt.
     */
    @Test
    fun trimmedPassphraseRetryIsNotRefusedByTheFirstAttemptsGarbage() {
        val plain = payload(4 * segment)
        val v3Source = tmp.newFile().apply { writeBytes(plain) }
        val v3 = File(tmp.root, "trim-keyed-v3.vtdb")
        writeLegacyV3Backup(v3Source, v3, "correct horse")
        val plenty = BackupCrypto.legacyDecryptHeapBytes(v3.length())
        var measurements = 0
        // Room for the first attempt only; afterwards its garbage makes the heap look full.
        BackupCrypto.heapHeadroomBytes = {
            measurements += 1
            if (measurements == 1) plenty else 0L
        }
        val out = File(tmp.root, "trim-keyed.db")

        val form = BackupCrypto.decryptFileWithTrimFallback(v3, out, "  correct horse ", Long.MAX_VALUE)

        assertEquals(BackupCrypto.PassphraseForm.LEGACY_TRIMMED, form)
        assertArrayEquals(plain, out.readBytes())
        assertEquals("the precheck runs once for both attempts", 1, measurements)
    }

    @Test
    fun trimFallbackStillPrechecksBeforeTheFirstAttempt() {
        val v3Source = tmp.newFile().apply { writeBytes(payload(4 * segment)) }
        val v3 = File(tmp.root, "too-big-v3.vtdb")
        writeLegacyV3Backup(v3Source, v3, "correct horse")
        BackupCrypto.heapHeadroomBytes = { 0L }
        val out = File(tmp.root, "too-big.db")
        try {
            BackupCrypto.decryptFileWithTrimFallback(v3, out, "  correct horse ", Long.MAX_VALUE)
            fail("a v3 container without heap headroom must be refused")
        } catch (ex: BackupCrypto.InsufficientMemoryException) {
            // Expected: refused before either attempt.
        }
        assertFalse(out.exists())
    }

    @Test
    fun v4DecryptIsRefusedWhenNotEvenOneSegmentFits() {
        val container = encrypt(payload(100), segmentBytes = BackupSegmentStream.DEFAULT_SEGMENT_BYTES)
        BackupCrypto.heapHeadroomBytes = { 1024L }
        try {
            decrypt(container)
            fail("decrypt must refuse to start without room for a segment")
        } catch (ex: BackupCrypto.InsufficientMemoryException) {
            // Expected.
        }
    }
}
