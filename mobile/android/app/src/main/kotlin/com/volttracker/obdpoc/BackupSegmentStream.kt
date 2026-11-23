package com.volttracker.obdpoc

import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * One AEAD operation over a single segment of the v4 backup body. Split out from [Cipher] so a test
 * can wrap the real cipher and observe how much data each call is handed.
 */
internal interface SegmentAead {
    /** Encrypts [length] bytes of [input] into [output]; returns the bytes written (length + tag). */
    @Throws(GeneralSecurityException::class)
    fun seal(
        nonce: ByteArray,
        aad: ByteArray,
        input: ByteArray,
        length: Int,
        output: ByteArray,
    ): Int

    /** Verifies and decrypts [length] bytes of [input] into [output]; returns the plaintext length. */
    @Throws(GeneralSecurityException::class)
    fun open(
        nonce: ByteArray,
        aad: ByteArray,
        input: ByteArray,
        length: Int,
        output: ByteArray,
    ): Int
}

/** Platform AES/GCM, one complete `doFinal` per segment, so the cipher never holds more than one. */
internal class GcmSegmentAead(
    private val key: SecretKey,
) : SegmentAead {
    private val cipher = Cipher.getInstance("AES/GCM/NoPadding")

    override fun seal(
        nonce: ByteArray,
        aad: ByteArray,
        input: ByteArray,
        length: Int,
        output: ByteArray,
    ): Int = run(Cipher.ENCRYPT_MODE, nonce, aad, input, length, output)

    override fun open(
        nonce: ByteArray,
        aad: ByteArray,
        input: ByteArray,
        length: Int,
        output: ByteArray,
    ): Int = run(Cipher.DECRYPT_MODE, nonce, aad, input, length, output)

    private fun run(
        mode: Int,
        nonce: ByteArray,
        aad: ByteArray,
        input: ByteArray,
        length: Int,
        output: ByteArray,
    ): Int {
        cipher.init(mode, key, GCMParameterSpec(BackupSegmentStream.TAG_BITS, nonce))
        cipher.updateAAD(aad)
        return cipher.doFinal(input, 0, length, output, 0)
    }
}

/**
 * The segmented body of the v4 encrypted backup (ADR 0008). Android's AES/GCM cipher buffers all
 * input until `doFinal`, so a single GCM operation over a whole database holds it on the heap two to
 * three times over. v4 instead seals fixed-size plaintext segments, each with its own tag:
 *
 * - nonce = the file's random 8-byte prefix ‖ the segment index as a big-endian uint32, so a
 *   reordered, duplicated or dropped segment is opened under the wrong nonce and fails;
 * - AAD = the whole file header ‖ a last-segment flag (1 for the final segment, 0 otherwise), so a
 *   segment cannot move to another file, and a file cut at a segment boundary (or extended past its
 *   final segment) fails because the segment now read as last was not sealed as last.
 *
 * Every segment is written out before the next one is read, so memory stays at about one segment no
 * matter how large the backup is. The final segment is always present, holding 0 to
 * `segmentBytes` bytes of plaintext; a file whose length is an exact multiple carries no extra
 * empty segment.
 */
internal object BackupSegmentStream {
    const val TAG_BITS = 128
    const val TAG_BYTES = TAG_BITS / 8
    const val NONCE_PREFIX_BYTES = 8
    const val SEGMENT_SIZE_FIELD_BYTES = 4
    const val DEFAULT_SEGMENT_BYTES = 1 shl 20

    // Bounds on the header-supplied segment size, so a corrupt or hostile header can't make the
    // reader allocate an absurd buffer. The default sits inside.
    const val MIN_SEGMENT_BYTES = 1 shl 10
    const val MAX_SEGMENT_BYTES = 1 shl 24

    // Heap one segment needs while it is being sealed or opened: the plaintext and ciphertext
    // buffers here plus the platform cipher's internal copy and its working output.
    private const val WORKING_SET_SEGMENTS = 4
    private const val MAX_SEGMENT_INDEX = 0xFFFF_FFFFL
    private const val LAST_FLAG: Byte = 1
    private const val NOT_LAST_FLAG: Byte = 0

    fun workingSetBytes(segmentBytes: Int): Long = WORKING_SET_SEGMENTS.toLong() * (segmentBytes + TAG_BYTES)

    fun encodeSegmentSize(segmentBytes: Int): ByteArray =
        ByteBuffer.allocate(SEGMENT_SIZE_FIELD_BYTES).putInt(requireSegmentSize(segmentBytes)).array()

