package io.audiobookshelf.aaos.mediacompat

internal object ShelfDriveCompatConstants {
    const val CMD_SEEK_BACK_15 = "io.shelfdrive.app.mediacompat.SEEK_BACK_15"
    const val CMD_SEEK_FORWARD_15 = "io.shelfdrive.app.mediacompat.SEEK_FORWARD_15"
    const val CMD_CYCLE_PLAYBACK_SPEED = "io.shelfdrive.app.mediacompat.CYCLE_PLAYBACK_SPEED"

    const val EXTRAS_KEY_CONTENT_STYLE_BROWSABLE = "android.media.browse.CONTENT_STYLE_BROWSABLE_HINT"
    const val EXTRAS_KEY_CONTENT_STYLE_PLAYABLE = "android.media.browse.CONTENT_STYLE_PLAYABLE_HINT"
    const val EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM = 1
    const val EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM = 2
    const val EXTRAS_VALUE_CONTENT_STYLE_CATEGORY_LIST_ITEM = 3
}

