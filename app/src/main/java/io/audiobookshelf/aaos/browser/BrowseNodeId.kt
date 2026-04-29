package io.audiobookshelf.aaos.browser

sealed interface BrowseNodeId {
    fun serialize(): String

    data object Root : BrowseNodeId {
        override fun serialize(): String = "root"
    }

    data object Recent : BrowseNodeId {
        override fun serialize(): String = "recent"
    }

    data object Books : BrowseNodeId {
        override fun serialize(): String = "books"
    }

    data class BooksBucket(val bucket: String) : BrowseNodeId {
        override fun serialize(): String = "books:bucket:$bucket"
    }

    data object Authors : BrowseNodeId {
        override fun serialize(): String = "authors"
    }

    data class AuthorsBucket(val bucket: String) : BrowseNodeId {
        override fun serialize(): String = "authors:bucket:$bucket"
    }

    data class Author(val authorId: String) : BrowseNodeId {
        override fun serialize(): String = "author:$authorId"
    }

    data class AuthorBooksBucket(val authorId: String, val bucket: String) : BrowseNodeId {
        override fun serialize(): String = "author:$authorId:bucket:$bucket"
    }

    data class Book(val bookId: String) : BrowseNodeId {
        override fun serialize(): String = "book:$bookId"
    }

    companion object {
        fun parse(rawValue: String): BrowseNodeId? {
            if (rawValue == Root.serialize()) return Root
            if (rawValue == Recent.serialize()) return Recent
            if (rawValue == Books.serialize()) return Books
            if (rawValue == Authors.serialize()) return Authors

            val parts = rawValue.split(":")
            return when {
                parts.size == 3 && parts[0] == "books" && parts[1] == "bucket" ->
                    BooksBucket(parts[2])

                parts.size == 3 && parts[0] == "authors" && parts[1] == "bucket" ->
                    AuthorsBucket(parts[2])

                parts.size == 2 && parts[0] == "author" ->
                    Author(parts[1])

                parts.size == 4 && parts[0] == "author" && parts[2] == "bucket" ->
                    AuthorBooksBucket(parts[1], parts[3])

                parts.size == 2 && parts[0] == "book" ->
                    Book(parts[1])

                else -> null
            }
        }
    }
}
