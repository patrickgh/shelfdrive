package io.audiobookshelf.aaos.catalog.persistence

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        LibraryEntity::class,
        BookEntity::class,
        AuthorEntity::class,
        BookAuthorCrossRef::class,
        SeriesEntity::class,
        BookSeriesCrossRef::class,
        MediaProgressEntity::class,
        SyncStateEntity::class,
    ],
    version = 5,
    exportSchema = true,
)
abstract class CatalogDatabase : RoomDatabase() {
    abstract fun libraryDao(): LibraryDao
    abstract fun bookDao(): BookDao
    abstract fun authorDao(): AuthorDao
    abstract fun bookAuthorCrossRefDao(): BookAuthorCrossRefDao
    abstract fun seriesDao(): SeriesDao
    abstract fun bookSeriesCrossRefDao(): BookSeriesCrossRefDao
    abstract fun mediaProgressDao(): MediaProgressDao
    abstract fun syncStateDao(): SyncStateDao

    companion object {
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS series (id TEXT NOT NULL, name TEXT NOT NULL, PRIMARY KEY(id))")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_series_name ON series (name)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS book_series_cross_refs (
                        bookId TEXT NOT NULL, seriesId TEXT NOT NULL, sequence TEXT,
                        PRIMARY KEY(bookId, seriesId),
                        FOREIGN KEY(bookId) REFERENCES books(id) ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(seriesId) REFERENCES series(id) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_book_series_cross_refs_seriesId ON book_series_cross_refs (seriesId)")
                // Series have not been fetched yet. Preserve the catalog and progress, but request a refresh.
                db.execSQL("UPDATE sync_state SET lastSyncedAt = NULL, status = 'IDLE', lastSyncError = NULL")
            }
        }

        @Volatile
        private var instance: CatalogDatabase? = null

        fun getInstance(context: Context): CatalogDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    CatalogDatabase::class.java,
                    "catalog.db",
                )
                    .addMigrations(MIGRATION_4_5)
                    .fallbackToDestructiveMigrationFrom(true, 1, 2, 3)
                    .build()
                    .also { instance = it }
            }
        }
    }
}
