package io.audiobookshelf.aaos.browser

import android.content.pm.ApplicationInfo
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Process
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.util.Log
import androidx.media.MediaBrowserServiceCompat
import androidx.media.utils.MediaConstants
import io.audiobookshelf.aaos.R
import io.audiobookshelf.aaos.account.AudiobookshelfAccountRegistry
import io.audiobookshelf.aaos.artwork.ArtworkUriFactory
import io.audiobookshelf.aaos.auth.AuthRepository
import io.audiobookshelf.aaos.auth.AuthSnapshot
import io.audiobookshelf.aaos.auth.AuthStatus
import io.audiobookshelf.aaos.auth.AuthStorage
import io.audiobookshelf.aaos.browser.CatalogBrowseRepository.BrowseCollection
import io.audiobookshelf.aaos.cache.CacheRepository
import io.audiobookshelf.aaos.catalog.persistence.AuthorEntity
import io.audiobookshelf.aaos.catalog.persistence.BookEntity
import io.audiobookshelf.aaos.catalog.persistence.CatalogDatabase
import io.audiobookshelf.aaos.playback.AudiobookshelfPlaybackManager
import io.audiobookshelf.aaos.playback.AudiobookshelfPlaybackNotificationController
import io.audiobookshelf.aaos.playback.AudiobookshelfPlaybackRepository
import io.audiobookshelf.aaos.progress.ProgressSyncRepository
import io.audiobookshelf.aaos.session.AudiobookshelfSession
import io.audiobookshelf.aaos.session.AudiobookshelfSessionCallback
import io.audiobookshelf.aaos.sync.CatalogSyncRepository
import io.audiobookshelf.aaos.sync.SyncSnapshot
import io.audiobookshelf.aaos.sync.SyncStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class AudiobookshelfBrowserService : MediaBrowserServiceCompat() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var authStorage: AuthStorage
    private lateinit var authRepository: AuthRepository
    private lateinit var syncRepository: CatalogSyncRepository
    private lateinit var browseRepository: CatalogBrowseRepository
    private lateinit var audiobookSession: AudiobookshelfSession
    private lateinit var playbackRepository: AudiobookshelfPlaybackRepository
    private lateinit var playbackManager: AudiobookshelfPlaybackManager
    private lateinit var playbackNotificationController: AudiobookshelfPlaybackNotificationController
    private lateinit var progressSyncRepository: ProgressSyncRepository
    private lateinit var cacheRepository: CacheRepository
    private var authSnapshot: AuthSnapshot = AuthSnapshot(status = AuthStatus.LOGGED_OUT)
    private var syncSnapshot: SyncSnapshot = SyncSnapshot(status = SyncStatus.IDLE)

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
        audiobookSession = AudiobookshelfSession(this)
        playbackNotificationController = AudiobookshelfPlaybackNotificationController(this, audiobookSession)
        audiobookSession.setStateObserver(playbackNotificationController::onSessionStateChanged)
        playbackManager = AudiobookshelfPlaybackManager(this, audiobookSession, serviceScope)
        val sessionCallback = AudiobookshelfSessionCallback(
            scope = serviceScope,
            session = audiobookSession,
            authRepository = authRepository,
            browseRepository = browseRepository,
            syncRepository = syncRepository,
            playbackRepository = playbackRepository,
            playbackManager = playbackManager,
            progressSyncRepository = progressSyncRepository,
            cacheRepository = cacheRepository,
            onAuthSnapshotChanged = ::updateAuthSnapshot,
            onSyncSnapshotChanged = ::updateSyncSnapshot,
            onRecentProgressChanged = ::notifyRecentChanged,
        )
        audiobookSession.setCallback(sessionCallback)
        setSessionToken(audiobookSession.sessionToken)
        serviceScope.launch {
            val initialAuth = authRepository.bootstrap()
            updateAuthSnapshot(initialAuth)
            updateSyncSnapshot(syncRepository.loadSnapshot())
            if (initialAuth.isAuthenticated) {
                val syncSnapshot = syncRepository.syncIfStale()
                updateSyncSnapshot(syncSnapshot)
                if (syncSnapshot.status != SyncStatus.FAILED) {
                    progressSyncRepository.refreshInProgress()
                }
                notifyRecentChanged()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        audiobookSession.handleMediaButtonIntent(intent)
        return START_STICKY
    }

    override fun onGetRoot(
        clientPackageName: String,
        clientUid: Int,
        rootHints: Bundle?,
    ): BrowserRoot? {
        if (!isAllowedBrowserClient(clientPackageName, clientUid)) {
            Log.w(TAG, "Rejected MediaBrowser client $clientPackageName/$clientUid.")
            return null
        }
        audiobookSession.republishCurrentState()
        return BrowserRoot(BrowseNodeId.Root.serialize(), rootExtras())
    }

    override fun onLoadChildren(
        parentId: String,
        result: Result<MutableList<MediaBrowserCompat.MediaItem>>,
    ) {
        result.detach()
        serviceScope.launch {
            result.sendResult(loadChildren(parentId))
        }
    }

    override fun onLoadItem(
        itemId: String,
        result: Result<MediaBrowserCompat.MediaItem>,
    ) {
        result.detach()
        serviceScope.launch {
            result.sendResult(loadItem(itemId))
        }
    }

    override fun onSearch(
        query: String,
        extras: Bundle?,
        result: Result<MutableList<MediaBrowserCompat.MediaItem>>,
    ) {
        result.detach()
        serviceScope.launch {
            result.sendResult(loadSearchResults(query))
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        if (::playbackNotificationController.isInitialized) {
            playbackNotificationController.release()
        }
        playbackManager.release()
        audiobookSession.release()
        super.onDestroy()
    }

    private suspend fun loadChildren(parentId: String): MutableList<MediaBrowserCompat.MediaItem> {
        val node = BrowseNodeId.parse(parentId) ?: return mutableListOf()
        return when (node) {
            BrowseNodeId.Root -> loadRootItems()
            BrowseNodeId.Recent -> loadRecentItems()
            BrowseNodeId.Books -> loadBooksItems()
            is BrowseNodeId.BooksBucket -> loadBookBucketItems(node.bucket)
            BrowseNodeId.Authors -> loadAuthorsItems()
            is BrowseNodeId.AuthorsBucket -> loadAuthorBucketItems(node.bucket)
            is BrowseNodeId.Author -> loadAuthorItems(node.authorId)
            is BrowseNodeId.AuthorBooksBucket -> loadAuthorBookBucketItems(node.authorId, node.bucket)
            is BrowseNodeId.Book -> mutableListOf()
        }
    }

    private suspend fun loadItem(itemId: String): MediaBrowserCompat.MediaItem? {
        val node = BrowseNodeId.parse(itemId) ?: return null
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

    private suspend fun loadRootItems(): MutableList<MediaBrowserCompat.MediaItem> {
        val rootItems = mutableListOf<MediaBrowserCompat.MediaItem>()
        rootItems += buildRecentRootItem()
        rootItems += buildBooksRootItem()
        rootItems += buildAuthorsRootItem()
        return rootItems
    }

    private fun buildRootItem(): MediaBrowserCompat.MediaItem {
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

    private fun buildRecentRootItem(): MediaBrowserCompat.MediaItem {
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

    private fun buildBooksRootItem(): MediaBrowserCompat.MediaItem {
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

    private fun buildAuthorsRootItem(): MediaBrowserCompat.MediaItem {
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

    private suspend fun loadRecentItems(): MutableList<MediaBrowserCompat.MediaItem> {
        if (!authSnapshot.isAuthenticated) {
            return mutableListOf(buildAuthStateItem())
        }

        val recentBooks = browseRepository.getRecentBooks()
        if (recentBooks.isNotEmpty()) {
            return recentBooks.mapTo(mutableListOf(), ::buildPlayableBookItem)
        }

        buildRecentEmptyStateItem()?.let { stateItem ->
            return mutableListOf(stateItem)
        }

        return mutableListOf(
            buildStateItem(
                mediaId = "recent:empty",
                title = getString(R.string.media_recent_empty_title),
                subtitle = getString(R.string.media_recent_empty_summary),
            ),
        )
    }

    private fun buildRecentEmptyStateItem(): MediaBrowserCompat.MediaItem? {
        if (
            authSnapshot.isAuthenticated &&
            syncSnapshot.status == SyncStatus.RUNNING &&
            syncSnapshot.bookCount == 0
        ) {
            return buildStateItem(
                mediaId = "recent:sync_running",
                title = getString(R.string.media_sync_running_title),
                subtitle = getString(R.string.media_sync_running_summary),
            )
        }

        if (
            authSnapshot.isAuthenticated &&
            syncSnapshot.status == SyncStatus.FAILED &&
            syncSnapshot.bookCount == 0
        ) {
            return buildConnectionProblemItem("recent:sync_failed")
        }

        return null
    }

    private fun buildConnectionProblemItem(mediaId: String): MediaBrowserCompat.MediaItem {
        return buildStateItem(
            mediaId = mediaId,
            title = getString(R.string.media_connection_problem_title),
            subtitle = getString(R.string.media_settings_hint),
            iconUri = drawableUri(R.drawable.ic_menu_connection_problem),
        )
    }

    private suspend fun loadBooksItems(): MutableList<MediaBrowserCompat.MediaItem> {
        if (!authSnapshot.isAuthenticated) {
            return mutableListOf(buildAuthStateItem())
        }
        if (syncSnapshot.status == SyncStatus.FAILED && syncSnapshot.bookCount == 0) {
            return mutableListOf(buildConnectionProblemItem("books:sync_failed"))
        }
        return when (val books = browseRepository.getBooksRoot()) {
            BrowseCollection.Empty -> mutableListOf(
                buildStateItem(
                    mediaId = "books:empty",
                    title = getString(R.string.media_books_empty_title),
                    subtitle = getString(R.string.media_books_empty_summary),
                ),
            )

            is BrowseCollection.Direct -> books.items.mapTo(mutableListOf(), ::buildPlayableBookItem)
            is BrowseCollection.Grouped -> books.groups.mapTo(mutableListOf()) { group ->
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

    private suspend fun loadBookBucketItems(bucket: String): MutableList<MediaBrowserCompat.MediaItem> {
        return browseRepository.getBooksForBucket(bucket).mapTo(mutableListOf(), ::buildPlayableBookItem)
    }

    private suspend fun loadAuthorsItems(): MutableList<MediaBrowserCompat.MediaItem> {
        if (!authSnapshot.isAuthenticated) {
            return mutableListOf(buildAuthStateItem())
        }
        if (syncSnapshot.status == SyncStatus.FAILED && syncSnapshot.authorCount == 0) {
            return mutableListOf(buildConnectionProblemItem("authors:sync_failed"))
        }
        return when (val authors = browseRepository.getAuthorsRoot()) {
            BrowseCollection.Empty -> mutableListOf(
                buildStateItem(
                    mediaId = "authors:empty",
                    title = getString(R.string.media_authors_empty_title),
                    subtitle = getString(R.string.media_authors_empty_summary),
                ),
            )

            is BrowseCollection.Direct -> authors.items.mapTo(mutableListOf(), ::buildAuthorItem)
            is BrowseCollection.Grouped -> authors.groups.mapTo(mutableListOf()) { group ->
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

    private suspend fun loadAuthorBucketItems(bucket: String): MutableList<MediaBrowserCompat.MediaItem> {
        return browseRepository.getAuthorsForBucket(bucket).mapTo(mutableListOf(), ::buildAuthorItem)
    }

    private suspend fun loadAuthorItems(authorId: String): MutableList<MediaBrowserCompat.MediaItem> {
        val author = browseRepository.getAuthor(authorId)
            ?: return mutableListOf(
                buildStateItem(
                    mediaId = "author:missing:$authorId",
                    title = getString(R.string.media_author_missing_title),
                    subtitle = getString(R.string.media_author_missing_summary),
                ),
            )
        return when (val books = browseRepository.getBooksForAuthor(authorId)) {
            BrowseCollection.Empty -> mutableListOf(
                buildStateItem(
                    mediaId = "author:$authorId:empty",
                    title = author.name,
                    subtitle = getString(R.string.media_author_books_empty_summary),
                ),
            )

            is BrowseCollection.Direct -> books.items.mapTo(mutableListOf(), ::buildPlayableBookItem)
            is BrowseCollection.Grouped -> books.groups.mapTo(mutableListOf()) { group ->
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

    private suspend fun loadAuthorBookBucketItems(
        authorId: String,
        bucket: String,
    ): MutableList<MediaBrowserCompat.MediaItem> {
        return browseRepository.getBooksForAuthorBucket(authorId, bucket)
            .mapTo(mutableListOf(), ::buildPlayableBookItem)
    }

    private suspend fun loadSearchResults(query: String): MutableList<MediaBrowserCompat.MediaItem> {
        if (!authSnapshot.isAuthenticated) {
            return mutableListOf(buildAuthStateItem())
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

        val results = mutableListOf<MediaBrowserCompat.MediaItem>()
        authors.mapTo(results, ::buildAuthorItem)
        books.mapTo(results, ::buildPlayableBookItem)

        if (results.isEmpty()) {
            results += buildStateItem(
                mediaId = "search:empty:${searchQuery.hashCode()}",
                title = getString(R.string.media_search_empty_title),
                subtitle = getString(R.string.media_search_empty_summary, searchQuery),
            )
        }
        return results
    }

    private fun buildAuthStateItem(): MediaBrowserCompat.MediaItem {
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

    private fun buildPlayableBookItem(book: BookEntity): MediaBrowserCompat.MediaItem {
        val subtitle = book.authorDisplay
            ?: book.subtitle
            ?: book.description
        val description = MediaDescriptionCompat.Builder()
            .setMediaId(BrowseNodeId.Book(book.id).serialize())
            .setTitle(book.title)
            .setSubtitle(subtitle)
            .setIconUri(ArtworkUriFactory.bookCover(book.id, ArtworkUriFactory.signatureFor(book.coverPath)))
            .build()
        return MediaBrowserCompat.MediaItem(description, MediaBrowserCompat.MediaItem.FLAG_PLAYABLE)
    }

    private fun buildAuthorItem(author: AuthorEntity): MediaBrowserCompat.MediaItem {
        val description = MediaDescriptionCompat.Builder()
            .setMediaId(BrowseNodeId.Author(author.id).serialize())
            .setTitle(author.name)
            .setSubtitle(
                resources.getQuantityString(
                    R.plurals.media_author_book_count,
                    author.numBooks,
                    author.numBooks,
                ),
            )
            .setIconUri(ArtworkUriFactory.authorImage(author.id, ArtworkUriFactory.signatureFor(author.imagePath)))
            .setExtras(
                childStyleExtras(
                    browsableStyle = MediaConstants.DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_CATEGORY_LIST_ITEM,
                    playableStyle = MediaConstants.DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM,
                ),
            )
            .build()
        return MediaBrowserCompat.MediaItem(
            description,
            MediaBrowserCompat.MediaItem.FLAG_BROWSABLE,
        )
    }

    private fun buildStateItem(
        mediaId: String,
        title: String,
        subtitle: String,
        iconUri: Uri? = null,
    ): MediaBrowserCompat.MediaItem {
        return buildBrowsableItem(mediaId, title, subtitle, iconUri)
    }

    private fun buildBrowsableItem(
        mediaId: String,
        title: String,
        subtitle: String? = null,
        iconUri: Uri? = null,
        extras: Bundle? = null,
    ): MediaBrowserCompat.MediaItem {
        val description = MediaDescriptionCompat.Builder()
            .setMediaId(mediaId)
            .setTitle(title)
            .setSubtitle(subtitle)
            .setIconUri(iconUri)
            .setExtras(extras)
            .build()
        return MediaBrowserCompat.MediaItem(description, MediaBrowserCompat.MediaItem.FLAG_BROWSABLE)
    }

    private fun drawableUri(drawableResId: Int): Uri {
        return Uri.Builder()
            .scheme("android.resource")
            .authority(packageName)
            .appendPath(resources.getResourceTypeName(drawableResId))
            .appendPath(resources.getResourceEntryName(drawableResId))
            .build()
    }

    private fun updateAuthSnapshot(snapshot: AuthSnapshot) {
        authSnapshot = snapshot
        if (snapshot.isAuthenticated) {
            audiobookSession.setAccount(snapshot.username)
            audiobookSession.clearAuthenticationRequired()
        } else {
            audiobookSession.setAccount(null)
            audiobookSession.publishAuthenticationRequired(snapshot.mediaErrorMessage())
        }
        notifyBrowseTreeChanged()
    }

    private fun AuthSnapshot.mediaErrorMessage(): String {
        return when (status) {
            AuthStatus.SESSION_EXPIRED -> getString(R.string.media_session_expired_summary)
            AuthStatus.LOGIN_FAILED -> statusMessage
                ?: getString(R.string.media_login_failed_summary)
            AuthStatus.LOGGED_OUT -> getString(R.string.media_auth_required_summary)
            AuthStatus.AUTHENTICATED -> statusMessage.orEmpty()
        }
    }

    private fun updateSyncSnapshot(snapshot: SyncSnapshot) {
        syncSnapshot = snapshot
        notifyBrowseTreeChanged()
    }

    private fun notifyBrowseTreeChanged() {
        notifyChildrenChanged(BrowseNodeId.Root.serialize())
        notifyRecentChanged()
        notifyChildrenChanged(BrowseNodeId.Books.serialize())
        notifyChildrenChanged(BrowseNodeId.Authors.serialize())
    }

    private fun notifyRecentChanged() {
        notifyChildrenChanged(BrowseNodeId.Recent.serialize())
    }

    private fun rootExtras(): Bundle {
        return childStyleExtras(
            browsableStyle = MediaConstants.DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM,
            playableStyle = MediaConstants.DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM,
            includeSupportFlag = true,
        )
    }

    private fun childStyleExtras(
        browsableStyle: Int? = null,
        playableStyle: Int? = null,
        includeSupportFlag: Boolean = false,
    ): Bundle {
        return Bundle().apply {
            if (includeSupportFlag) {
                putBoolean(CONTENT_STYLE_SUPPORTED, true)
                putBoolean(MediaConstants.BROWSER_SERVICE_EXTRAS_KEY_SEARCH_SUPPORTED, true)
            }
            browsableStyle?.let {
                putInt(MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, it)
            }
            playableStyle?.let {
                putInt(MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, it)
            }
        }
    }

    private fun isAllowedBrowserClient(clientPackageName: String, clientUid: Int): Boolean {
        val packagesForUid = packageManager.getPackagesForUid(clientUid)?.toSet().orEmpty()
        if (clientPackageName !in packagesForUid) {
            return false
        }

        if (clientUid == applicationInfo.uid && clientPackageName == packageName) {
            return true
        }

        if (clientUid == Process.SYSTEM_UID) {
            return true
        }

        return packagesForUid.any { packageName ->
            val info = runCatching {
                packageManager.getApplicationInfo(packageName, 0)
            }.getOrNull() ?: return@any false
            info.flags and ApplicationInfo.FLAG_SYSTEM != 0 ||
                info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0
        }
    }

    companion object {
        private const val CONTENT_STYLE_SUPPORTED = "android.media.browse.CONTENT_STYLE_SUPPORTED"
        private const val TAG = "AbsBrowserService"
    }
}
