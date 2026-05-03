package io.audiobookshelf.aaos.media3

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Bundle
import android.os.Process
import android.util.Log
import androidx.annotation.OptIn
import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.media.utils.MediaConstants
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.CommandButton
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionCommands
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import io.audiobookshelf.aaos.R
import io.audiobookshelf.aaos.absapi.ApiException
import io.audiobookshelf.aaos.account.AudiobookshelfAccountContract
import io.audiobookshelf.aaos.account.AudiobookshelfAccountRegistry
import io.audiobookshelf.aaos.artwork.ArtworkUriFactory
import io.audiobookshelf.aaos.auth.AuthCommands
import io.audiobookshelf.aaos.auth.AuthRepository
import io.audiobookshelf.aaos.auth.AuthSnapshot
import io.audiobookshelf.aaos.auth.AuthStatus
import io.audiobookshelf.aaos.auth.AuthStorage
import io.audiobookshelf.aaos.auth.AuthenticationRequiredException
import io.audiobookshelf.aaos.browser.BrowseNodeId
import io.audiobookshelf.aaos.browser.CatalogBrowseRepository
import io.audiobookshelf.aaos.browser.CatalogBrowseRepository.BrowseCollection
import io.audiobookshelf.aaos.cache.CacheCommands
import io.audiobookshelf.aaos.cache.CacheRepository
import io.audiobookshelf.aaos.catalog.persistence.AuthorEntity
import io.audiobookshelf.aaos.catalog.persistence.BookEntity
import io.audiobookshelf.aaos.catalog.persistence.CatalogDatabase
import io.audiobookshelf.aaos.host.MediaHostIntentFactory
import io.audiobookshelf.aaos.playback.AudiobookshelfPlaybackRepository
import io.audiobookshelf.aaos.playback.PlaybackPreferences
import io.audiobookshelf.aaos.playback.PlaybackQueueMath
import io.audiobookshelf.aaos.playback.PlaybackResumePolicy
import io.audiobookshelf.aaos.playback.ResolvedAudiobookPlayback
import io.audiobookshelf.aaos.playback.ResolvedAudiobookPlaybackSession
import io.audiobookshelf.aaos.progress.PlaybackProgressReason
import io.audiobookshelf.aaos.progress.PlaybackProgressSnapshot
import io.audiobookshelf.aaos.progress.ProgressSyncRepository
import io.audiobookshelf.aaos.status.UserVisibleStatus
import io.audiobookshelf.aaos.sync.CatalogSyncRepository
import io.audiobookshelf.aaos.sync.SyncCommands
import io.audiobookshelf.aaos.sync.SyncSnapshot
import io.audiobookshelf.aaos.sync.SyncStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.IOException

@OptIn(UnstableApi::class)
class ShelfDriveMediaLibraryService : MediaLibraryService(), Player.Listener {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var authStorage: AuthStorage
    private lateinit var authRepository: AuthRepository
    private lateinit var syncRepository: CatalogSyncRepository
    private lateinit var browseRepository: CatalogBrowseRepository
    private lateinit var playbackRepository: AudiobookshelfPlaybackRepository
    private lateinit var progressSyncRepository: ProgressSyncRepository
    private lateinit var cacheRepository: CacheRepository
    private lateinit var player: ExoPlayer
    private lateinit var mediaLibrarySession: MediaLibrarySession

    private val httpDataSourceFactory = DefaultHttpDataSource.Factory()
    private var authSnapshot: AuthSnapshot = AuthSnapshot(status = AuthStatus.LOGGED_OUT)
    private var syncSnapshot: SyncSnapshot = SyncSnapshot(status = SyncStatus.IDLE)
    private var activeBook: ResolvedAudiobookPlayback? = null
    private var periodicProgressJob: Job? = null
    private var wasPlayWhenReady: Boolean = false

    override fun onCreate() {
        super.onCreate()

        authStorage = AuthStorage(this)
        authRepository = AuthRepository(
            storage = authStorage,
            accountRegistry = AudiobookshelfAccountRegistry(this),
        )
        val database = CatalogDatabase.getInstance(this)
        browseRepository = CatalogBrowseRepository(database)
        syncRepository = CatalogSyncRepository(
            database = database,
            authRepository = authRepository,
            authStorage = authStorage,
        )
        progressSyncRepository = ProgressSyncRepository(
            database = database,
            authRepository = authRepository,
            authStorage = authStorage,
        )
        cacheRepository = CacheRepository(this, database)
        playbackRepository = AudiobookshelfPlaybackRepository(
            authRepository = authRepository,
            authStorage = authStorage,
            database = database,
        )

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(httpDataSourceFactory))
            .setSeekBackIncrementMs(SEEK_INCREMENT_MS)
            .setSeekForwardIncrementMs(SEEK_INCREMENT_MS)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
            .apply {
                addListener(this@ShelfDriveMediaLibraryService)
            }

