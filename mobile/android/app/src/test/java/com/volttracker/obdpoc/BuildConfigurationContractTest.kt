package com.volttracker.obdpoc

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

class BuildConfigurationContractTest {
    @Test
    fun versionCodeIsStableAcrossReleaseAndRepairWorkflows() {
        val appBuild = read(Paths.get("build.gradle"))
        val releaseWorkflow = read(Paths.get("../../../.github/workflows/release.yml"))
        val repairWorkflow = read(Paths.get("../../../.github/workflows/attach-release-apks.yml"))

        assertFalse(appBuild.contains("GITHUB_RUN_NUMBER"))
        assertFalse(releaseWorkflow.contains("GITHUB_RUN_NUMBER"))
        assertFalse(repairWorkflow.contains("GITHUB_RUN_NUMBER"))
        assertTrue(appBuild.contains("repoVersionNumbers[0]"))
        assertTrue(appBuild.contains("Math.multiplyExact"))
    }

    @Test
    fun dashboardBundleTracksDependencyManifests() {
        val rootBuild = read(Paths.get("../build.gradle"))
        val bundleTask =
            rootBuild
                .substringAfter("tasks.register(\"buildDashboardJs\"")
                .substringBefore("tasks.register(\"dashboardTest\"")
        assertTrue(bundleTask.contains("dashboardTestsDir}/package.json"))
        assertTrue(bundleTask.contains("dashboardTestsDir}/package-lock.json"))
    }

    @Test
    fun dashboardGenerationAndVerificationShareOneAssembler() {
        val rootBuild = read(Paths.get("../build.gradle"))
        val appBuild = read(Paths.get("build.gradle"))
        val androidGitignore = read(Paths.get("../.gitignore"))
        val assembler =
            read(Paths.get("../buildSrc/src/main/groovy/com/volttracker/build/DashboardHtmlAssembler.groovy"))

        assertTrue(assembler.contains("class DashboardHtmlAssembler"))
        assertTrue(rootBuild.contains("DashboardHtmlAssembler.assemble(srcDir)"))
        assertTrue(appBuild.contains("DashboardHtmlAssembler.assemble(srcDir)"))
        assertFalse(appBuild.contains("index.template.html").and(appBuild.contains("eachLine")))
        assertTrue(androidGitignore.contains("!buildSrc/src/main/groovy/com/volttracker/build/**"))
    }

    private fun read(path: Path): String = String(Files.readAllBytes(path), StandardCharsets.UTF_8)
}
