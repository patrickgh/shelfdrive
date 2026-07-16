package io.audiobookshelf.aaos.cache

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File
import kotlin.math.roundToLong

@OptIn(UnstableApi::class)
object PlaybackAudioCache {
    const val DIRECTORY_NAME = "media"

    private const val MAX_CACHE_BYTES = 128L * 1024L * 1024L
    private const val MIN_PLAYBACK_CACHE_BYTES = 512L * 1024L
    private const val CACHE_KEY_PREFIX = "shelfdrive-audio-v1"

    @Volatile
    private var cache: SimpleCache? = null

    data class TrackCacheRequest(
        val uri: String,
        val cacheKey: String,
    )

    fun stableCacheKey(bookId: String, trackId: String): String {
        return "$CACHE_KEY_PREFIX:${bookId.trim()}:${trackId.trim()}"
    }

    fun createDataSourceFactory(
        context: Context,
        upstreamFactory: DataSource.Factory,
    ): DataSource.Factory {
        return DataSource.Factory {
            CacheDataSource.Factory()
                .setCache(getCache(context))
                .setUpstreamDataSourceFactory(upstreamFactory)
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
                .createDataSource()
        }
    }

    fun clear(context: Context) {
        val activeCache = getCache(context)
        activeCache.keys.toList().forEach { key ->
            activeCache.removeResource(key)
        }
    }

    fun cacheTracks(
        context: Context,
        upstreamFactory: DataSource.Factory,
        requests: List<TrackCacheRequest>,
    ) {
        if (requests.isEmpty()) {
            return
        }
        val dataSourceFactory = CacheDataSource.Factory()
            .setCache(getCache(context))
            .setUpstreamDataSourceFactory(upstreamFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

        requests.forEach { request ->
            val dataSpec = DataSpec.Builder()
                .setUri(request.uri)
                .setKey(request.cacheKey)
                .build()
            CacheWriter(dataSourceFactory.createDataSourceForDownloading(), dataSpec, null, null).cache()
        }
    }

    fun directory(context: Context): File {
        val appContext = context.applicationContext
        val preferred = File(appContext.noBackupFilesDir, DIRECTORY_NAME)
        val legacy = File(appContext.cacheDir, DIRECTORY_NAME)
        if (!preferred.exists() && legacy.exists() && !legacy.renameTo(preferred)) {
            return legacy
        }
        return preferred
    }

    fun hasCachedDataAtPosition(
        context: Context,
        cacheKey: String,
        positionMs: Long,
        durationMs: Long?,
    ): Boolean {
        return isCachedAtPosition(getCache(context), cacheKey, positionMs, durationMs)
    }

    private fun isCachedAtPosition(
        cache: SimpleCache,
        cacheKey: String,
        positionMs: Long,
        durationMs: Long?,
    ): Boolean {
        val contentLength = contentLength(cache, cacheKey)
        if (contentLength == C.LENGTH_UNSET.toLong() || contentLength <= 0L || durationMs == null || durationMs <= 0L) {
            return positionMs <= 0L &&
                cache.getCachedLength(cacheKey, 0L, MIN_PLAYBACK_CACHE_BYTES) >= MIN_PLAYBACK_CACHE_BYTES
        }
        val ratio = positionMs.coerceIn(0L, durationMs).toDouble() / durationMs.toDouble()
        val estimatedByte = (contentLength.toDouble() * ratio).roundToLong()
            .coerceIn(0L, (contentLength - 1L).coerceAtLeast(0L))
        val requiredBytes = (contentLength - estimatedByte).coerceAtMost(MIN_PLAYBACK_CACHE_BYTES)
        return cache.getCachedLength(cacheKey, estimatedByte, requiredBytes) >= requiredBytes
    }

    private fun getCache(context: Context): SimpleCache {
        cache?.let { return it }
        return synchronized(this) {
            cache ?: SimpleCache(
                directory(context),
                LeastRecentlyUsedCacheEvictor(MAX_CACHE_BYTES),
                StandaloneDatabaseProvider(context.applicationContext),
            ).also { cache = it }
        }
    }

    private fun contentLength(cache: SimpleCache, cacheKey: String): Long {
        return ContentMetadata.getContentLength(cache.getContentMetadata(cacheKey))
    }
}
