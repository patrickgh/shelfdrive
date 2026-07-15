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
    private const val WARM_CACHE_TARGET_BYTES = 112L * 1024L * 1024L
    private const val PRE_CACHE_CHUNK_BYTES = 8L * 1024L * 1024L
    private const val MIN_PLAYBACK_CACHE_BYTES = 512L * 1024L
    private const val CACHE_KEY_PREFIX = "shelfdrive-audio-v1"

    @Volatile
    private var cache: SimpleCache? = null

    data class PreCacheRequest(
        val uri: String,
        val cacheKey: String,
        val positionMs: Long,
        val durationMs: Long?,
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

    fun preCache(
        context: Context,
        upstreamFactory: DataSource.Factory,
        requests: List<PreCacheRequest>,
    ) {
        if (requests.isEmpty()) {
            return
        }
        val activeCache = getCache(context)
        val dataSourceFactory = cacheDataSourceFactory(activeCache, upstreamFactory)
        ensureCachedAtPosition(activeCache, dataSourceFactory, requests.first())
        val cacheKeys = requests.mapTo(linkedSetOf()) { it.cacheKey }

        requests.forEach { request ->
            var startByte = estimateStartByte(activeCache, request)
            while (cachedBytes(activeCache, cacheKeys) < WARM_CACHE_TARGET_BYTES) {
                val requestedBytes = requestLength(activeCache, request.cacheKey, startByte)
                if (requestedBytes <= 0L) {
                    break
                }
                cacheChunk(dataSourceFactory, request, startByte, requestedBytes)
                val contentLength = contentLength(activeCache, request.cacheKey)
                if (contentLength == C.LENGTH_UNSET.toLong()) {
                    break
                }
                startByte += requestedBytes
            }
        }
    }

    fun ensureCachedAtPosition(
        context: Context,
        upstreamFactory: DataSource.Factory,
        request: PreCacheRequest,
    ): Boolean {
        val activeCache = getCache(context)
        return ensureCachedAtPosition(
            activeCache,
            cacheDataSourceFactory(activeCache, upstreamFactory),
            request,
        )
    }

    fun clear(context: Context) {
        val activeCache = getCache(context)
        activeCache.keys.toList().forEach { key ->
            activeCache.removeResource(key)
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

    private fun ensureCachedAtPosition(
        cache: SimpleCache,
        dataSourceFactory: CacheDataSource.Factory,
        request: PreCacheRequest,
    ): Boolean {
        if (isCachedAtPosition(cache, request.cacheKey, request.positionMs, request.durationMs)) {
            return true
        }
        if (cache.getCachedLength(request.cacheKey, 0L, MIN_PLAYBACK_CACHE_BYTES) < MIN_PLAYBACK_CACHE_BYTES) {
            val requestedBytes = requestLength(cache, request.cacheKey, 0L)
            if (requestedBytes > 0L) {
                cacheChunk(dataSourceFactory, request, 0L, requestedBytes)
            }
        }
        if (!isCachedAtPosition(cache, request.cacheKey, request.positionMs, request.durationMs)) {
            val startByte = estimateStartByte(cache, request)
            val requestedBytes = requestLength(cache, request.cacheKey, startByte)
            if (requestedBytes > 0L) {
                cacheChunk(dataSourceFactory, request, startByte, requestedBytes)
            }
        }
        return isCachedAtPosition(cache, request.cacheKey, request.positionMs, request.durationMs)
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

    private fun estimateStartByte(cache: SimpleCache, request: PreCacheRequest): Long {
        val contentLength = contentLength(cache, request.cacheKey)
        val durationMs = request.durationMs
        if (contentLength == C.LENGTH_UNSET.toLong() || contentLength <= 0L || durationMs == null || durationMs <= 0L) {
            return 0L
        }
        val ratio = request.positionMs.coerceIn(0L, durationMs).toDouble() / durationMs.toDouble()
        val estimatedByte = (contentLength.toDouble() * ratio).roundToLong()
        return (estimatedByte - PRE_CACHE_CHUNK_BYTES / 2L)
            .coerceAtLeast(0L)
            .coerceAtMost((contentLength - PRE_CACHE_CHUNK_BYTES).coerceAtLeast(0L))
    }

    private fun requestLength(cache: SimpleCache, cacheKey: String, startByte: Long): Long {
        val contentLength = contentLength(cache, cacheKey)
        if (contentLength == C.LENGTH_UNSET.toLong()) {
            return PRE_CACHE_CHUNK_BYTES
        }
        if (contentLength <= startByte) {
            return 0L
        }
        return (contentLength - startByte).coerceAtMost(PRE_CACHE_CHUNK_BYTES)
    }

    private fun cacheDataSourceFactory(
        cache: SimpleCache,
        upstreamFactory: DataSource.Factory,
    ): CacheDataSource.Factory {
        return CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(upstreamFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    private fun cacheChunk(
        dataSourceFactory: CacheDataSource.Factory,
        request: PreCacheRequest,
        startByte: Long,
        requestedBytes: Long,
    ) {
        val dataSpec = DataSpec.Builder()
            .setUri(request.uri)
            .setKey(request.cacheKey)
            .setPosition(startByte)
            .setLength(requestedBytes)
            .build()
        CacheWriter(dataSourceFactory.createDataSourceForDownloading(), dataSpec, null, null).cache()
    }

    private fun cachedBytes(cache: SimpleCache, cacheKeys: Set<String>): Long {
        return cacheKeys.sumOf { cacheKey ->
            cache.getCachedSpans(cacheKey).sumOf { span -> span.length }
        }
    }

    private fun contentLength(cache: SimpleCache, cacheKey: String): Long {
        return ContentMetadata.getContentLength(cache.getContentMetadata(cacheKey))
    }
}
