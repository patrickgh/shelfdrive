package io.audiobookshelf.aaos.media3

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.session.MediaConstants
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaLibraryService.LibraryParams
import io.audiobookshelf.aaos.R
import io.audiobookshelf.aaos.artwork.ArtworkUriFactory
import io.audiobookshelf.aaos.auth.AuthSnapshot
import io.audiobookshelf.aaos.auth.AuthStatus
import io.audiobookshelf.aaos.browser.BrowseNodeId
import io.audiobookshelf.aaos.browser.CatalogBrowseRepository
import io.audiobookshelf.aaos.browser.CatalogBrowseRepository.BrowseCollection
import io.audiobookshelf.aaos.catalog.persistence.AuthorEntity
import io.audiobookshelf.aaos.catalog.persistence.BookEntity
import io.audiobookshelf.aaos.sync.SyncSnapshot
import io.audiobookshelf.aaos.sync.SyncStatus

@OptIn(UnstableApi::class)
internal class ShelfDriveMediaCatalog(
    private val context: Context,
    private val browseRepository: CatalogBrowseRepository,
) {
    var authSnapshot: AuthSnapshot = AuthSnapshot(status = AuthStatus.LOGGED_OUT)
    var syncSnapshot: SyncSnapshot = SyncSnapshot(status = SyncStatus.IDLE)

    fun buildRootItem(): MediaItem {
        return buildBrowsableItem(
            mediaId = BrowseNodeId.Root.serialize(),
            title = context.getString(R.string.app_name),
            iconUri = drawableUri(R.drawable.ic_app_icon),
            extras = childStyleExtras(
                browsableStyle = MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM,
                playableStyle = MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM,
            ),
        )
    }

    suspend fun loadChildren(parentId: String): List<MediaItem> {
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

    suspend fun loadItem(mediaId: String): MediaItem? {
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

    suspend fun loadSearchResults(query: String): List<MediaItem> {
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
                title = context.getString(R.string.media_search_empty_title),
                subtitle = context.getString(R.string.media_search_empty_summary, searchQuery),
            ),
        )
    }

    fun rootParams(params: LibraryParams?): LibraryParams {
        val extras = Bundle(params?.extras ?: Bundle.EMPTY).apply {
            putInt(
                MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE,
                MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM,
            )
            putInt(
                MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE,
                MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM,
            )
        }
        return LibraryParams.Builder()
            .setExtras(extras)
            .build()
    }

    fun pageItems(items: List<MediaItem>, page: Int, pageSize: Int): List<MediaItem> {
        if (page < 0 || pageSize <= 0) {
            return items
        }
        val fromIndex = page * pageSize
        if (fromIndex >= items.size) {
            return emptyList()
        }
        return items.subList(fromIndex, (fromIndex + pageSize).coerceAtMost(items.size))
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
                    title = context.getString(R.string.media_sync_running_title),
                    subtitle = context.getString(R.string.media_sync_running_summary),
                ),
            )
        }
        if (authSnapshot.isAuthenticated && syncSnapshot.status == SyncStatus.FAILED && syncSnapshot.bookCount == 0) {
            return listOf(buildConnectionProblemItem("recent:sync_failed"))
        }
        return listOf(
            buildStateItem(
                mediaId = "recent:empty",
                title = context.getString(R.string.media_recent_empty_title),
                subtitle = context.getString(R.string.media_recent_empty_summary),
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
                    title = context.getString(R.string.media_books_empty_title),
                    subtitle = context.getString(R.string.media_books_empty_summary),
                ),
            )

            is BrowseCollection.Direct -> books.items.map(::buildPlayableBookItem)
            is BrowseCollection.Grouped -> books.groups.map { group ->
                buildBrowsableItem(
                    mediaId = BrowseNodeId.BooksBucket(group.key).serialize(),
                    title = group.label,
                    subtitle = context.resources.getQuantityString(
                        R.plurals.media_books_group_summary,
                        group.count,
                        group.count,
                    ),
                    extras = childStyleExtras(
                        playableStyle = MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM,
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
                    title = context.getString(R.string.media_authors_empty_title),
                    subtitle = context.getString(R.string.media_authors_empty_summary),
                ),
            )

            is BrowseCollection.Direct -> authors.items.map(::buildAuthorItem)
            is BrowseCollection.Grouped -> authors.groups.map { group ->
                buildBrowsableItem(
                    mediaId = BrowseNodeId.AuthorsBucket(group.key).serialize(),
                    title = group.label,
                    subtitle = context.resources.getQuantityString(
                        R.plurals.media_authors_group_summary,
                        group.count,
                        group.count,
                    ),
                    extras = childStyleExtras(
                        browsableStyle = MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM,
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
                    title = context.getString(R.string.media_author_missing_title),
                    subtitle = context.getString(R.string.media_author_missing_summary),
                ),
            )
        return when (val books = browseRepository.getBooksForAuthor(authorId)) {
            BrowseCollection.Empty -> listOf(
                buildStateItem(
                    mediaId = "author:$authorId:empty",
                    title = author.name,
                    subtitle = context.getString(R.string.media_author_books_empty_summary),
                ),
            )

            is BrowseCollection.Direct -> books.items.map(::buildPlayableBookItem)
            is BrowseCollection.Grouped -> books.groups.map { group ->
                buildBrowsableItem(
                    mediaId = BrowseNodeId.AuthorBooksBucket(authorId, group.key).serialize(),
                    title = group.label,
                    subtitle = context.resources.getQuantityString(
                        R.plurals.media_books_group_summary,
                        group.count,
                        group.count,
                    ),
                    extras = childStyleExtras(
                        playableStyle = MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM,
                    ),
                )
            }
        }
    }

    private fun buildRecentRootItem(): MediaItem {
        return buildBrowsableItem(
            mediaId = BrowseNodeId.Recent.serialize(),
            title = context.getString(R.string.media_root_recent),
            iconUri = drawableUri(R.drawable.ic_menu_recent),
            extras = childStyleExtras(
                browsableStyle = MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM,
                playableStyle = MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM,
            ),
        )
    }

    private fun buildBooksRootItem(): MediaItem {
        return buildBrowsableItem(
            mediaId = BrowseNodeId.Books.serialize(),
            title = context.getString(R.string.media_root_books),
            iconUri = drawableUri(R.drawable.ic_menu_books),
            extras = childStyleExtras(
                browsableStyle = MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_CATEGORY_LIST_ITEM,
                playableStyle = MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM,
            ),
        )
    }

    private fun buildAuthorsRootItem(): MediaItem {
        return buildBrowsableItem(
            mediaId = BrowseNodeId.Authors.serialize(),
            title = context.getString(R.string.media_root_authors),
            iconUri = drawableUri(R.drawable.ic_menu_authors),
            extras = childStyleExtras(
                browsableStyle = MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM,
                playableStyle = MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM,
            ),
        )
    }

    private fun buildAuthStateItem(): MediaItem {
        val state = when (authSnapshot.status) {
            AuthStatus.SESSION_EXPIRED -> Triple(
                "auth:expired",
                context.getString(R.string.media_session_expired_title),
                context.getString(R.string.media_session_expired_summary),
            )

            AuthStatus.LOGIN_FAILED -> Triple(
                "auth:login_failed",
                context.getString(R.string.media_login_failed_title),
                context.getString(R.string.media_settings_hint),
            )

            else -> Triple(
                "auth:required",
                context.getString(R.string.media_auth_required_title),
                context.getString(R.string.media_auth_required_summary),
            )
        }
        return buildStateItem(state.first, state.second, state.third, drawableUri(R.drawable.ic_menu_lock))
    }

    private fun buildConnectionProblemItem(mediaId: String): MediaItem {
        return buildStateItem(
            mediaId = mediaId,
            title = context.getString(R.string.media_connection_problem_title),
            subtitle = context.getString(R.string.media_settings_hint),
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
            subtitle = context.resources.getQuantityString(
                R.plurals.media_author_book_count,
                author.numBooks,
                author.numBooks,
            ),
            iconUri = ArtworkUriFactory.authorImage(author.id, ArtworkUriFactory.signatureFor(author.imagePath)),
            extras = childStyleExtras(
                browsableStyle = MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_CATEGORY_LIST_ITEM,
                playableStyle = MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM,
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

    private fun childStyleExtras(
        browsableStyle: Int? = null,
        playableStyle: Int? = null,
    ): Bundle {
        return Bundle().apply {
            browsableStyle?.let {
                putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, it)
            }
            playableStyle?.let {
                putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, it)
            }
        }
    }

    private fun drawableUri(drawableResId: Int): Uri {
        return Uri.Builder()
            .scheme("android.resource")
            .authority(context.packageName)
            .appendPath(context.resources.getResourceTypeName(drawableResId))
            .appendPath(context.resources.getResourceEntryName(drawableResId))
            .build()
    }

}