        mediaLibrarySession = MediaLibrarySession.Builder(this, player, LibraryCallback())
            .setSessionActivity(MediaHostIntentFactory.createMediaHostPendingIntent(this))
            .setMediaButtonPreferences(mediaButtonPreferences())
            .build()

        serviceScope.launch {
            val initialAuth = authRepository.bootstrap()
            updateAuthSnapshot(initialAuth)
            updateSyncSnapshot(syncRepository.loadSnapshot())
            if (initialAuth.isAuthenticated) {
                val snapshot = syncRepository.syncIfStale()
                updateSyncSnapshot(snapshot)
                if (snapshot.status != SyncStatus.FAILED) {
                    progressSyncRepository.refreshInProgress()
                }
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? {
        return mediaLibrarySession
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onDestroy() {
        periodicProgressJob?.cancel()
        mediaLibrarySession.release()
        player.removeListener(this)
        player.release()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        if (isPlaying) {
            startPeriodicProgressUpdates()
        } else {
            periodicProgressJob?.cancel()
        }
    }

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        if (wasPlayWhenReady && !playWhenReady && player.playbackState != Player.STATE_ENDED) {
            applyRewindOnPauseIfEnabled()
            emitProgress(PlaybackProgressReason.PAUSED)
        }
        wasPlayWhenReady = playWhenReady
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState == Player.STATE_ENDED) {
            emitProgress(PlaybackProgressReason.ENDED)
            periodicProgressJob?.cancel()
        }
    }

    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int,
    ) {
        if (reason == Player.DISCONTINUITY_REASON_SEEK) {
            emitProgress(PlaybackProgressReason.SEEKED)
        }
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) {
            emitProgress(PlaybackProgressReason.TRACK_CHANGED)
        }
    }

    override fun onPlayerError(error: PlaybackException) {
        val activeTrack = activeBook?.queue?.getOrNull(player.currentMediaItemIndex)
        Log.e(
            TAG,
            "Playback failed for book=${activeBook?.bookId} track=${activeTrack?.contentUrl?.substringBefore("?")}",
            error,
        )
    }

    private inner class LibraryCallback : MediaLibrarySession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            if (!isAllowedController(controller)) {
                Log.w(TAG, "Rejected Media3 controller ${controller.packageName}/${controller.uid}.")
                return MediaSession.ConnectionResult.reject()
            }

            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(availableSessionCommands())
                .setAvailablePlayerCommands(availablePlayerCommands())
                .setMediaButtonPreferences(mediaButtonPreferences())
                .setSessionActivity(MediaHostIntentFactory.createMediaHostPendingIntent(this@ShelfDriveMediaLibraryService))
                .build()
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            return Futures.immediateFuture(LibraryResult.ofItem(buildRootItem(), rootParams(params)))
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            return serviceFuture("getItem:$mediaId") {
                val item = loadItem(mediaId)
                if (item == null) {
                    LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
                } else {
                    LibraryResult.ofItem(item, null)
                }
            }
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            return serviceFuture("getChildren:$parentId") {
                val children = loadChildren(parentId)
                LibraryResult.ofItemList(pageItems(children, page, pageSize), params)
            }
        }

        override fun onSearch(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<Void>> {
            return serviceFuture("search:$query") {
                val count = loadSearchResults(query).size
                session.notifySearchResultChanged(browser, query, count, params)
                LibraryResult.ofVoid(params)
            }
        }

        override fun onGetSearchResult(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            return serviceFuture("getSearchResult:$query") {
                LibraryResult.ofItemList(pageItems(loadSearchResults(query), page, pageSize), params)
            }
        }

        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            return serviceFuture("setMediaItems") {
                val requestedItem = mediaItems.firstOrNull()
                    ?: throw PlaybackResolutionException("Kein Medium ausgewaehlt.")
                Log.i(TAG, "Host requested playback for mediaId=${requestedItem.mediaId}.")
                val playback = resolveRequestedPlayback(requestedItem)
                Log.i(TAG, "Resolved playback for book=${playback.playback.bookId} tracks=${playback.playback.queue.size}.")
                activeBook = playback.playback
                configureAuthenticatedPlayback(playback.accessToken)
                MediaSession.MediaItemsWithStartPosition(
                    playback.playback.toPlayableMediaItems(),
                    playback.playback.startIndex,
                    playback.playback.startPositionMs,
                )
            }
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            return when (customCommand.customAction) {
                AuthCommands.CMD_GET_AUTH_STATE -> serviceFuture(customCommand.customAction) {
                    val snapshot = authRepository.bootstrap()
                    updateAuthSnapshot(snapshot)
                    SessionResult(SessionResult.RESULT_SUCCESS, snapshot.toBundle())
                }

                AuthCommands.CMD_LOGIN -> serviceFuture(customCommand.customAction) {
                    val snapshot = authRepository.login(
                        requestedBaseUrl = args.getString(AuthCommands.EXTRA_SERVER_URL),
                        requestedUsername = args.getString(AuthCommands.EXTRA_USERNAME),
                        requestedPassword = args.getString(AuthCommands.EXTRA_PASSWORD),
                    )
                    updateAuthSnapshot(snapshot)
                    SessionResult(SessionResult.RESULT_SUCCESS, snapshot.toBundle())
                }

                AuthCommands.CMD_LOGOUT -> serviceFuture(customCommand.customAction) {
                    player.stop()
                    player.clearMediaItems()
                    activeBook = null
                    val snapshot = authRepository.logout()
                    cacheRepository.clearCache()
                    updateAuthSnapshot(snapshot)
                    updateSyncSnapshot(SyncSnapshot(status = SyncStatus.IDLE))
                    SessionResult(SessionResult.RESULT_SUCCESS, snapshot.toBundle())
                }

                CacheCommands.CMD_GET_CACHE_STATE -> serviceFuture(customCommand.customAction) {
                    SessionResult(SessionResult.RESULT_SUCCESS, cacheRepository.loadSnapshot().toBundle())
                }

                CacheCommands.CMD_CLEAR_CACHE -> serviceFuture(customCommand.customAction) {
                    player.stop()
                    player.clearMediaItems()
                    activeBook = null
                    val snapshot = cacheRepository.clearCache()
                    updateSyncSnapshot(SyncSnapshot(status = SyncStatus.IDLE))
                    SessionResult(SessionResult.RESULT_SUCCESS, snapshot.toBundle())
                }

                SyncCommands.CMD_GET_SYNC_STATE -> serviceFuture(customCommand.customAction) {
                    val snapshot = syncRepository.loadSnapshot()
                    updateSyncSnapshot(snapshot)
                    SessionResult(SessionResult.RESULT_SUCCESS, snapshot.toBundle())
                }

                SyncCommands.CMD_SYNC_NOW -> serviceFuture(customCommand.customAction) {
                    val snapshot = syncRepository.syncNow()
                    if (snapshot.status != SyncStatus.FAILED) {
                        progressSyncRepository.refreshInProgress()
                    }
                    updateSyncSnapshot(snapshot)
                    SessionResult(SessionResult.RESULT_SUCCESS, snapshot.toBundle())
                }

                else -> super.onCustomCommand(session, controller, customCommand, args)
            }
        }
    }

    private suspend fun loadChildren(parentId: String): List<MediaItem> {
        val node = BrowseNodeId.parse(parentId) ?: return emptyList()
        return when (node) {
            BrowseNodeId.Root -> listOf(buildRecentRootItem(), buildBooksRootItem(), buildAuthorsRootItem())
            BrowseNodeId.Recent -> loadRecentItems()
            BrowseNodeId.Books -> loadBooksItems()
            is BrowseNodeId.BooksBucket -> browseRepository.getBooksForBucket(node.bucket).map(::buildPlayableBookItem)
            BrowseNodeId.Authors -> loadAuthorsItems()
            is BrowseNodeId.AuthorsBucket -> browseRepository.getAuthorsForBucket(node.bucket).map(::buildAuthorItem)
            is BrowseNodeId.Author -> loadAuthorItems(node.authorId)
            is BrowseNodeId.AuthorBooksBucket -> browseRepository
                .getBooksForAuthorBucket(node.authorId, node.bucket)
                .map(::buildPlayableBookItem)
            is BrowseNodeId.Book -> emptyList()
        }
    }

    private suspend fun loadItem(mediaId: String): MediaItem? {
        val node = BrowseNodeId.parse(mediaId) ?: return null
        return when (node) {
            BrowseNodeId.Root -> buildRootItem()
            BrowseNodeId.Recent -> buildRecentRootItem()
            BrowseNodeId.Books -> buildBooksRootItem()
            BrowseNodeId.Authors -> buildAuthorsRootItem()
            is BrowseNodeId.Book -> browseRepository.getPlayableBook(node.bookId)?.let(::buildPlayableBookItem)
            is BrowseNodeId.Author -> browseRepository.getAuthor(node.authorId)?.let(::buildAuthorItem)
            is BrowseNodeId.BooksBucket,
            is BrowseNodeId.AuthorsBucket,
            is BrowseNodeId.AuthorBooksBucket,
            -> null
        }
    }

    private suspend fun loadRecentItems(): List<MediaItem> {
        if (!authSnapshot.isAuthenticated) {
            return listOf(buildAuthStateItem())
        }
        val recentBooks = browseRepository.getRecentBooks()
        if (recentBooks.isNotEmpty()) {
            return recentBooks.map(::buildPlayableBookItem)
        }
        if (authSnapshot.isAuthenticated && syncSnapshot.status == SyncStatus.RUNNING && syncSnapshot.bookCount == 0) {
            return listOf(
                buildStateItem(
                    mediaId = "recent:sync_running",
                    title = getString(R.string.media_sync_running_title),
                    subtitle = getString(R.string.media_sync_running_summary),
                ),
            )
        }
        if (authSnapshot.isAuthenticated && syncSnapshot.status == SyncStatus.FAILED && syncSnapshot.bookCount == 0) {
            return listOf(buildConnectionProblemItem("recent:sync_failed"))
        }
        return listOf(
            buildStateItem(
                mediaId = "recent:empty",
                title = getString(R.string.media_recent_empty_title),
                subtitle = getString(R.string.media_recent_empty_summary),
            ),
        )
    }

    private suspend fun loadBooksItems(): List<MediaItem> {
        if (!authSnapshot.isAuthenticated) {
            return listOf(buildAuthStateItem())
        }
        if (syncSnapshot.status == SyncStatus.FAILED && syncSnapshot.bookCount == 0) {
            return listOf(buildConnectionProblemItem("books:sync_failed"))
        }
        return when (val books = browseRepository.getBooksRoot()) {
            BrowseCollection.Empty -> listOf(
                buildStateItem(
                    mediaId = "books:empty",
                    title = getString(R.string.media_books_empty_title),
                    subtitle = getString(R.string.media_books_empty_summary),
                ),
            )

            is BrowseCollection.Direct -> books.items.map(::buildPlayableBookItem)
            is BrowseCollection.Grouped -> books.groups.map { group ->
                buildBrowsableItem(
                    mediaId = BrowseNodeId.BooksBucket(group.key).serialize(),
                    title = group.label,
                    subtitle = resources.getQuantityString(
                        R.plurals.media_books_group_summary,
                        group.count,
                        group.count,
                    ),
                    extras = childStyleExtras(
                        playableStyle = MediaConstants.DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM,
                    ),
                )
            }
        }
    }

    private suspend fun loadAuthorsItems(): List<MediaItem> {
        if (!authSnapshot.isAuthenticated) {
            return listOf(buildAuthStateItem())
        }
        if (syncSnapshot.status == SyncStatus.FAILED && syncSnapshot.authorCount == 0) {
            return listOf(buildConnectionProblemItem("authors:sync_failed"))
        }
        return when (val authors = browseRepository.getAuthorsRoot()) {
            BrowseCollection.Empty -> listOf(
                buildStateItem(
                    mediaId = "authors:empty",
                    title = getString(R.string.media_authors_empty_title),
                    subtitle = getString(R.string.media_authors_empty_summary),
                ),
            )

            is BrowseCollection.Direct -> authors.items.map(::buildAuthorItem)
            is BrowseCollection.Grouped -> authors.groups.map { group ->
                buildBrowsableItem(
                    mediaId = BrowseNodeId.AuthorsBucket(group.key).serialize(),
                    title = group.label,
                    subtitle = resources.getQuantityString(
                        R.plurals.media_authors_group_summary,
                        group.count,
                        group.count,
                    ),
                    extras = childStyleExtras(
                        browsableStyle = MediaConstants.DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM,
                    ),
                )
            }
        }
    }

    private suspend fun loadAuthorItems(authorId: String): List<MediaItem> {
        val author = browseRepository.getAuthor(authorId)
            ?: return listOf(
                buildStateItem(
                    mediaId = "author:missing:$authorId",
                    title = getString(R.string.media_author_missing_title),
                    subtitle = getString(R.string.media_author_missing_summary),
                ),
            )
        return when (val books = browseRepository.getBooksForAuthor(authorId)) {
            BrowseCollection.Empty -> listOf(
                buildStateItem(
                    mediaId = "author:$authorId:empty",
                    title = author.name,
                    subtitle = getString(R.string.media_author_books_empty_summary),
                ),
            )

            is BrowseCollection.Direct -> books.items.map(::buildPlayableBookItem)
            is BrowseCollection.Grouped -> books.groups.map { group ->
                buildBrowsableItem(
                    mediaId = BrowseNodeId.AuthorBooksBucket(authorId, group.key).serialize(),
                    title = group.label,
                    subtitle = resources.getQuantityString(
                        R.plurals.media_books_group_summary,
                        group.count,
                        group.count,
                    ),
                    extras = childStyleExtras(
                        playableStyle = MediaConstants.DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM,
                    ),
                )
            }
        }
    }

    private suspend fun loadSearchResults(query: String): List<MediaItem> {
        if (!authSnapshot.isAuthenticated) {
            return listOf(buildAuthStateItem())
        }

        val searchQuery = query.trim()
        val books = if (searchQuery.isBlank()) {
            browseRepository.getRecentBooks()
        } else {
            browseRepository.searchBooks(searchQuery)
        }
        val authors = if (searchQuery.isBlank()) {
            emptyList()
        } else {
            browseRepository.searchAuthors(searchQuery)
        }

        val results = authors.map(::buildAuthorItem) + books.map(::buildPlayableBookItem)
        if (results.isNotEmpty()) {
            return results
        }
        return listOf(
            buildStateItem(
                mediaId = "search:empty:${searchQuery.hashCode()}",
                title = getString(R.string.media_search_empty_title),
                subtitle = getString(R.string.media_search_empty_summary, searchQuery),
            ),
        )
    }

    private suspend fun resolveRequestedPlayback(requestedItem: MediaItem): ResolvedAudiobookPlaybackSession {
        val requestedBookId = (BrowseNodeId.parse(requestedItem.mediaId) as? BrowseNodeId.Book)?.bookId
        if (requestedBookId != null) {
            return playbackRepository.resolveBook(requestedBookId)
        }

        val searchQuery = requestedItem.requestMetadata.searchQuery.orEmpty()
        val book = browseRepository.findBestPlayableBookForVoice(listOf(searchQuery))
            ?: throw PlaybackResolutionException(
                if (searchQuery.isBlank()) {
                    "Kein zuletzt gehoertes Hoerbuch gefunden."
                } else {
                    "Kein Hoerbuch fuer '$searchQuery' gefunden."
                },
            )
        return playbackRepository.resolveBook(book.id)
    }

    private fun configureAuthenticatedPlayback(accessToken: String) {
        httpDataSourceFactory.setUserAgent("ShelfDrive/0.1.0")
        httpDataSourceFactory.setDefaultRequestProperties(mapOf("Authorization" to "Bearer $accessToken"))
    }

    private fun buildRootItem(): MediaItem {
        return buildBrowsableItem(
            mediaId = BrowseNodeId.Root.serialize(),
            title = getString(R.string.app_name),
            iconUri = drawableUri(R.drawable.ic_app_icon),
            extras = childStyleExtras(
                browsableStyle = MediaConstants.DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM,
                playableStyle = MediaConstants.DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM,
            ),
        )
    }

    private fun buildRecentRootItem(): MediaItem {
        return buildBrowsableItem(
            mediaId = BrowseNodeId.Recent.serialize(),
            title = getString(R.string.media_root_recent),
            iconUri = drawableUri(R.drawable.ic_menu_recent),
            extras = childStyleExtras(
                browsableStyle = MediaConstants.DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM,
                playableStyle = MediaConstants.DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM,
            ),
        )
    }

    private fun buildBooksRootItem(): MediaItem {
        return buildBrowsableItem(
            mediaId = BrowseNodeId.Books.serialize(),
            title = getString(R.string.media_root_books),
            iconUri = drawableUri(R.drawable.ic_menu_books),
            extras = childStyleExtras(
                browsableStyle = MediaConstants.DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_CATEGORY_LIST_ITEM,
                playableStyle = MediaConstants.DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM,
            ),
        )
    }

    private fun buildAuthorsRootItem(): MediaItem {
        return buildBrowsableItem(
            mediaId = BrowseNodeId.Authors.serialize(),
            title = getString(R.string.media_root_authors),
            iconUri = drawableUri(R.drawable.ic_menu_authors),
            extras = childStyleExtras(
                browsableStyle = MediaConstants.DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM,
                playableStyle = MediaConstants.DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM,
            ),
        )
    }

    private fun buildAuthStateItem(): MediaItem {
        val state = when (authSnapshot.status) {
            AuthStatus.SESSION_EXPIRED -> Triple(
                "auth:expired",
                getString(R.string.media_session_expired_title),
                getString(R.string.media_session_expired_summary),
            )

            AuthStatus.LOGIN_FAILED -> Triple(
                "auth:login_failed",
                getString(R.string.media_login_failed_title),
                getString(R.string.media_settings_hint),
            )

            else -> Triple(
                "auth:required",
                getString(R.string.media_auth_required_title),
                getString(R.string.media_auth_required_summary),
            )
        }
        return buildStateItem(state.first, state.second, state.third, drawableUri(R.drawable.ic_menu_lock))
    }

    private fun buildConnectionProblemItem(mediaId: String): MediaItem {
        return buildStateItem(
            mediaId = mediaId,
            title = getString(R.string.media_connection_problem_title),
            subtitle = getString(R.string.media_settings_hint),
            iconUri = drawableUri(R.drawable.ic_menu_connection_problem),
        )
    }

    private fun buildPlayableBookItem(book: BookEntity): MediaItem {
        val subtitle = book.authorDisplay ?: book.subtitle ?: book.description
        return MediaItem.Builder()
            .setMediaId(BrowseNodeId.Book(book.id).serialize())
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(book.title)
                    .setArtist(subtitle)
                    .setAlbumTitle(book.title)
                    .setArtworkUri(ArtworkUriFactory.bookCover(book.id, ArtworkUriFactory.signatureFor(book.coverPath)))
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .setDurationMs(book.durationMs)
                    .setExtras(childStyleExtras())
                    .build(),
            )
            .build()
    }

    private fun buildAuthorItem(author: AuthorEntity): MediaItem {
        return buildBrowsableItem(
            mediaId = BrowseNodeId.Author(author.id).serialize(),
            title = author.name,
            subtitle = resources.getQuantityString(
                R.plurals.media_author_book_count,
                author.numBooks,
                author.numBooks,
            ),
            iconUri = ArtworkUriFactory.authorImage(author.id, ArtworkUriFactory.signatureFor(author.imagePath)),
            extras = childStyleExtras(
                browsableStyle = MediaConstants.DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_CATEGORY_LIST_ITEM,
                playableStyle = MediaConstants.DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM,
            ),
        )
    }

    private fun buildStateItem(
        mediaId: String,
        title: String,
        subtitle: String,
        iconUri: Uri? = null,
    ): MediaItem {
        return buildBrowsableItem(mediaId, title, subtitle, iconUri)
    }

    private fun buildBrowsableItem(
        mediaId: String,
        title: String,
        subtitle: String? = null,
        iconUri: Uri? = null,
        extras: Bundle? = null,
    ): MediaItem {
        return MediaItem.Builder()
            .setMediaId(mediaId)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(subtitle)
                    .setArtworkUri(iconUri)
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setExtras(extras ?: childStyleExtras())
                    .build(),
            )
            .build()
    }

    private fun ResolvedAudiobookPlayback.toPlayableMediaItems(): List<MediaItem> {
        val browserMediaId = BrowseNodeId.Book(bookId).serialize()
        return queue.map { track ->
            MediaItem.Builder()
                .setMediaId("${browserMediaId}:${track.id}")
                .setUri(track.contentUrl)
                .setMimeType(track.mimeType)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(title)
                        .setArtist(author)
                        .setAlbumTitle(title)
                        .setAlbumArtist(author)
                        .setArtworkUri(artworkUri)
                        .setIsBrowsable(false)
                        .setIsPlayable(true)
                        .setDurationMs(durationMs ?: track.durationMs)
                        .build(),
                )
                .build()
        }
    }

    private fun startPeriodicProgressUpdates() {
        periodicProgressJob?.cancel()
        periodicProgressJob = serviceScope.launch {
            while (true) {
                delay(PROGRESS_UPDATE_INTERVAL_MS)
                emitProgress(PlaybackProgressReason.PERIODIC)
            }
        }
    }

    private fun emitProgress(reason: PlaybackProgressReason) {
        val playback = activeBook ?: return
        val snapshot = PlaybackProgressSnapshot(
            bookId = playback.bookId,
            currentTimeMs = logicalPlaybackPositionMs(),
            durationMs = playback.durationMs,
            isFinished = reason == PlaybackProgressReason.ENDED,
            reason = reason,
        )
        serviceScope.launch {
            val updated = runCatching {
                progressSyncRepository.pushProgress(snapshot)
            }.getOrElse { exception ->
                Log.w(TAG, "Progress sync failed for ${snapshot.bookId}", exception)
                false
            }
            if (updated && reason.shouldRefreshBrowse) {
                notifyBrowseTreeChanged()
            }
        }
    }

    private fun applyRewindOnPauseIfEnabled() {
        if (!PlaybackPreferences.isRewindOnPauseEnabled(this)) {
            return
        }
        val targetPositionMs = PlaybackResumePolicy.positionAfterPause(logicalPlaybackPositionMs())
        seekToLogicalPosition(targetPositionMs)
    }

    private fun seekToLogicalPosition(positionMs: Long) {
        val playback = activeBook
        if (playback == null) {
            player.seekTo(positionMs.coerceAtLeast(0L))
        } else {
            val startPosition = PlaybackQueueMath.locateStartPosition(playback.queue, positionMs)
            player.seekTo(startPosition.trackIndex, startPosition.positionMs)
        }
    }

    private fun logicalPlaybackPositionMs(): Long {
        val playback = activeBook ?: return player.currentPosition.coerceAtLeast(0L)
        val queueTrack = playback.queue.getOrNull(player.currentMediaItemIndex)
            ?: playback.queue.firstOrNull()
            ?: return player.currentPosition.coerceAtLeast(0L)
        return (queueTrack.startOffsetMs + player.currentPosition.coerceAtLeast(0L))
            .coerceAtMost(playback.durationMs ?: Long.MAX_VALUE)
    }

    private fun pageItems(items: List<MediaItem>, page: Int, pageSize: Int): List<MediaItem> {
        if (page < 0 || pageSize <= 0) {
            return items
        }
        val fromIndex = page * pageSize
        if (fromIndex >= items.size) {
            return emptyList()
        }
        return items.subList(fromIndex, (fromIndex + pageSize).coerceAtMost(items.size))
    }

    private fun updateAuthSnapshot(snapshot: AuthSnapshot) {
        authSnapshot = snapshot
        mediaLibrarySession.setSessionExtras(
            Bundle().apply {
                if (!snapshot.username.isNullOrBlank()) {
                    putString(MediaConstants.SESSION_EXTRAS_KEY_ACCOUNT_NAME, snapshot.username)
                    putString(MediaConstants.SESSION_EXTRAS_KEY_ACCOUNT_TYPE, AudiobookshelfAccountContract.ACCOUNT_TYPE)
                }
            },
        )
        notifyBrowseTreeChanged()
    }

    private fun updateSyncSnapshot(snapshot: SyncSnapshot) {
        syncSnapshot = snapshot
        notifyBrowseTreeChanged()
    }

    private fun notifyBrowseTreeChanged() {
        if (!this::mediaLibrarySession.isInitialized) {
            return
        }
        mediaLibrarySession.notifyChildrenChanged(BrowseNodeId.Root.serialize(), Int.MAX_VALUE, null)
        mediaLibrarySession.notifyChildrenChanged(BrowseNodeId.Recent.serialize(), Int.MAX_VALUE, null)
        mediaLibrarySession.notifyChildrenChanged(BrowseNodeId.Books.serialize(), Int.MAX_VALUE, null)
        mediaLibrarySession.notifyChildrenChanged(BrowseNodeId.Authors.serialize(), Int.MAX_VALUE, null)
    }

    private fun rootParams(params: LibraryParams?): LibraryParams {
        val extras = Bundle(params?.extras ?: Bundle.EMPTY).apply {
            putBoolean(CONTENT_STYLE_SUPPORTED, true)
            putBoolean(MediaConstants.BROWSER_SERVICE_EXTRAS_KEY_SEARCH_SUPPORTED, true)
            putInt(
                MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_BROWSABLE,
                MediaConstants.DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM,
            )
            putInt(
                MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_PLAYABLE,
                MediaConstants.DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM,
            )
        }
        return LibraryParams.Builder()
            .setExtras(extras)
            .build()
    }

    private fun childStyleExtras(
        browsableStyle: Int? = null,
        playableStyle: Int? = null,
    ): Bundle {
        return Bundle().apply {
            browsableStyle?.let {
                putInt(MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, it)
            }
            playableStyle?.let {
                putInt(MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, it)
            }
        }
    }

    private fun drawableUri(drawableResId: Int): Uri {
        return Uri.Builder()
            .scheme("android.resource")
            .authority(packageName)
            .appendPath(resources.getResourceTypeName(drawableResId))
            .appendPath(resources.getResourceEntryName(drawableResId))
            .build()
    }

    private fun isAllowedController(controller: MediaSession.ControllerInfo): Boolean {
        val uid = controller.uid
        val packageName = controller.packageName
        if (uid == Process.SYSTEM_UID) {
            return true
        }
        if (uid == applicationInfo.uid && packageName == this.packageName) {
            return true
        }
        val packagesForUid = packageManager.getPackagesForUid(uid)?.toSet().orEmpty()
        if (packagesForUid.isEmpty()) {
            return true
        }
        if (packageName !in packagesForUid) {
            return false
        }
        return packagesForUid.any { candidatePackage ->
            val info = runCatching {
                packageManager.getApplicationInfo(candidatePackage, 0)
            }.getOrNull() ?: return@any false
            info.flags and ApplicationInfo.FLAG_SYSTEM != 0 ||
                info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0 ||
                candidatePackage == this.packageName
        }
    }

    private fun availableSessionCommands(): SessionCommands {
        return MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS
            .buildUpon()
            .add(SessionCommand(AuthCommands.CMD_GET_AUTH_STATE, Bundle.EMPTY))
            .add(SessionCommand(AuthCommands.CMD_LOGIN, Bundle.EMPTY))
            .add(SessionCommand(AuthCommands.CMD_LOGOUT, Bundle.EMPTY))
            .add(SessionCommand(CacheCommands.CMD_GET_CACHE_STATE, Bundle.EMPTY))
            .add(SessionCommand(CacheCommands.CMD_CLEAR_CACHE, Bundle.EMPTY))
            .add(SessionCommand(SyncCommands.CMD_GET_SYNC_STATE, Bundle.EMPTY))
            .add(SessionCommand(SyncCommands.CMD_SYNC_NOW, Bundle.EMPTY))
            .build()
    }

    private fun availablePlayerCommands(): Player.Commands {
        return MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS
            .buildUpon()
            .remove(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
            .remove(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
            .remove(Player.COMMAND_SEEK_TO_NEXT)
            .remove(Player.COMMAND_SEEK_TO_PREVIOUS)
            .build()
    }

    private fun mediaButtonPreferences(): List<CommandButton> {
        return listOf(
            CommandButton.Builder(CommandButton.ICON_SKIP_BACK_15)
                .setPlayerCommand(Player.COMMAND_SEEK_BACK)
                .setDisplayName(getString(R.string.media_action_rewind_15))
                .setSlots(CommandButton.SLOT_BACK)
                .build(),
            CommandButton.Builder(CommandButton.ICON_SKIP_FORWARD_15)
                .setPlayerCommand(Player.COMMAND_SEEK_FORWARD)
                .setDisplayName(getString(R.string.media_action_forward_15))
                .setSlots(CommandButton.SLOT_FORWARD)
                .build(),
            CommandButton.Builder(CommandButton.ICON_PLAYBACK_SPEED)
                .setPlayerCommand(Player.COMMAND_SET_SPEED_AND_PITCH)
                .setDisplayName(getString(R.string.media_action_playback_speed))
                .setSlots(CommandButton.SLOT_OVERFLOW)
                .build(),
        )
    }

    private fun <T> serviceFuture(
        label: String,
        block: suspend () -> T,
    ): ListenableFuture<T> {
        return CallbackToFutureAdapter.getFuture { completer ->
            serviceScope.launch {
                try {
                    completer.set(block())
                } catch (exception: IOException) {
                    Log.w(TAG, "Media3 callback failed: $label", exception)
                    completer.setException(exception)
                } catch (throwable: Throwable) {
                    Log.e(TAG, "Media3 callback crashed: $label", throwable)
                    completer.setException(throwable)
                }
            }
            label
        }
    }

    private fun IOException.toPlaybackMessage(): String {
        return when (this) {
            is AuthenticationRequiredException -> UserVisibleStatus.SESSION_EXPIRED
            is ApiException -> when (statusCode) {
                401 -> UserVisibleStatus.SESSION_EXPIRED
                else -> UserVisibleStatus.PLAYBACK_START_FAILED
            }
            else -> message?.takeIf { it.isNotBlank() }
                ?: UserVisibleStatus.PLAYBACK_START_FAILED
        }
    }

    private val PlaybackProgressReason.shouldRefreshBrowse: Boolean
        get() = this == PlaybackProgressReason.PAUSED ||
            this == PlaybackProgressReason.STOPPED ||
            this == PlaybackProgressReason.ENDED

    companion object {
        private const val TAG = "ShelfDriveMedia3"
        private const val CONTENT_STYLE_SUPPORTED = "android.media.browse.CONTENT_STYLE_SUPPORTED"
        private const val SEEK_INCREMENT_MS = 15_000L
        private const val PROGRESS_UPDATE_INTERVAL_MS = 15_000L
    }
}

private class PlaybackResolutionException(
    override val message: String,
    override val cause: Throwable? = null,
) : IOException(message, cause)
