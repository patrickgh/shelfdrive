package io.audiobookshelf.aaos.server

import io.audiobookshelf.aaos.absapi.AuthenticatedSession
import io.audiobookshelf.aaos.absapi.AuthorSummary
import io.audiobookshelf.aaos.absapi.BookSummary
import io.audiobookshelf.aaos.absapi.LibrarySummary
import io.audiobookshelf.aaos.absapi.MediaProgressSummary
import io.audiobookshelf.aaos.absapi.PlaybackSessionSummary
import io.audiobookshelf.aaos.absapi.PlaybackSessionUpdateRequest
import io.audiobookshelf.aaos.absapi.ProgressItemSummary
import io.audiobookshelf.aaos.absapi.RetryProfile

interface AudiobookServerApi {
    suspend fun login(baseUrl: String, username: String, password: String): AuthenticatedSession
    suspend fun authorize(baseUrl: String, accessToken: String, fallbackUsername: String?): AuthenticatedSession?
    suspend fun refreshAccessToken(baseUrl: String, refreshToken: String, fallbackUsername: String?): AuthenticatedSession
    suspend fun getLibraries(baseUrl: String, accessToken: String): List<LibrarySummary>
    suspend fun getLibraryItems(baseUrl: String, accessToken: String, libraryId: String): List<BookSummary>
    suspend fun getLibraryAuthors(baseUrl: String, accessToken: String, libraryId: String): List<AuthorSummary>
    suspend fun createPlaybackSession(baseUrl: String, accessToken: String, itemId: String, sourceId: String? = null): PlaybackSessionSummary
    suspend fun getItemsInProgress(baseUrl: String, accessToken: String, limit: Int = 25): List<ProgressItemSummary>
    suspend fun getMediaProgress(baseUrl: String, accessToken: String, itemId: String,
        retryProfile: RetryProfile = RetryProfile.BACKGROUND_REFRESH, sourceId: String? = null): MediaProgressSummary
    suspend fun updateProgress(baseUrl: String, accessToken: String, bookId: String, sessionId: String?,
        sessionUpdate: PlaybackSessionUpdateRequest, close: Boolean): String?
}
