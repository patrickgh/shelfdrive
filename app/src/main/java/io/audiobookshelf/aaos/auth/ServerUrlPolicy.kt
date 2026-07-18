package io.audiobookshelf.aaos.auth

import io.audiobookshelf.aaos.status.UserVisibleStatus
import java.net.URI

object ServerUrlPolicy {
    fun validate(rawUrl: String?): ServerUrlValidation {
        val normalizedUrl = rawUrl?.trim()?.removeSuffix("/")?.takeUnless { it.isBlank() }
            ?: return ServerUrlValidation()

        val uri = runCatching { URI(normalizedUrl) }.getOrNull()
            ?: return ServerUrlValidation(errorCode = UserVisibleStatus.SERVER_URL_INVALID)
        val scheme = uri.scheme?.lowercase()
            ?: return ServerUrlValidation(errorCode = UserVisibleStatus.SERVER_URL_SCHEME_REQUIRED)
        uri.host
            ?: return ServerUrlValidation(errorCode = UserVisibleStatus.SERVER_URL_HOST_REQUIRED)

        if (!uri.userInfo.isNullOrBlank()) {
            return ServerUrlValidation(errorCode = UserVisibleStatus.SERVER_URL_CREDENTIALS_FORBIDDEN)
        }
        if (!uri.query.isNullOrBlank() || !uri.fragment.isNullOrBlank()) {
            return ServerUrlValidation(errorCode = UserVisibleStatus.SERVER_URL_QUERY_FORBIDDEN)
        }

        return when {
            scheme == "https" -> ServerUrlValidation(normalizedUrl = normalizedUrl)
            scheme == "http" -> ServerUrlValidation(errorCode = UserVisibleStatus.SERVER_URL_PUBLIC_HTTP_FORBIDDEN)
            else -> ServerUrlValidation(errorCode = UserVisibleStatus.SERVER_URL_SCHEME_REQUIRED)
        }
    }
}

data class ServerUrlValidation(
    val normalizedUrl: String? = null,
    val errorCode: String? = null,
)
