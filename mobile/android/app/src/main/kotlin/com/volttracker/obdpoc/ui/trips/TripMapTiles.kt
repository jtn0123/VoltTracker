package com.volttracker.obdpoc.ui.trips

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.volttracker.obdpoc.map.MapTileLoader
import com.volttracker.obdpoc.map.StadiaTiles
import com.volttracker.obdpoc.map.TileId
import com.volttracker.obdpoc.ui.theme.LocalVoltPalette
import com.volttracker.obdpoc.ui.theme.VoltType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * The basemap tile source for the Trips map. Null — the default, so previews and tests never
 * reach the network — means no tiles; the app's activity provides the Stadia loader when the
 * build has a key ([com.volttracker.obdpoc.map.StadiaTileLoader.create]).
 */
val LocalMapTileLoader = staticCompositionLocalOf<MapTileLoader?> { null }

/**
 * The Trips map's default ground: the plain [MapGround], with street tiles over it when a tile
 * loader is available and the route has a [viewport]. Tiles load off the main thread and appear
 * as they arrive; any that fail leave the plain ground showing. The style follows the theme.
 */
@Composable
fun BoxScope.TripMapGround(
    viewport: MapViewport?,
    loader: MapTileLoader? = LocalMapTileLoader.current,
) {
    MapGround()
    if (viewport == null || loader == null) return
    val style = StadiaTiles.styleFor(LocalVoltPalette.current.isDark)
    val slots = viewport.tiles()
    val tiles by produceState(initial(slots, style, loader), slots, style, loader) {
        // A new trip or theme: drop tiles that are no longer in view, keep the rest.
        val wanted = slots.map { it.tileId(style) }.toSet()
        value = value.filterKeys { it in wanted } + initial(slots, style, loader)
        val missing = slots.filter { slot -> slot.tileId(style) !in value }
        coroutineScope {
            missing
                .map { slot -> async(Dispatchers.IO) { slot.tileId(style) to loader.load(slot.tileId(style)) } }
                .forEach { pending ->
                    val (id, bitmap) = pending.await()
                    if (bitmap != null) value = value + (id to bitmap)
                }
        }
    }
    if (tiles.isEmpty()) return
    Canvas(Modifier.fillMaxSize()) {
        for (slot in slots) {
            val bitmap = tiles[slot.tileId(style)] ?: continue
            // Rounded outward by a pixel so neighbouring tiles never leave a hairline seam.
            val side = ceil(slot.sizePx).toInt() + 1
            drawImage(
                bitmap,
                dstOffset = IntOffset(slot.topLeft.x.roundToInt(), slot.topLeft.y.roundToInt()),
                dstSize = IntSize(side, side),
            )
        }
    }
    MapAttribution(Modifier.align(Alignment.TopEnd).padding(8.dp))
}

/** The small tile credit, shown whenever tiles are drawn. */
@Composable
private fun MapAttribution(modifier: Modifier) {
    val pal = LocalVoltPalette.current
    Text(
        text = StadiaTiles.ATTRIBUTION,
        style = VoltType.caption.copy(fontSize = 9.sp, lineHeight = 11.sp),
        color = pal.muted,
        maxLines = 1,
        modifier =
            modifier
                .background(pal.surface.copy(alpha = ATTRIBUTION_ALPHA), RoundedCornerShape(6.dp))
                .padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

private fun initial(
    slots: List<TileSlot>,
    style: String,
    loader: MapTileLoader,
): Map<TileId, ImageBitmap> =
    slots.mapNotNull { slot -> loader.cached(slot.tileId(style))?.let { slot.tileId(style) to it } }.toMap()

private fun TileSlot.tileId(style: String) = TileId(style, z, x, y)

private const val ATTRIBUTION_ALPHA = 0.8f
