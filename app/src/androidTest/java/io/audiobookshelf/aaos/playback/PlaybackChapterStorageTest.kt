package io.audiobookshelf.aaos.playback

import io.audiobookshelf.aaos.media3.toMedia3PlaybackItems
import android.content.ContextWrapper
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlaybackChapterStorageTest {
    @Test
    fun parsesServerChaptersAndPreservesMillisecondsInSnapshot() {
        val chapters = parsePlaybackChapters(JSONArray("""[
            {"id":0,"title":"One","start":0,"end":1.234},
            {"id":1,"title":"Two","start":1.234,"end":123.456}
        ]"""))
        assertEquals(1_234L, chapters[1].startMs)
        assertEquals(123_456L, chapters[1].endMs)
        val preciseChapters = listOf(PlaybackChapter("Milliseconds", 1_001L, 123_457L))
        assertEquals(preciseChapters, decodePlaybackChapters(encodePlaybackChapters(preciseChapters)))
        assertEquals(emptyList<PlaybackChapter>(), parsePlaybackChapters(null))
    }

    @Test
    fun rejectsMalformedChapterDataWithoutInventingBoundaries() {
        listOf(
            """[{"start":0}]""",
            """[{"start":null,"end":2}]""",
            """[{"start":"0","end":2}]""",
            """[{"start":2,"end":1}]""",
            """[{"start":0,"end":2},{"start":1,"end":3}]""",
        ).forEach { json ->
            assertThrows(Exception::class.java) { parsePlaybackChapters(JSONArray(json)) }
        }
    }

    @Test
    fun restoresNewAndOldPlaybackSnapshots() {
        // Use the test APK context so this test cannot overwrite the app's listening state.
        val context = object : ContextWrapper(InstrumentationRegistry.getInstrumentation().context) {
            override fun getApplicationContext(): Context = this
        }
        val preferences = context.getSharedPreferences("playback_state", Context.MODE_PRIVATE)
        val storage = PlaybackStateStorage(context)
        val chapters = listOf(PlaybackChapter("Chapter", 0L, 120_000L))
        val state = StoredPlaybackState(
            bookId = "book",
            title = "Book",
            durationMs = 120_000L,
            positionMs = 42_000L,
            queue = listOf(PlaybackTrack("track", "Track", playbackTrackUri("book", 0), null, 120_000L, 0L)),
            playbackSpeed = 1.2f,
            chapters = chapters,
        )
        try {
            storage.save(state)
            assertEquals(state, storage.load())
            assertEquals(chapters, storage.load()?.toResolvedPlayback()?.chapters)
            preferences.edit().remove("chapters").commit()
            assertEquals(state.copy(chapters = emptyList()), storage.load())
            preferences.edit().putString("chapters", "broken json").commit()
            assertEquals(state.copy(chapters = emptyList()), storage.load())
        } finally {
            storage.clear()
        }
    }
    @Test
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun readAlongSnapshotPreservesSourceStreamAndClipping() {
        val context = object : ContextWrapper(InstrumentationRegistry.getInstrumentation().context) {
            override fun getApplicationContext(): Context = this
        }
        val storage = PlaybackStateStorage(context)
        val track = PlaybackTrack("epub-track", "Chapter", playbackTrackUri("1", 0), "audio/mpeg", 20_000, 0,
            "/api/v1/epub/1/media-overlay/file/audio.mp3?fileId=20", 5000, 25000)
        val state = StoredPlaybackState("1", positionMs = 12_000, queue = listOf(track), playbackSpeed = 1f,
            sourceId = "epub:20:revision")
        try {
            storage.save(state)
            val restored = requireNotNull(storage.load())
            assertEquals(state, restored)
            val playback = requireNotNull(restored.toResolvedPlayback())
            assertEquals(state.sourceId, playback.sourceId)
            val item = playback.toMedia3PlaybackItems().single()
            assertEquals(5000L, item.clippingConfiguration.startPositionMs)
            assertEquals(25000L, item.clippingConfiguration.endPositionMs)
        } finally {
            storage.clear()
        }
    }

}
