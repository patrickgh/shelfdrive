package io.audiobookshelf.aaos.auth

import android.util.Log
import io.audiobookshelf.aaos.absapi.ApiException
import io.audiobookshelf.aaos.absapi.AudiobookshelfApiClient
import io.audiobookshelf.aaos.absapi.AuthenticatedSession
import io.audiobookshelf.aaos.status.UserVisibleStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

class AuthRepository(
    private val storage: AuthStorage,
    private val apiClient: AudiobookshelfApiClient = AudiobookshelfApiClient(),
) {

    suspend fun bootstrap(): AuthSnapshot = withContext(Dispatchers.IO) {
        val stored = storage.load()
        val baseUrl = ServerUrlPolicy.validate(stored.baseUrl).normalizedUrl
        val username = stored.username?.trim().orEmpty()
        val accessToken = stored.accessToken

        if (baseUrl.isNullOrBlank() || username.isBlank()) {
            return@withContext AuthSnapshot(
                status = AuthStatus.LOGGED_OUT,
                hasStoredPassword = !stored.password.isNullOrBlank(),
            )
        }

        if (!accessToken.isNullOrBlank()) {
            try {
                val authorization = apiClient.authorize(baseUrl, accessToken, username)
                if (authorization.isAuthorized) {
                    val session = authorization.session
                    return@withContext buildAuthenticatedSnapshot(
                        baseUrl = baseUrl,
                        username = session?.username ?: username,
                        session = session,
                        hasStoredPassword = !stored.password.isNullOrBlank(),
                    )
                }
            } catch (exception: ApiException) {
                if (exception.statusCode == 401) {
                    return@withContext refreshOrReauthenticate(stored, baseUrl, username)
                }
                Log.w(TAG, "Token validation failed with API error ${exception.statusCode}. Keeping cached session alive.", exception)
                return@withContext AuthSnapshot(
                    status = AuthStatus.AUTHENTICATED,
                    baseUrl = baseUrl,
                    username = username,
                    statusMessage = UserVisibleStatus.SERVER_UNREACHABLE,
                    hasStoredPassword = !stored.password.isNullOrBlank(),
                )
            } catch (exception: IOException) {
                Log.w(TAG, "Token validation failed due to IO error. Keeping cached session alive.", exception)
                return@withContext AuthSnapshot(
                    status = AuthStatus.AUTHENTICATED,
                    baseUrl = baseUrl,
                    username = username,
                    statusMessage = UserVisibleStatus.SERVER_UNREACHABLE,
                    hasStoredPassword = !stored.password.isNullOrBlank(),
                )
            }
        }

        refreshOrReauthenticate(stored, baseUrl, username)
    }

    suspend fun login(
        requestedBaseUrl: String?,
        requestedUsername: String?,
        requestedPassword: String?,
    ): AuthSnapshot = withContext(Dispatchers.IO) {
        val stored = storage.load()
        val requestedUrlValidation = ServerUrlPolicy.validate(requestedBaseUrl)
        val storedUrlValidation = ServerUrlPolicy.validate(stored.baseUrl)
        val baseUrl = requestedUrlValidation.normalizedUrl ?: storedUrlValidation.normalizedUrl
        val username = requestedUsername?.trim().takeUnless { it.isNullOrBlank() } ?: stored.username?.trim()
        val password = requestedPassword?.takeUnless { it.isNullOrBlank() } ?: stored.password

        if (requestedUrlValidation.errorMessage != null || (storedUrlValidation.errorMessage != null && baseUrl == null)) {
            return@withContext AuthSnapshot(
                status = AuthStatus.LOGIN_FAILED,
                baseUrl = baseUrl,
                username = username,
                statusMessage = requestedUrlValidation.errorMessage ?: storedUrlValidation.errorMessage,
                hasStoredPassword = !stored.password.isNullOrBlank(),
            )
        }

        if (baseUrl.isNullOrBlank() || username.isNullOrBlank() || password.isNullOrBlank()) {
            return@withContext AuthSnapshot(
                status = AuthStatus.LOGIN_FAILED,
                baseUrl = baseUrl,
                username = username,
                statusMessage = UserVisibleStatus.LOGIN_FIELDS_REQUIRED,
                hasStoredPassword = !stored.password.isNullOrBlank(),
            )
        }

        return@withContext try {
            val session = apiClient.login(baseUrl, username, password)
            if (!session.isSupported) {
                return@withContext AuthSnapshot(
                    status = AuthStatus.LOGIN_FAILED,
                    baseUrl = baseUrl,
                    username = session.username,
                    serverVersion = session.serverVersion,
                    statusMessage = session.compatibilityWarning,
                    hasStoredPassword = !stored.password.isNullOrBlank(),
                )
            }

            val accessToken = session.accessToken
                ?: return@withContext AuthSnapshot(
                    status = AuthStatus.LOGIN_FAILED,
                    baseUrl = baseUrl,
                    username = session.username,
                    serverVersion = session.serverVersion,
                    statusMessage = UserVisibleStatus.LOGIN_MISSING_ACCESS_TOKEN,
                    hasStoredPassword = !stored.password.isNullOrBlank(),
                )

            storage.save(
                StoredAuthState(
                    baseUrl = baseUrl,
                    username = session.username,
                    password = password,
                    accessToken = accessToken,
                    refreshToken = session.refreshToken ?: stored.refreshToken,
                ),
            )

            buildAuthenticatedSnapshot(
                baseUrl = baseUrl,
                username = session.username,
                session = session,
                hasStoredPassword = true,
            )
        } catch (exception: ApiException) {
            Log.w(TAG, "Login failed with API error ${exception.statusCode}.", exception)
            AuthSnapshot(
                status = AuthStatus.LOGIN_FAILED,
                baseUrl = baseUrl,
                username = username,
                statusMessage = exception.message,
                hasStoredPassword = !stored.password.isNullOrBlank(),
            )
        } catch (exception: IOException) {
            Log.w(TAG, "Login failed due to IO error.", exception)
            AuthSnapshot(
                status = AuthStatus.LOGIN_FAILED,
                baseUrl = baseUrl,
                username = username,
                statusMessage = UserVisibleStatus.SERVER_UNREACHABLE_OR_INVALID,
                hasStoredPassword = !stored.password.isNullOrBlank(),
            )
        }
    }

    suspend fun logout(): AuthSnapshot = withContext(Dispatchers.IO) {
        storage.clear()
        AuthSnapshot(status = AuthStatus.LOGGED_OUT)
    }

    private suspend fun refreshOrReauthenticate(
        stored: StoredAuthState,
        baseUrl: String,
        username: String,
    ): AuthSnapshot {
        val refreshToken = stored.refreshToken?.takeIf { it.isNotBlank() }
            ?: return silentReauthenticate(stored, baseUrl, username)
        return try {
            recoveredSessionSnapshot(
                stored = stored,
                baseUrl = baseUrl,
                session = apiClient.refreshAccessToken(baseUrl, refreshToken, username),
                password = stored.password,
                fallbackRefreshToken = refreshToken,
            )
        } catch (exception: ApiException) {
            if (exception.statusCode == 401 || exception.statusCode == 403) {
                silentReauthenticate(stored, baseUrl, username)
            } else {
                Log.w(TAG, "Token refresh failed with API error ${exception.statusCode}.", exception)
                offlineSnapshot(stored, baseUrl, username)
            }
        } catch (exception: IOException) {
            Log.w(TAG, "Token refresh failed due to IO error. Keeping cached session alive.", exception)
            offlineSnapshot(stored, baseUrl, username)
        }
    }

    private suspend fun silentReauthenticate(
        stored: StoredAuthState,
        baseUrl: String,
        username: String,
    ): AuthSnapshot {
        val password = stored.password
        if (password.isNullOrBlank()) {
            return expiredSnapshot(stored, baseUrl, username)
        }

        return try {
            recoveredSessionSnapshot(
                stored = stored,
                baseUrl = baseUrl,
                session = apiClient.login(baseUrl, username, password),
                password = password,
                fallbackRefreshToken = stored.refreshToken,
            )
        } catch (exception: ApiException) {
            if (exception.statusCode == 401) {
                return expiredSnapshot(stored, baseUrl, username)
            }
            Log.w(TAG, "Silent re-authentication failed with API error ${exception.statusCode}. Keeping cached session alive.", exception)
            offlineSnapshot(stored, baseUrl, username)
        } catch (exception: IOException) {
            Log.w(TAG, "Silent re-authentication failed due to IO error. Keeping cached session alive.", exception)
            offlineSnapshot(stored, baseUrl, username)
        }
    }

    private fun recoveredSessionSnapshot(
        stored: StoredAuthState,
        baseUrl: String,
        session: AuthenticatedSession,
        password: String?,
        fallbackRefreshToken: String?,
    ): AuthSnapshot {
        if (!session.isSupported) {
            return expiredSnapshot(
                stored,
                baseUrl,
                session.username,
                session.serverVersion,
                session.compatibilityWarning,
            )
        }
        val accessToken = session.accessToken?.takeIf { it.isNotBlank() }
            ?: return expiredSnapshot(
                stored,
                baseUrl,
                session.username,
                session.serverVersion,
                UserVisibleStatus.LOGIN_MISSING_ACCESS_TOKEN,
            )
        storage.save(
            StoredAuthState(
                baseUrl = baseUrl,
                username = session.username,
                password = password,
                accessToken = accessToken,
                refreshToken = session.refreshToken ?: fallbackRefreshToken,
            ),
        )
        return buildAuthenticatedSnapshot(
            baseUrl,
            session.username,
            session,
            hasStoredPassword = !password.isNullOrBlank(),
        )
    }

    private fun expiredSnapshot(
        stored: StoredAuthState,
        baseUrl: String,
        username: String,
        serverVersion: String? = null,
        message: String? = null,
    ): AuthSnapshot {
        storage.save(stored.copy(baseUrl = baseUrl, username = username, accessToken = null, refreshToken = null))
        return AuthSnapshot(
            status = AuthStatus.SESSION_EXPIRED,
            baseUrl = baseUrl,
            username = username,
            serverVersion = serverVersion,
            statusMessage = message,
            hasStoredPassword = !stored.password.isNullOrBlank(),
        )
    }

    private fun offlineSnapshot(stored: StoredAuthState, baseUrl: String, username: String): AuthSnapshot {
        return AuthSnapshot(
            status = AuthStatus.AUTHENTICATED,
            baseUrl = baseUrl,
            username = username,
            statusMessage = UserVisibleStatus.SERVER_UNREACHABLE,
            hasStoredPassword = !stored.password.isNullOrBlank(),
        )
    }

    private fun buildAuthenticatedSnapshot(
        baseUrl: String,
        username: String,
        session: AuthenticatedSession?,
        hasStoredPassword: Boolean,
    ): AuthSnapshot {
        return AuthSnapshot(
            status = AuthStatus.AUTHENTICATED,
            baseUrl = baseUrl,
            username = username,
            serverVersion = session?.serverVersion,
            statusMessage = session?.compatibilityWarning,
            hasStoredPassword = hasStoredPassword,
        )
    }

    companion object {
        private const val TAG = "AuthRepository"
    }
}
