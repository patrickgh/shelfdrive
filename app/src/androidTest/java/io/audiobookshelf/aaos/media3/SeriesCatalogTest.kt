package io.audiobookshelf.aaos.media3

import android.content.res.Configuration
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaConstants
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.audiobookshelf.aaos.R
import io.audiobookshelf.aaos.artwork.ArtworkUriFactory
import io.audiobookshelf.aaos.browser.BrowseNodeId
import io.audiobookshelf.aaos.browser.CatalogBrowseRepository
import io.audiobookshelf.aaos.browser.CatalogBrowseRepository.BrowseCollection
import io.audiobookshelf.aaos.catalog.persistence.AuthorEntity
import io.audiobookshelf.aaos.catalog.persistence.BookAuthorCrossRef
import io.audiobookshelf.aaos.catalog.persistence.BookEntity
import io.audiobookshelf.aaos.catalog.persistence.BookSeriesCrossRef
import io.audiobookshelf.aaos.catalog.persistence.CatalogDatabase
import io.audiobookshelf.aaos.catalog.persistence.LibraryEntity
import io.audiobookshelf.aaos.catalog.persistence.MediaProgressEntity
import io.audiobookshelf.aaos.catalog.persistence.SeriesEntity
import io.audiobookshelf.aaos.catalog.persistence.SyncStateEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

@OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class SeriesCatalogTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val database = Room.inMemoryDatabaseBuilder(context, CatalogDatabase::class.java).build()
    private val repository = CatalogBrowseRepository(database)
    private val catalog = ShelfDriveMediaCatalog(context, repository)

    @After
    fun close() = database.close()

    @Test
    fun voiceSearchPrefersBooksThenAuthorsAndPreservesBlankQueryFallback() = runBlocking {
        seedBooks()
        database.authorDao().upsertAll(listOf(
            AuthorEntity("same-title", "One", "One", null, 1),
            AuthorEntity("voice-author", "Änne Example", "Änne Example", null, 1),
        ))
        database.bookAuthorCrossRefDao().upsertAll(listOf(
            BookAuthorCrossRef("two", "same-title"),
            BookAuthorCrossRef("two", "voice-author"),
        ))
        assertEquals("one", repository.findBestPlayableBookForVoice(" ONE ")?.id)
        assertEquals("two", repository.findBestPlayableBookForVoice("anne example")?.id)
        assertNull(repository.findBestPlayableBookForVoice("unknown"))
        assertNull(repository.findBestPlayableBookForVoice("Missing"))
        assertEquals("one", repository.findBestPlayableBookForVoice(" ")?.id)
        database.mediaProgressDao().upsert(
            MediaProgressEntity("two", 1000L, 10_000L, false, false, 123L, 123L, null),
        )
        assertEquals("two", repository.findBestPlayableBookForVoice(" ")?.id)
    }

    @Test
    fun seriesNavigationPreservesBookIdsAndUsesPerSeriesPositions() = runBlocking {
        seedBooks()
        database.seriesDao().upsertAll(listOf(SeriesEntity("a", "Same name"), SeriesEntity("b", "Same name")))
        database.bookSeriesCrossRefDao().upsertAll(listOf(
            BookSeriesCrossRef("one", "a", "1"), BookSeriesCrossRef("two", "a", "2"),
            BookSeriesCrossRef("one", "b", "2"), BookSeriesCrossRef("two", "b", "1"),
            BookSeriesCrossRef("missing", "a", "0"),
        ))
        assertEquals(listOf("recent", "books", "authors", "series"), catalog.loadChildren("root").map { it.mediaId })
        assertEquals(listOf("series:a", "series:b"), catalog.loadChildren("series").map { it.mediaId })
        assertEquals(2, repository.getSeries("a")?.numBooks)
        assertEquals(listOf("book:one", "book:two"), catalog.loadChildren("series:a").map { it.mediaId })
        assertEquals(listOf("book:two", "book:one"), catalog.loadChildren("series:b").map { it.mediaId })
        val first = catalog.loadChildren("series:a").first()
        assertTrue(first.mediaMetadata.isPlayable == true)
        assertTrue(first.mediaMetadata.artist.toString().contains(context.getString(R.string.media_series_sequence, "1")))
        assertEquals("One", catalog.loadItem("book:one")?.mediaMetadata?.title.toString())

        database.bookSeriesCrossRefDao().clearAll()
        database.bookSeriesCrossRefDao().upsertAll(listOf(BookSeriesCrossRef("two", "b", "3")))
        database.seriesDao().deleteByIds(listOf("a"))
        assertNull(repository.getSeries("a"))
        assertEquals(listOf("two"), repository.getBooksForSeries("b").map { it.book.id })
        assertNotNull(repository.getPlayableBook("one"))
        database.bookDao().deleteByIds(listOf("two"))
        assertTrue(repository.getBooksForSeries("b").isEmpty())
    }

    @Test
    fun seriesTilesUseFirstAvailableCoverInVolumeOrderAcrossBrowsePaths() = runBlocking {
        seedBooks()
        database.bookDao().upsertAll(listOf(
            book("one", "Z title").copy(coverPath = "/covers/one.jpg"),
            book("two", "A title").copy(coverPath = "/covers/two.jpg"),
            book("missing", "Missing", false).copy(coverPath = "/covers/missing.jpg"),
        ))
        database.seriesDao().upsertAll(listOf(SeriesEntity("a", "Alpha"), SeriesEntity("b", "Beta")))
        database.bookSeriesCrossRefDao().upsertAll(listOf(
            BookSeriesCrossRef("one", "a", "1.5"), BookSeriesCrossRef("two", "a", "10"),
            BookSeriesCrossRef("missing", "a", "0"),
            BookSeriesCrossRef("one", "b", "10"), BookSeriesCrossRef("two", "b", "1.5"),
        ))
        val firstCover = ArtworkUriFactory.bookCover("one", ArtworkUriFactory.signatureFor("/covers/one.jpg"))
        val secondCover = ArtworkUriFactory.bookCover("two", ArtworkUriFactory.signatureFor("/covers/two.jpg"))
        assertEquals(listOf(firstCover, secondCover), catalog.loadChildren("series").map { it.mediaMetadata.artworkUri })
        assertEquals(firstCover, catalog.loadItem("series:a")?.mediaMetadata?.artworkUri)

        database.seriesDao().upsertAll((1..120).map { SeriesEntity("extra-$it", "Other $it") })
        assertTrue(catalog.loadChildren("series").any { it.mediaId == "series:bucket:A" })
        assertEquals(firstCover, catalog.loadChildren("series:bucket:A").single().mediaMetadata.artworkUri)
        for (parentId in listOf("series", "series:bucket:A")) {
            assertEquals(MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM,
                catalog.loadItem(parentId)?.mediaMetadata?.extras?.getInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE))
        }

        // Include the literal null stored by older catalog parsing as well as absent and blank paths.
        for (missingPath in listOf(null, "", " ", "null")) {
            database.bookDao().upsertAll(listOf(book("one", "Z title").copy(coverPath = missingPath)))
            assertEquals(secondCover, catalog.loadItem("series:a")?.mediaMetadata?.artworkUri)
        }
        database.bookDao().upsertAll(listOf(book("two", "A title")))
        val fallback = catalog.loadItem("series:a")?.mediaMetadata?.artworkUri
        assertEquals("android.resource", fallback?.scheme)
        assertEquals("ic_menu_series", fallback?.lastPathSegment)

        val germanContext = context.createConfigurationContext(Configuration(context.resources.configuration).apply {
            setLocale(Locale.GERMAN)
        })
        assertEquals("2 Hörbücher", ShelfDriveMediaCatalog(germanContext, repository)
            .loadItem("series:a")?.mediaMetadata?.artist.toString())
    }

    @Test
    fun groupsLargeSeriesListsUsingExistingAlphabetBuckets() = runBlocking {
        database.seriesDao().upsertAll((1..120).map { SeriesEntity("s$it", "Alpha $it") })
        assertTrue(repository.getSeriesRoot() is BrowseCollection.Direct)
        database.seriesDao().upsertAll(listOf(SeriesEntity("other", "9 lives"), SeriesEntity("accent", "Äther")))
        val grouped = repository.getSeriesRoot() as BrowseCollection.Grouped
        assertEquals(mapOf("#" to 1, "A" to 121), grouped.groups.associate { it.key to it.count })
        assertEquals(121, repository.getSeriesForBucket("A").size)
        assertEquals("other", repository.getSeriesForBucket("#").single().series.id)
        assertEquals(BrowseNodeId.SeriesBucket("A"), BrowseNodeId.parse("series:bucket:A"))
    }

    @Test
    fun distinguishesLoadingFailureAndEmptySeries() = runBlocking {
        assertStatus(R.string.media_series_sync_required)
        database.syncStateDao().upsert(SyncStateEntity(0, "RUNNING", null, null, 1, 2, 0))
        assertStatus(R.string.media_series_loading)
        database.syncStateDao().upsert(SyncStateEntity(0, "FAILED", null, "failed", 1, 2, 0))
        assertStatus(R.string.media_series_sync_failed)
        seedBooks()
        assertStatus(R.string.media_series_empty)
        database.seriesDao().upsertAll(listOf(SeriesEntity("a", "Unavailable")))
        database.bookSeriesCrossRefDao().upsertAll(listOf(BookSeriesCrossRef("missing", "a", "1")))
        val item = catalog.loadChildren("series:a").single()
        assertEquals(context.getString(R.string.media_series_no_playable_books), item.mediaMetadata.title.toString())
        assertFalse(item.mediaMetadata.isPlayable == true)
    }

    private suspend fun assertStatus(message: Int) {
        val item = catalog.loadChildren("series").single()
        assertEquals(context.getString(message), item.mediaMetadata.title.toString())
        assertFalse(item.mediaMetadata.isPlayable == true)
        assertFalse(item.mediaMetadata.isBrowsable == true)
        assertNull(BrowseNodeId.parse(item.mediaId))
    }

    private suspend fun seedBooks() {
        database.libraryDao().upsertAll(listOf(LibraryEntity("library")))
        database.bookDao().upsertAll(listOf(book("one", "One"), book("two", "Two"), book("missing", "Missing", false)))
        database.syncStateDao().upsert(SyncStateEntity(0, "SUCCESS", 1000, null, 1, 3, 0))
    }

    private fun book(id: String, title: String, playable: Boolean = true) = BookEntity(
        id = id, libraryId = "library", title = title, sortTitle = title,
        subtitle = null, description = null, coverPath = null, durationMs = null,
        authorDisplay = null, isPlayable = playable,
    )
}