    @Throws(IOException::class)
    fun decodeSegmentSize(bytes: ByteArray): Int {
        val value = ByteBuffer.wrap(bytes).int
        if (value < MIN_SEGMENT_BYTES || value > MAX_SEGMENT_BYTES) {
            throw IOException("Encrypted backup header has an out-of-range segment size")
        }
        return value
    }

    private fun requireSegmentSize(segmentBytes: Int): Int {
        require(segmentBytes in MIN_SEGMENT_BYTES..MAX_SEGMENT_BYTES) { "segment size out of range: $segmentBytes" }
        return segmentBytes
    }

    /** Seals all of [input] as segments onto [out]; the caller has already written [header]. */
    @Throws(IOException::class, GeneralSecurityException::class)
    fun encrypt(
        input: InputStream,
        out: OutputStream,
        header: ByteArray,
        noncePrefix: ByteArray,
        segmentBytes: Int,
        aead: SegmentAead,
    ) {
        requireSegmentSize(segmentBytes)
        val aads = segmentAads(header)
        val plain = ByteArray(segmentBytes)
        val sealed = ByteArray(segmentBytes + TAG_BYTES)
        var index = 0L
        var lookahead = -1
        while (true) {
            throwIfInterrupted()
            val filled = fillSegment(input, plain, lookahead)
            lookahead = if (filled == plain.size) input.read() else -1
            val last = lookahead < 0
            val length = aead.seal(segmentNonce(noncePrefix, index), aads.forSegment(last), plain, filled, sealed)
            out.write(sealed, 0, length)
            if (last) return
            index = nextIndex(index)
        }
    }

    /**
     * Opens every segment of [input] onto [out], verifying each tag before its plaintext is written,
     * and returns the plaintext length. Throws once the plaintext passes [maxPlaintextBytes].
     */
    @Throws(IOException::class, GeneralSecurityException::class)
    fun decrypt(
        input: InputStream,
        out: OutputStream,
        header: ByteArray,
        noncePrefix: ByteArray,
        segmentBytes: Int,
        aead: SegmentAead,
        maxPlaintextBytes: Long,
    ): Long {
        requireSegmentSize(segmentBytes)
        val aads = segmentAads(header)
        val sealed = ByteArray(segmentBytes + TAG_BYTES)
        val plain = ByteArray(segmentBytes + TAG_BYTES)
        var index = 0L
        var lookahead = -1
        var written = 0L
        while (true) {
            throwIfInterrupted()
            val filled = fillSegment(input, sealed, lookahead)
            lookahead = if (filled == sealed.size) input.read() else -1
            val last = lookahead < 0
            if (filled < TAG_BYTES) {
                throw IOException("Encrypted backup is truncated")
            }
            val length = aead.open(segmentNonce(noncePrefix, index), aads.forSegment(last), sealed, filled, plain)
            written += length
            if (written > maxPlaintextBytes) {
                throw IOException("Restore file exceeds size limit")
            }
            out.write(plain, 0, length)
            if (last) return written
            index = nextIndex(index)
        }
    }

    /** Fills [buffer] from [input] (after the carried [lookahead] byte, if any) until full or EOF. */
    private fun fillSegment(
        input: InputStream,
        buffer: ByteArray,
        lookahead: Int,
    ): Int {
        var filled = 0
        if (lookahead >= 0) {
            buffer[0] = lookahead.toByte()
            filled = 1
        }
        while (filled < buffer.size) {
            val read = input.read(buffer, filled, buffer.size - filled)
            if (read < 0) break
            filled += read
        }
        return filled
    }

    private fun segmentNonce(
        noncePrefix: ByteArray,
        index: Long,
    ): ByteArray =
        ByteBuffer
            .allocate(NONCE_PREFIX_BYTES + Int.SIZE_BYTES)
            .put(noncePrefix)
            .putInt(index.toInt())
            .array()

    private fun nextIndex(index: Long): Long {
        if (index >= MAX_SEGMENT_INDEX) {
            throw IOException("Encrypted backup has too many segments")
        }
        return index + 1
    }

    private fun throwIfInterrupted() {
        if (Thread.currentThread().isInterrupted) {
            throw InterruptedIOException("Encrypted backup stream interrupted")
        }
    }

    private class SegmentAads(
        private val notLast: ByteArray,
        private val last: ByteArray,
    ) {
        fun forSegment(isLast: Boolean): ByteArray = if (isLast) last else notLast
    }

    private fun segmentAads(header: ByteArray): SegmentAads = SegmentAads(header + NOT_LAST_FLAG, header + LAST_FLAG)
}
