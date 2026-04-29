package io.audiobookshelf.aaos.account

import android.accounts.AccountManager
import android.app.Service
import android.content.Intent
import android.os.IBinder

class AudiobookshelfAuthenticatorService : Service() {
    private val authenticator: AudiobookshelfAuthenticator by lazy {
        AudiobookshelfAuthenticator(this)
    }

    override fun onBind(intent: Intent?): IBinder? {
        return if (intent?.action == AccountManager.ACTION_AUTHENTICATOR_INTENT) {
            authenticator.iBinder
        } else {
            null
        }
    }
}
