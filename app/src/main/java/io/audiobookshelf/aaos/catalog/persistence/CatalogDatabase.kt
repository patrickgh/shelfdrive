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
    version = 3,
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
                    .addMigrations(MIGRATION_2_3)
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
    }
}
