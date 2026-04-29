package io.audiobookshelf.aaos.auth

import java.net.URI
import java.util.Locale

object ServerUrlPolicy {
    fun validate(rawUrl: String?): ServerUrlValidation {
        val normalizedUrl = rawUrl?.trim()?.removeSuffix("/")?.takeUnless { it.isBlank() }
            ?: return ServerUrlValidation()

        val uri = runCatching { URI(normalizedUrl) }.getOrNull()
            ?: return ServerUrlValidation(errorMessage = "Server-URL ist ungueltig.")
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
            ?: return ServerUrlValidation(errorMessage = "Server-URL muss mit https:// oder http:// beginnen.")
        val host = uri.host?.lowercase(Locale.ROOT)
            ?: return ServerUrlValidation(errorMessage = "Server-URL enthaelt keinen Host.")

        if (!uri.userInfo.isNullOrBlank()) {
            return ServerUrlValidation(errorMessage = "Server-URL darf keine Zugangsdaten enthalten.")
        }
        if (!uri.query.isNullOrBlank() || !uri.fragment.isNullOrBlank()) {
            return ServerUrlValidation(errorMessage = "Server-URL darf keine Query-Parameter oder Fragmente enthalten.")
        }

        return when {
            scheme == "https" -> ServerUrlValidation(normalizedUrl = normalizedUrl)
            scheme == "http" && host.isAllowedCleartextHost() -> ServerUrlValidation(normalizedUrl = normalizedUrl)
            scheme == "http" -> ServerUrlValidation(
                errorMessage = "HTTP ist nur fuer lokale oder private Server erlaubt. Fuer oeffentliche Server bitte HTTPS verwenden.",
            )
            else -> ServerUrlValidation(errorMessage = "Server-URL muss mit https:// oder http:// beginnen.")
        }
    }

    private fun String.isAllowedCleartextHost(): Boolean {
        if (this == "localhost" || this == "::1" || !contains(".")) {
            return true
        }
        if (endsWith(".local") || endsWith(".lan") || endsWith(".home.arpa")) {
            return true
        }

        val octets = split(".").map { it.toIntOrNull() ?: return false }
        if (octets.size != 4 || octets.any { it !in 0..255 }) {
            return false
        }

        val first = octets[0]
        val second = octets[1]
        return first == 10 ||
            first == 127 ||
            (first == 172 && second in 16..31) ||
            (first == 192 && second == 168)
    }
}

data class ServerUrlValidation(
    val normalizedUrl: String? = null,
    val errorMessage: String? = null,
)
