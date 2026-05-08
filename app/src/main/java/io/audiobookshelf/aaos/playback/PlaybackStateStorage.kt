package io.audiobookshelf.aaos.playback

import android.content.Context
import android.net.Uri

class PlaybackStateStorage(context: Context) {
    private val sharedPreferences = context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    fun load(): StoredPlaybackState? {
        val bookId = sharedPreferences.getString(KEY_BOOK_ID, null)
            ?.takeIf { it.isNotBlank() }
            ?: return null
        return StoredPlaybackState(
            bookId = bookId,
            title = sharedPreferences.getString(KEY_TITLE, null)
                ?.takeIf { it.isNotBlank() },
            author = sharedPreferences.getString(KEY_AUTHOR, null)
                ?.takeIf { it.isNotBlank() },
            artworkUri = sharedPreferences.getString(KEY_ARTWORK_URI, null)
                ?.takeIf { it.isNotBlank() }
                ?.let(Uri::parse),
            durationMs = sharedPreferences.getLong(KEY_DURATION_MS, UNKNOWN_DURATION_MS)
                .takeIf { it >= 0L },
            positionMs = sharedPreferences.getLong(KEY_POSITION_MS, 0L).coerceAtLeast(0L),
            trackIndex = sharedPreferences.getInt(KEY_TRACK_INDEX, 0).coerceAtLeast(0),
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
            .putString(KEY_TITLE, state.title)
            .putString(KEY_AUTHOR, state.author)
            .putString(KEY_ARTWORK_URI, state.artworkUri?.toString())
            .putLong(KEY_DURATION_MS, state.durationMs ?: UNKNOWN_DURATION_MS)
            .putLong(KEY_POSITION_MS, state.positionMs.coerceAtLeast(0L))
            .putInt(KEY_TRACK_INDEX, state.trackIndex.coerceAtLeast(0))
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
        private const val KEY_TITLE = "title"
        private const val KEY_AUTHOR = "author"
        private const val KEY_ARTWORK_URI = "artwork_uri"
        private const val KEY_DURATION_MS = "duration_ms"
        private const val KEY_POSITION_MS = "position_ms"
        private const val KEY_TRACK_INDEX = "track_index"
        private const val KEY_PLAYBACK_SPEED = "playback_speed"
        private const val KEY_WAS_PLAYING = "was_playing"
        private const val KEY_UPDATED_AT = "updated_at"
        private const val UNKNOWN_DURATION_MS = -1L
    }
}

data class StoredPlaybackState(
    val bookId: String,
    val title: String? = null,
    val author: String? = null,
    val artworkUri: Uri? = null,
    val durationMs: Long? = null,
    val positionMs: Long,
    val trackIndex: Int = 0,
    val playbackSpeed: Float,
    val wasPlaying: Boolean,
    val updatedAt: Long,
)
