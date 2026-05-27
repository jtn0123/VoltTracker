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

    @Test
    public void dataLayerDoesNotImportUiServiceOrEngineClasses() throws IOException {
        Path dataDir =
                sourceRoot()
                        .resolve("com")
                        .resolve("volttracker")
                        .resolve("obdpoc")
                        .resolve("data");
        List<String> violations = new ArrayList<>();
        try (java.util.stream.Stream<Path> paths = Files.walk(dataDir)) {
            paths.filter(path -> path.toString().endsWith(".java"))
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
                                                    sourceRoot().relativize(path)
                                                            + " imports upward via "
                                                            + forbidden);
                                        }
                                    }
                                } catch (IOException ex) {
                                    throw new AssertionError("Could not read " + path, ex);
                                }
                            });
        }
        assertTrue("Layering violations:\n" + String.join("\n", violations), violations.isEmpty());
    }

    private static Path sourceRoot() {
        Path fromAppProject = Paths.get("src/main/java");
        if (Files.isDirectory(fromAppProject)) {
            return fromAppProject;
        }
        Path fromAndroidRoot = Paths.get("app/src/main/java");
        if (Files.isDirectory(fromAndroidRoot)) {
            return fromAndroidRoot;
        }
        throw new AssertionError(
                "Could not locate Android source root from " + Paths.get("").toAbsolutePath());
    }
}
