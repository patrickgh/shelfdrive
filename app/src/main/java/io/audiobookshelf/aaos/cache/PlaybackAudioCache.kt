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
    private const val PRE_CACHE_BYTES_PER_TRACK = 8L * 1024L * 1024L
    private const val CACHE_KEY_PREFIX = "shelfdrive-audio-v1"

    @Volatile
    private var cache: SimpleCache? = null

    data class PreCacheRequest(
        val uri: String,
        val cacheKey: String,
        val positionMs: Long,
        val durationMs: Long?,
    )

    data class PreCacheResult(
        val cacheKey: String,
        val startByte: Long,
        val requestedBytes: Long,
    )

    fun stableCacheKey(bookId: String, trackId: String): String {
        return "$CACHE_KEY_PREFIX:${bookId.trim()}:${trackId.trim()}"
    }

    fun createDataSourceFactory(
        context: Context,
        upstreamFactory: DataSource.Factory,
    ): DataSource.Factory {
        return CacheDataSource.Factory()
            .setCache(getCache(context))
            .setUpstreamDataSourceFactory(upstreamFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    fun preCache(
        context: Context,
        upstreamFactory: DataSource.Factory,
        requests: List<PreCacheRequest>,
    ): List<PreCacheResult> {
        if (requests.isEmpty()) {
            return emptyList()
        }
        val activeCache = getCache(context)
        val dataSourceFactory = CacheDataSource.Factory()
            .setCache(activeCache)
            .setUpstreamDataSourceFactory(upstreamFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

        return requests.map { request ->
            val startByte = estimateStartByte(activeCache, request)
            val requestedBytes = requestLength(activeCache, request.cacheKey, startByte)
            val dataSpec = DataSpec.Builder()
                .setUri(request.uri)
                .setKey(request.cacheKey)
                .setPosition(startByte)
                .setLength(requestedBytes)
                .build()
            CacheWriter(
                dataSourceFactory.createDataSourceForDownloading(),
                dataSpec,
                null,
                null,
            ).cache()
            PreCacheResult(
                cacheKey = request.cacheKey,
                startByte = startByte,
                requestedBytes = requestedBytes,
            )
        }
    }

    fun clear(context: Context) {
        val activeCache = getCache(context)
        activeCache.keys.toList().forEach { key ->
            activeCache.removeResource(key)
        }
    }

    private fun getCache(context: Context): SimpleCache {
        cache?.let { return it }
        return synchronized(this) {
            cache ?: SimpleCache(
                File(context.applicationContext.cacheDir, DIRECTORY_NAME),
                LeastRecentlyUsedCacheEvictor(MAX_CACHE_BYTES),
                StandaloneDatabaseProvider(context.applicationContext),
            ).also { cache = it }
        }
    }

    private fun estimateStartByte(cache: SimpleCache, request: PreCacheRequest): Long {
        val contentLength = ContentMetadata.getContentLength(cache.getContentMetadata(request.cacheKey))
        val durationMs = request.durationMs
        if (contentLength == C.LENGTH_UNSET.toLong() || contentLength <= 0L || durationMs == null || durationMs <= 0L) {
            return 0L
        }
        val ratio = request.positionMs.coerceIn(0L, durationMs).toDouble() / durationMs.toDouble()
        val estimatedByte = (contentLength.toDouble() * ratio).roundToLong()
        return (estimatedByte - PRE_CACHE_BYTES_PER_TRACK / 2L)
            .coerceAtLeast(0L)
            .coerceAtMost((contentLength - PRE_CACHE_BYTES_PER_TRACK).coerceAtLeast(0L))
    }

    private fun requestLength(cache: SimpleCache, cacheKey: String, startByte: Long): Long {
        val contentLength = ContentMetadata.getContentLength(cache.getContentMetadata(cacheKey))
        if (contentLength == C.LENGTH_UNSET.toLong() || contentLength <= startByte) {
            return PRE_CACHE_BYTES_PER_TRACK
        }
        return (contentLength - startByte).coerceAtMost(PRE_CACHE_BYTES_PER_TRACK)
    }
}
