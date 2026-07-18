package io.audiobookshelf.aaos.auth

import io.audiobookshelf.aaos.absapi.ApiException
import io.audiobookshelf.aaos.status.UserVisibleStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException

class AuthenticatedRequestRunner(
    private val storage: AuthStorage,
    private val authRepository: AuthRepository,
) {
    private val recoveryMutex = Mutex()

    suspend fun <T> execute(block: suspend (AuthenticatedRequestContext) -> T): T = withContext(Dispatchers.IO) {
        val initialContext = loadContextOrRecover()
        try {
            block(initialContext)
        } catch (exception: ApiException) {
            if (exception.statusCode != 401) {
                throw exception
            }

            val refreshedContext = refreshContext(initialContext, exception)
            try {
                block(refreshedContext)
            } catch (secondException: ApiException) {
                if (secondException.statusCode == 401) {
                    throw AuthenticationRequiredException(
                        message = UserVisibleStatus.SESSION_EXPIRED,
                        cause = secondException,
                    )
                }
                throw secondException
            }
        }
    }

    private suspend fun loadContextOrRecover(): AuthenticatedRequestContext {
        loadContextOrNull()?.let { return it }

        return recoveryMutex.withLock {
            loadContextOrNull()?.let { return@withLock it }
            val snapshot = authRepository.bootstrap()
            if (!snapshot.isAuthenticated) {
                throw AuthenticationRequiredException(
                    snapshot.statusMessage ?: UserVisibleStatus.NO_ACTIVE_SESSION,
                )
            }
            loadContextOrNull()
                ?: throw AuthenticationRequiredException(UserVisibleStatus.NO_ACTIVE_SESSION)
        }
    }

    private suspend fun refreshContext(
        failedContext: AuthenticatedRequestContext,
        cause: ApiException,
    ): AuthenticatedRequestContext = recoveryMutex.withLock {
        loadContextOrNull()
            ?.takeIf { it.accessToken != failedContext.accessToken }
            ?.let { return@withLock it }

        val snapshot = authRepository.recoverAfterUnauthorized()
        if (!snapshot.isAuthenticated) {
            throw AuthenticationRequiredException(
                message = snapshot.statusMessage ?: UserVisibleStatus.SESSION_EXPIRED,
                cause = cause,
            )
        }
        loadContextOrNull()
            ?: throw AuthenticationRequiredException(
                message = UserVisibleStatus.NO_ACTIVE_SESSION,
                cause = cause,
            )
    }

    private fun loadContextOrNull(): AuthenticatedRequestContext? {
        val stored = storage.load()
        val validation = ServerUrlPolicy.validate(stored.baseUrl)
        if (!stored.baseUrl.isNullOrBlank() && validation.errorCode != null) {
            throw AuthenticationRequiredException(validation.errorCode)
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
