package io.audiobookshelf.aaos.server

import io.audiobookshelf.aaos.absapi.AudiobookshelfApiClient
import io.audiobookshelf.aaos.bookorbit.BookOrbitApiClient

class ServerApis(
    private val audiobookshelf: AudiobookServerApi = AudiobookshelfApiClient(),
    private val bookOrbit: AudiobookServerApi = BookOrbitApiClient(),
) {
    fun forType(type: ServerType): AudiobookServerApi = when (type) {
        ServerType.AUDIOBOOKSHELF -> audiobookshelf
        ServerType.BOOKORBIT -> bookOrbit
    }
}
