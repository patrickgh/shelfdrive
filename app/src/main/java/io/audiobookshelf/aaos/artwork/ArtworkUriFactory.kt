package io.audiobookshelf.aaos.artwork

import android.net.Uri

object ArtworkUriFactory {
    private const val AUTHORITY = "io.shelfdrive.app.artwork"

    fun bookCover(bookId: String, signature: String?): Uri {
        return baseBuilder()
            .appendPath("books")
            .appendPath(bookId)
            .apply {
                signature?.let { appendQueryParameter("sig", it) }
            }
            .build()
    }

    fun authorImage(authorId: String, signature: String?): Uri {
        return baseBuilder()
            .appendPath("authors")
            .appendPath(authorId)
            .apply {
                signature?.let { appendQueryParameter("sig", it) }
            }
            .build()
    }

    fun signatureFor(path: String?): String? {
        return path
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.hashCode()
            ?.toUInt()
            ?.toString(16)
    }

    private fun baseBuilder(): Uri.Builder {
        return Uri.Builder()
            .scheme("content")
            .authority(AUTHORITY)
    }
}
