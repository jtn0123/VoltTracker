package com.volttracker.obdpoc

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.volttracker.obdpoc.ui.diag.DiagUiState
import com.volttracker.obdpoc.ui.diag.DtcCatalog
import com.volttracker.obdpoc.ui.diag.healthReport
import com.volttracker.obdpoc.ui.diag.hvBattery
import com.volttracker.obdpoc.ui.diag.serviceGuidance
import com.volttracker.obdpoc.ui.drive.DriveUiState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GradeDiagnosticPrivacyRegressionTest {
    @Test
    fun unknownCodesDoNotDeclareTheVehicleSafe() {
        val guidance = serviceGuidance(listOf(DtcCatalog.EMPTY.describe("B1000")))!!.text
        assertFalse(guidance.contains("Safe to drive", ignoreCase = true))
        assertTrue(guidance.contains("not in the catalog"))
    }

    @Test
    fun engineCategoryDoesNotGuaranteeIndependentElectricPropulsion() {
        val guidance = serviceGuidance(DiagUiState.demo.codes.orEmpty())!!.text
        assertFalse(guidance.contains("affects the electric drive"))
        assertFalse(guidance.contains("Safe to drive", ignoreCase = true))
    }

    @Test
    fun savedDisconnectedHealthReportsDoNotCertifyDrivingSafety() {
        val state = DiagUiState.demo.copy(connected = false, nowMs = DiagUiState.DEMO_NOW_MS + 86_400_000L)
        val report = healthReport(state, hvBattery(DriveUiState(), null, null), demo = false)
        assertFalse(report.contains("Safe to drive", ignoreCase = true))
        assertFalse(report.contains("affects the electric drive"))
    }

    @Test
    fun legacyBackupExcludesEverySensitiveDomain() {
        assertSensitiveDomainsExcluded(R.xml.backup_rules, "legacy")
    }

    @Test
    fun modernCloudAndDeviceTransfersExcludeEverySensitiveDomain() {
        assertSensitiveDomainsExcluded(R.xml.data_extraction_rules, "cloud-backup")
        assertSensitiveDomainsExcluded(R.xml.data_extraction_rules, "device-transfer")
    }

    private fun assertSensitiveDomainsExcluded(
        resource: Int,
        section: String,
    ) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val parser = context.resources.getXml(resource)
        val excluded = mutableSetOf<String>()
        var current = "legacy"
        parser.use {
            while (it.eventType != XmlPullParser.END_DOCUMENT) {
                if (it.eventType == XmlPullParser.START_TAG) {
                    if (it.name == "cloud-backup" || it.name == "device-transfer") current = it.name
                    if (it.name == "exclude" && current == section && it.getAttributeValue(null, "path") == ".") {
                        excluded += it.getAttributeValue(null, "domain")
                    }
                }
                it.next()
            }
        }
        assertTrue(
            "$section exclusions: $excluded",
            excluded.containsAll(listOf("root", "file", "database", "sharedpref")),
        )
    }
}
