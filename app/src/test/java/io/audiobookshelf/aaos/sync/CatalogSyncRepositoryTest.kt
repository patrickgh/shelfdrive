package io.audiobookshelf.aaos.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogSyncRepositoryTest {
    @Test
    fun `deletes only stale ids`() {
        val chunks = missingIdChunks(
            existingIds = listOf("keep-1", "remove-1", "keep-2", "remove-2"),
            retainedIds = setOf("keep-1", "keep-2"),
        )

        assertEquals(listOf(listOf("remove-1", "remove-2")), chunks)
    }

    @Test
    fun `chunks large deletion sets below sqlite variable limit`() {
        val chunks = missingIdChunks(
            existingIds = (1..1_205).map(Int::toString),
            retainedIds = emptySet(),
        )

        assertEquals(listOf(500, 500, 205), chunks.map(List<String>::size))
        assertTrue(chunks.flatten().distinct().size == 1_205)
    }
}
