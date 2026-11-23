package com.volttracker.obdpoc

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.GeneralSecurityException
import java.util.Random
import javax.crypto.spec.SecretKeySpec

/**
 * Pins the segmented AES/GCM body of the v4 backup container ([BackupSegmentStream]) with a fixed
 * key, so each case runs without the 600k-iteration KDF: round trips at the segment boundaries,
 * every rearrangement of segments failing closed, and memory staying at about one segment.
 */
class BackupSegmentStreamTest {
    private val segment = 1024
    private val tag = BackupSegmentStream.TAG_BYTES
    private val key = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")
    private val prefix = ByteArray(BackupSegmentStream.NONCE_PREFIX_BYTES) { (0x40 + it).toByte() }
    private val header = "synthetic-header".toByteArray(Charsets.US_ASCII)

    private fun payload(size: Int): ByteArray = ByteArray(size).also { Random(size.toLong()).nextBytes(it) }

    private fun seal(
        plain: ByteArray,
        noncePrefix: ByteArray = prefix,
        aad: ByteArray = header,
        segmentBytes: Int = segment,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        BackupSegmentStream.encrypt(
            ByteArrayInputStream(plain),
            out,
            aad,
            noncePrefix,
            segmentBytes,
            GcmSegmentAead(key),
        )
        return out.toByteArray()
    }

    private fun open(
        body: ByteArray,
        noncePrefix: ByteArray = prefix,
        aad: ByteArray = header,
        segmentBytes: Int = segment,
        maxPlaintextBytes: Long = Long.MAX_VALUE,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        BackupSegmentStream.decrypt(
            ByteArrayInputStream(body),
            out,
            aad,
            noncePrefix,
            segmentBytes,
            GcmSegmentAead(key),
            maxPlaintextBytes,
        )
        return out.toByteArray()
    }

    /** The sealed segments of [body], in order. */
    private fun segmentsOf(body: ByteArray): List<ByteArray> =
        body.toList().chunked(segment + tag).map { it.toByteArray() }

    private fun join(parts: List<ByteArray>): ByteArray = parts.fold(ByteArray(0)) { acc, part -> acc + part }

    private fun assertFailsClosed(
        what: String,
        body: ByteArray,
        noncePrefix: ByteArray = prefix,
        aad: ByteArray = header,
    ) {
        try {
            open(body, noncePrefix, aad)
            fail("$what must not decrypt")
        } catch (ex: GeneralSecurityException) {
            // Expected: a segment's tag does not verify under the nonce / AAD it is read with.
        } catch (ex: IOException) {
            // Expected: too short to hold even a tag.
        }
    }

    @Test
    fun roundTripsAtEverySegmentBoundary() {
        val sizes = listOf(0, 1, segment - 1, segment, segment + 1, 2 * segment, 37 * segment + 5)
        for (size in sizes) {
            val plain = payload(size)
            val body = seal(plain)
            val expectedSegments = maxOf(1, (size + segment - 1) / segment)
            assertEquals(
                "ciphertext for $size bytes carries one tag per segment",
                size + expectedSegments * tag,
                body.size,
            )
            assertArrayEquals("$size bytes must round-trip", plain, open(body))
        }
    }

    @Test
    fun roundTripsManySegmentsAtTheDefaultSegmentSize() {
        val size = 2 * BackupSegmentStream.DEFAULT_SEGMENT_BYTES + 3
        val plain = payload(size)
        val body = seal(plain, segmentBytes = BackupSegmentStream.DEFAULT_SEGMENT_BYTES)
        assertEquals(size + 3 * tag, body.size)
        assertArrayEquals(plain, open(body, segmentBytes = BackupSegmentStream.DEFAULT_SEGMENT_BYTES))
    }

    @Test
    fun flippingAByteInAnySegmentFailsClosed() {
        val body = seal(payload(3 * segment + 10))
        var offset = 0
        for (part in segmentsOf(body)) {
            val tampered = body.copyOf()
            val at = offset + part.size / 2
            tampered[at] = (tampered[at].toInt() xor 0x01).toByte()
            assertFailsClosed("a flipped byte at $at", tampered)
            offset += part.size
        }
    }

