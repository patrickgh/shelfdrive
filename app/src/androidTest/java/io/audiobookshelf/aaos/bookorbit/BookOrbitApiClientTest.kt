package io.audiobookshelf.aaos.bookorbit

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.audiobookshelf.aaos.absapi.RetryProfile
import io.audiobookshelf.aaos.absapi.ApiException
import io.audiobookshelf.aaos.absapi.AudiobookshelfHttpClient
import io.audiobookshelf.aaos.absapi.PlaybackSessionUpdateRequest
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

@RunWith(AndroidJUnit4::class)
class BookOrbitApiClientTest {
    @Test
    fun nativeLoginAndRefreshUseSeparateCredentials() = runBlocking {
        val client = client { request ->
            val body = body(request)
            when (request.url.encodedPath) {
                "/prefix/api/v1/auth/login" -> {
                    assertEquals("native", body.getString("clientKind"))
                    assertEquals("reader", body.getString("username"))
                    assertEquals("secret", body.getString("password"))
                    assertNull(request.header("Authorization"))
                    200 to """{"user":{"username":"reader"},"accessToken":"access","refreshToken":"refresh"}"""
                }
                "/prefix/api/v1/auth/refresh" -> {
                    assertEquals("refresh", body.getString("refreshToken"))
                    assertFalse(body.has("password"))
                    200 to """{"accessToken":"new-access","refreshToken":"new-refresh"}"""
                }
                else -> error("Unexpected route ${request.url}")
            }
        }
        val loggedIn = client.login(BASE, "reader", "secret")
        assertEquals("access", loggedIn.accessToken)
        val refreshed = client.refreshAccessToken(BASE, requireNotNull(loggedIn.refreshToken), loggedIn.username)
        assertEquals("new-access", refreshed.accessToken)
        assertEquals("new-refresh", refreshed.refreshToken)
        assertEquals("reader", refreshed.username)
    }

    @Test
    fun rejectedAccessTokenRequiresRecovery() = runBlocking {
        val client = client { 401 to "{}" }
        assertNull(client.authorize(BASE, "expired", "reader"))
    }

    @Test
    fun missingNativeCredentialsAreAnError() = runBlocking {
        val client = client { 200 to """{"user":{"username":"reader"}}""" }
        try {
            client.login(BASE, "reader", "secret")
            fail("Missing access token must not authenticate the account")
        } catch (_: IOException) { }
    }

    @Test
    fun librariesWithoutTypeAreAccepted() = runBlocking {
        val client = client { 200 to """[{"id":1,"name":"Audiobooks","allowedFormats":["m4b","mp3"]},{"id":2,"name":"Mixed library","allowedFormats":[]}]""" }
        val libraries = client.getLibraries(BASE, TOKEN)
        assertEquals(listOf("1", "2"), libraries.map { it.id })
        assertTrue(libraries.all { it.mediaType == "book" })
    }

    @Test
    fun librariesStillRequireValidIds() = runBlocking {
        val client = client { 200 to """[{"name":"Audiobooks"}]""" }
        try {
            client.getLibraries(BASE, TOKEN)
            fail("A library without an ID must not enter the catalog")
        } catch (_: IOException) { }
    }

    @Test
    fun pagesAreExpandedAndOnlyRealAudioContentIsIncluded() = runBlocking {
        val requests = mutableListOf<String>()
        val client = client { request ->
            assertEquals("Bearer $TOKEN", request.header("Authorization"))
            requests += request.url.encodedPath
            val response = when (request.url.encodedPath) {
                "/prefix/api/v1/libraries/1/books" -> {
                    val query = body(request)
                    assertFalse(query.getBoolean("collapseSeries"))
                    assertEquals("format", query.getJSONObject("filter").getJSONArray("rules").getJSONObject(0).getString("field"))
                    val page = query.getJSONObject("pagination").getInt("page")
                    val items = if (page == 0) JSONArray().put(card(1, "m4b")).put(card(2, "epub"))
                        .put(card(3, "mp3", "sidecar")) else JSONArray().put(card(4, "mp3"))
                    JSONObject().put("items", items).put("total", 4).toString()
                }
                "/prefix/api/v1/books/1" -> detail(1).toString()
                "/prefix/api/v1/books/4" -> detail(4).toString()
                else -> error("Unexpected request ${request.url}")
            }
            200 to response
        }
        val books = client.getLibraryItems(BASE, TOKEN, "1")
        assertEquals(listOf("1", "4"), books.map { it.id })
        assertEquals("Author", books.first().authors.single().name)
        assertEquals("1.5", books.first().series.single().sequence)
        assertEquals("Description", books.first().description)
        assertEquals(120_000L, books.first().durationMs)
        assertEquals(2, requests.count { it.endsWith("/libraries/1/books") })
        assertEquals(4, requests.size)
    }

