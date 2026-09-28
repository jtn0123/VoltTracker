package com.volttracker.obdpoc.map

import android.graphics.Bitmap
import android.graphics.Color
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** [StadiaTileLoader] over a fake connection: only a 200 image response that decodes becomes a tile. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class StadiaTileLoaderTest {
    private val tile = TileId(StadiaTiles.STYLE_DARK, 3, 1, 2)
    private val requested = mutableListOf<String>()
    private val disconnected = mutableListOf<String>()

    private fun loader(
        key: String = "test-key",
        respond: (URL) -> FakeConnection,
    ) = StadiaTileLoader(key, LruCache<TileId, ImageBitmap>(8)) { url ->
        requested += url.toString()
        respond(url)
    }

    @Test
    fun aValidImageBecomesATileAndIsCachedInMemory() {
        val loader = loader { FakeConnection(it, 200, "image/png", png()) }
        assertNull(loader.cached(tile))

        val bitmap = loader.load(tile)

        assertNotNull(bitmap)
        assertEquals(PNG_SIDE, requireNotNull(bitmap).width)
        assertEquals(
            listOf("https://tiles.stadiamaps.com/tiles/alidade_smooth_dark/3/1/2@2x.png?api_key=test-key"),
            requested,
        )
        assertEquals(requested, disconnected)
        // Second ask: straight from memory, no request.
        assertSame(bitmap, loader.cached(tile))
        assertSame(bitmap, loader.load(tile))
        assertEquals(1, requested.size)
    }

    @Test
    fun anHttpErrorLeavesNoTile() {
        val loader = loader { FakeConnection(it, 401, "image/png", png()) }
        assertNull(loader.load(tile))
        assertNull(loader.cached(tile))
    }

    @Test
    fun aNonImageResponseLeavesNoTile() {
        // A 200 placeholder page (the CARTO failure mode) must not be drawn as a tile.
        val loader = loader { FakeConnection(it, 200, "text/html", "API KEY REQUIRED".toByteArray()) }
        assertNull(loader.load(tile))
        val untyped = loader { FakeConnection(it, 200, null, png()) }
        assertNull(untyped.load(tile))
    }

    @Test
    fun anUndecodableBodyLeavesNoTile() {
        val loader = loader { FakeConnection(it, 200, "image/png", byteArrayOf(1, 2, 3)) }
        assertNull(loader.load(tile))
        assertNull(loader.cached(tile))
    }

    @Test
    fun aNetworkFailureLeavesNoTile() {
        val loader = loader { FakeConnection(it, 200, "image/png", png(), fail = true) }
        assertNull(loader.load(tile))
        assertEquals(requested, disconnected)
    }

    @Test
    fun aBlankKeyNeverConnects() {
        val loader = loader(key = " ") { FakeConnection(it, 200, "image/png", png()) }
        assertNull(loader.load(tile))
        assertTrue(requested.isEmpty())
    }

    @Test
    fun createNeedsAKey() {
        assertNull(StadiaTileLoader.create(""))
        assertNull(StadiaTileLoader.create("  "))
        assertNotNull(StadiaTileLoader.create("k"))
    }

    private fun png(): ByteArray {
        val bitmap = Bitmap.createBitmap(PNG_SIDE, PNG_SIDE, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.DKGRAY)
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    inner class FakeConnection(
        url: URL,
        private val code: Int,
        private val type: String?,
        private val body: ByteArray,
        private val fail: Boolean = false,
    ) : HttpURLConnection(url) {
        override fun connect() {
            if (fail) throw IOException("offline")
        }

        override fun getResponseCode(): Int {
            connect()
            return code
        }

        override fun getContentType(): String? {
            connect()
            return type
        }

        override fun getInputStream(): InputStream = ByteArrayInputStream(body)

        override fun disconnect() {
            disconnected += url.toString()
        }

        override fun usingProxy(): Boolean = false
    }

    private companion object {
        const val PNG_SIDE = 8
    }
}
