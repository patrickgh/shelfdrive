package io.audiobookshelf.aaos.auth

import io.audiobookshelf.aaos.absapi.ApiException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

class AuthenticatedRequestRunner(
    private val storage: AuthStorage,
    private val authRepository: AuthRepository,
) {
    suspend fun <T> execute(block: suspend (AuthenticatedRequestContext) -> T): T = withContext(Dispatchers.IO) {
        val initialContext = loadContextOrRecover()
        try {
            block(initialContext)
        } catch (exception: ApiException) {
            if (exception.statusCode != 401) {
                throw exception
            }

            val refreshedContext = refreshContext(exception)
            try {
                block(refreshedContext)
            } catch (secondException: ApiException) {
                if (secondException.statusCode == 401) {
                    throw AuthenticationRequiredException(
                        message = "Sitzung abgelaufen. Bitte erneut anmelden.",
                        cause = secondException,
                    )
                }
                throw secondException
            }
        }
    }

    private suspend fun loadContextOrRecover(): AuthenticatedRequestContext {
        loadContextOrNull()?.let { return it }

        val snapshot = authRepository.bootstrap()
        if (!snapshot.isAuthenticated) {
            throw AuthenticationRequiredException(
                snapshot.statusMessage ?: "Keine aktive Audiobookshelf-Sitzung verfuegbar.",
            )
        }
        return loadContextOrNull()
            ?: throw AuthenticationRequiredException("Keine aktive Audiobookshelf-Sitzung verfuegbar.")
    }

    private suspend fun refreshContext(cause: ApiException): AuthenticatedRequestContext {
        val snapshot = authRepository.login(
            requestedBaseUrl = null,
            requestedUsername = null,
            requestedPassword = null,
        ).takeIf { it.isAuthenticated }
            ?: authRepository.bootstrap()
        if (!snapshot.isAuthenticated) {
            throw AuthenticationRequiredException(
                message = snapshot.statusMessage ?: "Sitzung abgelaufen. Bitte erneut anmelden.",
                cause = cause,
            )
        }
        return loadContextOrNull()
            ?: throw AuthenticationRequiredException(
                message = "Keine aktive Audiobookshelf-Sitzung verfuegbar.",
                cause = cause,
            )
    }

    private fun loadContextOrNull(): AuthenticatedRequestContext? {
        val stored = storage.load()
        val validation = ServerUrlPolicy.validate(stored.baseUrl)
        if (!stored.baseUrl.isNullOrBlank() && validation.errorMessage != null) {
            throw AuthenticationRequiredException(validation.errorMessage)
        }

        val baseUrl = validation.normalizedUrl ?: return null
        val accessToken = stored.accessToken?.takeIf { it.isNotBlank() } ?: return null
        return AuthenticatedRequestContext(
            baseUrl = baseUrl,
            accessToken = accessToken,
            username = stored.username?.trim()?.takeIf { it.isNotBlank() },
        )
    }
}

data class AuthenticatedRequestContext(
    val baseUrl: String,
    val accessToken: String,
    val username: String?,
)

class AuthenticationRequiredException(
    override val message: String,
    override val cause: Throwable? = null,
) : IOException(message, cause)