    @Test
    fun reorderedSegmentsFailClosed() {
        val parts = segmentsOf(seal(payload(3 * segment + 10)))
        assertFailsClosed("swapped segments 0 and 1", join(listOf(parts[1], parts[0], parts[2], parts[3])))
    }

    @Test
    fun duplicatedSegmentFailsClosed() {
        val parts = segmentsOf(seal(payload(3 * segment + 10)))
        assertFailsClosed("a repeated segment", join(listOf(parts[0], parts[0], parts[1], parts[2], parts[3])))
    }

    @Test
    fun droppingTheFinalSegmentFailsClosed() {
        // Exact multiple: every segment is full, so a cut at a segment boundary still parses.
        val parts = segmentsOf(seal(payload(3 * segment)))
        assertEquals(3, parts.size)
        assertFailsClosed("the file without its final segment", join(parts.dropLast(1)))
        val partial = segmentsOf(seal(payload(3 * segment + 10)))
        assertFailsClosed("the file without its short final segment", join(partial.dropLast(1)))
    }

    @Test
    fun droppingAMiddleSegmentFailsClosed() {
        val parts = segmentsOf(seal(payload(3 * segment + 10)))
        assertFailsClosed("the file without segment 1", join(listOf(parts[0], parts[2], parts[3])))
    }

    @Test
    fun truncationInsideASegmentFailsClosed() {
        val body = seal(payload(2 * segment + 100))
        assertFailsClosed("a file cut mid-segment", body.copyOf(body.size - 7))
        assertFailsClosed("a file cut inside the first segment", body.copyOf(segment / 2))
        assertFailsClosed("a file with no segments at all", ByteArray(0))
        assertFailsClosed("a file shorter than a tag", body.copyOf(tag - 1))
    }

    @Test
    fun bytesAppendedAfterTheFinalSegmentFailClosed() {
        val exact = seal(payload(2 * segment))
        assertFailsClosed("a full final segment followed by extra bytes", exact + byteArrayOf(1, 2, 3))
        val parts = segmentsOf(exact)
        assertFailsClosed("the final segment repeated", exact + parts.last())
    }

    @Test
    fun segmentsDoNotMoveBetweenFilesOrHeaders() {
        val plain = payload(2 * segment + 10)
        val first = segmentsOf(seal(plain))
        val otherPrefix = prefix.copyOf().also { it[0] = 0x7F }
        val second = segmentsOf(seal(plain, noncePrefix = otherPrefix))
        assertFailsClosed("a segment from another file", join(listOf(first[0], second[1], first[2])))
        assertFailsClosed("the body under another file's nonce prefix", join(first), noncePrefix = otherPrefix)
        val otherHeader = header.copyOf().also { it[0] = 'X'.code.toByte() }
        assertFailsClosed("the body under a different header", join(first), aad = otherHeader)
    }

    @Test
    fun plaintextCapStopsTheRestore() {
        val body = seal(payload(3 * segment))
        try {
            open(body, maxPlaintextBytes = 2L * segment)
            fail("output past the cap must throw")
        } catch (ex: IOException) {
            assertTrue(ex.message!!.contains("size limit"))
        }
    }

    @Test
    fun segmentSizeFieldRejectsOutOfRangeValues() {
        for (bad in listOf(
            0,
            BackupSegmentStream.MIN_SEGMENT_BYTES - 1,
            BackupSegmentStream.MAX_SEGMENT_BYTES + 1,
            -1,
        )) {
            val field =
                java.nio.ByteBuffer
                    .allocate(4)
                    .putInt(bad)
                    .array()
            try {
                BackupSegmentStream.decodeSegmentSize(field)
                fail("segment size $bad must be rejected")
            } catch (ex: IOException) {
                assertTrue(ex.message!!.contains("segment size"))
            }
        }
        val good = BackupSegmentStream.encodeSegmentSize(segment)
        assertEquals(segment, BackupSegmentStream.decodeSegmentSize(good))
    }

