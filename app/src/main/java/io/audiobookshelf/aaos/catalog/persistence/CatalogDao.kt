package io.audiobookshelf.aaos.catalog.persistence

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface LibraryDao {
    @Upsert
    suspend fun upsertAll(libraries: List<LibraryEntity>)

    @Query("SELECT id FROM libraries")
    suspend fun getAllIds(): List<String>

    @Query("DELETE FROM libraries WHERE id IN (:libraryIds)")
    suspend fun deleteByIds(libraryIds: List<String>)

    @Query("DELETE FROM libraries")
    suspend fun clearAll()

    @Query("SELECT COUNT(*) FROM libraries")
    suspend fun count(): Int
}

@Dao
interface BookDao {
    @Upsert
    suspend fun upsertAll(books: List<BookEntity>)

    @Query("SELECT id FROM books")
    suspend fun getAllIds(): List<String>

    @Query("DELETE FROM books WHERE id IN (:bookIds)")
    suspend fun deleteByIds(bookIds: List<String>)

    @Query("DELETE FROM books")
    suspend fun clearAll()

    @Query("SELECT COUNT(*) FROM books")
    suspend fun count(): Int

    @Query("SELECT id FROM books WHERE id IN (:bookIds)")
    suspend fun getExistingIds(bookIds: List<String>): List<String>

    @Query("SELECT EXISTS(SELECT 1 FROM books WHERE id = :bookId)")
    suspend fun existsById(bookId: String): Boolean

    @Query(
        """
        SELECT * FROM books
        WHERE isPlayable = 1
        ORDER BY sortTitle COLLATE NOCASE ASC, title COLLATE NOCASE ASC
        """,
    )
    suspend fun getAllPlayableSorted(): List<BookEntity>

    @Query(
        """
        SELECT * FROM books
        WHERE isPlayable = 1 AND id = :bookId
        LIMIT 1
        """,
    )
    suspend fun getPlayableById(bookId: String): BookEntity?

    @Query(
        """
        SELECT b.* FROM books b
        INNER JOIN book_author_cross_refs bar ON bar.bookId = b.id
        WHERE b.isPlayable = 1 AND bar.authorId = :authorId
        GROUP BY b.id
        ORDER BY b.sortTitle COLLATE NOCASE ASC, b.title COLLATE NOCASE ASC
        """,
    )
    suspend fun getPlayableForAuthor(authorId: String): List<BookEntity>

    @Query(
        """
        SELECT b.* FROM books b
        INNER JOIN media_progress mp ON mp.bookId = b.id
        WHERE b.isPlayable = 1
          AND mp.isFinished = 0
          AND mp.hideFromContinueListening = 0
        ORDER BY mp.lastUpdateAt DESC, b.sortTitle COLLATE NOCASE ASC
        LIMIT :limit
        """,
    )
    suspend fun getRecentPlayable(limit: Int): List<BookEntity>
}

@Dao
interface AuthorDao {
    @Upsert
    suspend fun upsertAll(authors: List<AuthorEntity>)

    @Query("SELECT id FROM authors")
    suspend fun getAllIds(): List<String>

    @Query("DELETE FROM authors WHERE id IN (:authorIds)")
    suspend fun deleteByIds(authorIds: List<String>)

    @Query("DELETE FROM authors")
    suspend fun clearAll()

    @Query("SELECT COUNT(*) FROM authors")
    suspend fun count(): Int

    @Query(
        """
        SELECT * FROM authors
        ORDER BY sortName COLLATE NOCASE ASC, name COLLATE NOCASE ASC
        """,
    )
    suspend fun getAllSorted(): List<AuthorEntity>

    @Query(
        """
        SELECT * FROM authors
        WHERE id = :authorId
        LIMIT 1
        """,
    )
    suspend fun getById(authorId: String): AuthorEntity?
}

@Dao
interface BookAuthorCrossRefDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(crossRefs: List<BookAuthorCrossRef>)

    @Query("DELETE FROM book_author_cross_refs")
    suspend fun clearAll()
}

@Dao
interface MediaProgressDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(progressEntries: List<MediaProgressEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(progressEntry: MediaProgressEntity)

    @Query("DELETE FROM media_progress")
    suspend fun clearAll()

    @Query("DELETE FROM media_progress WHERE pendingUpload = 0")
    suspend fun clearUploaded()

    @Query("DELETE FROM media_progress WHERE bookId = :bookId")
    suspend fun deleteByBookId(bookId: String)

    @Query("SELECT * FROM media_progress WHERE bookId = :bookId LIMIT 1")
    suspend fun getByBookId(bookId: String): MediaProgressEntity?
}

@Dao
interface SyncStateDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(syncState: SyncStateEntity)

    @Query("SELECT * FROM sync_state WHERE id = 0")
    suspend fun get(): SyncStateEntity?

    @Query("DELETE FROM sync_state")
    suspend fun clearAll()
}
