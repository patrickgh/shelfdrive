package io.audiobookshelf.aaos

import org.junit.Assert.assertEquals
import org.junit.Test

class BuildVariantTest {
    @Test
    fun diagnosticsFlagMatchesFlavor() {
        assertEquals(BuildConfig.FLAVOR == "diagnostics", BuildConfig.DIAGNOSTICS_ENABLED)
        if (!BuildConfig.DIAGNOSTICS_ENABLED) {
            assertEquals("", BuildConfig.DIAGNOSTICS_UPLOAD_URL)
            assertEquals("", BuildConfig.DIAGNOSTICS_UPLOAD_PASSWORD)
        }
    }
}
