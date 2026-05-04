package io.audiobookshelf.aaos.playback

import android.content.Context

class PlaybackStateStorage(context: Context) {
    private val sharedPreferences = context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    fun load(): StoredPlaybackState? {
        val bookId = sharedPreferences.getString(KEY_BOOK_ID, null)
            ?.takeIf { it.isNotBlank() }
            ?: return null
        return StoredPlaybackState(
            bookId = bookId,
            positionMs = sharedPreferences.getLong(KEY_POSITION_MS, 0L).coerceAtLeast(0L),
            playbackSpeed = sharedPreferences.getFloat(KEY_PLAYBACK_SPEED, 1f)
                .takeIf { it.isFinite() && it > 0f }
                ?: 1f,
            wasPlaying = sharedPreferences.getBoolean(KEY_WAS_PLAYING, false),
            updatedAt = sharedPreferences.getLong(KEY_UPDATED_AT, 0L),
        )
    }

    fun save(state: StoredPlaybackState) {
        sharedPreferences.edit()
            .putString(KEY_BOOK_ID, state.bookId)
            .putLong(KEY_POSITION_MS, state.positionMs.coerceAtLeast(0L))
            .putFloat(KEY_PLAYBACK_SPEED, state.playbackSpeed.takeIf { it.isFinite() && it > 0f } ?: 1f)
            .putBoolean(KEY_WAS_PLAYING, state.wasPlaying)
            .putLong(KEY_UPDATED_AT, state.updatedAt)
            .apply()
    }

    fun clear() {
        sharedPreferences.edit().clear().apply()
    }

    companion object {
        private const val FILE_NAME = "playback_state"
        private const val KEY_BOOK_ID = "book_id"
        private const val KEY_POSITION_MS = "position_ms"
        private const val KEY_PLAYBACK_SPEED = "playback_speed"
        private const val KEY_WAS_PLAYING = "was_playing"
        private const val KEY_UPDATED_AT = "updated_at"
    }
}

data class StoredPlaybackState(
    val bookId: String,
    val positionMs: Long,
    val playbackSpeed: Float,
    val wasPlaying: Boolean,
    val updatedAt: Long,
)
