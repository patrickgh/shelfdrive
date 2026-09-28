package io.audiobookshelf.aaos.browser

import io.audiobookshelf.aaos.catalog.persistence.SeriesBook

internal val SERIES_BOOK_ORDER: Comparator<SeriesBook> =
    compareBy<SeriesBook> {
        when {
            it.sequence.isNullOrBlank() -> 2
            it.sequence.trim().toBigDecimalOrNull() != null -> 0
            else -> 1
        }
    }
        .thenBy { it.sequence?.trim()?.toBigDecimalOrNull() }
        .thenBy(String.CASE_INSENSITIVE_ORDER) {
            it.sequence?.trim()?.takeIf { sequence -> sequence.toBigDecimalOrNull() == null }.orEmpty()
        }
        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.book.sortTitle.ifBlank { it.book.title } }
        .thenBy { it.book.id }
