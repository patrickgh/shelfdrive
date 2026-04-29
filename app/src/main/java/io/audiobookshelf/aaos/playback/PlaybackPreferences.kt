package io.audiobookshelf.aaos.playback

import android.content.Context
import androidx.preference.PreferenceManager
import io.audiobookshelf.aaos.R

object PlaybackPreferences {
    fun isRewindOnPauseEnabled(context: Context): Boolean {
        val appContext = context.applicationContext
        return PreferenceManager.getDefaultSharedPreferences(appContext)
            .getBoolean(appContext.getString(R.string.settings_key_rewind_on_pause), false)
    }
}
