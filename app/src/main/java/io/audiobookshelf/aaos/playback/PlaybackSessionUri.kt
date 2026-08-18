package io.audiobookshelf.aaos.playback

private const val PLAYBACK_URI_PREFIX = "shelfdrive://book/"
private val PLAYBACK_URI_PATTERN = Regex(
    "^${Regex.escape(PLAYBACK_URI_PREFIX)}([^/?#]+)/track/(\\d+)$",
)

internal data class PlaybackTrackUri(
    val bookId: String,
    val trackIndex: Int,
)

internal fun playbackTrackUri(bookId: String, trackIndex: Int): String {
    require(bookId.isNotBlank())
    require(trackIndex >= 0)
    return "$PLAYBACK_URI_PREFIX$bookId/track/$trackIndex"
}

internal fun parsePlaybackTrackUri(contentUrl: String): PlaybackTrackUri? {
    val match = PLAYBACK_URI_PATTERN.matchEntire(contentUrl) ?: return null
    val trackIndex = match.groupValues[2].toIntOrNull() ?: return null
    return PlaybackTrackUri(
        bookId = match.groupValues[1],
        trackIndex = trackIndex,
    )
}

internal fun isShelfDrivePlaybackUri(contentUrl: String): Boolean {
    return parsePlaybackTrackUri(contentUrl) != null
}

internal fun playbackSessionTrackUrl(
    baseUrl: String,
    sessionId: String,
    trackIndex: Int,
): String {
    return "${baseUrl.trimEnd('/')}/public/session/$sessionId/track/$trackIndex"
}
