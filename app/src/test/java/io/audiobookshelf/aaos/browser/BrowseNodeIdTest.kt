package io.audiobookshelf.aaos.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BrowseNodeIdTest {
    @Test
    fun `round trips browsable bucket ids`() {
        val nodes = listOf(
            BrowseNodeId.BooksBucket("A"),
            BrowseNodeId.AuthorsBucket("B"),
            BrowseNodeId.AuthorBooksBucket("author-id", "C"),
        )

        nodes.forEach { node ->
            assertEquals(node, BrowseNodeId.parse(node.serialize()))
        }
    }

    @Test
    fun `does not expose a second root for playback resumption`() {
        assertNull(BrowseNodeId.parse("resume"))
    }

    @Test
    fun `does not treat presentation state ids as browse nodes`() {
        assertNull(BrowseNodeId.parse("recent:empty"))
        assertNull(BrowseNodeId.parse("books:sync_failed"))
        assertNull(BrowseNodeId.parse("search:empty:123"))
    }
}
