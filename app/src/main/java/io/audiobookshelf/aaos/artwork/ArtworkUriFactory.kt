package io.audiobookshelf.aaos.artwork

import android.net.Uri
import io.audiobookshelf.aaos.BuildConfig

object ArtworkUriFactory {
    private val authority = "${BuildConfig.APPLICATION_ID}.artwork"

    fun bookCover(bookId: String, signature: String?): Uri = artworkUri("books", bookId, signature)

    fun authorImage(authorId: String, signature: String?): Uri = artworkUri("authors", authorId, signature)

    fun signatureFor(path: String?): String? {
        return path
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.hashCode()
            ?.toUInt()
            ?.toString(16)
    }

    private fun artworkUri(type: String, id: String, signature: String?): Uri {
        return Uri.Builder()
            .scheme("content")
            .authority(authority)
            .appendPath(type)
            .appendPath(id)
            .apply {
                signature?.let { appendQueryParameter("sig", it) }
            }
            .build()
    }
}
