package io.audiobookshelf.aaos.browser

import org.junit.Assert.assertEquals
import org.junit.Test

class BrowseNodeIdTest {
    @Test
    fun `round trips system resume root`() {
        assertEquals(
            BrowseNodeId.Resume,
            BrowseNodeId.parse(BrowseNodeId.Resume.serialize()),
        )
    }
}
