package io.audiobookshelf.aaos.playback

import android.content.Context
import android.net.Uri
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject

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
            queue = decodeQueue(sharedPreferences.getString(KEY_QUEUE, null)),
            playbackSpeed = sharedPreferences.getFloat(KEY_PLAYBACK_SPEED, 1f)
                .takeIf { it.isFinite() && it > 0f }
                ?: 1f,
            updatedAt = sharedPreferences.getLong(KEY_UPDATED_AT, 0L),
        )
    }

    fun save(state: StoredPlaybackState) {
        sharedPreferences.edit {
            putString(KEY_BOOK_ID, state.bookId)
            putString(KEY_TITLE, state.title)
            putString(KEY_AUTHOR, state.author)
            putString(KEY_ARTWORK_URI, state.artworkUri?.toString())
            putLong(KEY_DURATION_MS, state.durationMs ?: UNKNOWN_DURATION_MS)
            putLong(KEY_POSITION_MS, state.positionMs.coerceAtLeast(0L))
            putString(KEY_QUEUE, encodeQueue(state.queue))
            putFloat(KEY_PLAYBACK_SPEED, state.playbackSpeed.takeIf { it.isFinite() && it > 0f } ?: 1f)
            putLong(KEY_UPDATED_AT, state.updatedAt)
            remove(KEY_LAST_APPLIED_SERVER_UPDATE_AT)
        }
    }

    fun clear() {
        sharedPreferences.edit { clear() }
    }

    private fun encodeQueue(queue: List<PlaybackTrack>): String? {
        if (queue.isEmpty()) {
            return null
        }
        val array = JSONArray()
        queue.forEach { track ->
            array.put(
                JSONObject()
                    .put("id", track.id)
                    .put("title", track.title)
                    .put("contentUrl", track.contentUrl)
                    .put("mimeType", track.mimeType)
                    .put("durationMs", track.durationMs)
                    .put("startOffsetMs", track.startOffsetMs),
            )
        }
        return array.toString()
    }

    private fun decodeQueue(raw: String?): List<PlaybackTrack> {
        if (raw.isNullOrBlank()) {
            return emptyList()
        }
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val id = item.optString("id").takeIf { it.isNotBlank() } ?: continue
                    val contentUrl = item.optString("contentUrl").takeIf { it.isNotBlank() } ?: continue
                    add(
                        PlaybackTrack(
                            id = id,
                            title = item.optString("title").takeIf { it.isNotBlank() } ?: "Hoerbuch ${index + 1}",
                            contentUrl = contentUrl,
                            mimeType = item.optString("mimeType").takeIf { it.isNotBlank() },
                            durationMs = item.optLongOrNull("durationMs"),
                            startOffsetMs = item.optLong("startOffsetMs", 0L).coerceAtLeast(0L),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun JSONObject.optLongOrNull(name: String): Long? {
        if (!has(name) || isNull(name)) {
            return null
        }
        return optLong(name).takeIf { it >= 0L }
    }

    companion object {
        private const val FILE_NAME = "playback_state"
        private const val KEY_BOOK_ID = "book_id"
        private const val KEY_TITLE = "title"
        private const val KEY_AUTHOR = "author"
        private const val KEY_ARTWORK_URI = "artwork_uri"
        private const val KEY_DURATION_MS = "duration_ms"
        private const val KEY_POSITION_MS = "position_ms"
        private const val KEY_QUEUE = "queue"
        private const val KEY_PLAYBACK_SPEED = "playback_speed"
        private const val KEY_UPDATED_AT = "updated_at"
        private const val KEY_LAST_APPLIED_SERVER_UPDATE_AT = "last_applied_server_update_at"
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
    val queue: List<PlaybackTrack> = emptyList(),
    val playbackSpeed: Float,
    val updatedAt: Long,
)
