package io.audiobookshelf.aaos.playback

import android.content.Context
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import io.audiobookshelf.aaos.R

object PlaybackPreferences {
    fun isRewindOnPauseEnabled(context: Context): Boolean {
        val appContext = context.applicationContext
        return PreferenceManager.getDefaultSharedPreferences(appContext)
            .getBoolean(appContext.getString(R.string.settings_key_rewind_on_pause), false)
    }

    fun skipIncrementSeconds(context: Context): Long {
        val appContext = context.applicationContext
        val preferenceKey = appContext.getString(R.string.settings_key_skip_increment)
        val rawValue = PreferenceManager.getDefaultSharedPreferences(appContext)
            .getString(preferenceKey, PlaybackSkipPolicy.DEFAULT_SKIP_INCREMENT_SECONDS.toString())
        return PlaybackSkipPolicy.secondsFromPreference(rawValue)
    }

    fun skipIncrementMs(context: Context): Long {
        return skipIncrementSeconds(context) * 1_000L
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
            .edit {
                putFloat(KEY_PLAYBACK_SPEED, playbackSpeed.validPlaybackSpeed())
            }
    }

    private fun Float.validPlaybackSpeed(): Float {
        return takeIf { it.isFinite() && it > 0f } ?: DEFAULT_PLAYBACK_SPEED
    }

    private const val KEY_PLAYBACK_SPEED = "playback_speed"
    private const val DEFAULT_PLAYBACK_SPEED = 1.0f
}
