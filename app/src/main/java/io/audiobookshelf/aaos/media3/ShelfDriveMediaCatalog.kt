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
import io.audiobookshelf.aaos.browser.BrowseNodeId
import io.audiobookshelf.aaos.browser.CatalogBrowseRepository
import io.audiobookshelf.aaos.browser.CatalogBrowseRepository.BrowseCollection
import io.audiobookshelf.aaos.catalog.persistence.AuthorEntity
import io.audiobookshelf.aaos.catalog.persistence.BookEntity

@OptIn(UnstableApi::class)
internal class ShelfDriveMediaCatalog(
    private val context: Context,
    private val browseRepository: CatalogBrowseRepository,
) {
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
            is BrowseNodeId.BooksBucket -> buildBooksBucketItem(node.bucket)
            BrowseNodeId.Authors -> buildAuthorsRootItem()
            is BrowseNodeId.AuthorsBucket -> buildAuthorsBucketItem(node.bucket)
            is BrowseNodeId.Book -> browseRepository.getPlayableBook(node.bookId)?.let(::buildPlayableBookItem)
            is BrowseNodeId.Author -> browseRepository.getAuthor(node.authorId)?.let(::buildAuthorItem)
            is BrowseNodeId.AuthorBooksBucket -> buildAuthorBooksBucketItem(node.authorId, node.bucket)
        }
    }

    suspend fun loadSearchResults(query: String): List<MediaItem> {
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

        return authors.map(::buildAuthorItem) + books.map(::buildPlayableBookItem)
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
            .setRecent(false)
            .setOffline(params?.isOffline == true)
            .setSuggested(params?.isSuggested == true)
            .build()
    }

    fun pageItems(items: List<MediaItem>, page: Int, pageSize: Int): List<MediaItem> {
        val fromIndex = page.toLong() * pageSize
        if (fromIndex >= items.size) {
            return emptyList()
        }
        val toIndex = (fromIndex + pageSize).coerceAtMost(items.size.toLong())
        return items.subList(fromIndex.toInt(), toIndex.toInt())
    }

    private suspend fun loadRecentItems(): List<MediaItem> {
        return browseRepository.getRecentBooks().map(::buildPlayableBookItem)
    }

    private suspend fun loadBooksItems(): List<MediaItem> {
        return when (val books = browseRepository.getBooksRoot()) {
            BrowseCollection.Empty -> emptyList()

            is BrowseCollection.Direct -> books.items.map(::buildPlayableBookItem)
            is BrowseCollection.Grouped -> books.groups.map { group ->
                buildBooksBucketItem(group.key, group.count)
            }
        }
    }

    private suspend fun loadAuthorsItems(): List<MediaItem> {
        return when (val authors = browseRepository.getAuthorsRoot()) {
            BrowseCollection.Empty -> emptyList()

            is BrowseCollection.Direct -> authors.items.map(::buildAuthorItem)
            is BrowseCollection.Grouped -> authors.groups.map { group ->
                buildAuthorsBucketItem(group.key, group.count)
            }
        }
    }

    private suspend fun loadAuthorItems(authorId: String): List<MediaItem> {
        if (browseRepository.getAuthor(authorId) == null) {
            return emptyList()
        }
        return when (val books = browseRepository.getBooksForAuthor(authorId)) {
            BrowseCollection.Empty -> emptyList()

            is BrowseCollection.Direct -> books.items.map(::buildPlayableBookItem)
            is BrowseCollection.Grouped -> books.groups.map { group ->
                buildAuthorBooksBucketItem(authorId, group.key, group.count)
            }
        }
    }

    private fun buildBooksBucketItem(bucket: String, count: Int? = null): MediaItem {
        return buildBrowsableItem(
            mediaId = BrowseNodeId.BooksBucket(bucket).serialize(),
            title = bucket,
            subtitle = count?.let {
                context.resources.getQuantityString(R.plurals.media_books_group_summary, it, it)
            },
            extras = childStyleExtras(
                playableStyle = MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM,
            ),
        )
    }

    private fun buildAuthorsBucketItem(bucket: String, count: Int? = null): MediaItem {
        return buildBrowsableItem(
            mediaId = BrowseNodeId.AuthorsBucket(bucket).serialize(),
            title = bucket,
            subtitle = count?.let {
                context.resources.getQuantityString(R.plurals.media_authors_group_summary, it, it)
            },
            extras = childStyleExtras(
                browsableStyle = MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM,
            ),
        )
    }

    private fun buildAuthorBooksBucketItem(
        authorId: String,
        bucket: String,
        count: Int? = null,
    ): MediaItem {
        return buildBrowsableItem(
            mediaId = BrowseNodeId.AuthorBooksBucket(authorId, bucket).serialize(),
            title = bucket,
            subtitle = count?.let {
                context.resources.getQuantityString(R.plurals.media_books_group_summary, it, it)
            },
            extras = childStyleExtras(
                playableStyle = MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM,
            ),
        )
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
