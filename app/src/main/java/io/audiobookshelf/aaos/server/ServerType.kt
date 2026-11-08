package io.audiobookshelf.aaos.server

enum class ServerType(val label: String) {
    AUDIOBOOKSHELF("Audiobookshelf"),
    BOOKORBIT("BookOrbit");

    companion object {
        fun fromStored(value: String?): ServerType =
            if (value == null) AUDIOBOOKSHELF else valueOf(value)
    }
}
