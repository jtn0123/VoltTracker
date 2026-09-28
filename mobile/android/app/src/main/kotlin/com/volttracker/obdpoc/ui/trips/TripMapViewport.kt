package com.volttracker.obdpoc.ui.trips

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.tan

/**
 * The Trips map's view: Web-Mercator ("slippy map") world coordinates, 0..1 across the world,
 * scaled by [scale] px and shifted by ([offsetX], [offsetY]) into the card. The route and the
 * basemap tiles are both placed through it, so the track lines up with the streets under it.
 *
 * Tiles come from the integer [zoom] whose native size is closest below the fitted scale, drawn
 * [tilePx] wide (between one and two nominal tile sizes), so any fit is covered without gaps.
 */
class MapViewport(
    val scale: Double,
    val offsetX: Double,
    val offsetY: Double,
    val size: Size,
    tileSizePx: Float,
) {
    /** The tile zoom level for this scale. */
    val zoom: Int = floor(log2(scale / tileSizePx)).toInt().coerceIn(0, MAX_ZOOM)

    /** Drawn width (and height) of one tile at [zoom], in px. */
    val tilePx: Double = scale / 2.0.pow(zoom)

    /** Where a point lands in the card. */
    fun project(
        lat: Double,
        lon: Double,
    ): Offset = Offset((worldX(lon) * scale + offsetX).toFloat(), (worldY(lat) * scale + offsetY).toFloat())

    fun project(p: TripPoint): Offset = project(p.lat, p.lon)

    /** Every tile at [zoom] that overlaps the card, with where to draw it. */
    fun tiles(): List<TileSlot> {
        val last = (1 shl zoom) - 1
        val xs = tileRange(offsetX, size.width, last)
        val ys = tileRange(offsetY, size.height, last)
        return xs.flatMap { x ->
            ys.map { y ->
                TileSlot(
                    zoom,
                    x,
                    y,
                    Offset((offsetX + x * tilePx).toFloat(), (offsetY + y * tilePx).toFloat()),
                    tilePx.toFloat(),
                )
            }
        }
    }

    private fun tileRange(
        offset: Double,
        extent: Float,
        last: Int,
    ): IntRange {
        val first = floor(-offset / tilePx).toInt().coerceIn(0, last)
        val end = floor((extent - offset) / tilePx).toInt().coerceIn(0, last)
        return first..end
    }

    companion object {
        /** Deepest tile zoom used; a very short track stops zooming in here. */
        const val MAX_ZOOM = 18

        /** Nominal on-screen size of one tile, in dp (the @2x image is drawn at this size). */
        const val TILE_DP = 256

        fun worldX(lon: Double): Double = (lon + 180.0) / 360.0

        fun worldY(lat: Double): Double {
            val rad = Math.toRadians(lat.coerceIn(-MAX_LAT, MAX_LAT))
            return (1.0 - ln(tan(rad) + 1.0 / cos(rad)) / PI) / 2.0
        }

        private const val MAX_LAT = 85.05112878
    }
}

/** One basemap tile's place in the card: tile ([z], [x], [y]) drawn at [topLeft], [sizePx] square. */
data class TileSlot(
    val z: Int,
    val x: Int,
    val y: Int,
    val topLeft: Offset,
    val sizePx: Float,
)

/**
 * Fits [points] into [size] with the given side / top / bottom padding, keeping the track's
 * shape (Web Mercator, as the tiles are) and centring it in the space left. The scale stops at
 * [MapViewport.MAX_ZOOM] so a near-stationary track doesn't zoom in without limit.
 */
internal fun fitViewport(
    points: List<TripPoint>,
    size: Size,
    padSide: Float,
    padTop: Float,
    padBottom: Float,
    tileSizePx: Float,
): MapViewport {
    val xs = points.map { MapViewport.worldX(it.lon) }
    val ys = points.map { MapViewport.worldY(it.lat) }
    val minX = xs.min()
    val maxX = xs.max()
    val minY = ys.min()
    val maxY = ys.max()
    val width = (size.width - 2 * padSide).coerceAtLeast(1f)
    val height = (size.height - padTop - padBottom).coerceAtLeast(1f)
    val maxScale = tileSizePx * 2.0.pow(MapViewport.MAX_ZOOM)
    val scale =
        min(
            min(
                width / (maxX - minX).coerceAtLeast(MIN_WORLD_SPAN),
                height / (maxY - minY).coerceAtLeast(MIN_WORLD_SPAN),
            ),
            maxScale,
        )
    val offsetX = padSide + width / 2.0 - (minX + maxX) / 2.0 * scale
    val offsetY = padTop + height / 2.0 - (minY + maxY) / 2.0 * scale
    return MapViewport(scale, offsetX, offsetY, size, tileSizePx)
}

private const val MIN_WORLD_SPAN = 1e-12
