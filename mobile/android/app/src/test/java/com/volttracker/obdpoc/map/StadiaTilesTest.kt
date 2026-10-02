package com.volttracker.obdpoc.map

import com.volttracker.obdpoc.BuildConfig
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StadiaTilesTest {
    @Test
    fun darkThemesUseSmoothDarkAndLightUsesSmooth() {
        assertEquals("alidade_smooth_dark", StadiaTiles.styleFor(dark = true))
        assertEquals("alidade_smooth", StadiaTiles.styleFor(dark = false))
    }

    @Test
    fun templateIsTheStadiaRetinaUrlWithTheKey() {
        assertEquals(
            "https://tiles.stadiamaps.com/tiles/alidade_smooth_dark/{z}/{x}/{y}@2x.png?api_key=abc-123",
            StadiaTiles.urlTemplate(StadiaTiles.STYLE_DARK, "abc-123"),
        )
        assertEquals(
            "https://tiles.stadiamaps.com/tiles/alidade_smooth/{z}/{x}/{y}@2x.png?api_key=abc-123",
            StadiaTiles.urlTemplate(StadiaTiles.STYLE_LIGHT, "  abc-123 "),
        )
    }

    @Test
    fun keyIsUrlEncodedSoItCannotBreakTheUrlOrTemplate() {
        assertEquals(
            "https://tiles.stadiamaps.com/tiles/alidade_smooth/{z}/{x}/{y}@2x.png?api_key=a%26b%7Bz%7D",
            StadiaTiles.urlTemplate(StadiaTiles.STYLE_LIGHT, "a&b{z}"),
        )
    }

    @Test
    fun tileUrlFillsTheCoordinates() {
        assertEquals(
            "https://tiles.stadiamaps.com/tiles/alidade_smooth_dark/12/654/1583@2x.png?api_key=k",
            StadiaTiles.tileUrl(TileId(StadiaTiles.STYLE_DARK, 12, 654, 1583), "k"),
        )
    }

    @Test
    fun noKeyMeansNoTilesAnywhere() {
        for (blank in listOf("", "   ")) {
            assertNull(StadiaTiles.urlTemplate(StadiaTiles.STYLE_DARK, blank))
            assertNull(StadiaTiles.tileUrl(TileId(StadiaTiles.STYLE_LIGHT, 1, 0, 0), blank))
            assertEquals("{}", StadiaTiles.webViewConfigJson(blank))
        }
    }

    @Test
    fun webViewConfigCarriesBothStylesAndTheAttribution() {
        val config = JSONObject(StadiaTiles.webViewConfigJson("k"))
        assertEquals(StadiaTiles.urlTemplate(StadiaTiles.STYLE_DARK, "k"), config.getString("dark"))
        assertEquals(StadiaTiles.urlTemplate(StadiaTiles.STYLE_LIGHT, "k"), config.getString("light"))
        assertEquals("© Stadia Maps © OpenMapTiles © OpenStreetMap", config.getString("attribution"))
    }

    @Test
    fun defaultKeyIsTheBuildConfigKey() {
        assertEquals(BuildConfig.STADIA_API_KEY.trim(), StadiaTiles.apiKey)
        assertEquals(StadiaTiles.webViewConfigJson(BuildConfig.STADIA_API_KEY), StadiaTiles.webViewConfigJson())
    }
}
