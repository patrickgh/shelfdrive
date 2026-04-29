package io.audiobookshelf.aaos.account

import android.accounts.Account
import android.accounts.AccountManager
import android.content.Context
import android.os.Bundle
import android.util.Log

class AudiobookshelfAccountRegistry(context: Context) {
    private val accountManager = AccountManager.get(context.applicationContext)

    fun upsert(
        username: String,
        baseUrl: String,
        accessToken: String?,
        serverVersion: String?,
    ) {
        if (username.isBlank() || baseUrl.isBlank()) {
            return
        }

        runCatching {
            val account = Account(username, AudiobookshelfAccountContract.ACCOUNT_TYPE)
            removeAccountsExcept(username)

            val userData = Bundle().apply {
                putString(AudiobookshelfAccountContract.KEY_BASE_URL, baseUrl)
                putString(AudiobookshelfAccountContract.KEY_SERVER_VERSION, serverVersion)
            }
            val created = accountManager.addAccountExplicitly(account, null, userData)
            if (!created) {
                accountManager.setUserData(account, AudiobookshelfAccountContract.KEY_BASE_URL, baseUrl)
                accountManager.setUserData(account, AudiobookshelfAccountContract.KEY_SERVER_VERSION, serverVersion)
            }

            if (!accessToken.isNullOrBlank()) {
                accountManager.setAuthToken(
                    account,
                    AudiobookshelfAccountContract.AUTH_TOKEN_TYPE_ACCESS,
                    accessToken,
                )
            }
        }.onFailure { exception ->
            Log.w(TAG, "Could not sync Audiobookshelf account with AccountManager.", exception)
        }
    }

    fun clear() {
        runCatching {
            accounts().forEach(accountManager::removeAccountExplicitly)
        }.onFailure { exception ->
            Log.w(TAG, "Could not clear Audiobookshelf accounts from AccountManager.", exception)
        }
    }

    private fun removeAccountsExcept(username: String) {
        accounts()
            .filterNot { it.name == username }
            .forEach(accountManager::removeAccountExplicitly)
    }

    private fun accounts(): Array<Account> {
        return accountManager.getAccountsByType(AudiobookshelfAccountContract.ACCOUNT_TYPE)
    }

    companion object {
        private const val TAG = "AbsAccountRegistry"
    }
}
