package com.volttracker.obdpoc;

import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class ArchitectureBoundaryTest {

    private static final List<String> FORBIDDEN_DATA_LAYER_IMPORTS =
            Arrays.asList(
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
                    "import android.webkit.");

    private static final List<String> FORBIDDEN_ENGINE_UI_REFERENCES =
            Arrays.asList(
                    "import com.volttracker.obdpoc.VoltBridge",
                    "import com.volttracker.obdpoc.WebViewBootstrap",
                    "import android.webkit.",
                    "evaluateJavascript",
                    "webView");

    private static final List<String> FORBIDDEN_SERVICE_WEBVIEW_REFERENCES =
            Arrays.asList(
                    "import android.webkit.",
                    "import com.volttracker.obdpoc.VoltBridge",
                    "import com.volttracker.obdpoc.WebViewBootstrap",
                    "evaluateJavascript");

    @Test
    public void dataLayerDoesNotImportUiServiceOrEngineClasses() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path sourceRoot : sourceRoots()) {
            Path dataDir =
                    sourceRoot
                            .resolve("com")
                            .resolve("volttracker")
                            .resolve("obdpoc")
                            .resolve("data");
            if (!Files.isDirectory(dataDir)) {
                continue;
            }
            try (java.util.stream.Stream<Path> paths = Files.walk(dataDir)) {
                paths.filter(ArchitectureBoundaryTest::isSourceFile)
                        .forEach(
                                path -> {
                                    try {
                                        String source =
                                                new String(
                                                        Files.readAllBytes(path),
                                                        StandardCharsets.UTF_8);
                                        for (String forbidden : FORBIDDEN_DATA_LAYER_IMPORTS) {
                                            if (source.contains(forbidden)) {
                                                violations.add(
                                                        displayPath(path)
                                                                + " imports upward via "
                                                                + forbidden);
                                            }
                                        }
                                    } catch (IOException ex) {
                                        throw new AssertionError("Could not read " + path, ex);
                                    }
                                });
            }
        }
        assertTrue("Layering violations:\n" + String.join("\n", violations), violations.isEmpty());
    }

    @Test
    public void engineDoesNotReferenceDashboardBridgeOrWebViewApis() throws IOException {
        List<String> violations =
                scanFilesForForbiddenReferences(
                        Arrays.asList(
                                "ObdPollingEngine.java",
                                "ElmConnection.java",
                                "DiagnosticScanRunner.java",
                                "ClearDtcRunner.java"),
                        FORBIDDEN_ENGINE_UI_REFERENCES);

        assertTrue(
                "Engine/UI boundary violations:\n" + String.join("\n", violations),
                violations.isEmpty());
    }

    @Test
    public void serviceLayerDoesNotCallWebViewApis() throws IOException {
        List<String> violations =
                scanFilesForForbiddenReferences(
                        Arrays.asList(
                                "ObdService.java", "ObdNotifications.java", "PermissionGate.java"),
                        FORBIDDEN_SERVICE_WEBVIEW_REFERENCES);

        assertTrue(
                "Service/WebView boundary violations:\n" + String.join("\n", violations),
                violations.isEmpty());
    }

    @Test
    public void dashboardBridgeDoesNotOpenWritableDatabaseDirectly() throws IOException {
        List<String> violations =
                scanFilesForForbiddenReferences(
                        Arrays.asList(
                                "MainActivity.java", "VoltBridge.java", "WebViewBootstrap.java"),
                        Arrays.asList("getWritableDatabase("));

        assertTrue(
                "UI/data boundary violations:\n" + String.join("\n", violations),
                violations.isEmpty());
    }

    private static List<String> scanFilesForForbiddenReferences(
            List<String> fileNames, List<String> forbiddenReferences) throws IOException {
        List<String> violations = new ArrayList<>();

        for (String fileName : fileNames) {
            Path path = sourcePath(fileName);
            String source = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
            for (String forbidden : forbiddenReferences) {
                if (source.contains(forbidden)) {
                    violations.add(displayPath(path) + " references " + forbidden);
                }
            }
        }
        return violations;
    }

    private static Path sourcePath(String javaFileName) {
        List<String> candidates = new ArrayList<>();
        candidates.add(javaFileName);
        if (javaFileName.endsWith(".java")) {
            candidates.add(
                    javaFileName.substring(0, javaFileName.length() - ".java".length()) + ".kt");
        }

        for (Path sourceRoot : sourceRoots()) {
            Path packageRoot = sourceRoot.resolve("com").resolve("volttracker").resolve("obdpoc");
            for (String candidate : candidates) {
                Path path = packageRoot.resolve(candidate);
                if (Files.isRegularFile(path)) {
                    return path;
                }
            }
        }
        throw new AssertionError("Could not locate Android source file " + javaFileName);
    }

    private static List<Path> sourceRoots() {
        List<Path> roots = new ArrayList<>();
        for (Path root :
                Arrays.asList(
                        Paths.get("src/main/java"),
                        Paths.get("src/main/kotlin"),
                        Paths.get("app/src/main/java"),
                        Paths.get("app/src/main/kotlin"))) {
            if (Files.isDirectory(root)) {
                roots.add(root);
            }
        }
        if (roots.isEmpty()) {
            throw new AssertionError(
                    "Could not locate Android source root from " + Paths.get("").toAbsolutePath());
        }
        return roots;
    }

    private static boolean isSourceFile(Path path) {
        String name = path.toString();
        return name.endsWith(".java") || name.endsWith(".kt");
    }

    private static String displayPath(Path path) {
        for (Path sourceRoot : sourceRoots()) {
            if (path.startsWith(sourceRoot)) {
                return sourceRoot.relativize(path).toString();
            }
        }
        return path.toString();
    }
}
