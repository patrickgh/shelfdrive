package io.audiobookshelf.aaos.playback

internal object PlaybackSkipPolicy {
    const val DEFAULT_SKIP_INCREMENT_SECONDS = 15L

    val supportedSkipIncrementSeconds = listOf(5L, 10L, 15L, 30L, 60L)

    fun secondsFromPreference(rawValue: String?): Long {
        return rawValue
            ?.toLongOrNull()
            ?.takeIf { it in supportedSkipIncrementSeconds }
            ?: DEFAULT_SKIP_INCREMENT_SECONDS
    }
}
