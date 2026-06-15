package com.volttracker.obdpoc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Cross-checks the Kotlin `@JavascriptInterface` surface (every VoltBridge* file) against the
 * JS-side `VOLT_BRIDGE_METHODS` mirror in `dashboard-tests/setup/voltbridge.fixture.js`. The
 * dashboard calls these methods by name across the WebView bridge, so a method added or renamed on
 * one side without the other silently no-ops on device until a field bug — this test fails the build
 * instead. (Report item A1; this is the gap `docs/bridge-abi.md` flagged as un-guarded.)
 */
class BridgeAbiContractTest {
    @Test
    fun kotlinJavascriptInterfaceMethodsMatchJsMirror() {
        val kotlinMethods = kotlinBridgeMethods()
        val jsMethods = jsMirrorMethods()

        assertTrue(
            "Expected to find @JavascriptInterface methods in the Kotlin bridge; found none.",
            kotlinMethods.isNotEmpty(),
        )
        assertEquals(
            "Kotlin @JavascriptInterface surface and the JS VOLT_BRIDGE_METHODS mirror have drifted.\n" +
                "Only in Kotlin (add to voltbridge.fixture.js): ${(kotlinMethods - jsMethods).sorted()}\n" +
                "Only in JS mirror (stale / remove or implement): ${(jsMethods - kotlinMethods).sorted()}",
            jsMethods,
            kotlinMethods,
        )
    }

    private companion object {
        private val JS_INTERFACE_FUN = Regex("@JavascriptInterface[\\s\\S]*?\\bfun\\s+([A-Za-z0-9_]+)")
        private val JS_METHOD_ENTRY = Regex("'([A-Za-z0-9_]+)'")

        fun kotlinBridgeMethods(): Set<String> {
            val methods = HashSet<String>()
            val packageRoot = sourceRoots().first()
            Files.walk(packageRoot).use { paths ->
                paths
                    .filter { it.fileName.toString().let { n -> n.startsWith("VoltBridge") && n.endsWith(".kt") } }
                    .forEach { path ->
                        val source = String(Files.readAllBytes(path), StandardCharsets.UTF_8)
                        for (match in JS_INTERFACE_FUN.findAll(source)) {
                            methods.add(match.groupValues[1])
                        }
                    }
            }
            return methods
        }

        fun jsMirrorMethods(): Set<String> {
            val source = String(Files.readAllBytes(jsFixturePath()), StandardCharsets.UTF_8)
            val start = source.indexOf("VOLT_BRIDGE_METHODS")
            assertTrue("VOLT_BRIDGE_METHODS not found in fixture", start >= 0)
            val arrayStart = source.indexOf('[', start)
            val arrayEnd = source.indexOf(']', arrayStart)
            assertTrue("VOLT_BRIDGE_METHODS array brackets not found", arrayStart in 0 until arrayEnd)
            val arrayBody = source.substring(arrayStart, arrayEnd)
            return JS_METHOD_ENTRY.findAll(arrayBody).map { it.groupValues[1] }.toHashSet()
        }

        fun sourceRoots(): List<Path> {
            val roots =
                listOf(
                    Paths.get("src/main/kotlin/com/volttracker/obdpoc"),
                    Paths.get("app/src/main/kotlin/com/volttracker/obdpoc"),
                ).filter { Files.isDirectory(it) }
            if (roots.isEmpty()) {
                throw AssertionError(
                    "Could not locate Android source root from " + Paths.get("").toAbsolutePath(),
                )
            }
            return roots
        }

        fun jsFixturePath(): Path {
            val candidates =
                listOf(
                    Paths.get("../dashboard-tests/setup/voltbridge.fixture.js"),
                    Paths.get("dashboard-tests/setup/voltbridge.fixture.js"),
                    Paths.get("mobile/android/dashboard-tests/setup/voltbridge.fixture.js"),
                )
            return candidates.firstOrNull { Files.isRegularFile(it) }
                ?: throw AssertionError(
                    "Could not locate voltbridge.fixture.js from " + Paths.get("").toAbsolutePath(),
                )
        }
    }
}
