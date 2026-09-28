package com.volttracker.obdpoc.map

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Supplies basemap tiles to the Compose Trips map. */
interface MapTileLoader {
    /** A tile already in memory, or null. Cheap: safe to call during composition. */
    fun cached(tile: TileId): ImageBitmap?

    /** The tile, from memory or the network; null on any failure. Blocking: call off the main thread. */
    fun load(tile: TileId): ImageBitmap?
}

/**
 * Loads Stadia Maps raster tiles ([StadiaTiles]) for the Compose map, cached in memory only
 * (shared across screens, bounded). Deliberately no disk cache: a persistent tile store would be
 * a location-revealing archive of everywhere the map was viewed (docs/privacy-data-handling.md).
 *
 * Every failure — no network, a non-200 status, a response that isn't an image, a body that
 * doesn't decode — yields null, and the map keeps its plain background for that tile. Only
 * successfully decoded tiles are cached.
 */
class StadiaTileLoader(
    private val key: String,
    private val memory: LruCache<TileId, ImageBitmap> = sharedMemory,
    private val connect: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
) : MapTileLoader {
    override fun cached(tile: TileId): ImageBitmap? = memory.get(tile)

    override fun load(tile: TileId): ImageBitmap? {
        memory.get(tile)?.let { return it }
        val bitmap = fetch(tile)?.let(::decode)
        bitmap?.let { memory.put(tile, it) }
        return bitmap
    }

    private fun fetch(tile: TileId): ByteArray? {
        val url = StadiaTiles.tileUrl(tile, key) ?: return null
        return try {
            val conn = connect(URL(url))
            try {
                conn.connectTimeout = TIMEOUT_MS
                conn.readTimeout = TIMEOUT_MS
                conn.setRequestProperty("User-Agent", USER_AGENT)
                val isImage = conn.contentType.orEmpty().startsWith("image/")
                if (conn.responseCode == HttpURLConnection.HTTP_OK && isImage) {
                    conn.inputStream.use { it.readBytes() }
                } else {
                    null
                }
            } finally {
                conn.disconnect()
            }
        } catch (_: IOException) {
            null
        }
    }

    private fun decode(bytes: ByteArray): ImageBitmap? =
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()

    companion object {
        private const val TIMEOUT_MS = 5_000
        private const val USER_AGENT = "VoltTracker-Android"
        private const val MEMORY_TILES = 48

        /** In-memory tiles shared by every loader, so reopening a trip redraws instantly. */
        val sharedMemory = LruCache<TileId, ImageBitmap>(MEMORY_TILES)

        /** The app's loader, or null when the build has no Stadia key — no tiles at all. */
        fun create(key: String = StadiaTiles.apiKey): StadiaTileLoader? =
            if (key.isBlank()) null else StadiaTileLoader(key)
    }
}