    // ---- bounded memory ------------------------------------------------------------------------

    /** Counts what flows through so a test can see how far reading runs ahead of writing. */
    private class Counter {
        var read = 0L
        var written = 0L
    }

    private class CountingInput(
        private val delegate: InputStream,
        private val counter: Counter,
    ) : InputStream() {
        override fun read(): Int = delegate.read().also { if (it >= 0) counter.read += 1 }

        override fun read(
            b: ByteArray,
            off: Int,
            len: Int,
        ): Int = delegate.read(b, off, len).also { if (it > 0) counter.read += it }
    }

    private class CountingOutput(
        private val counter: Counter,
    ) : OutputStream() {
        override fun write(b: Int) {
            counter.written += 1
        }

        override fun write(
            b: ByteArray,
            off: Int,
            len: Int,
        ) {
            counter.written += len
        }
    }

    /**
     * Wraps the real GCM segment cipher and records, at every call, the size it was handed and how
     * many bytes had been read and written so far.
     */
    private class CountingAead(
        private val delegate: SegmentAead,
        private val counter: Counter,
    ) : SegmentAead {
        val calls = ArrayList<Triple<Int, Long, Long>>()

        override fun seal(
            nonce: ByteArray,
            aad: ByteArray,
            input: ByteArray,
            length: Int,
            output: ByteArray,
        ): Int {
            calls.add(Triple(length, counter.read, counter.written))
            return delegate.seal(nonce, aad, input, length, output)
        }

        override fun open(
            nonce: ByteArray,
            aad: ByteArray,
            input: ByteArray,
            length: Int,
            output: ByteArray,
        ): Int {
            calls.add(Triple(length, counter.read, counter.written))
            return delegate.open(nonce, aad, input, length, output)
        }
    }

    /**
     * Encrypting holds at most one segment: each call is handed at most one segment, and by the time
     * segment k is sealed every earlier segment is already written out and no more than one segment
     * (plus a one-byte lookahead) has been read past them.
     */
    @Test
    fun encryptHoldsAtMostOneSegmentAtATime() {
        val segments = 50
        val plain = payload(segments * segment)
        val counter = Counter()
        val aead = CountingAead(GcmSegmentAead(key), counter)

        BackupSegmentStream.encrypt(
            CountingInput(ByteArrayInputStream(plain), counter),
            CountingOutput(counter),
            header,
            prefix,
            segment,
            aead,
        )

        assertEquals(segments, aead.calls.size)
        aead.calls.forEachIndexed { k, (length, read, written) ->
            assertTrue("call $k was handed $length bytes", length <= segment)
            assertEquals("segments before $k must already be written", k.toLong() * (segment + tag), written)
            assertTrue("call $k ran $read bytes ahead", read <= (k + 1L) * segment + 1)
        }
        assertEquals(plain.size.toLong(), counter.read)
        assertEquals(plain.size.toLong() + segments * tag, counter.written)
    }

    /** Decrypting holds at most one sealed segment, writing each before reading the next. */
    @Test
    fun decryptHoldsAtMostOneSegmentAtATime() {
        val segments = 50
        val plain = payload(segments * segment - 3)
        val body = seal(plain)
        val counter = Counter()
        val aead = CountingAead(GcmSegmentAead(key), counter)

        BackupSegmentStream.decrypt(
            CountingInput(ByteArrayInputStream(body), counter),
            CountingOutput(counter),
            header,
            prefix,
            segment,
            aead,
            Long.MAX_VALUE,
        )

        assertEquals(segments, aead.calls.size)
        aead.calls.forEachIndexed { k, (length, read, written) ->
            assertTrue("call $k was handed $length bytes", length <= segment + tag)
            assertEquals("plaintext before $k must already be written", k.toLong() * segment, written)
            assertTrue("call $k ran $read bytes ahead", read <= (k + 1L) * (segment + tag) + 1)
        }
        assertEquals(plain.size.toLong(), counter.written)
    }
}
