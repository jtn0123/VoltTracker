package com.volttracker.obdpoc

import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

class BridgeThreatModelContractTest {
    @Test
    fun highRiskBridgeMethodNamesAreCoveredByThreatModel() {
        val methods = kotlinBridgeMethods().filter { HIGH_RISK_NAME.containsMatchIn(it) }.sorted()
        assertTrue("Expected at least one high-risk-ish bridge method name", methods.isNotEmpty())

        val threatModel = String(Files.readAllBytes(threatModelPath()), StandardCharsets.UTF_8)
        val missing = methods.filterNot { threatModel.contains("`$it") }

        assertTrue(
            "Bridge methods with high-risk verbs must be named in docs/bridge-threat-model.md: $missing",
            missing.isEmpty(),
        )
    }

    private companion object {
        private val JS_INTERFACE_FUN = Regex("@JavascriptInterface[\\s\\S]*?\\bfun\\s+([A-Za-z0-9_]+)")
        private val HIGH_RISK_NAME =
            Regex("^(export|share|restore|delete|clear|force|open|start)", RegexOption.IGNORE_CASE)

        fun kotlinBridgeMethods(): Set<String> {
            val methods = HashSet<String>()
            Files.walk(sourceRoot()).use { paths ->
                paths
                    .filter {
                        it.fileName.toString().let { name ->
                            name.startsWith("VoltBridge") && name.endsWith(".kt")
                        }
                    }.forEach { path ->
                        val source = String(Files.readAllBytes(path), StandardCharsets.UTF_8)
                        for (match in JS_INTERFACE_FUN.findAll(source)) {
                            methods.add(match.groupValues[1])
                        }
                    }
            }
            return methods
        }

        fun sourceRoot(): Path =
            listOf(
                Paths.get("src/main/kotlin/com/volttracker/obdpoc"),
                Paths.get("app/src/main/kotlin/com/volttracker/obdpoc"),
            ).firstOrNull { Files.isDirectory(it) }
                ?: throw AssertionError("Could not locate Android source root")

        fun threatModelPath(): Path =
            listOf(
                Paths.get("../docs/bridge-threat-model.md"),
                Paths.get("docs/bridge-threat-model.md"),
                Paths.get("mobile/android/docs/bridge-threat-model.md"),
            ).firstOrNull { Files.isRegularFile(it) }
                ?: throw AssertionError("Could not locate docs/bridge-threat-model.md")
    }
}
