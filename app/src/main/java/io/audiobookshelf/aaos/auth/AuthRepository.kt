package io.audiobookshelf.aaos.auth

import android.util.Log
import io.audiobookshelf.aaos.absapi.ApiException
import io.audiobookshelf.aaos.server.ServerApis
import io.audiobookshelf.aaos.server.ServerType
import io.audiobookshelf.aaos.absapi.AuthenticatedSession
import io.audiobookshelf.aaos.status.UserVisibleStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

class AuthRepository(
    private val storage: AuthStorage,
    private val serverApis: ServerApis = ServerApis(),
) {

    suspend fun bootstrap(): AuthSnapshot = withContext(Dispatchers.IO) {
        val stored = storage.load()
        val baseUrl = ServerUrlPolicy.validate(stored.baseUrl).normalizedUrl
        val username = stored.username?.trim().orEmpty()
        val accessToken = stored.accessToken

        if (baseUrl.isNullOrBlank() || username.isBlank()) {
            return@withContext AuthSnapshot(
                serverType = stored.serverType,
                status = AuthStatus.LOGGED_OUT,
                hasStoredPassword = !stored.password.isNullOrBlank(),
            )
        }

        if (!accessToken.isNullOrBlank()) {
            try {
                val session = serverApis.forType(stored.serverType).authorize(baseUrl, accessToken, username)
                if (session != null) {
                    return@withContext buildAuthenticatedSnapshot(
                        serverType = stored.serverType,
                        baseUrl = baseUrl,
                        username = session.username,
                        session = session,
                        hasStoredPassword = !stored.password.isNullOrBlank(),
                    )
                }
            } catch (exception: ApiException) {
                if (exception.statusCode == 401) {
                    return@withContext refreshOrReauthenticate(stored, baseUrl, username)
                }
                Log.w(TAG, "Token validation failed with API error ${exception.statusCode}. Keeping cached session alive.", exception)
                return@withContext offlineSnapshot(stored, baseUrl, username)
            } catch (exception: IOException) {
                Log.w(TAG, "Token validation failed due to IO error. Keeping cached session alive.", exception)
                return@withContext offlineSnapshot(stored, baseUrl, username)
            }
        }

        refreshOrReauthenticate(stored, baseUrl, username)
    }

    suspend fun login(
        requestedServerType: ServerType,
        requestedBaseUrl: String?,
        requestedUsername: String?,
        requestedPassword: String?,
    ): AuthSnapshot = withContext(Dispatchers.IO) {
        val previous = storage.load()
        val sameAccount = previous.serverType == requestedServerType &&
            ServerUrlPolicy.validate(requestedBaseUrl).normalizedUrl == previous.baseUrl &&
            requestedUsername?.trim() == previous.username
        val stored = if (sameAccount) previous else StoredAuthState(serverType = requestedServerType)
        val requestedUrlValidation = ServerUrlPolicy.validate(requestedBaseUrl)
        val storedUrlValidation = ServerUrlPolicy.validate(stored.baseUrl)
        val baseUrl = requestedUrlValidation.normalizedUrl ?: storedUrlValidation.normalizedUrl
        val username = requestedUsername?.trim().takeUnless { it.isNullOrBlank() } ?: stored.username?.trim()
        val password = requestedPassword?.takeUnless { it.isNullOrBlank() } ?: stored.password

        if (requestedUrlValidation.errorCode != null || (storedUrlValidation.errorCode != null && baseUrl == null)) {
            return@withContext AuthSnapshot(
                serverType = stored.serverType,
                status = AuthStatus.LOGIN_FAILED,
                baseUrl = baseUrl,
                username = username,
                statusMessage = requestedUrlValidation.errorCode ?: storedUrlValidation.errorCode,
                hasStoredPassword = !stored.password.isNullOrBlank(),
            )
        }

        if (baseUrl.isNullOrBlank() || username.isNullOrBlank() || password.isNullOrBlank()) {
            return@withContext AuthSnapshot(
                serverType = stored.serverType,
                status = AuthStatus.LOGIN_FAILED,
                baseUrl = baseUrl,
                username = username,
                statusMessage = UserVisibleStatus.LOGIN_FIELDS_REQUIRED,
                hasStoredPassword = !stored.password.isNullOrBlank(),
            )
        }

        return@withContext try {
            val session = serverApis.forType(stored.serverType).login(baseUrl, username, password)
            if (!session.isSupported) {
                return@withContext AuthSnapshot(
                    serverType = stored.serverType,
                    status = AuthStatus.LOGIN_FAILED,
                    baseUrl = baseUrl,
                    username = session.username,
                    serverVersion = session.serverVersion,
                    statusMessage = session.compatibilityWarningCode,
                    hasStoredPassword = !stored.password.isNullOrBlank(),
                )
            }

            val accessToken = session.accessToken
                ?: return@withContext AuthSnapshot(
                    serverType = stored.serverType,
                    status = AuthStatus.LOGIN_FAILED,
                    baseUrl = baseUrl,
                    username = session.username,
                    serverVersion = session.serverVersion,
                    statusMessage = UserVisibleStatus.LOGIN_MISSING_ACCESS_TOKEN,
                    hasStoredPassword = !stored.password.isNullOrBlank(),
                )

            storage.save(
                StoredAuthState(
                    serverType = stored.serverType,
                    baseUrl = baseUrl,
                    username = session.username,
                    password = password,
                    accessToken = accessToken,
                    refreshToken = session.refreshToken ?: stored.refreshToken,
                ),
            )

            buildAuthenticatedSnapshot(
                serverType = stored.serverType,
                baseUrl = baseUrl,
                username = session.username,
                session = session,
                hasStoredPassword = true,
            )
        } catch (exception: ApiException) {
            Log.w(TAG, "Login failed with API error ${exception.statusCode}.", exception)
            AuthSnapshot(
                serverType = stored.serverType,
                status = AuthStatus.LOGIN_FAILED,
                baseUrl = baseUrl,
                username = username,
                statusMessage = exception.message,
                hasStoredPassword = !stored.password.isNullOrBlank(),
            )
        } catch (exception: IOException) {
            Log.w(TAG, "Login failed due to IO error.", exception)
            AuthSnapshot(
                serverType = stored.serverType,
                status = AuthStatus.LOGIN_FAILED,
                baseUrl = baseUrl,
                username = username,
                statusMessage = UserVisibleStatus.SERVER_UNREACHABLE_OR_INVALID,
                hasStoredPassword = !stored.password.isNullOrBlank(),
            )
        }
    }

    suspend fun logout(): AuthSnapshot = withContext(Dispatchers.IO) {
        val type = storage.load().serverType
        storage.clear()
        storage.save(StoredAuthState(serverType = type))
        AuthSnapshot(status = AuthStatus.LOGGED_OUT, serverType = type)
    }

    suspend fun recoverAfterUnauthorized(): AuthSnapshot = withContext(Dispatchers.IO) {
        val stored = storage.load()
        val baseUrl = ServerUrlPolicy.validate(stored.baseUrl).normalizedUrl
        val username = stored.username?.trim().orEmpty()
        if (baseUrl.isNullOrBlank() || username.isBlank()) {
            return@withContext AuthSnapshot(
                serverType = stored.serverType,
                status = AuthStatus.LOGGED_OUT,
                hasStoredPassword = !stored.password.isNullOrBlank(),
            )
        }
        refreshOrReauthenticate(stored, baseUrl, username)
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
                session = serverApis.forType(stored.serverType).refreshAccessToken(baseUrl, refreshToken, username),
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
                session = serverApis.forType(stored.serverType).login(baseUrl, username, password),
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
                session.compatibilityWarningCode,
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
                serverType = stored.serverType,
                baseUrl = baseUrl,
                username = session.username,
                password = password,
                accessToken = accessToken,
                refreshToken = session.refreshToken ?: fallbackRefreshToken,
            ),
        )
        return buildAuthenticatedSnapshot(
            serverType = stored.serverType,
            baseUrl = baseUrl,
            username = session.username,
            session = session,
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
            serverType = stored.serverType,
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
            serverType = stored.serverType,
            status = AuthStatus.AUTHENTICATED,
            baseUrl = baseUrl,
            username = username,
            statusMessage = UserVisibleStatus.SERVER_UNREACHABLE,
            hasStoredPassword = !stored.password.isNullOrBlank(),
        )
    }

    private fun buildAuthenticatedSnapshot(
        serverType: ServerType,
        baseUrl: String,
        username: String,
        session: AuthenticatedSession?,
        hasStoredPassword: Boolean,
    ): AuthSnapshot {
        return AuthSnapshot(
            serverType = serverType,
            status = AuthStatus.AUTHENTICATED,
            baseUrl = baseUrl,
            username = username,
            serverVersion = session?.serverVersion,
            statusMessage = session?.compatibilityWarningCode,
            hasStoredPassword = hasStoredPassword,
        )
    }

    companion object {
        private const val TAG = "AuthRepository"
    }
}
