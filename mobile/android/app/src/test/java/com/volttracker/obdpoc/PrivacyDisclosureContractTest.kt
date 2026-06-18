package com.volttracker.obdpoc

import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths

class PrivacyDisclosureContractTest {
    @Test
    fun privacyDocsNameEveryPlaintextExportFamily() {
        val doc = privacyDoc().lowercase()
        val required =
            listOf(
                "plaintext backup",
                "diagnostics exports",
                "per-trip",
                "gpx",
                "csv",
                "full-precision gps",
                "all-trips csv",
                "charge-history csv",
                "android share sheet",
            )

        val missing = required.filterNot { doc.contains(it) }

        assertTrue(
            "privacy-data-handling.md must disclose every plaintext export family; missing $missing",
            missing.isEmpty(),
        )
    }

    private companion object {
        fun privacyDoc(): String {
            val path =
                listOf(
                    Paths.get("../docs/privacy-data-handling.md"),
                    Paths.get("docs/privacy-data-handling.md"),
                    Paths.get("mobile/android/docs/privacy-data-handling.md"),
                ).firstOrNull { Files.isRegularFile(it) }
                    ?: throw AssertionError("Could not locate docs/privacy-data-handling.md")
            return String(Files.readAllBytes(path), StandardCharsets.UTF_8)
        }
    }
}
