package io.audiobookshelf.aaos.media3

import android.content.Context
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionCommands
import io.audiobookshelf.aaos.auth.AuthCommands
import io.audiobookshelf.aaos.cache.CacheCommands
import io.audiobookshelf.aaos.sync.SyncCommands

@OptIn(UnstableApi::class)
internal class ShelfDriveSessionPolicy(
    private val context: Context,
) {
    fun availableSessionCommands(controller: MediaSession.ControllerInfo): SessionCommands {
        val builder = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS
            .buildUpon()
        if (!isAppController(controller)) {
            return builder.build()
        }
        return builder
            .add(SessionCommand(AuthCommands.CMD_GET_AUTH_STATE, Bundle.EMPTY))
            .add(SessionCommand(AuthCommands.CMD_LOGIN, Bundle.EMPTY))
            .add(SessionCommand(AuthCommands.CMD_LOGOUT, Bundle.EMPTY))
            .add(SessionCommand(CacheCommands.CMD_GET_CACHE_STATE, Bundle.EMPTY))
            .add(SessionCommand(CacheCommands.CMD_CLEAR_CACHE, Bundle.EMPTY))
            .add(SessionCommand(SyncCommands.CMD_GET_SYNC_STATE, Bundle.EMPTY))
            .add(SessionCommand(SyncCommands.CMD_SYNC_NOW, Bundle.EMPTY))
            .build()
    }

    fun availablePlayerCommands(): Player.Commands {
        return MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS
            .buildUpon()
            .remove(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
            .remove(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
            .remove(Player.COMMAND_SEEK_TO_NEXT)
            .remove(Player.COMMAND_SEEK_TO_PREVIOUS)
            .remove(Player.COMMAND_SET_SPEED_AND_PITCH)
            .build()
    }

    private fun isAppController(controller: MediaSession.ControllerInfo): Boolean =
        controller.uid == context.applicationInfo.uid && controller.packageName == context.packageName
}
