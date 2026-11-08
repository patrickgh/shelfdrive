package io.audiobookshelf.aaos.bookorbit

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.audiobookshelf.aaos.absapi.AudiobookshelfHttpClient
import io.audiobookshelf.aaos.absapi.PlaybackSessionUpdateRequest
import io.audiobookshelf.aaos.absapi.RetryProfile
import io.audiobookshelf.aaos.server.ServerProgressSupersededException
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class BookOrbitReadAlongTest {
    @Test
    fun catalogIncludesNarratedEpubsButExcludesTextAndSidecars() = runBlocking {
        val narrated = book()
        val text = book().put("id", 2)
        text.getJSONArray("files").getJSONObject(0).getJSONObject("mediaOverlay").put("available", false)
        text.getJSONArray("files").remove(1)
        val sidecar = book().put("id", 3)
        sidecar.getJSONArray("files").getJSONObject(0).put("role", "sidecar")
        sidecar.getJSONArray("files").remove(1)
        val client = client { request ->
            200 to when {
                request.url.encodedPath.endsWith("/libraries/1/books") -> {
                    val formats = body(request).getJSONObject("filter").getJSONArray("rules").getJSONObject(0).getJSONArray("value")
                    assertTrue(formats.toString().contains("epub:readalong"))
                    JSONObject().put("items", JSONArray().put(narrated).put(text).put(sidecar)).put("total", 3).toString()
                }
                request.url.encodedPath.endsWith("/books/1") -> narrated.toString()
                else -> error("Unexpected route")
            }
        }
        val result = client.getLibraryItems(BASE, TOKEN, "1")
        assertEquals(listOf("1"), result.map { it.id })
        assertEquals(30_000L, result.single().durationMs)
    }

    @Test
    fun readAlongWinsOverAudioAndKeepsClippingAndChapterOffsets() = runBlocking {
        val session = playbackClient().createPlaybackSession(BASE, TOKEN, "1")
        assertTrue(requireNotNull(session.sourceId).startsWith("epub:20:"))
        assertEquals(2, session.audioTracks.size)
        assertEquals(30_000L, session.durationMs)
        assertEquals(15_000L, session.currentTimeMs)
        assertEquals(5_000L, session.audioTracks[0].clipStartMs)
        assertEquals(25_000L, session.audioTracks[0].clipEndMs)
        assertEquals(20_000L, session.audioTracks[1].startOffsetMs)
        assertEquals(listOf(0L, 20_000L), session.chapters.map { it.startMs })
        assertTrue(session.audioTracks[0].contentUrl.endsWith("Audio/50%25%20off.mp3?fileId=20"))
    }

    @Test
    fun readingMovedTheMarkerWithoutUpdatingAudioSeconds() {
        val timeline = parsed()
        val state = progress(15.0, "chapter2.xhtml#c", 1)
        assertEquals(20_000L, timeline.position(state))
        assertEquals(15_000L, timeline.position(progress()))
    }

    @Test
    fun secondsOnlyProgressAndBookEndAreRestored() {
        val state = progress(30.0)
        state.getJSONObject("narration").put("fragment", JSONObject.NULL).put("sectionIndex", JSONObject.NULL)
        assertEquals(30_000L, parsed().position(state))
        assertEquals("chapter2.xhtml#c", parsed().narration(30_000).getString("fragment"))
    }

    @Test
    fun unknownOrUnmappedProgressIsNotInvented() {
        val missing = progress()
        missing.getJSONObject("narration").put("fragment", JSONObject.NULL).put("seconds", JSONObject.NULL)
        assertThrows(IOException::class.java) { parsed().position(missing) }
        assertThrows(IOException::class.java) { parsed().position(progress(15.0, "unknown#x", 0)) }
        missing.put("percentage", 0).put("updatedAt", JSONObject.NULL)
        assertNull(parsed().position(missing))
    }

    @Test
    fun gapsInOneResourceRemainSeparateClippedTracks() {
        val json = playlist()
        json.getJSONArray("items").getJSONObject(1).put("clipBeginSeconds", 16).put("clipEndSeconds", 26)
        val parsed = BookOrbitReadAlong.parsePlaylist(json, "1", "20")
        assertEquals(3, parsed.tracks.size)
        assertEquals(10_000L, parsed.tracks[1].startOffsetMs)
        assertEquals(16_000L, parsed.tracks[1].clipStartMs)
        assertEquals(parsed.tracks[0].id, parsed.tracks[1].id)
    }

    @Test
    fun invalidTimelinesFailInsteadOfFallingBackToAnotherEdition() {
        val missingEnd = playlist()
        missingEnd.getJSONArray("items").getJSONObject(0).put("clipEndSeconds", JSONObject.NULL)
        assertThrows(IOException::class.java) { BookOrbitReadAlong.parsePlaylist(missingEnd, "1", "20") }
        val wrongFile = playlist().put("fileId", 21)
        assertThrows(IOException::class.java) { BookOrbitReadAlong.parsePlaylist(wrongFile, "1", "20") }
        val unknownResource = playlist().put("resources", JSONArray())
        assertThrows(IOException::class.java) { BookOrbitReadAlong.parsePlaylist(unknownResource, "1", "20") }
        val duration = playlist().put("durationSeconds", 40)
        assertThrows(IOException::class.java) { BookOrbitReadAlong.parsePlaylist(duration, "1", "20") }
    }

    @Test
    fun changedEditionCannotReceivePendingProgress() = runBlocking {
        val original = parsed().sourceId
        val modified = playlist()
        modified.getJSONArray("items").getJSONObject(2).put("textFragment", "changed")
        val client = playbackClient(playlist = modified)
        try {
            client.updateProgress(BASE, TOKEN, "1", null, update(15_000, original), false)
            fail("Changed timeline must not receive an old position")
        } catch (_: IOException) { }
    }

    @Test
    fun narrationSendsSentenceAndExactPositionToTheSelectedFile() = runBlocking {
        var saves = 0
        var sessions = 0
        val client = playbackClient { request ->
            when (request.method) {
                "PUT" -> {
                    val write = body(request).getJSONArray("writes").getJSONObject(0)
                    assertEquals(20L, write.getLong("fileId"))
                    assertEquals("narration", write.getString("kind"))
                    assertEquals(STAMP, write.getString("editedAt"))
                    assertEquals(25.0, write.getJSONObject("narration").getDouble("seconds"), 0.0)
                    assertEquals("chapter2.xhtml#c", write.getJSONObject("narration").getString("fragment"))
                    assertEquals(1, write.getJSONObject("narration").getInt("sectionIndex"))
                    assertFalse(write.has("text"))
                    saves++
                    200 to result("saved", progress(25.0, "chapter2.xhtml#c", 1)).toString()
                }
                "POST" -> {
                    assertTrue(request.url.encodedPath.endsWith("/books/files/20/sessions"))
                    assertEquals("listen", body(request).getString("sessionType"))
                    sessions++
                    204 to ""
                }
                else -> error("Unexpected write")
            }
        }
        val source = requireNotNull(client.createPlaybackSession(BASE, TOKEN, "1").sourceId)
        client.updateProgress(BASE, TOKEN, "1", null, update(25_000, source).copy(timeListenedMs = 5000), false)
        assertEquals(1, saves)
        assertEquals(1, sessions)
    }

    @Test
    fun consecutiveSeeksUseServerTimestampsEvenWhenTheDeviceClockLags() = runBlocking {
        var serverState = progress()
        var saves = 0
        val client = playbackClient { request ->
            val write = body(request).getJSONArray("writes").getJSONObject(0)
            assertEquals(serverState.getString("updatedAt"), write.getString("editedAt"))
            val position = write.getJSONObject("narration").getDouble("seconds")
            serverState = progress(position, "chapter2.xhtml#c", 1)
                .put("updatedAt", Instant.parse(LATER).plusMillis(++saves * 100L).toString())
            200 to result("saved", serverState).toString()
        }
        val source = requireNotNull(client.createPlaybackSession(BASE, TOKEN, "1").sourceId)
        for (position in listOf(25_000L, 21_000L, 28_000L)) {
            client.updateProgress(BASE, TOKEN, "1", null,
                update(position, source).copy(lastUpdateAt = Instant.parse(STAMP).minusSeconds(60).toEpochMilli()), false)
            assertEquals(position / 1000.0, serverState.getJSONObject("narration").getDouble("seconds"), 0.0)
        }
        assertEquals(3, saves)
    }

    @Test
    fun newerReadingPositionIsReturnedWithoutRetryOrListeningSession() = runBlocking {
        var saves = 0
        val client = playbackClient { request ->
            assertEquals("PUT", request.method)
            saves++
            200 to result("superseded", progress(2.0, "chapter1.xhtml#a", 0).put("updatedAt", LATER)).toString()
        }
        val source = requireNotNull(client.createPlaybackSession(BASE, TOKEN, "1").sourceId)
        try {
            client.updateProgress(BASE, TOKEN, "1", null, update(25_000, source), false)
            fail("A newer reading position must win, even when it is behind")
        } catch (error: ServerProgressSupersededException) {
            assertEquals(2_000L, error.progress.currentTimeMs)
            assertEquals(source, error.progress.sourceId)
        }
        assertEquals(1, saves)
    }

    @Test
    fun aProgressCheckCannotHideConcurrentReaderChanges() = runBlocking {
        var state = progress()
        val client = client { request ->
            when {
                request.url.encodedPath.endsWith("/books/1") -> 200 to book().toString()
                request.url.encodedPath.endsWith("/media-overlay") -> 200 to playlist().toString()
                request.method == "GET" -> 200 to JSONObject().put("items", JSONArray().put(state)).toString()
                else -> {
                    assertEquals(LATER, body(request).getJSONArray("writes").getJSONObject(0).getString("editedAt"))
                    200 to result("superseded", state).toString()
                }
            }
        }
        val source = requireNotNull(client.createPlaybackSession(BASE, TOKEN, "1").sourceId)
        state = progress(2.0, "chapter1.xhtml#a", 0).put("updatedAt", LATER)
        client.createPlaybackSession(BASE, TOKEN, "1", source)
        assertFalse(client.getMediaProgress(BASE, TOKEN, "1", RetryProfile.BACKGROUND_REFRESH, source).serverChanged)
        assertTrue(client.getMediaProgress(BASE, TOKEN, "1", RetryProfile.NONE, source).serverChanged)
        try {
            client.updateProgress(BASE, TOKEN, "1", null, update(25_000, source), false)
            fail("A write rejected by the server must still be surfaced")
        } catch (_: ServerProgressSupersededException) { }
    }

    @Test
    fun emptyNarrationStartsAtZeroAndUsesAnEpochConflictGuard() = runBlocking {
        val empty = progress().put("percentage", 0).put("updatedAt", JSONObject.NULL)
        empty.getJSONObject("narration").put("seconds", JSONObject.NULL).put("fragment", JSONObject.NULL)
        val client = playbackClient(state = empty) { request ->
            assertEquals("1970-01-01T00:00:00Z", body(request).getJSONArray("writes").getJSONObject(0).getString("editedAt"))
            200 to result("saved", progress()).toString()
        }
        val session = client.createPlaybackSession(BASE, TOKEN, "1")
        assertEquals(0L, session.currentTimeMs)
        client.updateProgress(BASE, TOKEN, "1", null, update(15_000, requireNotNull(session.sourceId)), false)
        Unit
    }

    private fun parsed() = BookOrbitReadAlong.parsePlaylist(playlist(), "1", "20")
    private fun update(position: Long, source: String) = PlaybackSessionUpdateRequest(position, 30_000, 0,
        Instant.parse(LATER).toEpochMilli(), source)
    private fun playbackClient(playlist: JSONObject = playlist(), state: JSONObject = progress(),
        write: (Request) -> Pair<Int, String> = { error("Unexpected write") }): BookOrbitApiClient = client { request ->
        when {
            request.url.encodedPath.endsWith("/books/1") -> 200 to book().toString()
            request.url.encodedPath.endsWith("/media-overlay") -> {
                assertEquals("20", request.url.queryParameter("fileId"))
                200 to playlist.toString()
            }
            request.method == "GET" && request.url.encodedPath.endsWith("/reading-progress") ->
                200 to JSONObject().put("items", JSONArray().put(state)).toString()
            else -> write(request)
        }
    }
    private fun client(handler: (Request) -> Pair<Int, String>) = BookOrbitApiClient(AudiobookshelfHttpClient(
        OkHttpClient.Builder().addInterceptor { chain ->
            assertEquals("Bearer $TOKEN", chain.request().header("Authorization"))
            val (code, json) = handler(chain.request())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("Test")
                .body(json.toResponseBody("application/json".toMediaType())).build()
        }.build()))
    private fun body(request: Request) = JSONObject(Buffer().also { request.body!!.writeTo(it) }.readUtf8())
    private fun result(status: String, state: JSONObject) = JSONObject().put("results", JSONArray().put(
        JSONObject().put("fileId", 20).put("status", status).put("progress", state)))
    private fun progress(seconds: Double = 15.0, fragment: String = "chapter1.xhtml#b", section: Int = 0) = JSONObject()
        .put("fileId", 20).put("percentage", 50).put("updatedAt", STAMP)
        .put("narration", JSONObject().put("seconds", seconds).put("fragment", fragment).put("sectionIndex", section))
    private fun book() = JSONObject("""{"id":1,"libraryId":1,"status":"present","title":"Read Along",
        "authors":[{"id":2,"name":"Author"}],"seriesMemberships":[],"audioMetadata":{"durationSeconds":50},
        "files":[{"id":20,"format":"epub","role":"content","mediaOverlay":{"available":true,"durationSeconds":30}},
                 {"id":21,"format":"mp3","role":"primary"}]}""")
    private fun playlist() = JSONObject("""{"bookId":1,"fileId":20,"durationSeconds":30,
        "resources":[{"href":"Audio/50% off.mp3"},{"href":"Audio/second.mp3"}],
        "items":[
          {"index":0,"sectionIndex":0,"textHref":"chapter1.xhtml","textFragment":"a","audioHref":"Audio/50% off.mp3","audioMimeType":"audio/mpeg","clipBeginSeconds":5,"clipEndSeconds":15,"durationSeconds":10,"label":"First"},
          {"index":1,"sectionIndex":0,"textHref":"chapter1.xhtml","textFragment":"b","audioHref":"Audio/50% off.mp3","audioMimeType":"audio/mpeg","clipBeginSeconds":15,"clipEndSeconds":25,"durationSeconds":10,"label":"First"},
          {"index":2,"sectionIndex":1,"textHref":"chapter2.xhtml","textFragment":"c","audioHref":"Audio/second.mp3","audioMimeType":"audio/mpeg","clipBeginSeconds":0,"clipEndSeconds":10,"durationSeconds":10,"label":"Second"}
        ]}""")
    companion object {
        private const val BASE = "https://books.example/prefix"
        private const val TOKEN = "test-token"
        private const val STAMP = "2026-11-08T12:00:00Z"
        private const val LATER = "2026-11-08T12:01:00Z"
    }
}
