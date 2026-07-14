package io.audiobookshelf.aaos.absapi

import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

object NetworkPolicy {
    const val CONNECT_TIMEOUT_MS = 5_000
    const val READ_TIMEOUT_MS = 15_000
    const val WRITE_TIMEOUT_MS = 15_000

    private val backgroundRetryDelaysMs = listOf(2_000L, 5_000L, 15_000L)

    fun retryDelays(profile: RetryProfile): List<Long> {
        return when (profile) {
            RetryProfile.NONE -> emptyList()
            RetryProfile.SESSION_SYNC -> listOf(0L)
            RetryProfile.FOREGROUND_IDEMPOTENT -> listOf(0L)
            RetryProfile.BACKGROUND_REFRESH -> backgroundRetryDelaysMs
        }
    }

    fun shouldRetry(exception: IOException): Boolean {
        return exception is SocketTimeoutException ||
            exception is UnknownHostException ||
            exception is ConnectException ||
            exception is SocketException
    }

    fun shouldRetryHttpStatus(statusCode: Int, profile: RetryProfile): Boolean {
        if (profile == RetryProfile.NONE) {
            return false
        }
        return statusCode in setOf(502, 503, 504)
    }
}

enum class RetryProfile {
    NONE,
    SESSION_SYNC,
    FOREGROUND_IDEMPOTENT,
    BACKGROUND_REFRESH,
}
