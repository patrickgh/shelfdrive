package io.audiobookshelf.aaos.absapi

import io.audiobookshelf.aaos.status.UserVisibleStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerCompatibilityTest {

    @Test
    fun `versions below minimum are rejected`() {
        val report = ServerCompatibility.evaluate("2.30.9")

        assertFalse(report.isSupported)
        assertEquals(UserVisibleStatus.SERVER_VERSION_UNSUPPORTED, report.warningCode)
    }

    @Test
    fun `versions at minimum are supported`() {
        val report = ServerCompatibility.evaluate("2.31.0")

        assertTrue(report.isSupported)
    }

    @Test
    fun `unparseable version stays non-blocking`() {
        val report = ServerCompatibility.evaluate("nightly-build")

        assertTrue(report.isSupported)
        assertEquals(UserVisibleStatus.SERVER_VERSION_UNPARSEABLE, report.warningCode)
    }
}
