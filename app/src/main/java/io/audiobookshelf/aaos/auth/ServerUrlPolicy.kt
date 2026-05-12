package io.audiobookshelf.aaos.auth

import java.net.URI

object ServerUrlPolicy {
    fun validate(rawUrl: String?): ServerUrlValidation {
        val normalizedUrl = rawUrl?.trim()?.removeSuffix("/")?.takeUnless { it.isBlank() }
            ?: return ServerUrlValidation()

        val uri = runCatching { URI(normalizedUrl) }.getOrNull()
            ?: return ServerUrlValidation(errorMessage = "Server-URL ist ungueltig.")
        val scheme = uri.scheme?.lowercase()
            ?: return ServerUrlValidation(errorMessage = "Server-URL muss mit https:// beginnen.")
        uri.host
            ?: return ServerUrlValidation(errorMessage = "Server-URL enthaelt keinen Host.")

        if (!uri.userInfo.isNullOrBlank()) {
            return ServerUrlValidation(errorMessage = "Server-URL darf keine Zugangsdaten enthalten.")
        }
        if (!uri.query.isNullOrBlank() || !uri.fragment.isNullOrBlank()) {
            return ServerUrlValidation(errorMessage = "Server-URL darf keine Query-Parameter oder Fragmente enthalten.")
        }

        return when {
            scheme == "https" -> ServerUrlValidation(normalizedUrl = normalizedUrl)
            scheme == "http" -> ServerUrlValidation(errorMessage = "HTTP ist nicht erlaubt. Bitte HTTPS verwenden.")
            else -> ServerUrlValidation(errorMessage = "Server-URL muss mit https:// beginnen.")
        }
    }
}

data class ServerUrlValidation(
    val normalizedUrl: String? = null,
    val errorMessage: String? = null,
)