    @Test
    fun incompleteCatalogPagesDoNotReplaceTheCache() = runBlocking {
        val client = client { 200 to """{"items":[],"total":1}""" }
        try {
            client.getLibraryItems(BASE, TOKEN, "1")
            fail("Expected incomplete page failure")
        } catch (_: IOException) { }
    }

    @Test
    fun manifestResumesInTheSecondAssetWithoutAnAbsSession() = runBlocking {
        val client = playbackClient()
        val playback = client.createPlaybackSession(BASE, TOKEN, "1")
        assertNull(playback.sessionId)
        assertEquals(125_000L, playback.currentTimeMs)
        assertEquals(listOf(0L, 120_000L), playback.audioTracks.map { it.startOffsetMs })
        assertEquals(180_000L, playback.durationMs)
        assertEquals(120_000L, playback.chapters[1].startMs)
        assertTrue(playback.audioTracks[1].contentUrl.endsWith("/$ASSET_TWO/content"))
    }

    @Test
    fun unknownTrackDurationIsReportedInsteadOfInvented() {
        val manifest = manifest()
        manifest.getJSONArray("assets").getJSONObject(0).put("durationMs", JSONObject.NULL)
        assertThrows(IOException::class.java) { BookOrbitApiClient.parseManifest(manifest, "1") }
    }

    @Test
    fun fractionalIdsAndDurationsAreNotSilentlyRounded() {
        val fractionalId = manifest()
        fractionalId.getJSONObject("book").put("id", 1.5)
        assertThrows(IOException::class.java) { BookOrbitApiClient.parseManifest(fractionalId, "1") }
        val fractionalDuration = manifest()
        fractionalDuration.getJSONArray("assets").getJSONObject(0).put("durationMs", 120000.5)
        assertThrows(IOException::class.java) { BookOrbitApiClient.parseManifest(fractionalDuration, "1") }
    }

    @Test
    fun invalidChaptersAndWrongBooksAreRejected() {
        val manifest = manifest()
        manifest.getJSONArray("chapters").getJSONObject(1).put("startMs", 119_000)
        assertThrows(IOException::class.java) { BookOrbitApiClient.parseManifest(manifest, "1") }
        assertThrows(IOException::class.java) { BookOrbitApiClient.parseManifest(manifest(), "9") }
    }

    @Test
    fun progressUsesTheObservedRevisionAndTrackRelativePosition() = runBlocking {
        var gets = 0
        var puts = 0
        val client = client { request ->
            when {
                request.url.encodedPath.endsWith("/books/1") -> 200 to detail(1).toString()
                request.url.encodedPath.endsWith("/manifest") -> 200 to manifest().toString()
                request.method == "GET" -> { gets++; 200 to state().toString() }
                else -> {
                    puts++
                    val payload = body(request)
                    assertEquals(7L, payload.getLong("baseRevision"))
                    assertEquals(REVISION, payload.getString("manifestRevision"))
                    assertEquals(ASSET_TWO, payload.getString("assetId"))
                    assertEquals(15_000L, payload.getLong("positionMs"))
                    assertFalse(payload.has("percentage"))
                    java.util.UUID.fromString(payload.getString("operationId"))
                    200 to state(8, 15_000).toString()
                }
            }
        }
        assertEquals(125_000L, client.getMediaProgress(BASE, TOKEN, "1", RetryProfile.NONE).currentTimeMs)
        assertNull(client.updateProgress(BASE, TOKEN, "1", null, update(135_000), false))
        assertEquals(1, gets)
        assertEquals(1, puts)
    }

