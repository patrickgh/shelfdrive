package io.audiobookshelf.aaos.browser

import io.audiobookshelf.aaos.catalog.persistence.AuthorEntity
import io.audiobookshelf.aaos.catalog.persistence.BookEntity
import io.audiobookshelf.aaos.catalog.persistence.CatalogDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.Normalizer
import java.util.Locale

class CatalogBrowseRepository(
    private val database: CatalogDatabase,
) {

    suspend fun getBooksRoot(): BrowseCollection<BookEntity> = withContext(Dispatchers.IO) {
        val books = database.bookDao().getAllPlayableSorted()
        toCollection(books) { it.sortTitle.ifBlank { it.title } }
    }

    suspend fun getRecentBooks(limit: Int = DEFAULT_RECENT_LIMIT): List<BookEntity> = withContext(Dispatchers.IO) {
        database.bookDao().getRecentPlayable(limit)
    }

    suspend fun getPlayableBook(bookId: String): BookEntity? = withContext(Dispatchers.IO) {
        database.bookDao().getPlayableById(bookId)
    }

    suspend fun searchBooks(query: String, limit: Int = DEFAULT_SEARCH_LIMIT): List<BookEntity> =
        withContext(Dispatchers.IO) {
            val normalizedQuery = normalizeSearchText(query)
            if (normalizedQuery.isBlank()) {
                return@withContext emptyList()
            }

            database.bookDao().getAllPlayableSorted()
                .rankedMatches(
                    query = normalizedQuery,
                    sortKey = { it.sortTitle.ifBlank { it.title } },
                    rank = { book, searchQuery -> book.searchRank(searchQuery) },
                )
                .take(limit)
        }

    suspend fun searchAuthors(query: String, limit: Int = DEFAULT_SEARCH_LIMIT): List<AuthorEntity> =
        withContext(Dispatchers.IO) {
            val normalizedQuery = normalizeSearchText(query)
            if (normalizedQuery.isBlank()) {
                return@withContext emptyList()
            }

            database.authorDao().getAllSorted()
                .rankedMatches(
                    query = normalizedQuery,
                    sortKey = { it.sortName.ifBlank { it.name } },
                    rank = { author, searchQuery -> author.searchRank(searchQuery) },
                )
                .take(limit)
        }

    suspend fun findBestPlayableBookForVoice(queries: List<String>): BookEntity? = withContext(Dispatchers.IO) {
        val normalizedQueries = queries
            .map(::normalizeSearchText)
            .filter { it.isNotBlank() }
            .distinct()

        if (normalizedQueries.isEmpty()) {
            return@withContext defaultVoiceBook()
        }

        val books = database.bookDao().getAllPlayableSorted()
        normalizedQueries.forEach { query ->
            books.rankedMatches(
                query = query,
                sortKey = { it.sortTitle.ifBlank { it.title } },
                rank = { book, searchQuery -> book.searchRank(searchQuery) },
            ).firstOrNull()?.let { return@withContext it }
        }

        val authors = database.authorDao().getAllSorted()
        normalizedQueries.forEach { query ->
            val author = authors.rankedMatches(
                query = query,
                sortKey = { it.sortName.ifBlank { it.name } },
                rank = { candidate, searchQuery -> candidate.searchRank(searchQuery) },
            ).firstOrNull()

            if (author != null) {
                database.bookDao().getPlayableForAuthor(author.id).firstOrNull()?.let {
                    return@withContext it
                }
            }
        }

        null
    }

    suspend fun getBooksForBucket(bucket: String): List<BookEntity> = withContext(Dispatchers.IO) {
        database.bookDao().getAllPlayableSorted().filterToBucket(bucket) { it.sortTitle.ifBlank { it.title } }
    }

    suspend fun getAuthorsRoot(): BrowseCollection<AuthorEntity> = withContext(Dispatchers.IO) {
        val authors = database.authorDao().getAllSorted()
        toCollection(authors) { it.sortName.ifBlank { it.name } }
    }

    suspend fun getAuthorsForBucket(bucket: String): List<AuthorEntity> = withContext(Dispatchers.IO) {
        database.authorDao().getAllSorted().filterToBucket(bucket) { it.sortName.ifBlank { it.name } }
    }

    suspend fun getAuthor(authorId: String): AuthorEntity? = withContext(Dispatchers.IO) {
        database.authorDao().getById(authorId)
    }

    suspend fun getBooksForAuthor(authorId: String): BrowseCollection<BookEntity> = withContext(Dispatchers.IO) {
        val books = database.bookDao().getPlayableForAuthor(authorId)
        toCollection(books) { it.sortTitle.ifBlank { it.title } }
    }

    suspend fun getBooksForAuthorBucket(authorId: String, bucket: String): List<BookEntity> =
        withContext(Dispatchers.IO) {
            database.bookDao().getPlayableForAuthor(authorId)
                .filterToBucket(bucket) { it.sortTitle.ifBlank { it.title } }
        }

    sealed interface BrowseCollection<out T> {
        data object Empty : BrowseCollection<Nothing>
        data class Direct<T>(val items: List<T>) : BrowseCollection<T>
        data class Grouped(val groups: List<BrowseBucket>) : BrowseCollection<Nothing>
    }

    data class BrowseBucket(
        val key: String,
        val label: String,
        val count: Int,
    )

    private fun <T> toCollection(
        items: List<T>,
        sortKey: (T) -> String,
    ): BrowseCollection<T> {
        if (items.isEmpty()) {
            return BrowseCollection.Empty
        }
        if (items.size <= MAX_DIRECT_CHILDREN) {
            return BrowseCollection.Direct(items)
        }

        val groups = linkedMapOf<String, Int>()
        items.forEach { item ->
            val bucketKey = bucketKeyFor(sortKey(item))
            groups[bucketKey] = (groups[bucketKey] ?: 0) + 1
        }
        return BrowseCollection.Grouped(
            groups.entries.map { entry ->
                BrowseBucket(
                    key = entry.key,
                    label = entry.key,
                    count = entry.value,
                )
            },
        )
    }

    private fun <T> List<T>.filterToBucket(
        bucket: String,
        sortKey: (T) -> String,
    ): List<T> {
        return filter { item -> bucketKeyFor(sortKey(item)) == bucket }
    }

    private suspend fun defaultVoiceBook(): BookEntity? {
        return database.bookDao().getRecentPlayable(1).firstOrNull()
            ?: database.bookDao().getAllPlayableSorted().firstOrNull()
    }

    private fun BookEntity.searchRank(query: String): Int? {
        return rankSearchFields(
            query = query,
            primaryFields = listOf(title, sortTitle),
            secondaryFields = listOf(authorDisplay, subtitle),
        )
    }

    private fun AuthorEntity.searchRank(query: String): Int? {
        return rankSearchFields(
            query = query,
            primaryFields = listOf(name, sortName),
            secondaryFields = emptyList(),
        )
    }

    private fun rankSearchFields(
        query: String,
        primaryFields: List<String?>,
        secondaryFields: List<String?>,
    ): Int? {
        val primary = primaryFields.normalizedFields()
        val secondary = secondaryFields.normalizedFields()
        val combined = (primary + secondary).joinToString(" ")
        if (combined.isBlank()) {
            return null
        }

        return when {
            primary.any { it == query } -> 0
            secondary.any { it == query } -> 1
            primary.any { it.startsWith(query) } -> 2
            secondary.any { it.startsWith(query) } -> 3
            primary.any { it.contains(query) } -> 4
            secondary.any { it.contains(query) } -> 5
            query.splitToSequence(" ").filter { it.isNotBlank() }.all { combined.contains(it) } -> 6
            else -> null
        }
    }

    private fun <T> List<T>.rankedMatches(
        query: String,
        sortKey: (T) -> String,
        rank: (T, String) -> Int?,
    ): List<T> {
        return mapNotNull { item ->
            rank(item, query)?.let { RankedMatch(item, it, sortKey(item)) }
        }
            .sortedWith(
                compareBy<RankedMatch<T>> { it.rank }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.sortKey },
            )
            .map { it.item }
    }

    private fun List<String?>.normalizedFields(): List<String> {
        return mapNotNull { value ->
            normalizeSearchText(value.orEmpty()).takeIf { it.isNotBlank() }
        }
    }

    private data class RankedMatch<T>(
        val item: T,
        val rank: Int,
        val sortKey: String,
    )

    private fun bucketKeyFor(value: String): String {
        val normalized = Normalizer.normalize(value.trim(), Normalizer.Form.NFD)
            .replace(DIACRITICS_REGEX, "")
            .uppercase(Locale.ROOT)
        val first = normalized.firstOrNull() ?: return OTHER_BUCKET
        return if (first in 'A'..'Z') first.toString() else OTHER_BUCKET
    }

    companion object {
        private const val MAX_DIRECT_CHILDREN = 120
        private const val DEFAULT_RECENT_LIMIT = 24
        private const val DEFAULT_SEARCH_LIMIT = 24
        private const val OTHER_BUCKET = "#"
        private val DIACRITICS_REGEX = "\\p{M}+".toRegex()

        private fun normalizeSearchText(value: String): String {
            return Normalizer.normalize(value.trim(), Normalizer.Form.NFD)
                .replace(DIACRITICS_REGEX, "")
                .lowercase(Locale.ROOT)
        }
    }
}
