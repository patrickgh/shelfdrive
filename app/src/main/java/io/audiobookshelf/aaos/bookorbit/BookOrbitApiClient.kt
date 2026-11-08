package io.audiobookshelf.aaos.bookorbit

import io.audiobookshelf.aaos.absapi.ApiException
import io.audiobookshelf.aaos.absapi.AudiobookshelfHttpClient
import io.audiobookshelf.aaos.absapi.AuthenticatedSession
import io.audiobookshelf.aaos.absapi.AuthorSummary
import io.audiobookshelf.aaos.absapi.BookSeriesSummary
import io.audiobookshelf.aaos.absapi.BookSummary
import io.audiobookshelf.aaos.absapi.HttpRequest
import io.audiobookshelf.aaos.absapi.LibrarySummary
import io.audiobookshelf.aaos.absapi.MediaProgressSummary
import io.audiobookshelf.aaos.absapi.PlaybackSessionSummary
import io.audiobookshelf.aaos.absapi.PlaybackSessionUpdateRequest
import io.audiobookshelf.aaos.absapi.PlaybackTrackSummary
import io.audiobookshelf.aaos.absapi.ProgressItemSummary
import io.audiobookshelf.aaos.absapi.RetryProfile
import io.audiobookshelf.aaos.playback.PlaybackChapter
import io.audiobookshelf.aaos.playback.validateChapters
import io.audiobookshelf.aaos.server.AudiobookServerApi
import io.audiobookshelf.aaos.server.ServerProgressSupersededException
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class BookOrbitApiClient(
    private val httpClient: AudiobookshelfHttpClient = AudiobookshelfHttpClient(),
) : AudiobookServerApi {
    private data class ProgressKey(val baseUrl: String, val bookId: String, val sourceId: String? = null)
    private data class ProgressContext(val manifest: Manifest, val state: JSONObject?)
    private val observedProgress = ConcurrentHashMap<ProgressKey, ProgressContext>()
    private var authenticatedAccount: Pair<String, String>? = null

    @Synchronized
    private fun rememberAccount(baseUrl: String, username: String) {
        val account = baseUrl to username
        if (authenticatedAccount != null && authenticatedAccount != account) observedProgress.clear()
        authenticatedAccount = account
    }

    override suspend fun login(baseUrl: String, username: String, password: String): AuthenticatedSession {
        val body = JSONObject().put("username", username).put("password", password)
            .put("clientKind", "native").put("deviceLabel", "ShelfDrive")
        val json = request(baseUrl, null, "/auth/login", "POST", body)
        return parseAuth(json, username).also { rememberAccount(baseUrl, it.username) }
    }

    override suspend fun authorize(baseUrl: String, accessToken: String, fallbackUsername: String?): AuthenticatedSession? =
        try {
            val user = request(baseUrl, accessToken, "/auth/me")
            AuthenticatedSession(user.requiredString("username"), accessToken, null, null, null, true)
                .also { rememberAccount(baseUrl, it.username) }
        } catch (error: ApiException) {
            if (error.statusCode == 401) null else throw error
        }

    override suspend fun refreshAccessToken(baseUrl: String, refreshToken: String, fallbackUsername: String?): AuthenticatedSession =
        parseAuth(request(baseUrl, null, "/auth/refresh", "POST", JSONObject().put("refreshToken", refreshToken)), fallbackUsername)
            .also { rememberAccount(baseUrl, it.username) }

    private fun parseAuth(json: JSONObject, username: String?): AuthenticatedSession =
        AuthenticatedSession(
            json.optJSONObject("user")?.requiredString("username") ?: username
                ?: throw IOException("BookOrbit authentication is missing its username."),
            json.requiredString("accessToken"), json.requiredString("refreshToken"), null, null, true,
        )

    override suspend fun getLibraries(baseUrl: String, accessToken: String): List<LibrarySummary> = parse {
        val libraries = JSONArray(rawRequest(baseUrl, accessToken, "/libraries"))
        objects(libraries).map { LibrarySummary(it.numericId(), "book") }
    }

    override suspend fun getLibraryAuthors(baseUrl: String, accessToken: String, libraryId: String): List<AuthorSummary> = emptyList()

    override suspend fun getLibraryItems(baseUrl: String, accessToken: String, libraryId: String): List<BookSummary> = parse {
        checkId(libraryId)
        val books = mutableListOf<BookSummary>()
        val seen = hashSetOf<String>()
        var page = 0
        var received = 0
        do {
            val query = JSONObject()
                .put("filter", JSONObject().put("type", "group").put("join", "AND").put("rules", JSONArray().put(
                    JSONObject().put("type", "rule").put("field", "format").put("operator", "includesAny")
                        .put("value", JSONArray(AUDIO_FORMATS.toList() + "epub:readalong")),
                )))
                .put("sort", JSONArray().put(JSONObject().put("field", "title").put("dir", "asc")))
                .put("pagination", JSONObject().put("page", page).put("size", 100))
                .put("collapseSeries", false)
            val result = request(baseUrl, accessToken, "/libraries/$libraryId/books", "POST", query)
            val items = objects(result.getJSONArray("items"))
            val total = result.getInt("total")
            if (total < 0 || (items.isEmpty() && received < total)) throw IOException("Incomplete BookOrbit catalog page.")
            for (item in items) {
                val id = item.numericId()
                if (!seen.add(id)) throw IOException("Duplicate BookOrbit book $id across catalog pages.")
                if (!hasAudio(item)) continue
                val detail = request(baseUrl, accessToken, "/books/$id")
                if (detail.numericId() != id || detail.numericId("libraryId") != libraryId) {
                    throw IOException("BookOrbit returned an unexpected book.")
                }
                if (hasAudio(detail)) books += parseBook(detail, libraryId)
            }
            received += items.size
            page++
        } while (received < total)
        books
    }

    private fun parseBook(json: JSONObject, libraryId: String): BookSummary {
        val authors = objects(json.getJSONArray("authors")).map {
            val name = it.requiredString("name")
            AuthorSummary(it.numericId(), name, it.optionalString("sortName") ?: name, null, 0)
        }
        val series = objects(json.getJSONArray("seriesMemberships")).map {
            BookSeriesSummary(it.numericId("seriesId"), it.requiredString("seriesName"), it.optionalString("seriesIndex"))
        }
        val title = json.optionalString("title")
            ?: throw IOException("BookOrbit book ${json.numericId()} has no title.")
        val overlay = preferredReadAlong(json)
        val duration = (overlay?.optJSONObject("mediaOverlay") ?: json.optJSONObject("audioMetadata"))?.opt("durationSeconds") as? Number
        val durationMs = duration?.toDouble()?.takeIf { it.isFinite() && it > 0 }?.let { (it * 1000).toLong() }
        return BookSummary(
            json.numericId(), libraryId, title, title, json.optionalString("subtitle"), json.optionalString("description"),
            json.optionalString("coverVersion"), durationMs, authors.joinToString(", ") { it.name }.ifBlank { null },
            authors, series, json.requiredString("status") == "present",
        )
    }

    override suspend fun createPlaybackSession(baseUrl: String, accessToken: String, itemId: String, sourceId: String?): PlaybackSessionSummary {
        val context = loadProgress(baseUrl, accessToken, itemId, sourceId)
        context.manifest.overlay?.let { overlay ->
            val key = ProgressKey(baseUrl, itemId, overlay.sourceId)
            if (sourceId == null) observedProgress[key] = context else observedProgress.putIfAbsent(key, context)
        }
        val manifest = context.manifest
        return PlaybackSessionSummary(
            null, itemId, manifest.title, manifest.author, manifest.title, manifest.author,
            manifest.revision, manifest.durationMs, context.state?.let { manifest.absolutePosition(it) } ?: 0L,
            null, manifest.tracks, manifest.chapters, manifest.overlay?.sourceId,
        )
    }

    override suspend fun getItemsInProgress(baseUrl: String, accessToken: String, limit: Int): List<ProgressItemSummary> = parse {
        val response = request(baseUrl, accessToken, "/dashboard/scrollers/continue-listening?limit=$limit")
        objects(response.getJSONArray("books")).filter(::hasAudio).map { ProgressItemSummary(it.numericId(), null) }
    }

    override suspend fun getMediaProgress(baseUrl: String, accessToken: String, itemId: String, retryProfile: RetryProfile, sourceId: String?): MediaProgressSummary {
        val context = loadProgress(baseUrl, accessToken, itemId,
            sourceId ?: if (retryProfile == RetryProfile.NONE) "audio" else null)
        var serverChanged = false
        if (retryProfile == RetryProfile.NONE) {
            val key = ProgressKey(baseUrl, itemId, context.manifest.overlay?.sourceId)
            val previous = observedProgress.put(key, context)
            if (context.manifest.overlay != null && previous != null) {
                serverChanged = previous.state?.optionalString("updatedAt") != context.state?.optionalString("updatedAt") &&
                    previous.state?.let { previous.manifest.absolutePosition(it) } != context.state?.let { context.manifest.absolutePosition(it) }
            }
        }
        return progressSummary(itemId, context).copy(serverChanged = serverChanged)
    }

    private fun progressSummary(bookId: String, context: ProgressContext): MediaProgressSummary = parse {
        val state = context.state ?: throw ApiException(404, "No BookOrbit listening progress.")
        val overlay = context.manifest.overlay
        val position = if (overlay != null) overlay.position(state)
            ?: throw ApiException(404, "No BookOrbit narration progress.") else context.manifest.absolutePosition(state)
        val updatedAt = Instant.parse(state.requiredString(if (overlay == null) "capturedAt" else "updatedAt")).toEpochMilli()
        val finished = if (overlay == null) state.getBoolean("completed") else position >= overlay.durationMs
        MediaProgressSummary(bookId, position, context.manifest.durationMs,
            finished, false, null, if (finished) updatedAt else null, updatedAt, overlay?.sourceId)
    }

    override suspend fun updateProgress(
        baseUrl: String, accessToken: String, bookId: String, sessionId: String?,
        sessionUpdate: PlaybackSessionUpdateRequest, close: Boolean,
    ): String? = parse {
        val key = ProgressKey(baseUrl, bookId, sessionUpdate.sourceId)
        // Use the revision observed by the conflict check, never replace it immediately before writing.
        val context = observedProgress[key] ?: loadProgress(baseUrl, accessToken, bookId, sessionUpdate.sourceId ?: "audio")
        val manifest = context.manifest
        if (manifest.overlay != null) return@parse updateNarration(baseUrl, accessToken, bookId, context, sessionUpdate)
        val position = sessionUpdate.currentTimeMs.coerceIn(0, manifest.durationMs)
        val track = manifest.tracks.lastOrNull { it.startOffsetMs <= position } ?: manifest.tracks.first()
        val body = JSONObject().put("assetId", track.id).put("positionMs", position - track.startOffsetMs)
            .put("capturedAt", Instant.ofEpochMilli(sessionUpdate.lastUpdateAt).toString())
            .put("operationId", UUID.randomUUID().toString())
            .put("baseRevision", context.state?.requiredLong("revision") ?: 0)
            .put("manifestRevision", manifest.revision)
        try {
            val state = request(baseUrl, accessToken, "/audiobooks/$bookId/playback-state", "PUT", body)
            manifest.absolutePosition(state)
            observedProgress[key] = ProgressContext(manifest, state)
            val listenedSeconds = sessionUpdate.timeListenedMs / 1000
            if (listenedSeconds > 0) {
                val listening = JSONObject()
                    .put("sessionId", body.getString("operationId"))
                    .put("sessionType", "listen").put("source", "android")
                    .put("startedAt", Instant.ofEpochMilli(sessionUpdate.lastUpdateAt - sessionUpdate.timeListenedMs).toString())
                    .put("endedAt", body.getString("capturedAt"))
                    .put("durationSeconds", listenedSeconds)
                    .put("endProgress", position.toDouble() / manifest.durationMs * 100)
                rawRequest(baseUrl, accessToken, "/books/files/${manifest.fileIds[track.index]}/sessions", "POST", listening)
            }
        } catch (error: IOException) {
            observedProgress.remove(key)
            throw error
        }
        null
    }

    private suspend fun loadProgress(baseUrl: String, token: String, id: String, sourceId: String?): ProgressContext = parse {
        checkId(id)
        if (sourceId != "audio") {
            val book = request(baseUrl, token, "/books/$id")
            if (book.numericId() != id) throw IOException("Unexpected BookOrbit book.")
            val file = if (sourceId == null) preferredReadAlong(book) else {
                val match = Regex("epub:([1-9][0-9]*):[0-9a-f]{64}").matchEntire(sourceId)
                    ?: throw IOException("Invalid BookOrbit playback source.")
                objects(book.getJSONArray("files")).find { it.numericId() == match.groupValues[1] && isReadAlong(it) }
                    ?: throw IOException("The selected BookOrbit Read-Aloud EPUB is no longer available.")
            }
            if (file != null) {
                val fileId = file.numericId()
                val overlay = BookOrbitReadAlong.parsePlaylist(request(baseUrl, token, "/epub/$id/media-overlay?fileId=$fileId"), id, fileId)
                if (sourceId != null && sourceId != overlay.sourceId) throw IOException("The BookOrbit EPUB narration timeline changed.")
                val manifest = Manifest(overlay.sourceId, book.requiredString("title"),
                    objects(book.getJSONArray("authors")).joinToString(", ") { it.requiredString("name") }.ifBlank { null },
                    overlay.durationMs, overlay.tracks, overlay.chapters, List(overlay.tracks.size) { fileId }, overlay)
                val rows = objects(request(baseUrl, token, "/reading-progress?fileId=$fileId").getJSONArray("items"))
                val state = rows.singleOrNull()?.takeIf { it.numericId("fileId") == fileId }
                    ?: throw IOException("Missing BookOrbit EPUB progress response.")
                overlay.position(state)
                return@parse ProgressContext(manifest, state)
            }
        }
        val manifest = parseManifest(request(baseUrl, token, "/audiobooks/$id/manifest"), id)
        val rawState = rawRequest(baseUrl, token, "/audiobooks/$id/playback-state")
        val state = if (rawState.trim() == "null" || rawState.isBlank()) null else JSONObject(rawState)
        state?.let {
            manifest.absolutePosition(it)
            if (it.requiredLong("revision") < 1) throw IOException("Invalid BookOrbit progress revision.")
        }
        ProgressContext(manifest, state)
    }

    private suspend fun updateNarration(baseUrl: String, token: String, bookId: String, context: ProgressContext,
        update: PlaybackSessionUpdateRequest): String? {
        val overlay = requireNotNull(context.manifest.overlay)
        val position = update.currentTimeMs.coerceIn(0, overlay.durationMs)
        // The conflict guard is a server timestamp; the device clock can lag behind it.
        val observedAt = context.state?.optionalString("updatedAt") ?: Instant.EPOCH.toString()
        val write = JSONObject().put("fileId", overlay.fileId.toLong()).put("kind", "narration")
            .put("percentage", position.toDouble() / overlay.durationMs * 100)
            .put("editedAt", observedAt)
            .put("narration", overlay.narration(position))
        val response = request(baseUrl, token, "/reading-progress", "PUT", JSONObject().put("writes", JSONArray().put(write)))
        val result = objects(response.getJSONArray("results")).singleOrNull()
            ?: throw IOException("Missing BookOrbit narration save result.")
        if (result.numericId("fileId") != overlay.fileId) throw IOException("Unexpected BookOrbit narration save result.")
        val state = result.getJSONObject("progress")
        if (state.numericId("fileId") != overlay.fileId) throw IOException("Unexpected BookOrbit narration progress.")
        val updated = ProgressContext(context.manifest, state)
        val progress = progressSummary(bookId, updated)
        when (result.requiredString("status")) {
            "saved" -> observedProgress[ProgressKey(baseUrl, bookId, overlay.sourceId)] = updated
            "superseded" -> {
                observedProgress[ProgressKey(baseUrl, bookId, overlay.sourceId)] = updated
                throw ServerProgressSupersededException(progress)
            }
            else -> throw IOException("Unknown BookOrbit narration save status.")
        }
        if (update.timeListenedMs >= 1000) {
            val session = JSONObject().put("sessionId", UUID.randomUUID().toString()).put("sessionType", "listen").put("source", "android")
                .put("startedAt", Instant.ofEpochMilli(update.lastUpdateAt - update.timeListenedMs).toString())
                .put("endedAt", Instant.ofEpochMilli(update.lastUpdateAt).toString())
                .put("durationSeconds", update.timeListenedMs / 1000).put("endProgress", position.toDouble() / overlay.durationMs * 100)
            rawRequest(baseUrl, token, "/books/files/${overlay.fileId}/sessions", "POST", session)
        }
        return null
    }

    private suspend fun request(baseUrl: String, token: String?, path: String, method: String = "GET", body: JSONObject? = null): JSONObject =
        parse { JSONObject(rawRequest(baseUrl, token, path, method, body)) }

    private suspend fun rawRequest(baseUrl: String, token: String?, path: String, method: String = "GET", body: JSONObject? = null): String {
        val headers = buildMap {
            put("Content-Type", "application/json")
            token?.let { put("Authorization", "Bearer $it") }
        }
        val response = httpClient.execute(baseUrl, HttpRequest("/api/v1$path", method, headers, body?.toString()))
        if (response.statusCode !in 200..299) throw ApiException(response.statusCode, "BookOrbit: HTTP ${response.statusCode}")
        return response.body
    }

    companion object {
        private fun isReadAlong(file: JSONObject): Boolean = file.optionalString("format") == "epub" &&
            file.optionalString("role") in setOf("primary", "content") && file.optJSONObject("mediaOverlay")?.optBoolean("available") == true

        private fun preferredReadAlong(book: JSONObject): JSONObject? {
            val files = objects(book.getJSONArray("files")).filter(::isReadAlong)
            val selected = book.optJSONObject("readAloudSync")?.let { if (it.isNull("overlayFileId")) null else it.numericId("overlayFileId") }
            return files.find { it.numericId() == selected } ?: files.firstOrNull { it.optionalString("role") == "primary" }
                ?: files.minByOrNull { it.numericId().toLong() }
        }

        internal val AUDIO_FORMATS = setOf("m4b", "mp3", "m4a", "opus", "ogg", "flac")
        internal fun hasAudio(json: JSONObject): Boolean = objects(json.getJSONArray("files")).any {
            it.optionalString("role") in setOf("primary", "content") &&
                (it.optionalString("format") in AUDIO_FORMATS || isReadAlong(it))
        }

        internal fun parseManifest(json: JSONObject, bookId: String): Manifest = parse {
            if (json.requiredString("schema") != "bookorbit.audiobook-manifest" || json.getInt("schemaVersion") != 2) {
                throw IOException("Unsupported BookOrbit audiobook manifest.")
            }
            val book = json.getJSONObject("book")
            if (book.numericId() != bookId) throw IOException("Unexpected BookOrbit manifest book.")
            var offset = 0L
            val ids = hashSetOf<String>()
            val tracks = objects(json.getJSONArray("assets")).sortedBy { it.getInt("sequence") }.mapIndexed { index, asset ->
                val id = asset.requiredString("assetId")
                if (!Regex("aud_[0-9a-fA-F-]{36}").matches(id) || !ids.add(id) || asset.getInt("sequence") != index) {
                    throw IOException("Invalid BookOrbit asset order or identity.")
                }
                val duration = asset.requiredLong("durationMs")
                if (duration <= 0 || duration > Long.MAX_VALUE - offset) throw IOException("BookOrbit audio duration is missing or invalid.")
                val format = asset.requiredString("format")
                val mime = when (format) {
                    "m4b", "m4a" -> "audio/mp4"
                    "mp3" -> "audio/mpeg"
                    "ogg", "opus" -> "audio/ogg"
                    "flac" -> "audio/flac"
                    else -> throw IOException("Unsupported BookOrbit audio format: $format")
                }
                PlaybackTrackSummary(id, index, null, "/api/v1/audiobooks/$bookId/assets/$id/content", mime, duration, offset)
                    .also { offset += duration }
            }
            if (tracks.isEmpty() || json.requiredLong("totalDurationMs") != offset) throw IOException("Inconsistent BookOrbit audio duration.")
            val chapters = objects(json.getJSONArray("chapters")).map {
                PlaybackChapter(it.optionalString("title"), it.getLong("startMs"), it.getLong("endMs"))
            }.validateChapters()
            if (chapters.any { it.endMs > offset }) throw IOException("BookOrbit chapter exceeds book duration.")
            val revision = json.requiredString("revision")
            if (!Regex("[0-9a-f]{64}").matches(revision)) throw IOException("Invalid BookOrbit manifest revision.")
            val fileIds = objects(json.getJSONArray("assets")).sortedBy { it.getInt("sequence") }.map { it.numericId("fileId") }
            Manifest(revision, book.requiredString("title"),
                (0 until book.getJSONArray("authors").length()).joinToString(", ") { book.getJSONArray("authors").getString(it) }.ifBlank { null },
                offset, tracks, chapters, fileIds)
        }

        internal fun objects(array: JSONArray): List<JSONObject> = List(array.length()) { array.getJSONObject(it) }
        internal fun JSONObject.requiredString(key: String): String = (opt(key) as? String)?.takeIf { it.isNotBlank() }
            ?: throw IOException("BookOrbit response is missing $key.")
        internal fun JSONObject.optionalString(key: String): String? = if (isNull(key)) null else requiredString(key)
        internal fun JSONObject.numericId(key: String = "id"): String {
            val id = requiredLong(key).toString()
            checkId(id)
            return id
        }
        internal fun JSONObject.requiredLong(key: String): Long {
            val number = opt(key) as? Number ?: throw IOException("BookOrbit response is missing numeric $key.")
            val value = number.toDouble()
            val integer = number.toLong()
            if (!value.isFinite() || value != integer.toDouble() || value >= Long.MAX_VALUE.toDouble()) {
                throw IOException("BookOrbit response has invalid integer $key.")
            }
            return integer
        }
        private fun checkId(id: String) {
            if (!Regex("[1-9][0-9]*").matches(id)) throw IOException("Invalid BookOrbit ID.")
        }
        internal inline fun <T> parse(block: () -> T): T = try { block() } catch (error: JSONException) {
            throw IOException("Invalid BookOrbit response.", error)
        } catch (error: IllegalArgumentException) {
            throw IOException("Invalid BookOrbit response.", error)
        } catch (error: java.time.DateTimeException) {
            throw IOException("Invalid BookOrbit timestamp.", error)
        }
    }

    internal data class Manifest(
        val revision: String, val title: String, val author: String?, val durationMs: Long,
        val tracks: List<PlaybackTrackSummary>, val chapters: List<PlaybackChapter>, val fileIds: List<String>,
        val overlay: BookOrbitReadAlong? = null,
    ) {
        fun absolutePosition(state: JSONObject): Long {
            overlay?.let { return it.position(state) ?: 0L }
            val assetId = state.requiredString("assetId")
            val track = tracks.find { it.id == assetId } ?: throw IOException("Unknown BookOrbit progress asset.")
            val position = state.requiredLong("positionMs")
            if (position < 0 || position > requireNotNull(track.durationMs)) throw IOException("Invalid BookOrbit listening position.")
            return track.startOffsetMs + position
        }
    }
}
