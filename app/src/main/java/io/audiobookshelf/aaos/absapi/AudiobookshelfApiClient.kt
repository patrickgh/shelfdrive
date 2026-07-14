package io.audiobookshelf.aaos.absapi

import io.audiobookshelf.aaos.BuildConfig
import io.audiobookshelf.aaos.status.UserVisibleStatus
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.util.Locale

class AudiobookshelfApiClient(
    private val httpClient: AudiobookshelfHttpClient = AudiobookshelfHttpClient(),
) {

    @Throws(ApiException::class, IOException::class)
    suspend fun login(baseUrl: String, username: String, password: String): AuthenticatedSession {
        val payload = JSONObject()
            .put("username", username)
            .put("password", password)
            .toString()

        val response = httpClient.execute(
            baseUrl = baseUrl,
            request = HttpRequest(
                path = "/login",
                method = "POST",
                headers = mapOf(
                    "Content-Type" to "application/json",
                    "x-return-tokens" to "true",
                ),
                body = payload,
                retryProfile = RetryProfile.SESSION_SYNC,
            ),
        )

        if (response.statusCode !in 200..299) {
            throw ApiException(response.statusCode, extractErrorMessage(response.body))
        }

        return parseAuthenticatedSession(
            body = response.body,
            fallbackUsername = username,
            fallbackRefreshToken = extractRefreshToken(response.headers),
        )
    }

    @Throws(ApiException::class, IOException::class)
    suspend fun authorize(
        baseUrl: String,
        accessToken: String,
        fallbackUsername: String?,
    ): AuthorizationState {
        return authorizeWithPath(
            baseUrl = baseUrl,
            accessToken = accessToken,
            fallbackUsername = fallbackUsername,
            path = "/api/authorize",
        ) {
            authorizeWithPath(
                baseUrl = baseUrl,
                accessToken = accessToken,
                fallbackUsername = fallbackUsername,
                path = "/api/me",
            ) {
                validateTokenWithLibraries(baseUrl, accessToken, fallbackUsername)
            }
        }
    }

    private suspend fun authorizeWithPath(
        baseUrl: String,
        accessToken: String,
        fallbackUsername: String?,
        path: String,
        onNotFound: suspend () -> AuthorizationState,
    ): AuthorizationState {
        val response = httpClient.execute(
            baseUrl = baseUrl,
            request = HttpRequest(
                path = path,
                method = "GET",
                headers = mapOf("Authorization" to "Bearer $accessToken"),
                retryProfile = RetryProfile.FOREGROUND_IDEMPOTENT,
            ),
        )

        return when (response.statusCode) {
            in 200..299 -> AuthorizationState(
                isAuthorized = true,
                session = parseAuthenticatedSession(
                    body = response.body,
                    fallbackUsername = fallbackUsername,
                    fallbackAccessToken = accessToken,
                ),
            )

            HttpURLConnection.HTTP_UNAUTHORIZED -> AuthorizationState(
                isAuthorized = false,
                session = null,
            )

            HttpURLConnection.HTTP_NOT_FOUND -> onNotFound()
            else -> throw ApiException(response.statusCode, extractErrorMessage(response.body))
        }
    }

    @Throws(ApiException::class, IOException::class)
    suspend fun refreshAccessToken(
        baseUrl: String,
        refreshToken: String,
        fallbackUsername: String?,
    ): AuthenticatedSession {
        val response = httpClient.execute(
            baseUrl = baseUrl,
            request = HttpRequest(
                path = "/auth/refresh",
                method = "POST",
                headers = mapOf(
                    "Authorization" to "Bearer $refreshToken",
                    "x-refresh-token" to refreshToken,
                    "x-return-tokens" to "true",
                ),
                retryProfile = RetryProfile.NONE,
            ),
        )

        if (response.statusCode !in 200..299) {
            throw ApiException(response.statusCode, extractErrorMessage(response.body))
        }

        return parseAuthenticatedSession(
            body = response.body,
            fallbackUsername = fallbackUsername,
            fallbackRefreshToken = extractRefreshToken(response.headers) ?: refreshToken,
        )
    }

    @Throws(ApiException::class, IOException::class)
    suspend fun getLibraries(baseUrl: String, accessToken: String): List<LibrarySummary> {
        val response = executeAuthorized(
            baseUrl = baseUrl,
            accessToken = accessToken,
            path = "/api/libraries",
            retryProfile = RetryProfile.BACKGROUND_REFRESH,
        )

        val root = JSONObject(response.body)
        val libraries = root.optJSONArray("libraries") ?: return emptyList()
        return buildList(libraries.length()) {
            for (index in 0 until libraries.length()) {
                val library = libraries.optJSONObject(index) ?: continue
                add(
                    LibrarySummary(
                        id = library.optString("id"),
                        name = library.optString("name"),
                        mediaType = library.optString("mediaType"),
                    ),
                )
            }
        }
    }

    @Throws(ApiException::class, IOException::class)
    suspend fun getLibraryItems(
        baseUrl: String,
        accessToken: String,
        libraryId: String,
    ): List<BookSummary> {
        val response = executeAuthorized(
            baseUrl = baseUrl,
            accessToken = accessToken,
            path = "/api/libraries/$libraryId/items?limit=0&sort=media.metadata.title&desc=0",
            retryProfile = RetryProfile.BACKGROUND_REFRESH,
        )

        val root = JSONObject(response.body)
        val results = root.optJSONArray("results") ?: return emptyList()
        return buildList(results.length()) {
            for (index in 0 until results.length()) {
                val item = results.optJSONObject(index) ?: continue
                val media = item.optJSONObject("media") ?: continue
                val metadata = media.optJSONObject("metadata") ?: continue
                add(
                    BookSummary(
                        id = item.optString("id"),
                        libraryId = libraryId,
                        title = metadata.optString("title").ifBlank { item.optString("title") },
                        sortTitle = metadata.optString("titleIgnorePrefix")
                            .ifBlank { metadata.optString("title") }
                            .ifBlank { item.optString("title") },
                        subtitle = metadata.optString("subtitle").takeIf { it.isNotBlank() },
                        description = metadata.optString("description").takeIf { it.isNotBlank() },
                        coverPath = media.optString("coverPath").takeIf { it.isNotBlank() },
                        durationMs = media.optDouble("duration").takeIf { !it.isNaN() }?.let { (it * 1000).toLong() },
                        authorDisplay = metadata.optString("authorName").takeIf { it.isNotBlank() },
                        authors = parseAuthors(metadata),
                        addedAt = item.optLong("addedAt").takeIf { it > 0L },
                        isPlayable = item.optBoolean("isMissing").not() && item.optBoolean("isInvalid").not(),
                    ),
                )
            }
        }
    }

    @Throws(ApiException::class, IOException::class)
    suspend fun getLibraryAuthors(
        baseUrl: String,
        accessToken: String,
        libraryId: String,
    ): List<AuthorSummary> {
        val response = executeAuthorized(
            baseUrl = baseUrl,
            accessToken = accessToken,
            path = "/api/libraries/$libraryId/authors",
            retryProfile = RetryProfile.BACKGROUND_REFRESH,
        )

        val root = JSONObject(response.body)
        val authors = root.optJSONArray("authors") ?: return emptyList()
        return buildList(authors.length()) {
            for (index in 0 until authors.length()) {
                val author = authors.optJSONObject(index) ?: continue
                add(
                    AuthorSummary(
                        id = author.optString("id"),
                        name = author.optString("name"),
                        sortName = author.optString("nameIgnorePrefix").ifBlank { author.optString("name") },
                        imagePath = author.optString("imagePath").takeIf { it.isNotBlank() },
                        numBooks = author.optInt("numBooks"),
                    ),
                )
            }
        }
    }

    @Throws(ApiException::class, IOException::class)
    suspend fun createPlaybackSession(
        baseUrl: String,
        accessToken: String,
        itemId: String,
    ): PlaybackSessionSummary {
        val response = httpClient.execute(
            baseUrl = baseUrl,
            request = HttpRequest(
                path = "/api/items/$itemId/play",
                method = "POST",
                headers = mapOf(
                    "Authorization" to "Bearer $accessToken",
                    "Content-Type" to "application/json",
                ),
                body = playbackSessionRequestBody(),
                retryProfile = RetryProfile.NONE,
            ),
        )
        if (response.statusCode !in 200..299) {
            throw ApiException(response.statusCode, extractErrorMessage(response.body))
        }

        val root = JSONObject(response.body)
        val libraryItem = root.optJSONObject("libraryItem")
        val media = libraryItem?.optJSONObject("media")
        val metadata = media?.optJSONObject("metadata")
        val audioTracks = root.optJSONArray("audioTracks") ?: media?.optJSONArray("audioTracks")

        val tracks = buildList(audioTracks?.length() ?: 0) {
            if (audioTracks == null) return@buildList
            for (index in 0 until audioTracks.length()) {
                val track = audioTracks.optJSONObject(index) ?: continue
                val metadataObject = track.optJSONObject("metadata")
                val contentUrl = track.optString("contentUrl").takeIf { it.isNotBlank() } ?: continue
                add(
                    PlaybackTrackSummary(
                        id = track.optString("id").ifBlank { "track-$index" },
                        index = track.optInt("index", index),
                        title = track.optString("title").ifBlank {
                            metadataObject?.optString("title").orEmpty()
                        }.takeIf { it.isNotBlank() },
                        contentUrl = contentUrl,
                        mimeType = track.optString("mimeType")
                            .ifBlank { inferMimeTypeFromPath(contentUrl) },
                        durationMs = secondsToMillis(track.optDouble("duration")),
                        startOffsetMs = secondsToMillis(track.optDouble("startOffset")) ?: 0L,
                    ),
                )
            }
        }

        return PlaybackSessionSummary(
            sessionId = root.optString("id").takeIf { it.isNotBlank() },
            bookId = itemId,
            displayTitle = root.optString("displayTitle").takeIf { it.isNotBlank() },
            displayAuthor = root.optString("displayAuthor").takeIf { it.isNotBlank() },
            title = metadata?.optString("title").takeIf { !it.isNullOrBlank() },
            author = metadata?.optString("authorName").takeIf { !it.isNullOrBlank() },
            coverPath = media?.optString("coverPath").takeIf { !it.isNullOrBlank() },
            durationMs = secondsToMillis(media?.optDouble("duration")),
            currentTimeMs = secondsToMillis(root.optDouble("currentTime")),
            startTimeMs = secondsToMillis(root.optDouble("startTime")),
            audioTracks = tracks,
        )
    }

    @Throws(ApiException::class, IOException::class)
    suspend fun syncPlaybackSession(
        baseUrl: String,
        accessToken: String,
        sessionId: String,
        sessionUpdate: PlaybackSessionUpdateRequest,
    ) {
        updatePlaybackSession(
            baseUrl = baseUrl,
            accessToken = accessToken,
            sessionId = sessionId,
            pathSuffix = "sync",
            sessionUpdate = sessionUpdate,
        )
    }

    @Throws(ApiException::class, IOException::class)
    suspend fun closePlaybackSession(
        baseUrl: String,
        accessToken: String,
        sessionId: String,
        sessionUpdate: PlaybackSessionUpdateRequest,
    ) {
        updatePlaybackSession(
            baseUrl = baseUrl,
            accessToken = accessToken,
            sessionId = sessionId,
            pathSuffix = "close",
            sessionUpdate = sessionUpdate,
        )
    }

    private suspend fun updatePlaybackSession(
        baseUrl: String,
        accessToken: String,
        sessionId: String,
        pathSuffix: String,
        sessionUpdate: PlaybackSessionUpdateRequest,
    ) {
        val response = httpClient.execute(
            baseUrl = baseUrl,
            request = HttpRequest(
                path = "/api/session/$sessionId/$pathSuffix",
                method = "POST",
                headers = mapOf(
                    "Authorization" to "Bearer $accessToken",
                    "Content-Type" to "application/json",
                ),
                body = playbackSessionUpdateRequestBody(sessionUpdate),
                retryProfile = RetryProfile.NONE,
            ),
        )
        if (response.statusCode !in 200..299) {
            throw ApiException(response.statusCode, extractErrorMessage(response.body))
        }
    }

    @Throws(ApiException::class, IOException::class)
    suspend fun getItemsInProgress(
        baseUrl: String,
        accessToken: String,
        limit: Int = 25,
    ): List<ProgressItemSummary> {
        val response = executeAuthorized(
            baseUrl = baseUrl,
            accessToken = accessToken,
            path = "/api/me/items-in-progress?limit=$limit",
            retryProfile = RetryProfile.BACKGROUND_REFRESH,
        )

        val root = JSONObject(response.body)
        val items = root.optJSONArray("libraryItems") ?: return emptyList()
        return buildList(items.length()) {
            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index) ?: continue
                val media = item.optJSONObject("media")
                val metadata = media?.optJSONObject("metadata")
                add(
                    ProgressItemSummary(
                        bookId = item.optString("id"),
                        progressLastUpdateAt = parseAbsTimestamp(item.opt("progressLastUpdate")),
                        title = metadata?.optString("title").takeIf { !it.isNullOrBlank() },
                    ),
                )
            }
        }
    }

    @Throws(ApiException::class, IOException::class)
    suspend fun getMediaProgress(
        baseUrl: String,
        accessToken: String,
        itemId: String,
    ): MediaProgressSummary {
        val response = executeAuthorized(
            baseUrl = baseUrl,
            accessToken = accessToken,
            path = "/api/me/progress/$itemId",
            retryProfile = RetryProfile.BACKGROUND_REFRESH,
        )

        val root = JSONObject(response.body)
        return MediaProgressSummary(
            bookId = itemId,
            currentTimeMs = secondsToMillis(root.optDouble("currentTime")) ?: 0L,
            durationMs = secondsToMillis(root.optDouble("duration")),
            progressFraction = root.optDouble("progress").takeIf { !it.isNaN() },
            isFinished = root.optBoolean("isFinished"),
            hideFromContinueListening = root.optBoolean("hideFromContinueListening"),
            startedAt = parseAbsTimestamp(root.opt("startedAt")),
            finishedAt = parseAbsTimestamp(root.opt("finishedAt")),
            lastUpdateAt = parseAbsTimestamp(root.opt("lastUpdate")) ?: System.currentTimeMillis(),
        )
    }

    private suspend fun executeAuthorized(
        baseUrl: String,
        accessToken: String,
        path: String,
        retryProfile: RetryProfile,
    ): HttpResponse {
        val response = httpClient.execute(
            baseUrl = baseUrl,
            request = HttpRequest(
                path = path,
                method = "GET",
                headers = mapOf("Authorization" to "Bearer $accessToken"),
                retryProfile = retryProfile,
            ),
        )
        if (response.statusCode !in 200..299) {
            throw ApiException(response.statusCode, extractErrorMessage(response.body))
        }
        return response
    }

    private suspend fun validateTokenWithLibraries(
        baseUrl: String,
        accessToken: String,
        fallbackUsername: String?,
    ): AuthorizationState {
        val response = httpClient.execute(
            baseUrl = baseUrl,
            request = HttpRequest(
                path = "/api/libraries",
                method = "GET",
                headers = mapOf("Authorization" to "Bearer $accessToken"),
                retryProfile = RetryProfile.FOREGROUND_IDEMPOTENT,
            ),
        )

        return when (response.statusCode) {
            in 200..299 -> AuthorizationState(
                isAuthorized = true,
                session = AuthenticatedSession(
                    username = fallbackUsername.orEmpty(),
                    accessToken = accessToken,
                    refreshToken = null,
                    serverVersion = null,
                    compatibilityWarning = UserVisibleStatus.SERVER_VERSION_UNKNOWN,
                    isSupported = true,
                ),
            )

            HttpURLConnection.HTTP_UNAUTHORIZED -> AuthorizationState(isAuthorized = false, session = null)
            else -> throw ApiException(response.statusCode, extractErrorMessage(response.body))
        }
    }

    private fun parseAuthenticatedSession(
        body: String,
        fallbackUsername: String?,
        fallbackAccessToken: String? = null,
        fallbackRefreshToken: String? = null,
    ): AuthenticatedSession {
        val json = JSONObject(body)
        val user = json.optJSONObject("user")
            ?: json.takeIf { it.has("username") }
            ?: throw ApiException(200, "Antwort enthält kein user-Objekt.")
        val token = user.optString("accessToken").takeIf { it.isNotBlank() }
            ?: user.optString("token").takeIf { it.isNotBlank() }
            ?: fallbackAccessToken
        val refreshToken = user.optString("refreshToken").takeIf { it.isNotBlank() }
            ?: fallbackRefreshToken
        val serverSettings = json.optJSONObject("serverSettings")
        val version = serverSettings?.optString("version")?.takeIf { it.isNotBlank() }
        val compatibility = ServerCompatibility.evaluate(version)

        return AuthenticatedSession(
            username = user.optString("username").ifBlank { fallbackUsername.orEmpty() },
            accessToken = token,
            refreshToken = refreshToken,
            serverVersion = compatibility.serverVersion,
            compatibilityWarning = compatibility.warningMessage,
            isSupported = compatibility.isSupported,
        )
    }

    private fun extractErrorMessage(body: String): String {
        val trimmedBody = body.trim()
        if (trimmedBody.startsWith("<!DOCTYPE", ignoreCase = true) || trimmedBody.startsWith("<html", ignoreCase = true)) {
            return stripHtml(trimmedBody)
                .ifBlank { "Server lieferte eine HTML-Fehlerseite." }
                .take(MAX_ERROR_MESSAGE_LENGTH)
        }

        return runCatching {
            val json = JSONObject(body)
            json.optString("error").ifBlank {
                json.optString("message").ifBlank { body }
            }
        }.getOrElse { body }
            .ifBlank { "Unbekannter Fehler" }
            .take(MAX_ERROR_MESSAGE_LENGTH)
    }

    private fun stripHtml(body: String): String {
        val preContent = Regex("<pre>(.*?)</pre>", RegexOption.DOT_MATCHES_ALL)
            .find(body)
            ?.groupValues
            ?.getOrNull(1)
        return (preContent ?: body)
            .replace(Regex("<[^>]+>"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun extractRefreshToken(headers: Map<String, List<String>>): String? {
        return headers.entries
            .firstOrNull { it.key.equals("Set-Cookie", ignoreCase = true) }
            ?.value
            ?.asSequence()
            ?.mapNotNull { cookie ->
                cookie.split(";")
                    .firstOrNull()
                    ?.trim()
                    ?.takeIf { it.startsWith("refresh_token=") }
                    ?.substringAfter("refresh_token=")
                    ?.takeIf { it.isNotBlank() }
            }
            ?.firstOrNull()
    }

    private fun playbackSessionRequestBody(): String {
        return JSONObject()
            .put("forceDirectPlay", true)
            .put("forceTranscode", false)
            .put("mediaPlayer", "ExoPlayer")
            .put(
                "supportedMimeTypes",
                JSONArray(
                    listOf(
                        "audio/mpeg",
                        "audio/mp4",
                        "audio/m4b",
                        "audio/x-m4b",
                        "audio/aac",
                        "audio/flac",
                        "audio/ogg",
                        "audio/vorbis",
                        "audio/wav",
                        "audio/x-wav",
                    ),
                ),
            )
            .put(
                "deviceInfo",
                JSONObject()
                    .put("clientName", "ShelfDrive")
                    .put("clientVersion", BuildConfig.VERSION_NAME)
                    .put("manufacturer", "Android Automotive")
                    .put("model", "AAOS"),
            )
            .toString()
    }

    private fun parseAuthors(metadata: JSONObject): List<AuthorSummary> {
        val authors = metadata.optJSONArray("authors")
        if (authors == null || authors.length() == 0) {
            return emptyList()
        }

        return buildList(authors.length()) {
            for (index in 0 until authors.length()) {
                val author = authors.optJSONObject(index) ?: continue
                add(
                    AuthorSummary(
                        id = author.optString("id"),
                        name = author.optString("name"),
                        sortName = author.optString("nameIgnorePrefix").ifBlank { author.optString("name") },
                        imagePath = author.optString("imagePath").takeIf { it.isNotBlank() },
                        numBooks = author.optInt("numBooks"),
                    ),
                )
            }
        }
    }

    private fun secondsToMillis(value: Double?): Long? {
        if (value == null || value.isNaN() || value < 0.0) {
            return null
        }
        return (value * 1000.0).toLong()
    }

    private fun inferMimeTypeFromPath(path: String): String? {
        val extension = path.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return when (extension) {
            "mp3" -> "audio/mpeg"
            "m4b", "m4a", "mp4" -> "audio/mp4"
            "aac" -> "audio/aac"
            else -> null
        }
    }

    private fun parseAbsTimestamp(value: Any?): Long? {
        if (value == null || value == JSONObject.NULL) {
            return null
        }
        if (value is Number) {
            return value.toLong().takeIf { it >= 0L }
        }

        val rawValue = value.toString().trim()
        if (rawValue.isBlank()) {
            return null
        }
        rawValue.toLongOrNull()?.let { timestamp ->
            return timestamp.takeIf { it >= 0L }
        }
        return runCatching {
            java.time.Instant.parse(rawValue).toEpochMilli()
        }.getOrNull()
    }

    companion object {
        private const val MAX_ERROR_MESSAGE_LENGTH = 240
    }
}

internal fun playbackSessionUpdateRequestBody(sessionUpdate: PlaybackSessionUpdateRequest): String {
    return JSONObject(playbackSessionUpdateRequestFields(sessionUpdate)).toString()
}

internal fun playbackSessionUpdateRequestFields(sessionUpdate: PlaybackSessionUpdateRequest): Map<String, Any> {
    return buildMap {
        put("currentTime", sessionUpdate.currentTimeMs / 1000.0)
        put("timeListened", sessionUpdate.timeListenedMs / 1000.0)
        put("duration", sessionUpdate.durationMs / 1000.0)
        put("lastUpdate", sessionUpdate.lastUpdateAt)
    }
}

data class AuthenticatedSession(
    val username: String,
    val accessToken: String?,
    val refreshToken: String?,
    val serverVersion: String?,
    val compatibilityWarning: String?,
    val isSupported: Boolean,
)

data class AuthorizationState(
    val isAuthorized: Boolean,
    val session: AuthenticatedSession?,
)

data class LibrarySummary(
    val id: String,
    val name: String,
    val mediaType: String,
)

data class BookSummary(
    val id: String,
    val libraryId: String,
    val title: String,
    val sortTitle: String,
    val subtitle: String?,
    val description: String?,
    val coverPath: String?,
    val durationMs: Long?,
    val authorDisplay: String?,
    val authors: List<AuthorSummary>,
    val addedAt: Long?,
    val isPlayable: Boolean,
)

data class AuthorSummary(
    val id: String,
    val name: String,
    val sortName: String,
    val imagePath: String?,
    val numBooks: Int,
)

data class PlaybackSessionSummary(
    val sessionId: String?,
    val bookId: String,
    val displayTitle: String?,
    val displayAuthor: String?,
    val title: String?,
    val author: String?,
    val coverPath: String?,
    val durationMs: Long?,
    val currentTimeMs: Long?,
    val startTimeMs: Long?,
    val audioTracks: List<PlaybackTrackSummary>,
)

data class PlaybackTrackSummary(
    val id: String,
    val index: Int,
    val title: String?,
    val contentUrl: String,
    val mimeType: String?,
    val durationMs: Long?,
    val startOffsetMs: Long,
)

data class ProgressItemSummary(
    val bookId: String,
    val progressLastUpdateAt: Long?,
    val title: String?,
)

data class MediaProgressSummary(
    val bookId: String,
    val currentTimeMs: Long,
    val durationMs: Long?,
    val progressFraction: Double?,
    val isFinished: Boolean,
    val hideFromContinueListening: Boolean,
    val startedAt: Long?,
    val finishedAt: Long?,
    val lastUpdateAt: Long,
)

data class PlaybackSessionUpdateRequest(
    val currentTimeMs: Long,
    val durationMs: Long,
    val timeListenedMs: Long,
    val lastUpdateAt: Long,
)

class ApiException(
    val statusCode: Int,
    override val message: String,
) : IOException(message)
