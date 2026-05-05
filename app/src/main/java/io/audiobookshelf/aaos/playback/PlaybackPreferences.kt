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

    fun playbackSpeed(context: Context): Float {
        val appContext = context.applicationContext
        return PreferenceManager.getDefaultSharedPreferences(appContext)
            .getFloat(KEY_PLAYBACK_SPEED, DEFAULT_PLAYBACK_SPEED)
            .validPlaybackSpeed()
    }

    fun savePlaybackSpeed(context: Context, playbackSpeed: Float) {
        val appContext = context.applicationContext
        PreferenceManager.getDefaultSharedPreferences(appContext)
            .edit()
            .putFloat(KEY_PLAYBACK_SPEED, playbackSpeed.validPlaybackSpeed())
            .apply()
    }

    private fun Float.validPlaybackSpeed(): Float {
        return takeIf { it.isFinite() && it > 0f } ?: DEFAULT_PLAYBACK_SPEED
    }

    private const val KEY_PLAYBACK_SPEED = "playback_speed"
    private const val DEFAULT_PLAYBACK_SPEED = 1.0f
}
