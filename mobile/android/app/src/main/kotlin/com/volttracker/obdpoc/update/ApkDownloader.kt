package com.volttracker.obdpoc.update

import android.util.Log
import com.volttracker.obdpoc.AppPrefs
import com.volttracker.obdpoc.data.deleteOrLog
import com.volttracker.obdpoc.update.UpdateManager.InstallFailure
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.URL

/**
 * Streams a release APK into [updatesDir] and proves it is installable — complete, and this
 * app signed with this key — before anything reaches the installer. Runs on the caller's
 * (background) thread. Every failure comes back as a classified [Result.Failed] instead of an
 * exception, so the UI can say what went wrong and what to do about it.
 */
internal class ApkDownloader(
    private val updatesDir: File,
    /** The default opens a real connection; tests serve canned bytes and status codes. */
    private val openConnection: (URL) -> HttpURLConnection,
    private val verifyApk: (File) -> Boolean,
) {
    sealed interface Result {
        /** A complete APK that passed the signer check. */
        data class Ready(
            val apk: File,
        ) : Result

        data class Failed(
            val reason: InstallFailure,
        ) : Result
    }

    /** Downloads [build]; [onProgress] gets 0..99 while bytes arrive (never 100). */
    fun download(
        build: UpdateFeed.AvailableBuild,
        onProgress: (Int) -> Unit,
    ): Result {
        // Comes from the GitHub API response, not from the app: the bytes must not travel
        // over cleartext.
        val url = httpsUrlOrNull(build.downloadUrl) ?: return failure(InstallFailure.SERVER, "unusable download url")
        val destination = prepareDestination(build) ?: return failure(InstallFailure.STORAGE, "no updates dir")
        val result =
            try {
                fetchInto(url, build, destination, onProgress)
            } catch (ex: StorageWriteException) {
                failure(InstallFailure.STORAGE, "could not write the download", ex)
            } catch (ex: IOException) {
                failure(InstallFailure.NETWORK, "download interrupted", ex)
            } catch (ex: RuntimeException) {
                failure(InstallFailure.UNEXPECTED, "download failed", ex)
            }
        // Never leave a partial or rejected file where a later step could mistake it for a
        // good download.
        if (result is Result.Failed) destination.deleteOrLog()
        return result
    }

    private fun httpsUrlOrNull(raw: String): URL? {
        val url =
            try {
                URL(raw)
            } catch (_: MalformedURLException) {
                return null
            }
        return url.takeIf { it.protocol.equals("https", ignoreCase = true) }
    }

    /**
     * Anything downloaded earlier is thrown away first: a half-finished file from a cancelled
     * attempt must never be offered to the installer as though it were complete, and stale
     * 20 MB APKs add up.
     */
    private fun prepareDestination(build: UpdateFeed.AvailableBuild): File? {
        updatesDir.deleteRecursively()
        if (!updatesDir.mkdirs()) return null
        // The asset name comes from GitHub too, so it must not path-traverse out of the dir.
        return File(updatesDir, UpdateFeed.safeAssetFileName(build.assetName))
    }

    private fun fetchInto(
        url: URL,
        build: UpdateFeed.AvailableBuild,
        destination: File,
        onProgress: (Int) -> Unit,
    ): Result {
        val conn = openConnection(url)
        try {
            conn.connectTimeout = UpdateManager.CHECK_TIMEOUT_MS
            conn.readTimeout = UpdateManager.DOWNLOAD_READ_TIMEOUT_MS
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("Accept", "application/octet-stream")
            conn.setRequestProperty("User-Agent", UpdateManager.USER_AGENT)
            val status = conn.responseCode
            if (status != HttpURLConnection.HTTP_OK) {
                return failure(InstallFailure.SERVER, "download answered $status")
            }
            // Content-Length may be absent; the release already told us the size.
            // (contentLength, not contentLengthLong: the Long variant is API 24+
            // and minSdk is 23 — an APK will not overflow Int.)
            val total = conn.contentLength.toLong().takeIf { it > 0 } ?: build.sizeBytes.takeIf { it > 0 } ?: -1L
            val output =
                try {
                    destination.outputStream()
                } catch (ex: IOException) {
                    throw StorageWriteException(ex)
                }
            output.use { out -> conn.inputStream.use { input -> copyWithProgress(input, out, total, onProgress) } }
            // A stream that ends early "succeeds" as far as copy is concerned;
            // a truncated APK must fail here, not as an installer parse error.
            if (total > 0 && destination.length() != total) {
                return failure(InstallFailure.INCOMPLETE, "download was ${destination.length()} of $total bytes")
            }
            if (!verifyApk(destination)) {
                return failure(InstallFailure.NOT_THIS_APP, "downloaded APK is not this app or not signed with its key")
            }
            return Result.Ready(destination)
        } finally {
            conn.disconnect()
        }
    }

    private fun failure(
        reason: InstallFailure,
        detail: String,
        cause: Exception? = null,
    ): Result.Failed {
        Log.w(AppPrefs.LOG_TAG, "update download failed ($reason): $detail", cause)
        return Result.Failed(reason)
    }

    /** A write to the destination failed (full or unwritable storage), as opposed to the read side. */
    internal class StorageWriteException(
        cause: IOException,
    ) : IOException(cause)

    internal companion object {
        const val BUFFER_BYTES = 64 * 1024

        fun copyWithProgress(
            input: InputStream,
            output: OutputStream,
            totalBytes: Long,
            onProgress: (Int) -> Unit,
        ) {
            val buffer = ByteArray(BUFFER_BYTES)
            var written = 0L
            var lastPct = -1
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                try {
                    output.write(buffer, 0, read)
                } catch (ex: IOException) {
                    throw StorageWriteException(ex)
                }
                written += read
                if (totalBytes <= 0) continue
                // Capped: a stream longer than the advertised size must not read as "done".
                val pct = ((written * 99) / totalBytes).toInt().coerceAtMost(99)
                if (pct != lastPct) {
                    lastPct = pct
                    onProgress(pct)
                }
            }
        }
    }
}
