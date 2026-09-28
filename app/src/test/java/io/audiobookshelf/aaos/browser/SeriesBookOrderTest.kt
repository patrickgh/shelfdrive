package io.audiobookshelf.aaos.browser

import io.audiobookshelf.aaos.catalog.persistence.BookEntity
import io.audiobookshelf.aaos.catalog.persistence.SeriesBook
import org.junit.Assert.assertEquals
import org.junit.Test

class SeriesBookOrderTest {
    @Test
    fun `orders numeric volumes before text and missing positions`() {
        val books = listOf(
            book("Ten", "10"), book("Two", "2"), book("Interlude", "1.5"),
            book("One", "1"), book("Zero", "0"), book("Special", "Special"),
            book("Appendix", "Appendix"), book("Z missing", null), book("A missing", " "),
        )
        assertEquals(
            listOf("Zero", "One", "Interlude", "Two", "Ten", "Appendix", "Special", "A missing", "Z missing"),
            books.sortedWith(SERIES_BOOK_ORDER).map { it.book.title },
        )
    }

    @Test
    fun `equal numeric positions use title and ID for stable order`() {
        val books = listOf(book("Z", "1"), book("A", "01"), book("B", "1.0"))
        assertEquals(listOf("A", "B", "Z"), books.sortedWith(SERIES_BOOK_ORDER).map { it.book.title })
        val duplicateTitle = book("A", "1").copy(book = book("A", "1").book.copy(id = "other"))
        assertEquals(listOf("A", "other"), listOf(duplicateTitle, book("A", "1"))
            .sortedWith(SERIES_BOOK_ORDER).map { it.book.id })
    }

    private fun book(title: String, sequence: String?) = SeriesBook(
        book = BookEntity(
            id = title, libraryId = "library", title = title, sortTitle = title,
            subtitle = null, description = null, coverPath = null, durationMs = null,
            authorDisplay = null, isPlayable = true,
        ),
        sequence = sequence,
    )
}
