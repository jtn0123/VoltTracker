package com.volttracker.obdpoc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Drives {@link ElmConnection#transact} against in-memory streams — no Bluetooth needed. */
public class ElmConnectionTest {

    @Test
    public void transactWritesCommandAndReadsUntilPrompt() throws IOException {
        ReplyStream in = new ReplyStream("41 0C 1880\r>");
        TriggerOutputStream out = new TriggerOutputStream(in);
        ElmConnection connection = new ElmConnection(in, out);

        String response = connection.transact("010C", 1000L, () -> true);

        assertEquals("41 0C 1880\r>", response);
        // the command is written with a trailing carriage return
        assertEquals("010C\r", out.toString("US-ASCII"));
    }

    @Test
    public void transactReturnsImmediatelyWhenKeepWaitingIsFalse() throws IOException {
        ElmConnection connection = new ElmConnection(
                new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream());

        long start = System.currentTimeMillis();
        String response = connection.transact("010C", 10_000L, () -> false);

        assertEquals("", response);
        assertTrue("must not block on the timeout", System.currentTimeMillis() - start < 1_000L);
    }

    @Test
    public void transactThrowsWhenStreamIsNotOpen() {
        try {
            new ElmConnection().transact("010C", 1000L, () -> true);
            fail("expected IOException for an unopened connection");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("not open"));
        }
    }

    /** Yields its bytes only once {@link #release()} is called — i.e. after the request. */
    private static final class ReplyStream extends InputStream {
        private final byte[] data;
        private int pos;
        private boolean released;

        ReplyStream(String reply) {
            data = reply.getBytes(StandardCharsets.US_ASCII);
        }

        void release() {
            released = true;
        }

        @Override
        public int available() {
            return released ? data.length - pos : 0;
        }

        @Override
        public int read() {
            if (!released || pos >= data.length) {
                return -1;
            }
            return data[pos++] & 0xFF;
        }
    }

    /** Releases the paired {@link ReplyStream} once the command has been written. */
    private static final class TriggerOutputStream extends ByteArrayOutputStream {
        private final ReplyStream reply;

        TriggerOutputStream(ReplyStream reply) {
            this.reply = reply;
        }

        @Override
        public void write(byte[] b) {
            super.write(b, 0, b.length);
            reply.release();
        }

        @Override
        public void write(byte[] b, int off, int len) {
            super.write(b, off, len);
            reply.release();
        }

        @Override
        public void write(int b) {
            super.write(b);
            reply.release();
        }
    }
}
