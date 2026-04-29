package io.audiobookshelf.aaos.artwork

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import io.audiobookshelf.aaos.absapi.NetworkPolicy
import io.audiobookshelf.aaos.auth.AuthStorage
import java.io.File
import java.io.FileNotFoundException
import java.net.HttpURLConnection
import java.net.URL

class ArtworkContentProvider : ContentProvider() {
    private lateinit var authStorage: AuthStorage

    override fun onCreate(): Boolean {
        val ctx = context ?: return false
        authStorage = AuthStorage(ctx)
        return true
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") {
            throw FileNotFoundException("Artwork ist nur lesbar.")
        }
        val ctx = context ?: throw FileNotFoundException("Kein Context verfuegbar.")
        val match = URI_MATCHER.match(uri)
        if (match == UriMatcher.NO_MATCH) {
            throw FileNotFoundException("Unbekannte Artwork-URI.")
        }
        val id = uri.lastPathSegment?.takeIf { SAFE_ID_REGEX.matches(it) }
            ?: throw FileNotFoundException("Ungueltige Artwork-ID in URI.")
        val signature = uri.getQueryParameter("sig").orEmpty().takeIf { SAFE_SIGNATURE_REGEX.matches(it) }
            ?: throw FileNotFoundException("Ungueltige Artwork-Signatur.")
        val cacheFile = cacheFileFor(ctx.cacheDir, match, id, signature)
        if (!cacheFile.exists()) {
            fetchIntoCache(match, id, cacheFile)
        }
        if (!cacheFile.exists()) {
            throw FileNotFoundException("Artwork konnte nicht geladen werden: $uri")
        }
        return ParcelFileDescriptor.open(cacheFile, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String {
        return "image/webp"
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    private fun fetchIntoCache(match: Int, id: String, targetFile: File) {
        val stored = authStorage.load()
        val baseUrl = stored.baseUrl?.trim()?.removeSuffix("/")
            ?: throw FileNotFoundException("Keine Server-URL gespeichert.")
        val accessToken = stored.accessToken
            ?: throw FileNotFoundException("Keine aktive Sitzung fuer Artwork verfuegbar.")

        val path = when (match) {
            BOOK_COVER -> "/api/items/$id/cover?width=400&format=webp"
            AUTHOR_IMAGE -> "/api/authors/$id/image?width=400&format=webp"
            else -> throw FileNotFoundException("Unbekannter Artwork-Typ.")
        }

        val connection = (URL("$baseUrl$path").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = NetworkPolicy.CONNECT_TIMEOUT_MS
            readTimeout = NetworkPolicy.READ_TIMEOUT_MS
            doInput = true
            setRequestProperty("Authorization", "Bearer $accessToken")
            setRequestProperty("Accept", "image/webp,image/*")
        }

        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                throw FileNotFoundException("Artwork-Request fehlgeschlagen: HTTP $code")
            }

            targetFile.parentFile?.mkdirs()
            connection.inputStream.use { input ->
                targetFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun cacheFileFor(cacheRoot: File, match: Int, id: String, signature: String): File {
        val typePrefix = when (match) {
            BOOK_COVER -> "book"
            AUTHOR_IMAGE -> "author"
            else -> "unknown"
        }
        return File(cacheRoot, "artwork/$typePrefix-$id-$signature.webp")
    }

    companion object {
        private const val BOOK_COVER = 1
        private const val AUTHOR_IMAGE = 2
        private val SAFE_ID_REGEX = Regex("[A-Za-z0-9._-]{1,128}")
        private val SAFE_SIGNATURE_REGEX = Regex("[A-Fa-f0-9]{0,16}")

        private val URI_MATCHER = UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI("io.shelfdrive.app.artwork", "books/*", BOOK_COVER)
            addURI("io.shelfdrive.app.artwork", "authors/*", AUTHOR_IMAGE)
        }
    }
}
