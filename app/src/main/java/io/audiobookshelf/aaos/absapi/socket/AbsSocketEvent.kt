package io.audiobookshelf.aaos.absapi.socket

import io.audiobookshelf.aaos.catalog.persistence.MediaProgressEntity

sealed interface AbsSocketEvent {
    data class UserUpdated(val progressEntries: List<MediaProgressEntity>) : AbsSocketEvent
    data class ProgressUpdated(val progress: MediaProgressEntity) : AbsSocketEvent
    data class ItemUpdated(val itemId: String, val libraryId: String?) : AbsSocketEvent
    data class ItemRemoved(val itemId: String, val libraryId: String?) : AbsSocketEvent
}

enum class SocketConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    AUTHENTICATED,
}
