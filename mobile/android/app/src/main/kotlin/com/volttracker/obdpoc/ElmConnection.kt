package com.volttracker.obdpoc

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
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
    ) {
        fun interface Clock {
            fun nowMs(): Long
        }

        fun interface KeepWaiting {
            fun getAsBoolean(): Boolean
        }

        @JvmField var rfcommConnectMs: Long = -1L

        @JvmField var getStreamsMs: Long = -1L

        @JvmField var firstReadMs: Long = -1L

        @JvmField var lastErrorPhase: String = ""

        @Volatile @JvmField
        var watchdogFired: Boolean = false

        @JvmField var lastTransactTruncated: Boolean = false

        private var socket: BluetoothSocket? = null

        fun isOpen(): Boolean = input != null && output != null

        /**
         * Opens an RFCOMM socket to [device]. `BluetoothSocket.connect()` has no timeout and can
         * block indefinitely on a dead adapter, so a daemon watchdog closes the socket after
         * [connectTimeoutMs].
         */
        @Throws(IOException::class)
        open fun `open`(
            device: BluetoothDevice,
            uuid: UUID,
            connectTimeoutMs: Long,
        ) {
            rfcommConnectMs = -1L
            getStreamsMs = -1L
            firstReadMs = -1L
            lastErrorPhase = ""
            watchdogFired = false

            val pendingSocket = device.createRfcommSocketToServiceRecord(uuid)
            socket = pendingSocket
            val watchdog =
                Thread {
                    try {
                        Thread.sleep(connectTimeoutMs)
                    } catch (_: InterruptedException) {
                        return@Thread
                    }
                    if (!pendingSocket.isConnected) {
                        watchdogFired = true
                        try {
                            pendingSocket.close()
                        } catch (_: IOException) {
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
            val deadline = clock.nowMs() + timeoutMs
            val buffer = ByteArray(128)
            while (clock.nowMs() < deadline && keepWaiting.getAsBoolean()) {
                val available = inputStream.available()
                if (available > 0) {
                    val read = inputStream.read(buffer, 0, minOf(buffer.size, available))
                    if (read > 0) {
                        val chunk = String(buffer, 0, read, StandardCharsets.US_ASCII)
                        response.append(chunk)
                        if (chunk.indexOf('>') >= 0) {
                            break
                        }
                    }
                } else {
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
            } catch (_: IOException) {
            }
            try {
                output?.close()
            } catch (_: IOException) {
            }
            try {
                socket?.close()
            } catch (_: IOException) {
            }
            input = null
            output = null
            socket = null
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
            fun sleep(millis: Long) {
                try {
                    Thread.sleep(millis)
                } catch (ex: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        }
    }
