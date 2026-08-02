package io.audiobookshelf.aaos.cache

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import io.audiobookshelf.aaos.catalog.persistence.CatalogDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class CacheRepository(
    context: Context,
    private val database: CatalogDatabase,
) {
    private val appContext = context.applicationContext

    suspend fun loadSnapshot(): CacheSnapshot = withContext(Dispatchers.IO) {
        buildSnapshot()
    }

    suspend fun clearCache(): CacheSnapshot = withContext(Dispatchers.IO) {
        database.withTransaction {
            database.mediaProgressDao().clearAll()
            database.bookAuthorCrossRefDao().clearAll()
            database.bookDao().clearAll()
            database.authorDao().clearAll()
            database.libraryDao().clearAll()
            database.syncStateDao().clearAll()
        }

        File(appContext.cacheDir, ARTWORK_CACHE_DIR).deleteRecursively()
        PlaybackAudioCache.clear(appContext)
        compactDatabase()
        buildSnapshot(clearedAt = System.currentTimeMillis())
    }

    private fun buildSnapshot(clearedAt: Long? = null): CacheSnapshot {
        val catalog = databaseFiles().fold(Size()) { total, file -> total + file.sizeRecursively() }
        val artwork = File(appContext.cacheDir, ARTWORK_CACHE_DIR).sizeRecursively()
        val audio = PlaybackAudioCache.directory(appContext).sizeRecursively()
        val total = catalog + artwork + audio
        return CacheSnapshot(
            totalBytes = total.bytes,
            catalogBytes = catalog.bytes,
            artworkBytes = artwork.bytes,
            audioBytes = audio.bytes,
            fileCount = total.fileCount,
            clearedAt = clearedAt,
        )
    }

    private fun compactDatabase() {
        runCatching {
            val writableDatabase = database.openHelper.writableDatabase
            writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").use { /* Drain checkpoint cursor. */ }
            writableDatabase.execSQL("VACUUM")
        }.onFailure { exception ->
            Log.w(TAG, "Could not compact catalog database after clearing cache.", exception)
        }
    }

    private fun databaseFiles(): List<File> {
        val databaseFile = appContext.getDatabasePath(DATABASE_NAME)
        return listOf(
            databaseFile,
            File("${databaseFile.path}-wal"),
            File("${databaseFile.path}-shm"),
        )
    }

    private fun File.sizeRecursively(): Size {
        return walkTopDown()
            .filter { it.isFile }
            .fold(Size()) { total, file ->
                Size(total.bytes + file.length(), total.fileCount + 1)
            }
    }

    private data class Size(
        val bytes: Long = 0L,
        val fileCount: Int = 0,
    ) {
        operator fun plus(other: Size) = Size(bytes + other.bytes, fileCount + other.fileCount)
    }

    companion object {
        private const val TAG = "CacheRepository"
        private const val DATABASE_NAME = "catalog.db"
        private const val ARTWORK_CACHE_DIR = "artwork"
    }
}
