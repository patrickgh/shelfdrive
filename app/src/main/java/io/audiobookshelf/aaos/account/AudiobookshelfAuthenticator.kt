package io.audiobookshelf.aaos.account

import android.accounts.AbstractAccountAuthenticator
import android.accounts.Account
import android.accounts.AccountAuthenticatorResponse
import android.accounts.AccountManager
import android.accounts.NetworkErrorException
import android.content.Context
import android.content.Intent
import android.os.Bundle
import io.audiobookshelf.aaos.R
import io.audiobookshelf.aaos.auth.AuthStorage
import io.audiobookshelf.aaos.settings.SettingsActivity

class AudiobookshelfAuthenticator(
    private val context: Context,
) : AbstractAccountAuthenticator(context) {

    override fun editProperties(response: AccountAuthenticatorResponse?, accountType: String?): Bundle {
        throw UnsupportedOperationException()
    }

    override fun addAccount(
        response: AccountAuthenticatorResponse?,
        accountType: String?,
        authTokenType: String?,
        requiredFeatures: Array<out String>?,
        options: Bundle?,
    ): Bundle = settingsIntentBundle(response)

    override fun confirmCredentials(
        response: AccountAuthenticatorResponse?,
        account: Account?,
        options: Bundle?,
    ): Bundle? = null

    override fun getAuthToken(
        response: AccountAuthenticatorResponse?,
        account: Account?,
        authTokenType: String?,
        options: Bundle?,
    ): Bundle {
        if (account == null || authTokenType != AudiobookshelfAccountContract.AUTH_TOKEN_TYPE_ACCESS) {
            return errorBundle(AccountManager.ERROR_CODE_BAD_ARGUMENTS, context.getString(R.string.account_error_bad_request))
        }

        val token = AccountManager.get(context).peekAuthToken(account, authTokenType)
            ?: AuthStorage(context).load().accessToken
        if (token.isNullOrBlank()) {
            return settingsIntentBundle(response)
        }

        return Bundle().apply {
            putString(AccountManager.KEY_ACCOUNT_NAME, account.name)
            putString(AccountManager.KEY_ACCOUNT_TYPE, account.type)
            putString(AccountManager.KEY_AUTHTOKEN, token)
        }
    }

    override fun getAuthTokenLabel(authTokenType: String?): String {
        return context.getString(R.string.app_name)
    }

    @Throws(NetworkErrorException::class)
    override fun updateCredentials(
        response: AccountAuthenticatorResponse?,
        account: Account?,
        authTokenType: String?,
        options: Bundle?,
    ): Bundle = settingsIntentBundle(response)

    override fun hasFeatures(
        response: AccountAuthenticatorResponse?,
        account: Account?,
        features: Array<out String>?,
    ): Bundle {
        return Bundle().apply {
            putBoolean(AccountManager.KEY_BOOLEAN_RESULT, false)
        }
    }

    private fun settingsIntentBundle(response: AccountAuthenticatorResponse?): Bundle {
        val intent = Intent(Intent.ACTION_APPLICATION_PREFERENCES)
            .setClass(context, SettingsActivity::class.java)
            .putExtra(AccountManager.KEY_ACCOUNT_AUTHENTICATOR_RESPONSE, response)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return Bundle().apply {
            putParcelable(AccountManager.KEY_INTENT, intent)
        }
    }

    private fun errorBundle(errorCode: Int, message: String): Bundle {
        return Bundle().apply {
            putInt(AccountManager.KEY_ERROR_CODE, errorCode)
            putString(AccountManager.KEY_ERROR_MESSAGE, message)
        }
    }
}
