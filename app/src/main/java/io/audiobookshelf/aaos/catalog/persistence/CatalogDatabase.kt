package io.audiobookshelf.aaos.catalog.persistence

import android.content.Context
import androidx.room.Database
import androidx.room.migration.Migration
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        LibraryEntity::class,
        BookEntity::class,
        AuthorEntity::class,
        BookAuthorCrossRef::class,
        MediaProgressEntity::class,
        SyncStateEntity::class,
    ],
    version = 4,
    exportSchema = true,
)
abstract class CatalogDatabase : RoomDatabase() {
    abstract fun libraryDao(): LibraryDao
    abstract fun bookDao(): BookDao
    abstract fun authorDao(): AuthorDao
    abstract fun bookAuthorCrossRefDao(): BookAuthorCrossRefDao
    abstract fun mediaProgressDao(): MediaProgressDao
    abstract fun syncStateDao(): SyncStateDao

    companion object {
        @Volatile
        private var instance: CatalogDatabase? = null

        fun getInstance(context: Context): CatalogDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    CatalogDatabase::class.java,
                    "catalog.db",
                )
                    .addMigrations(MIGRATION_2_3, MIGRATION_3_4)
                    .fallbackToDestructiveMigrationFrom(true, 1)
                    .build()
                    .also { instance = it }
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE media_progress ADD COLUMN pendingUpload INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE book_author_cross_refs RENAME TO book_author_cross_refs_old")
                db.execSQL("ALTER TABLE media_progress RENAME TO media_progress_old")
                db.execSQL("ALTER TABLE books RENAME TO books_old")
                db.execSQL("ALTER TABLE libraries RENAME TO libraries_old")
                db.execSQL("ALTER TABLE sync_state RENAME TO sync_state_old")

                db.execSQL(
                    """
                    CREATE TABLE libraries (
                        id TEXT NOT NULL,
                        PRIMARY KEY(id)
                    )
                    """.trimIndent(),
                )
                db.execSQL("INSERT INTO libraries (id) SELECT id FROM libraries_old")

                db.execSQL(
                    """
                    CREATE TABLE books (
                        id TEXT NOT NULL,
                        libraryId TEXT NOT NULL,
                        title TEXT NOT NULL,
                        sortTitle TEXT NOT NULL,
                        subtitle TEXT,
                        description TEXT,
                        coverPath TEXT,
                        durationMs INTEGER,
                        authorDisplay TEXT,
                        isPlayable INTEGER NOT NULL,
                        PRIMARY KEY(id),
                        FOREIGN KEY(libraryId) REFERENCES libraries(id)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    INSERT INTO books (
                        id,
                        libraryId,
                        title,
                        sortTitle,
                        subtitle,
                        description,
                        coverPath,
                        durationMs,
                        authorDisplay,
                        isPlayable
                    )
                    SELECT
                        id,
                        libraryId,
                        title,
                        sortTitle,
                        subtitle,
                        description,
                        coverPath,
                        durationMs,
                        authorDisplay,
                        isPlayable
                    FROM books_old
                    """.trimIndent(),
                )

                db.execSQL(
                    """
                    CREATE TABLE book_author_cross_refs (
                        bookId TEXT NOT NULL,
                        authorId TEXT NOT NULL,
                        PRIMARY KEY(bookId, authorId),
                        FOREIGN KEY(bookId) REFERENCES books(id)
                            ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(authorId) REFERENCES authors(id)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    INSERT INTO book_author_cross_refs (bookId, authorId)
                    SELECT bookId, authorId FROM book_author_cross_refs_old
                    """.trimIndent(),
                )

                db.execSQL(
                    """
                    CREATE TABLE media_progress (
                        bookId TEXT NOT NULL,
                        currentTimeMs INTEGER NOT NULL,
                        durationMs INTEGER,
                        isFinished INTEGER NOT NULL,
                        hideFromContinueListening INTEGER NOT NULL,
                        lastUpdateAt INTEGER NOT NULL,
                        startedAt INTEGER,
                        finishedAt INTEGER,
                        pendingUpload INTEGER NOT NULL,
                        PRIMARY KEY(bookId),
                        FOREIGN KEY(bookId) REFERENCES books(id)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    INSERT INTO media_progress (
                        bookId,
                        currentTimeMs,
                        durationMs,
                        isFinished,
                        hideFromContinueListening,
                        lastUpdateAt,
                        startedAt,
                        finishedAt,
                        pendingUpload
                    )
                    SELECT
                        bookId,
                        currentTimeMs,
                        durationMs,
                        isFinished,
                        hideFromContinueListening,
                        lastUpdateAt,
                        startedAt,
                        finishedAt,
                        pendingUpload
                    FROM media_progress_old
                    """.trimIndent(),
                )

                db.execSQL(
                    """
                    CREATE TABLE sync_state (
                        id INTEGER NOT NULL,
                        status TEXT NOT NULL,
                        lastSyncedAt INTEGER,
                        lastSyncError TEXT,
                        libraryCount INTEGER NOT NULL,
                        bookCount INTEGER NOT NULL,
                        authorCount INTEGER NOT NULL,
                        PRIMARY KEY(id)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    INSERT INTO sync_state (
                        id,
                        status,
                        lastSyncedAt,
                        lastSyncError,
                        libraryCount,
                        bookCount,
                        authorCount
                    )
                    SELECT
                        id,
                        status,
                        COALESCE(lastFullSyncAt, lastDeltaSyncAt),
                        lastSyncError,
                        libraryCount,
                        bookCount,
                        authorCount
                    FROM sync_state_old
                    """.trimIndent(),
                )

                db.execSQL("DROP TABLE book_author_cross_refs_old")
                db.execSQL("DROP TABLE media_progress_old")
                db.execSQL("DROP TABLE books_old")
                db.execSQL("DROP TABLE libraries_old")
                db.execSQL("DROP TABLE sync_state_old")

                db.execSQL("CREATE INDEX index_books_libraryId ON books(libraryId)")
                db.execSQL("CREATE INDEX index_books_sortTitle ON books(sortTitle)")
                db.execSQL("CREATE INDEX index_books_authorDisplay ON books(authorDisplay)")
                db.execSQL("CREATE INDEX index_book_author_cross_refs_authorId ON book_author_cross_refs(authorId)")
                db.execSQL("CREATE INDEX index_media_progress_lastUpdateAt ON media_progress(lastUpdateAt)")
                db.execSQL("CREATE INDEX index_media_progress_isFinished ON media_progress(isFinished)")
            }
        }
    }
}
