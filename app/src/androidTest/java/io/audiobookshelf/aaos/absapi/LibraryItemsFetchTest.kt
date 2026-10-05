package io.audiobookshelf.aaos.absapi

import androidx.test.ext.junit.runners.AndroidJUnit4
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class LibraryItemsFetchTest {
    @Test
    fun updatesPlaybackSessionUsingSyncOrCloseWithTheSamePayload() = runBlocking {
        for (close in listOf(false, true)) {
            var called = false
            val client = client { request ->
                called = true
                assertEquals("POST", request.method)
                assertEquals("/api/session/session/${if (close) "close" else "sync"}", request.url.encodedPath)
                assertEquals("Bearer test-token", request.header("Authorization"))
                val buffer = Buffer()
                requireNotNull(request.body).writeTo(buffer)
                val payload = JSONObject(buffer.readUtf8())
                assertEquals(12.345, payload.getDouble("currentTime"), 0.0)
                assertEquals(100.0, payload.getDouble("duration"), 0.0)
                assertEquals(5.432, payload.getDouble("timeListened"), 0.0)
                assertEquals(123L, payload.getLong("lastUpdate"))
                "{}"
            }
            client.updatePlaybackSession(
                "https://abs.example", "test-token", "session",
                PlaybackSessionUpdateRequest(12_345L, 100_000L, 5_432L, 123L),
                close = close,
            )
            assertTrue(called)
        }
    }

    @Test
    fun loadsSeriesFromExpandedBatchWhenLibraryListHasOnlySeriesName() = runBlocking {
        val requests = mutableListOf<String>()
        val client = client { request ->
            requests += request.method + " " + request.url.encodedPath
            assertEquals("Bearer test-token", request.header("Authorization"))
            when (request.url.encodedPath) {
                "/api/libraries/library/items" ->
                    """{"results":[
                        {"id":"one","media":{"metadata":{"title":"One","seriesName":"Main #1.5, Other #2"}}},
                        {"id":"two","media":{"metadata":{"title":"Two","seriesName":""}}}
                    ]}"""
                "/api/items/batch/get" -> {
                    assertEquals(listOf("one", "two"), requestedIds(request))
                    val first = expandedBook("one")
                    first.getJSONObject("media").put("coverPath", "/covers/one.jpg")
                    first.getJSONObject("media").getJSONObject("metadata").put("series", JSONArray(
                        """[{"id":"main","name":"Main","sequence":"1.5"},{"id":"other","name":"Other","sequence":"2"}]""",
                    ))
                    // Batch responses need not be in the same order as the requested IDs.
                    val second = expandedBook("two")
                    second.getJSONObject("media").put("coverPath", JSONObject.NULL)
                    JSONObject().put("libraryItems", JSONArray().put(second).put(first)).toString()
                }
                else -> error("Unexpected request: ${request.url}")
            }
        }

        val books = client.getLibraryItems("https://abs.example", "test-token", "library")
        assertEquals(listOf("one", "two"), books.map { it.id })
        assertEquals(
            listOf(BookSeriesSummary("main", "Main", "1.5"), BookSeriesSummary("other", "Other", "2")),
            books[0].series,
        )
        assertTrue(books[1].series.isEmpty())
        assertEquals("Author", books[0].authorDisplay)
        assertEquals(120000L, books[0].durationMs)
        assertEquals("/covers/one.jpg", books[0].coverPath)
        assertNull(books[1].coverPath)
        assertEquals(listOf("GET /api/libraries/library/items", "POST /api/items/batch/get"), requests)
    }

    @Test
    fun boundsExpandedBatchesAndLoadsEveryBook() = runBlocking {
        val batches = mutableListOf<List<String>>()
        val client = client { request ->
            if (request.method == "GET") {
                JSONObject().put("results", JSONArray((1..101).map { JSONObject().put("id", "book-$it") })).toString()
            } else {
                val ids = requestedIds(request)
                batches += ids
                JSONObject().put("libraryItems", JSONArray(ids.map(::expandedBook))).toString()
            }
        }
        val books = client.getLibraryItems("https://abs.example", "test-token", "library")
        assertEquals(listOf(100, 1), batches.map { it.size })
        assertEquals((1..101).map { "book-$it" }, books.map { it.id })
    }

    @Test
    fun emptyLibraryDoesNotSendAnEmptyBatch() = runBlocking {
        val client = client { request ->
            assertEquals("GET", request.method)
            """{"results":[]}"""
        }
        assertTrue(client.getLibraryItems("https://abs.example", "test-token", "library").isEmpty())
    }

    @Test
    fun incompleteBatchFailsInsteadOfRemovingMissingBooksDuringSync() {
        val client = client { request ->
            if (request.method == "GET") """{"results":[{"id":"one"}]}"""
            else """{"libraryItems":[]}"""
        }
        val failure = assertThrows(IOException::class.java) {
            runBlocking { client.getLibraryItems("https://abs.example", "test-token", "library") }
        }
        assertTrue(failure.message.orEmpty().contains("one"))
    }

    private fun requestedIds(request: Request): List<String> {
        assertEquals("POST", request.method)
        assertEquals("application/json", request.header("Content-Type"))
        val buffer = Buffer()
        requireNotNull(request.body).writeTo(buffer)
        val ids = JSONObject(buffer.readUtf8()).getJSONArray("libraryItemIds")
        return List(ids.length()) { ids.getString(it) }
    }

    private fun expandedBook(id: String): JSONObject = JSONObject(
        """{"id":"$id","libraryId":"library","media":{
            "duration":120,"metadata":{"title":"$id","authorName":"Author","authors":[],"series":[]}
        }}""",
    )

    private fun client(body: (Request) -> String): AudiobookshelfApiClient {
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(body(chain.request()).toResponseBody("application/json".toMediaType()))
                .build()
        }.build()
        return AudiobookshelfApiClient(AudiobookshelfHttpClient(http))
    }
}
