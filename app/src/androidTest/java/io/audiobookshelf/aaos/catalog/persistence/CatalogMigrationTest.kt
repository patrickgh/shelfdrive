package io.audiobookshelf.aaos.catalog.persistence

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CatalogMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), CatalogDatabase::class.java)

    @Test
    fun migrate4To5PreservesCatalogAndPendingProgressAndRequestsSync() {
        helper.createDatabase("catalog-migration-test", 4).apply {
            execSQL("INSERT INTO libraries (id) VALUES ('library')")
            execSQL("INSERT INTO books (id, libraryId, title, sortTitle, isPlayable) VALUES ('book', 'library', 'Book', 'Book', 1)")
            execSQL("INSERT INTO authors (id, name, sortName, numBooks) VALUES ('author', 'Author', 'Author', 1)")
            execSQL("INSERT INTO book_author_cross_refs (bookId, authorId) VALUES ('book', 'author')")
            execSQL(
                """
                INSERT INTO media_progress (bookId, currentTimeMs, durationMs, isFinished,
                    hideFromContinueListening, lastUpdateAt, startedAt, finishedAt, pendingUpload)
                VALUES ('book', 123456, 900000, 0, 0, 1000, 500, NULL, 1)
                """.trimIndent(),
            )
            execSQL("INSERT INTO sync_state VALUES (0, 'SUCCESS', 1000, NULL, 1, 1, 1)")
            close()
        }

        helper.runMigrationsAndValidate("catalog-migration-test", 5, true, CatalogDatabase.MIGRATION_4_5).apply {
            query("SELECT b.title, p.currentTimeMs, p.pendingUpload FROM books b JOIN media_progress p ON p.bookId = b.id").use {
                assertTrue(it.moveToFirst())
                assertEquals("Book", it.getString(0))
                assertEquals(123456L, it.getLong(1))
                assertEquals(1, it.getInt(2))
            }
            query("SELECT COUNT(*) FROM book_author_cross_refs").use {
                assertTrue(it.moveToFirst())
                assertEquals(1, it.getInt(0))
            }
            query("SELECT status, lastSyncedAt, bookCount FROM sync_state").use {
                assertTrue(it.moveToFirst())
                assertEquals("IDLE", it.getString(0))
                assertTrue(it.isNull(1))
                assertEquals(1, it.getInt(2))
            }
            query("SELECT COUNT(*) FROM series").use {
                assertTrue(it.moveToFirst())
                assertEquals(0, it.getInt(0))
            }
            execSQL("INSERT INTO series (id, name) VALUES ('series', 'Series')")
            execSQL("INSERT INTO book_series_cross_refs (bookId, seriesId, sequence) VALUES ('book', 'series', '1.5')")
            close()
        }
    }
}
