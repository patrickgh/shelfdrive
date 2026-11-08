package io.audiobookshelf.aaos.bookorbit

import android.net.Uri
import io.audiobookshelf.aaos.absapi.PlaybackTrackSummary
import io.audiobookshelf.aaos.bookorbit.BookOrbitApiClient.Companion.numericId
import io.audiobookshelf.aaos.bookorbit.BookOrbitApiClient.Companion.objects
import io.audiobookshelf.aaos.bookorbit.BookOrbitApiClient.Companion.optionalString
import io.audiobookshelf.aaos.bookorbit.BookOrbitApiClient.Companion.parse
import io.audiobookshelf.aaos.bookorbit.BookOrbitApiClient.Companion.requiredLong
import io.audiobookshelf.aaos.bookorbit.BookOrbitApiClient.Companion.requiredString
import io.audiobookshelf.aaos.playback.PlaybackChapter
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.roundToLong

/** The server's SMIL timeline connects audio offsets to the reader's sentence markers. */
internal data class BookOrbitReadAlong(
    val fileId: String,
    val sourceId: String,
    val durationMs: Long,
    val segments: List<Segment>,
    val tracks: List<PlaybackTrackSummary>,
    val chapters: List<PlaybackChapter>,
) {
    data class Segment(
        val section: Int, val fragment: String, val startMs: Long, val durationMs: Long,
        val audioHref: String, val mime: String, val clipStartMs: Long, val clipEndMs: Long, val label: String?,
    )

    fun position(state: JSONObject): Long? = parse {
        val narration = state.getJSONObject("narration")
        val fragment = narration.optionalString("fragment")
        val section = if (narration.isNull("sectionIndex")) null else narration.requiredLong("sectionIndex").toInt()
        val segment = fragment?.let { marker -> segments.firstOrNull { it.fragment == marker && (section == null || it.section == section) }
            ?: throw IOException("BookOrbit narration marker is not in the selected EPUB.") }
        val seconds = if (narration.isNull("seconds")) null else narration.milliseconds("seconds")
        // Reading can move the sentence marker without moving the old seconds field.
        if (segment != null) {
            return@parse seconds?.takeIf { it >= segment.startMs && it <= segment.startMs + segment.durationMs }
                ?: segment.startMs
        }
        if (seconds != null && seconds > durationMs) throw IOException("BookOrbit narration exceeds the EPUB duration.")
        if (seconds == null && !state.isNull("updatedAt") && state.getDouble("percentage") > 0) {
            throw IOException("BookOrbit has reading progress but no mapped narration position.")
        }
        seconds
    }

    fun narration(positionMs: Long): JSONObject {
        val segment = segments.lastOrNull { it.startMs <= positionMs } ?: segments.first()
        return JSONObject().put("seconds", positionMs / 1000.0)
            .put("fragment", segment.fragment).put("sectionIndex", segment.section)
    }

    companion object {
        fun parsePlaylist(json: JSONObject, bookId: String, fileId: String): BookOrbitReadAlong = parse {
            if (json.numericId("bookId") != bookId || json.numericId("fileId") != fileId) {
                throw IOException("BookOrbit returned an unexpected EPUB playlist.")
            }
            val resources = objects(json.getJSONArray("resources")).map { it.requiredString("href") }.toSet()
            var elapsed = 0L
            val segments = objects(json.getJSONArray("items")).mapIndexed { index, item ->
                if (item.requiredLong("index") != index.toLong()) throw IOException("Invalid BookOrbit narration order.")
                val section = item.requiredLong("sectionIndex")
                if (section < 0 || section > Int.MAX_VALUE) throw IOException("Invalid BookOrbit narration section.")
                val href = item.requiredString("audioHref")
                if (href !in resources || href.startsWith('/') || href.split('/').any { it == ".." || it == "." }) {
                    throw IOException("Invalid BookOrbit narration resource.")
                }
                val mime = item.requiredString("audioMimeType")
                if (!mime.startsWith("audio/")) throw IOException("Unsupported BookOrbit narration audio type.")
                val begin = item.milliseconds("clipBeginSeconds")
                val end = item.milliseconds("clipEndSeconds")
                val duration = end - begin
                if (duration <= 0 || abs(item.milliseconds("durationSeconds") - duration) > 1 || duration > Long.MAX_VALUE - elapsed) {
                    throw IOException("Invalid BookOrbit narration clip duration.")
                }
                val fragment = item.requiredString("textHref") + (item.optionalString("textFragment")?.let { "#$it" } ?: "")
                Segment(section.toInt(), fragment, elapsed, duration, href, mime, begin, end, item.optionalString("label"))
                    .also { elapsed += duration }
            }
            if (segments.isEmpty() || abs(json.milliseconds("durationSeconds") - elapsed) > segments.size.coerceAtLeast(2)) {
                throw IOException("Incomplete BookOrbit narration timeline.")
            }
            val revision = digest(segments.joinToString("\n") { "${it.section}|${it.fragment}|${it.audioHref}|${it.clipStartMs}|${it.clipEndMs}" })
            val sourceId = "epub:$fileId:$revision"
            val tracks = mutableListOf<PlaybackTrackSummary>()
            segments.forEach { segment ->
                val previous = tracks.lastOrNull()
                val path = "/api/v1/epub/$bookId/media-overlay/file/" +
                    segment.audioHref.split('/').joinToString("/") { Uri.encode(it) } + "?fileId=$fileId"
                if (previous != null && previous.contentUrl == path && previous.clipEndMs == segment.clipStartMs) {
                    tracks[tracks.lastIndex] = previous.copy(durationMs = requireNotNull(previous.durationMs) + segment.durationMs,
                        clipEndMs = segment.clipEndMs)
                } else {
                    tracks += PlaybackTrackSummary("$sourceId:${digest(segment.audioHref)}", tracks.size, segment.label,
                        path, segment.mime, segment.durationMs, segment.startMs, segment.clipStartMs, segment.clipEndMs)
                }
            }
            val chapters = mutableListOf<PlaybackChapter>()
            segments.forEachIndexed { index, segment ->
                if (index == 0 || segments[index - 1].section != segment.section) {
                    if (chapters.isNotEmpty()) chapters[chapters.lastIndex] = chapters.last().copy(endMs = segment.startMs)
                    chapters += PlaybackChapter(segment.label, segment.startMs, elapsed)
                }
            }
            BookOrbitReadAlong(fileId, sourceId, elapsed, segments, tracks, chapters)
        }

        private fun JSONObject.milliseconds(key: String): Long {
            val seconds = (opt(key) as? Number)?.toDouble() ?: throw IOException("Missing BookOrbit narration $key.")
            if (!seconds.isFinite() || seconds < 0 || seconds * 1000 >= Long.MAX_VALUE.toDouble()) {
                throw IOException("Invalid BookOrbit narration $key.")
            }
            return (seconds * 1000).roundToLong()
        }

        private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
