@file:Suppress("DEPRECATION")
package io.audiobookshelf.aaos.auth

import android.content.Context
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class AuthStorage(context: Context) {
    private val sharedPreferences = EncryptedSharedPreferences.create(
        context,
        FILE_NAME,
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun load(): StoredAuthState {
        return StoredAuthState(
            baseUrl = sharedPreferences.getString(KEY_BASE_URL, null),
            username = sharedPreferences.getString(KEY_USERNAME, null),
            password = sharedPreferences.getString(KEY_PASSWORD, null),
            accessToken = sharedPreferences.getString(KEY_ACCESS_TOKEN, null),
            refreshToken = sharedPreferences.getString(KEY_REFRESH_TOKEN, null),
        )
    }

    fun save(state: StoredAuthState) {
        sharedPreferences.edit {
            putString(KEY_BASE_URL, state.baseUrl)
            putString(KEY_USERNAME, state.username)
            putString(KEY_PASSWORD, state.password)
            putString(KEY_ACCESS_TOKEN, state.accessToken)
            putString(KEY_REFRESH_TOKEN, state.refreshToken)
        }
    }

    fun clear() {
        sharedPreferences.edit { clear() }
    }

    companion object {
        private const val FILE_NAME = "auth_store"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_USERNAME = "username"
        private const val KEY_PASSWORD = "password"
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
    }
}

data class StoredAuthState(
    val baseUrl: String? = null,
    val username: String? = null,
    val password: String? = null,
    val accessToken: String? = null,
    val refreshToken: String? = null,
)
