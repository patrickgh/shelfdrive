package io.audiobookshelf.aaos.auth

import android.os.Bundle

data class AuthSnapshot(
    val status: AuthStatus,
    val baseUrl: String? = null,
    val username: String? = null,
    val libraryCount: Int? = null,
    val serverVersion: String? = null,
    val statusMessage: String? = null,
    val hasStoredPassword: Boolean = false,
) {
    val isAuthenticated: Boolean
        get() = status == AuthStatus.AUTHENTICATED

    fun toBundle(): Bundle {
        return Bundle().apply {
            putString(KEY_STATUS, status.name)
            putString(KEY_BASE_URL, baseUrl)
            putString(KEY_USERNAME, username)
            if (libraryCount != null) {
                putInt(KEY_LIBRARY_COUNT, libraryCount)
            }
            putString(KEY_SERVER_VERSION, serverVersion)
            putString(KEY_STATUS_MESSAGE, statusMessage)
            putBoolean(KEY_HAS_STORED_PASSWORD, hasStoredPassword)
        }
    }

    companion object {
        private const val KEY_STATUS = "auth_status"
        private const val KEY_BASE_URL = "auth_base_url"
        private const val KEY_USERNAME = "auth_username"
        private const val KEY_LIBRARY_COUNT = "auth_library_count"
        private const val KEY_SERVER_VERSION = "auth_server_version"
        private const val KEY_STATUS_MESSAGE = "auth_status_message"
        private const val KEY_HAS_STORED_PASSWORD = "auth_has_stored_password"

        fun fromBundle(bundle: Bundle?): AuthSnapshot? {
            bundle ?: return null
            val statusName = bundle.getString(KEY_STATUS) ?: return null
            val status = runCatching { AuthStatus.valueOf(statusName) }.getOrNull() ?: return null
            val containsLibraryCount = bundle.containsKey(KEY_LIBRARY_COUNT)
            return AuthSnapshot(
                status = status,
                baseUrl = bundle.getString(KEY_BASE_URL),
                username = bundle.getString(KEY_USERNAME),
                libraryCount = if (containsLibraryCount) bundle.getInt(KEY_LIBRARY_COUNT) else null,
                serverVersion = bundle.getString(KEY_SERVER_VERSION),
                statusMessage = bundle.getString(KEY_STATUS_MESSAGE),
                hasStoredPassword = bundle.getBoolean(KEY_HAS_STORED_PASSWORD),
            )
        }
    }
}
