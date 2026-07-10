package com.volttracker.obdpoc

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

class ArchitectureBoundaryTest {
    @Test
    fun dataLayerDoesNotImportUiServiceOrEngineClasses() {
        val violations = ArrayList<String>()
        for (sourceRoot in sourceRoots()) {
            val dataDir =
                sourceRoot
                    .resolve("com")
                    .resolve("volttracker")
                    .resolve("obdpoc")
                    .resolve("data")
            if (!Files.isDirectory(dataDir)) {
                continue
            }
            Files.walk(dataDir).use { paths ->
                paths
                    .filter { isSourceFile(it) }
                    .forEach { path ->
                        try {
                            val source = String(Files.readAllBytes(path), StandardCharsets.UTF_8)
                            for (forbidden in FORBIDDEN_DATA_LAYER_IMPORTS) {
                                if (source.contains(forbidden)) {
                                    violations.add(
                                        displayPath(path) + " imports upward via " + forbidden,
                                    )
                                }
                            }
                        } catch (ex: IOException) {
                            throw AssertionError("Could not read $path", ex)
                        }
                    }
            }
        }
        assertTrue("Layering violations:\n" + violations.joinToString("\n"), violations.isEmpty())
    }

    @Test
    fun engineDoesNotReferenceDashboardBridgeOrWebViewApis() {
        val violations =
            scanProductionFilesForForbiddenReferences(
                { name ->
                    name.contains("Engine") ||
                        name.endsWith("Runner.kt") ||
                        name == "ElmConnection.kt"
                },
                FORBIDDEN_ENGINE_UI_REFERENCES,
            )

        assertTrue(
            "Engine/UI boundary violations:\n" + violations.joinToString("\n"),
            violations.isEmpty(),
        )
    }

    @Test
    fun serviceLayerDoesNotCallWebViewApis() {
        val violations =
            scanProductionFilesForForbiddenReferences(
                { name ->
                    !name.startsWith("VoltBridge") &&
                        (
                            name.endsWith("Service.kt") ||
                                name.endsWith("Notifications.kt") ||
                                name.endsWith("PermissionGate.kt") ||
                                name.endsWith("Worker.kt") ||
                                name.endsWith("Recorder.kt")
                        )
                },
                FORBIDDEN_SERVICE_WEBVIEW_REFERENCES,
            )

        assertTrue(
            "Service/WebView boundary violations:\n" + violations.joinToString("\n"),
            violations.isEmpty(),
        )
    }

    @Test
    fun productionCodeUsesTheSharedLogTagWithoutDependingOnMainActivity() {
        val violations =
            scanProductionFilesForForbiddenReferences(
                { true },
                listOf("MainActivity.TAG"),
            )

        assertTrue(
            "Shared logging must not couple background code to MainActivity:\n" +
                violations.joinToString("\n"),
            violations.isEmpty(),
        )
    }

    @Test
    fun dashboardBridgeDoesNotOpenWritableDatabaseDirectly() {
        val violations =
            scanFilesForForbiddenReferences(
                listOf("MainActivity.kt", "VoltBridge.kt", "WebViewBootstrap.kt"),
                listOf("getWritableDatabase("),
            )

        assertTrue(
            "UI/data boundary violations:\n" + violations.joinToString("\n"),
            violations.isEmpty(),
        )
    }

    @Test
    fun widgetLayerDoesNotImportWebViewOrBridgeApis() {
        val violations = ArrayList<String>()
        for (sourceRoot in sourceRoots()) {
            val widgetDir =
                sourceRoot
                    .resolve("com")
                    .resolve("volttracker")
                    .resolve("obdpoc")
                    .resolve("widget")
            if (!Files.isDirectory(widgetDir)) {
                continue
            }
            Files.walk(widgetDir).use { paths ->
                paths
                    .filter { isSourceFile(it) }
                    .forEach { path ->
                        val source = String(Files.readAllBytes(path), StandardCharsets.UTF_8)
                        for (forbidden in FORBIDDEN_WIDGET_REFERENCES) {
                            if (source.contains(forbidden)) {
                                violations.add(displayPath(path) + " references " + forbidden)
                            }
                        }
                    }
            }
        }
        assertTrue(
            "Widget must stay a thin UI surface (no WebView/bridge/engine deps):\n" +
                violations.joinToString("\n"),
            violations.isEmpty(),
        )
    }

    @Test
    fun dashboardBridgeDependsOnHostSeamNotConcreteActivity() {
        val violations =
            scanFilesForForbiddenReferences(
                listOf("VoltBridge.kt"),
                FORBIDDEN_BRIDGE_ACTIVITY_REFERENCES,
            )

        assertTrue(
            "VoltBridge must depend on the DashboardHost seam, not the concrete MainActivity:\n" +
                violations.joinToString("\n"),
            violations.isEmpty(),
        )
    }