    @Test
    fun backgroundRefreshCannotReplaceTheRevisionUsedByAnActiveProgressCheck() = runBlocking {
        var revision = 7
        val client = client { request ->
            when {
                request.url.encodedPath.endsWith("/books/1") -> 200 to detail(1).toString()
                request.url.encodedPath.endsWith("/manifest") -> 200 to manifest().toString()
                request.method == "GET" -> 200 to state(revision).toString()
                else -> {
                    assertEquals(7, body(request).getInt("baseRevision"))
                    409 to "{}"
                }
            }
        }
        client.getMediaProgress(BASE, TOKEN, "1", RetryProfile.NONE)
        revision = 8
        client.getMediaProgress(BASE, TOKEN, "1", RetryProfile.BACKGROUND_REFRESH)
        try {
            client.updateProgress(BASE, TOKEN, "1", null, update(135_000), false)
            fail("A background refresh must not hide a concurrent update")
        } catch (error: ApiException) { assertEquals(409, error.statusCode) }
    }

    @Test
    fun tokenRotationPreservesTheRevisionFromTheProgressCheck() = runBlocking {
        var stateReads = 0
        val client = client { request ->
            when {
                request.url.encodedPath.endsWith("/auth/refresh") ->
                    200 to """{"accessToken":"new-token","refreshToken":"new-refresh"}"""
                request.url.encodedPath.endsWith("/books/1") -> 200 to detail(1).toString()
                request.url.encodedPath.endsWith("/manifest") -> 200 to manifest().toString()
                request.method == "GET" -> { stateReads++; 200 to state().toString() }
                else -> {
                    assertEquals("Bearer new-token", request.header("Authorization"))
                    assertEquals(7, body(request).getInt("baseRevision"))
                    200 to state(8, 15000).toString()
                }
            }
        }
        client.getMediaProgress(BASE, TOKEN, "1", RetryProfile.NONE)
        client.refreshAccessToken(BASE, "refresh", "reader")
        client.updateProgress(BASE, "new-token", "1", null, update(135000), false)
        assertEquals(1, stateReads)
    }

    @Test
    fun concurrentProgressConflictIsNotBlindlyRetried() = runBlocking {
        var puts = 0
        val client = client { request ->
            when {
                request.url.encodedPath.endsWith("/books/1") -> 200 to detail(1).toString()
                request.url.encodedPath.endsWith("/manifest") -> 200 to manifest().toString()
                request.method == "GET" -> 200 to state().toString()
                else -> { puts++; 409 to "{}" }
            }
        }
        client.getMediaProgress(BASE, TOKEN, "1", RetryProfile.NONE)
        try {
            client.updateProgress(BASE, TOKEN, "1", null, update(135_000), false)
            fail("Concurrent update must be reported")
        } catch (error: ApiException) { assertEquals(409, error.statusCode) }
        assertEquals(1, puts)
    }

    @Test
    fun newProgressStartsAtRevisionZeroAndFinishingReachesTheLastAssetEnd() = runBlocking {
        val client = client { request ->
            when {
                request.url.encodedPath.endsWith("/books/1") -> 200 to detail(1).toString()
                request.url.encodedPath.endsWith("/manifest") -> 200 to manifest().toString()
                request.method == "GET" -> 200 to "null"
                else -> {
                    val payload = body(request)
                    assertEquals(0, payload.getInt("baseRevision"))
                    assertEquals(ASSET_TWO, payload.getString("assetId"))
                    assertEquals(60_000, payload.getInt("positionMs"))
                    200 to state(1, 60_000).put("completed", true).toString()
                }
            }
        }
        assertEquals(0L, client.createPlaybackSession(BASE, TOKEN, "1").currentTimeMs)
        client.updateProgress(BASE, TOKEN, "1", null, update(180_000), true)
        Unit
    }

