package com.volttracker.obdpoc.data

import android.util.Log
import java.io.File

/**
 * Best-effort delete for temp, export and backup leftovers: true once [this] is gone (deleted or
 * never there). A file that survives is logged rather than ignored, so a stuck file shows up in
 * the app log instead of silently filling the cache.
 */
fun File.deleteOrLog(): Boolean {
    if (delete() || !exists()) return true
    Log.w(LOG_TAG, "could not delete $name")
    return false
}

private const val LOG_TAG = "FileCleanup"
