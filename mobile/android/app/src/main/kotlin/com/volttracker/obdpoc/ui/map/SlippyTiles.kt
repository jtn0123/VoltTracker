package com.volttracker.obdpoc.ui.map

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.core.graphics.createBitmap
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

/** A geographic point on the route. */
data class LatLng(
    val lat: Double,
    val lon: Double,
)

/**
 * Maps lat/lon to pixel offsets inside a composed map bitmap
 * (Web-Mercator "slippy map" pixel space at a fixed zoom).
 */
class RouteProjection(
    private val zoom: Int,
    private val originX: Double,
    private val originY: Double,
    private val tileSizePx: Int,
) {
    fun offsetOf(
        lat: Double,
        lon: Double,
    ): Offset {
        val scale = (1 shl zoom).toDouble() * tileSizePx
        val x = SlippyMath.worldX(lon) * scale - originX
        val y = SlippyMath.worldY(lat) * scale - originY
        return Offset(x.toFloat(), y.toFloat())
    }
}

/** A stitched basemap bitmap plus the projection that places the route on it. */
data class ComposedMap(
    val bitmap: Bitmap,
    val projection: RouteProjection,
)

/** Web-Mercator world-coordinate math (0..1 across the whole world). */
object SlippyMath {
    fun worldX(lon: Double): Double = (lon + 180.0) / 360.0

    fun worldY(lat: Double): Double {
        val latRad = Math.toRadians(lat.coerceIn(-85.05, 85.05))
        return (1.0 - ln(tan(latRad) + 1.0 / kotlin.math.cos(latRad)) / PI) / 2.0
    }
}

/**
 * Composes a static basemap for a route's bounding box by stitching raster
 * tiles from the SAME provider the legacy Leaflet dashboard used
 * (CARTO dark_all, © OpenStreetMap © CARTO). Blocking network I/O — call
 * off the main thread in the app; screenshot tests call it directly.
 */
object MapTileCompositor {
    private const val TILE_PX = 512
    private const val MIN_ZOOM = 3
    private const val MAX_ZOOM = 17
    private const val PAD_FRACTION = 0.14
    private const val PER_TILE_TIMEOUT_MS = 4_000
    private const val TOTAL_BUDGET_MS = 12_000L
    private val subdomains = listOf("a", "b", "c", "d")

    fun compose(
        points: List<LatLng>,
        widthPx: Int,
        heightPx: Int,
    ): ComposedMap? {
        if (points.size < 2 || widthPx <= 0 || heightPx <= 0) return null
        val xs = points.map { SlippyMath.worldX(it.lon) }
        val ys = points.map { SlippyMath.worldY(it.lat) }
        val padX = ((xs.max() - xs.min()) * PAD_FRACTION).coerceAtLeast(1e-7)
        val padY = ((ys.max() - ys.min()) * PAD_FRACTION).coerceAtLeast(1e-7)
        val minX = xs.min() - padX
        val maxX = xs.max() + padX
        val minY = ys.min() - padY
        val maxY = ys.max() + padY

        var zoom = MAX_ZOOM
        while (zoom > MIN_ZOOM) {
            val scale = (1 shl zoom).toDouble() * TILE_PX
            if ((maxX - minX) * scale <= widthPx && (maxY - minY) * scale <= heightPx) break
            zoom--
        }
        val scale = (1 shl zoom).toDouble() * TILE_PX
        val originX = ((minX + maxX) / 2.0) * scale - widthPx / 2.0
        val originY = ((minY + maxY) / 2.0) * scale - heightPx / 2.0

        val bitmap = createBitmap(widthPx, heightPx)
        val canvas = Canvas(bitmap)
        canvas.drawColor(0xFF0A0A0E.toInt())

        val maxTileIndex = (1 shl zoom) - 1
        val txFrom = floor(originX / TILE_PX).toInt()
        val txTo = floor((originX + widthPx) / TILE_PX).toInt()
        val tyFrom = floor(originY / TILE_PX).toInt()
        val tyTo = floor((originY + heightPx) / TILE_PX).toInt()
        var drawn = 0
        // Wall-clock budget for the whole grid: a slow CDN degrades to the
        // offline fallback quickly instead of serially eating N x timeout.
        val deadline = System.nanoTime() + TOTAL_BUDGET_MS * 1_000_000
        for (tx in txFrom..txTo) {
            for (ty in tyFrom..tyTo) {
                if (tx < 0 || ty < 0 || tx > maxTileIndex || ty > maxTileIndex) continue
                if (System.nanoTime() > deadline) break
                val tile = fetchTile(zoom, tx, ty) ?: continue
                val left = (tx * TILE_PX - originX).toFloat()
                val top = (ty * TILE_PX - originY).toFloat()
                canvas.drawBitmap(
                    tile,
                    null,
                    android.graphics.RectF(left, top, left + TILE_PX, top + TILE_PX),
                    null,
                )
                drawn++
            }
        }
        if (drawn == 0) return null
        return ComposedMap(bitmap, RouteProjection(zoom, originX, originY, TILE_PX))
    }

    private fun fetchTile(
        zoom: Int,
        tx: Int,
        ty: Int,
    ): Bitmap? =
        try {
            val sub = subdomains[(tx + ty) % subdomains.size]
            // @2x raster (512px) for crisp rendering on dense screens; same
            // dark_all style + attribution the legacy WebView map shipped.
            val url = URL("https://$sub.basemaps.cartocdn.com/dark_all/$zoom/$tx/$ty@2x.png")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = PER_TILE_TIMEOUT_MS
            conn.readTimeout = PER_TILE_TIMEOUT_MS
            conn.setRequestProperty("User-Agent", "VoltTracker-Android")
            conn.inputStream.use { BitmapFactory.decodeStream(it) }
        } catch (_: java.io.IOException) {
            null
        }
}