    @Test
    fun activeListeningTimeIsReportedAsAnAndroidListeningSession() = runBlocking {
        var operationId: String? = null
        var sessionCount = 0
        val client = client { request ->
            when {
                request.url.encodedPath.endsWith("/books/1") -> 200 to detail(1).toString()
                request.url.encodedPath.endsWith("/manifest") -> 200 to manifest().toString()
                request.method == "GET" -> 200 to state().toString()
                request.method == "PUT" -> {
                    operationId = body(request).getString("operationId")
                    200 to state(8, 15_000).toString()
                }
                else -> {
                    val session = body(request)
                    assertEquals("/prefix/api/v1/books/files/11/sessions", request.url.encodedPath)
                    assertEquals(operationId, session.getString("sessionId"))
                    assertEquals("listen", session.getString("sessionType"))
                    assertEquals("android", session.getString("source"))
                    assertEquals(5, session.getInt("durationSeconds"))
                    sessionCount++
                    204 to ""
                }
            }
        }
        client.updateProgress(BASE, TOKEN, "1", null, update(135_000).copy(timeListenedMs = 5000), false)
        assertEquals(1, sessionCount)
    }

    private fun playbackClient() = client { request ->
        200 to when {
            request.url.encodedPath.endsWith("/books/1") -> detail(1).toString()
            request.url.encodedPath.endsWith("/manifest") -> manifest().toString()
            else -> state().toString()
        }
    }

    private fun client(handler: (Request) -> Pair<Int, String>): BookOrbitApiClient = BookOrbitApiClient(
        AudiobookshelfHttpClient(OkHttpClient.Builder().addInterceptor { chain ->
            val (status, json) = handler(chain.request())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(status).message("Test")
                .body(json.toResponseBody("application/json".toMediaType())).build()
        }.build()),
    )

    private fun body(request: Request): JSONObject = JSONObject(Buffer().also { request.body!!.writeTo(it) }.readUtf8())
    private fun update(position: Long) = PlaybackSessionUpdateRequest(position, 180_000, 0, 1_700_000_000_000)
    private fun card(id: Int, format: String = "m4b", role: String = "content") = JSONObject()
        .put("id", id).put("files", JSONArray().put(JSONObject().put("format", format).put("role", role)))
    private fun detail(id: Int) = card(id).put("libraryId", 1).put("status", "present").put("title", "Book $id")
        .put("description", "Description").put("coverVersion", "cover-v1")
        .put("authors", JSONArray("""[{"id":5,"name":"Author","sortName":"Author"}]"""))
        .put("seriesMemberships", JSONArray("""[{"seriesId":6,"seriesName":"Series","seriesIndex":"1.5"}]"""))
        .put("audioMetadata", JSONObject().put("durationSeconds", 120))

    private fun state(revision: Int = 7, position: Int = 5000) = JSONObject()
        .put("assetId", ASSET_TWO).put("positionMs", position).put("percentage", 70).put("completed", false)
        .put("capturedAt", "2026-10-22T12:00:00.000Z").put("revision", revision).put("manifestRevision", REVISION)

    private fun manifest() = JSONObject("""{
      "schema":"bookorbit.audiobook-manifest","schemaVersion":2,"revision":"$REVISION",
      "book":{"id":1,"title":"Book","authors":["Author"],"hasCover":true},
      "assets":[
        {"assetId":"$ASSET_ONE","fileId":10,"sequence":0,"format":"m4b","durationMs":120000},
        {"assetId":"$ASSET_TWO","fileId":11,"sequence":1,"format":"mp3","durationMs":60000}
      ],
      "chapters":[{"title":"First","startMs":0,"endMs":120000},{"title":"Second","startMs":120000,"endMs":180000}],
      "totalDurationMs":180000
    }""")

    companion object {
        private const val BASE = "https://books.example/prefix"
        private const val TOKEN = "test-token"
        private const val ASSET_ONE = "aud_00000000-0000-4000-8000-000000000001"
        private const val ASSET_TWO = "aud_00000000-0000-4000-8000-000000000002"
        private val REVISION = "a".repeat(64)
    }
}
