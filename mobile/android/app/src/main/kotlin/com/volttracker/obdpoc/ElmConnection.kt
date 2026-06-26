package com.volttracker.obdpoc

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import androidx.annotation.VisibleForTesting
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * Owns the Bluetooth RFCOMM socket to the ELM327 adapter and the low-level command IO. Extracted
 * from [ObdService] so socket/stream handling is isolated and [transact] is unit-testable against
 * in-memory streams.
 *
 * Open for `ObdPollingEngineTest`, which subclasses this to script adapter responses without a real
 * RFCOMM socket.
 */
open class ElmConnection
    @JvmOverloads
    constructor(
        private var input: InputStream? = null,
        private var output: OutputStream? = null,
        private val clock: Clock = Clock { System.currentTimeMillis() },
        private val socketFactory: SocketFactory =
            SocketFactory { device, uuid ->
                BluetoothElmSocket(device.createRfcommSocketToServiceRecord(uuid))
            },
    ) {
        fun interface Clock {
            fun nowMs(): Long
        }

        fun interface SocketFactory {
            fun create(
                device: BluetoothDevice,
                uuid: UUID,
            ): ElmSocket
        }

        fun interface KeepWaiting {
            fun getAsBoolean(): Boolean
        }

        interface ElmSocket {
            val isConnected: Boolean
            val inputStream: InputStream
            val outputStream: OutputStream

            @Throws(IOException::class)
            fun connect()

            @Throws(IOException::class)
            fun close()
        }

        @JvmField var rfcommConnectMs: Long = -1L

        @JvmField var getStreamsMs: Long = -1L

        @JvmField var firstReadMs: Long = -1L

        @JvmField var lastErrorPhase: String = ""

        @Volatile @JvmField
        var watchdogFired: Boolean = false

        @JvmField var lastTransactTruncated: Boolean = false

        private var socket: ElmSocket? = null

        fun isOpen(): Boolean = input != null && output != null

        /**
         * Opens an RFCOMM socket to [device]. `BluetoothSocket.connect()` has no timeout and can
         * block indefinitely on a dead adapter, so a daemon watchdog closes the socket after
         * [connectTimeoutMs].
         *
         * The backticked name reads naturally at call sites (connection.open(...)) despite
         * colliding with Kotlin's soft keyword; not worth renaming a stable seam over.
         */
        @Suppress("FunctionNaming")
        @Throws(IOException::class)
        open fun `open`(
            device: BluetoothDevice,
            uuid: UUID,
            connectTimeoutMs: Long,
        ) {
            openSocket(socketFactory.create(device, uuid), connectTimeoutMs)
        }

        @Throws(IOException::class)
        @VisibleForTesting
        internal fun openSocketForTest(
            pendingSocket: ElmSocket,
            connectTimeoutMs: Long,
        ) {
            openSocket(pendingSocket, connectTimeoutMs)
        }

        private fun openSocket(
            pendingSocket: ElmSocket,
            connectTimeoutMs: Long,
        ) {
            rfcommConnectMs = -1L
            getStreamsMs = -1L
            firstReadMs = -1L
            lastErrorPhase = ""
            watchdogFired = false
            // Per-session state: a fresh socket must not inherit the previous session's
            // truncation verdict (readers consult it before the first transact completes).
            lastTransactTruncated = false

            socket = pendingSocket
            val watchdog =
                Thread {
                    try {
                        Thread.sleep(connectTimeoutMs)
                    } catch (ex: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return@Thread
                    }
                    if (!pendingSocket.isConnected) {
                        watchdogFired = true
                        try {
                            pendingSocket.close()
                        } catch (ex: IOException) {
                            OBDLog.warn("ElmConnection", "watchdog socket close failed: ${ex.message}")
                        }
                    }
                }
            watchdog.isDaemon = true
            watchdog.start()

            val connectStart = clock.nowMs()
            try {
                pendingSocket.connect()
                rfcommConnectMs = maxOf(0L, clock.nowMs() - connectStart)
            } catch (ex: IOException) {
                rfcommConnectMs = maxOf(0L, clock.nowMs() - connectStart)
                lastErrorPhase = "rfcomm_connect"
                throw ex
            } finally {
                watchdog.interrupt()
            }

            val streamsStart = clock.nowMs()
            try {
                input = socket?.inputStream
                output = socket?.outputStream
                getStreamsMs = maxOf(0L, clock.nowMs() - streamsStart)
            } catch (ex: IOException) {
                getStreamsMs = maxOf(0L, clock.nowMs() - streamsStart)
                lastErrorPhase = "get_streams"
                throw ex
            }
        }

        /** Result of a [wakeNudge] call, recorded onto the `wake_nudge` JSONL event. */
        class WakeNudgeResult(
            @JvmField val durationMs: Long,
            @JvmField val gotResponse: Boolean,
        )

        /**
         * Sends a single no-op carriage return and waits up to [toleranceMs] for any response byte.
         * Doubles as the first-read timing source.
         */
        @Throws(IOException::class)
        open fun wakeNudge(toleranceMs: Long): WakeNudgeResult {
            val out = output ?: throw IOException("Adapter stream is not open")
            val inputStream = input ?: throw IOException("Adapter stream is not open")
            val start = clock.nowMs()
            var gotResponse = false
            try {
                out.write('\r'.code)
                out.flush()
                val deadline = start + maxOf(0L, toleranceMs)
                val buffer = ByteArray(64)
                while (clock.nowMs() < deadline) {
                    val available = inputStream.available()
                    if (available > 0) {
                        val read = inputStream.read(buffer, 0, minOf(buffer.size, available))
                        if (read > 0) {
                            gotResponse = true
                            break
                        }
                    } else {
                        sleep(20)
                    }
                }
                firstReadMs = maxOf(0L, clock.nowMs() - start)
                return WakeNudgeResult(firstReadMs, gotResponse)
            } catch (ex: IOException) {
                firstReadMs = maxOf(0L, clock.nowMs() - start)
                lastErrorPhase = "first_read"
                throw ex
            }
        }

        /**
         * Writes [command] and reads the reply until the ELM `>` prompt, the timeout, or
         * [keepWaiting] going false.
         */
        @Throws(IOException::class)
        open fun transact(
            command: String,
            timeoutMs: Long,
            keepWaiting: KeepWaiting,
        ): String {
            val out = output ?: throw IOException("Adapter stream is not open")
            val inputStream = input ?: throw IOException("Adapter stream is not open")
            lastTransactTruncated = false
            drainInput()
            out.write((command + "\r").toByteArray(StandardCharsets.US_ASCII))
            out.flush()

            val response = StringBuilder()
            val startMs = clock.nowMs()
            val deadline = startMs + timeoutMs
            val buffer = ByteArray(128)
            var lastByteAtMs = startMs
            while (clock.nowMs() < deadline && keepWaiting.getAsBoolean()) {
                val available = inputStream.available()
                if (available > 0) {
                    val read = inputStream.read(buffer, 0, minOf(buffer.size, available))
                    if (read > 0) {
                        lastByteAtMs = clock.nowMs()
                        val chunk = String(buffer, 0, read, StandardCharsets.US_ASCII)
                        response.append(chunk)
                        if (chunk.indexOf('>') >= 0) {
                            break
                        }
                    }
                } else {
                    // The '>' prompt hasn't arrived. If a response already came in and the adapter has
                    // since gone quiet for longer than any normal inter-frame gap, the ELM327 v1.4b
                    // almost certainly dropped the prompt — stop here instead of burning the rest of
                    // the timeout, so the caller's prompt-recovery runs now (saves ~1 s+ per drop).
                    if (response.isNotEmpty() && clock.nowMs() - lastByteAtMs >= NO_PROMPT_QUIET_PERIOD_MS) {
                        break
                    }
                    sleep(25)
                }
            }
            val text = response.toString()
            lastTransactTruncated =
                text.isNotEmpty() &&
                text.indexOf('>') < 0 &&
                clock.nowMs() >= deadline
            return text
        }

        /** Sends the ELM escape byte and drains whatever the adapter echoes back. */
        @Throws(IOException::class)
        open fun sendEscape(settleMs: Long) {
            val out = output ?: return
            input ?: return
            out.write(0x1B)
            out.flush()
            sleep(settleMs)
            drainInput()
        }

        open fun close() {
            try {
                input?.close()
            } catch (ex: IOException) {
                OBDLog.warn("ElmConnection", "input stream close failed: ${ex.message}")
            }
            try {
                output?.close()
            } catch (ex: IOException) {
                OBDLog.warn("ElmConnection", "output stream close failed: ${ex.message}")
            }
            try {
                socket?.close()
            } catch (ex: IOException) {
                OBDLog.warn("ElmConnection", "socket close failed: ${ex.message}")
            }
            input = null
            output = null
            socket = null
            lastTransactTruncated = false
        }

        @Throws(IOException::class)
        private fun drainInput() {
            val inputStream = input ?: return
            val buffer = ByteArray(128)
            var drained = 0
            while (inputStream.available() > 0 && drained < 8192) {
                val read = inputStream.read(buffer, 0, minOf(buffer.size, inputStream.available()))
                if (read < 0) {
                    break
                }
                drained += read
            }
        }

        private companion object {
            // Once a response has arrived, this much continued silence with still no '>' prompt means
            // the ELM327 v1.4b dropped the prompt (a known quirk). It is far longer than a normal
            // inter-frame gap (<100 ms), so a legitimate slow multi-frame reply is not truncated; it
            // just lets prompt-recovery start ~1 s+ sooner than waiting out the full command timeout.
            const val NO_PROMPT_QUIET_PERIOD_MS = 250L

            fun sleep(millis: Long) {
                try {
                    Thread.sleep(millis)
                } catch (ex: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        }

        private class BluetoothElmSocket(
            private val delegate: BluetoothSocket,
        ) : ElmSocket {
            override val isConnected: Boolean
                get() = delegate.isConnected

            override val inputStream: InputStream
                get() = delegate.inputStream

            override val outputStream: OutputStream
                get() = delegate.outputStream

            override fun connect() {
                delegate.connect()
            }

            override fun close() {
                delegate.close()
            }
        }
    }
