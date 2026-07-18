package io.audiobookshelf.aaos.catalog.persistence

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

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
                    .fallbackToDestructiveMigrationFrom(true, 1, 2, 3)
                    .build()
                    .also { instance = it }
            }
        }
    }
}
