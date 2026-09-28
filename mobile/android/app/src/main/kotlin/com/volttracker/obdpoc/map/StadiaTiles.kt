package com.volttracker.obdpoc.map

import com.volttracker.obdpoc.BuildConfig
import org.json.JSONObject
import java.net.URLEncoder

/**
 * The basemap tile source — the single source of truth for both maps: the WebView Map tab (via
 * [VoltBridge.getMapTileConfig][com.volttracker.obdpoc.VoltBridge.getMapTileConfig]) and the
 * Compose Trips map ([StadiaTileLoader]).
 *
 * Tiles are Stadia Maps raster tiles, keyed by `BuildConfig.STADIA_API_KEY` (local.properties or
 * the CI `STADIA_API_KEY` secret). With no key every entry point here answers "no tiles" (null /
 * an empty config), and both maps draw the route on their plain background — there is no
 * keyless fallback provider.
 */
object StadiaTiles {
    /** Dark basemap style, for the OLED and Saddle themes. */
    const val STYLE_DARK = "alidade_smooth_dark"

    /** Light basemap style, for Latte. */
    const val STYLE_LIGHT = "alidade_smooth"

    /** The credit both maps show whenever tiles are drawn. */
    const val ATTRIBUTION = "© Stadia Maps © OpenMapTiles © OpenStreetMap"

    /** Tile host; the dashboard CSP allowlists exactly this origin. */
    const val HOST = "tiles.stadiamaps.com"

    /** The build's key, trimmed; blank when none was configured. */
    val apiKey: String get() = BuildConfig.STADIA_API_KEY.trim()

    /** The basemap style for a dark or light theme. */
    fun styleFor(dark: Boolean): String = if (dark) STYLE_DARK else STYLE_LIGHT

    /**
     * The `{z}/{x}/{y}` URL template for [style] (Leaflet's placeholder syntax), or null when
     * [key] is blank — tiles are off.
     */
    fun urlTemplate(
        style: String,
        key: String = apiKey,
    ): String? {
        val clean = key.trim()
        if (clean.isEmpty()) return null
        val encoded = URLEncoder.encode(clean, "UTF-8")
        return "https://$HOST/tiles/$style/{z}/{x}/{y}@2x.png?api_key=$encoded"
    }

    /** One tile's URL, or null when [key] is blank. */
    fun tileUrl(
        tile: TileId,
        key: String = apiKey,
    ): String? =
        urlTemplate(tile.style, key)
            ?.replace("{z}", tile.z.toString())
            ?.replace("{x}", tile.x.toString())
            ?.replace("{y}", tile.y.toString())

    /**
     * The WebView map's tile config: `{"dark": template, "light": template, "attribution": …}`,
     * or `{}` when [key] is blank so the dashboard draws no tile layer at all.
     */
    fun webViewConfigJson(key: String = apiKey): String {
        val dark = urlTemplate(STYLE_DARK, key) ?: return "{}"
        return JSONObject()
            .put("dark", dark)
            .put("light", urlTemplate(STYLE_LIGHT, key))
            .put("attribution", ATTRIBUTION)
            .toString()
    }
}

/** One slippy-map tile of a basemap style. */
data class TileId(
    val style: String,
    val z: Int,
    val x: Int,
    val y: Int,
)