    private companion object {
        val FORBIDDEN_BRIDGE_ACTIVITY_REFERENCES =
            listOf(
                "MainActivity",
            )

        val FORBIDDEN_WIDGET_REFERENCES =
            listOf(
                "import android.webkit.",
                "import com.volttracker.obdpoc.VoltBridge",
                "import com.volttracker.obdpoc.WebViewBootstrap",
                "import com.volttracker.obdpoc.ObdPollingEngine",
                "import com.volttracker.obdpoc.ElmConnection",
                "evaluateJavascript",
            )

        val FORBIDDEN_DATA_LAYER_IMPORTS =
            listOf(
                "import com.volttracker.obdpoc.MainActivity",
                "import com.volttracker.obdpoc.VoltBridge",
                "import com.volttracker.obdpoc.WebViewBootstrap",
                "import com.volttracker.obdpoc.ObdService",
                "import com.volttracker.obdpoc.ObdPollingEngine",
                "import com.volttracker.obdpoc.ElmConnection",
                "import com.volttracker.obdpoc.DiagnosticScanRunner",
                "import com.volttracker.obdpoc.ClearDtcRunner",
                "import com.volttracker.obdpoc.ObdNotifications",
                "import com.volttracker.obdpoc.PermissionGate",
                "import com.volttracker.obdpoc.BackupController",
                "import com.volttracker.obdpoc.DataBackup",
                "import android.webkit.",
            )

        val FORBIDDEN_ENGINE_UI_REFERENCES =
            listOf(
                "import com.volttracker.obdpoc.VoltBridge",
                "import com.volttracker.obdpoc.WebViewBootstrap",
                "import android.webkit.",
                "evaluateJavascript",
                "webView",
            )

        val FORBIDDEN_SERVICE_WEBVIEW_REFERENCES =
            listOf(
                "import android.webkit.",
                "import com.volttracker.obdpoc.VoltBridge",
                "import com.volttracker.obdpoc.WebViewBootstrap",
                "evaluateJavascript",
            )

        @Throws(IOException::class)
        fun scanFilesForForbiddenReferences(
            fileNames: List<String>,
            forbiddenReferences: List<String>,
        ): List<String> {
            val violations = ArrayList<String>()

            for (fileName in fileNames) {
                val path = sourcePath(fileName)
                val source = String(Files.readAllBytes(path), StandardCharsets.UTF_8)
                for (forbidden in forbiddenReferences) {
                    if (source.contains(forbidden)) {
                        violations.add(displayPath(path) + " references " + forbidden)
                    }
                }
            }
            return violations
        }

        @Throws(IOException::class)
        fun scanProductionFilesForForbiddenReferences(
            includeName: (String) -> Boolean,
            forbiddenReferences: List<String>,
        ): List<String> {
            val violations = ArrayList<String>()
            for (sourceRoot in sourceRoots()) {
                val packageRoot =
                    sourceRoot.resolve("com").resolve("volttracker").resolve("obdpoc")
                if (!Files.isDirectory(packageRoot)) continue
                Files.walk(packageRoot).use { paths ->
                    paths
                        .filter { isSourceFile(it) && includeName(it.fileName.toString()) }
                        .forEach { path ->
                            val source = String(Files.readAllBytes(path), StandardCharsets.UTF_8)
                            for (forbidden in forbiddenReferences) {
                                if (source.contains(forbidden)) {
                                    violations.add(displayPath(path) + " references " + forbidden)
                                }
                            }
                        }
                }
            }
            return violations
        }

        fun sourcePath(sourceFileName: String): Path {
            val candidates = ArrayList<String>()
            candidates.add(sourceFileName)
            if (sourceFileName.endsWith(".java")) {
                candidates.add(
                    sourceFileName.substring(0, sourceFileName.length - ".java".length) + ".kt",
                )
            }

            for (sourceRoot in sourceRoots()) {
                val packageRoot =
                    sourceRoot.resolve("com").resolve("volttracker").resolve("obdpoc")
                for (candidate in candidates) {
                    val path = packageRoot.resolve(candidate)
                    if (Files.isRegularFile(path)) {
                        return path
                    }
                }
            }
            throw AssertionError("Could not locate Android source file $sourceFileName")
        }

        fun sourceRoots(): List<Path> {
            val roots = ArrayList<Path>()
            for (
            root in
            listOf(
                Paths.get("src/main/java"),
                Paths.get("src/main/kotlin"),
                Paths.get("app/src/main/java"),
                Paths.get("app/src/main/kotlin"),
            )
            ) {
                if (Files.isDirectory(root)) {
                    roots.add(root)
                }
            }
            if (roots.isEmpty()) {
                throw AssertionError(
                    "Could not locate Android source root from " +
                        Paths.get("").toAbsolutePath(),
                )
            }
            return roots
        }

        fun isSourceFile(path: Path): Boolean {
            val name = path.toString()
            return name.endsWith(".java") || name.endsWith(".kt")
        }

        fun displayPath(path: Path): String {
            for (sourceRoot in sourceRoots()) {
                if (path.startsWith(sourceRoot)) {
                    return sourceRoot.relativize(path).toString()
                }
            }
            return path.toString()
        }
    }
}
