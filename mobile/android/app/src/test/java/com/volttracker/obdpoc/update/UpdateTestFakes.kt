package com.volttracker.obdpoc.update

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executor

// Test doubles for the updater's seams (connection, installer, executors), shared by
// UpdateManagerTest and UpdateCoordinatorTest.

/** Records what it was asked to install (bytes read at launch time); [opens] is the launch result. */
internal class RecordingInstaller(
    private val opens: Boolean = true,
) : ApkInstaller {
    val launches = mutableListOf<ByteArray>()

    override fun launch(apk: File): Boolean {
        launches += apk.readBytes()
        return opens
    }
}

/** Holds submitted jobs until [runAll], to model a busy worker or a backed-up main thread. */
internal class QueuedExecutor : Executor {
    private val jobs = ArrayDeque<Runnable>()

    override fun execute(command: Runnable) {
        jobs.addLast(command)
    }

    fun runAll() {
        while (jobs.isNotEmpty()) jobs.removeFirst().run()
    }
}

/**
 * A canned HTTP response. [advertisedLength] is the Content-Length header (-1 = absent);
 * [failure] is thrown when the status is read; [breakAfter] makes the body stream throw
 * after that many bytes, like a dropped connection.
 */
internal class FakeConnection(
    private val status: Int = HttpURLConnection.HTTP_OK,
    private val body: ByteArray = ByteArray(0),
    private val advertisedLength: Int = body.size,
    private val failure: IOException? = null,
    private val breakAfter: Int = -1,
) {
    var disconnected = false
    var acceptHeader: String? = null
    var userAgentHeader: String? = null

    fun at(url: URL): HttpURLConnection =
        object : HttpURLConnection(url) {
            override fun connect() {
                failure?.let { throw it }
            }

            override fun disconnect() {
                disconnected = true
                acceptHeader = getRequestProperty("Accept")
                userAgentHeader = getRequestProperty("User-Agent")
            }

            override fun usingProxy(): Boolean = false

            override fun getResponseCode(): Int {
                connect()
                return status
            }

            override fun getContentLength(): Int = advertisedLength

            override fun getInputStream(): InputStream =
                if (breakAfter < 0) ByteArrayInputStream(body) else BreakingStream(body, breakAfter)
        }
}

private class BreakingStream(
    body: ByteArray,
    private val breakAfter: Int,
) : InputStream() {
    private val source = ByteArrayInputStream(body)
    private var served = 0

    override fun read(): Int {
        if (served >= breakAfter) throw IOException("connection reset")
        served++
        return source.read()
    }

    override fun read(
        b: ByteArray,
        off: Int,
        len: Int,
    ): Int {
        if (served >= breakAfter) throw IOException("connection reset")
        val n = source.read(b, off, minOf(len, breakAfter - served))
        if (n > 0) served += n
        return n
    }
}
