package io.audiobookshelf.aaos.media3

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.os.Process
import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionCommands
import io.audiobookshelf.aaos.R
import io.audiobookshelf.aaos.auth.AuthCommands
import io.audiobookshelf.aaos.cache.CacheCommands
import io.audiobookshelf.aaos.sync.SyncCommands

@OptIn(UnstableApi::class)
internal class ShelfDriveSessionPolicy(
    private val context: Context,
) {
    fun isAllowedController(controller: MediaSession.ControllerInfo): Boolean {
        val uid = controller.uid
        val packageName = controller.packageName
        if (uid == Process.SYSTEM_UID) {
            return true
        }
        if (uid == context.applicationInfo.uid && packageName == context.packageName) {
            return true
        }
        val packagesForUid = context.packageManager.getPackagesForUid(uid)?.toSet().orEmpty()
        if (packagesForUid.isEmpty()) {
            return true
        }
        if (packageName !in packagesForUid) {
            return false
        }
        return packagesForUid.any { candidatePackage ->
            val info = runCatching {
                context.packageManager.getApplicationInfo(candidatePackage, 0)
            }.getOrNull() ?: return@any false
            info.flags and ApplicationInfo.FLAG_SYSTEM != 0 ||
                info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0 ||
                candidatePackage == context.packageName
        }
    }

    fun availableSessionCommands(): SessionCommands {
        val builder = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS
            .buildUpon()
            .add(SessionCommand(CMD_SEEK_BACK_15, Bundle.EMPTY))
            .add(SessionCommand(CMD_SEEK_FORWARD_15, Bundle.EMPTY))
        PLAYBACK_SPEEDS.forEach { option ->
            builder.add(SessionCommand(option.command, Bundle.EMPTY))
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
            .build()
    }

    fun mediaButtonPreferences(): List<CommandButton> {
        return listOf(
            CommandButton.Builder(CommandButton.ICON_SKIP_BACK_15)
                .setSessionCommand(SessionCommand(CMD_SEEK_BACK_15, Bundle.EMPTY))
                .setDisplayName(context.getString(R.string.media_action_rewind_15))
                .setSlots(CommandButton.SLOT_BACK)
                .build(),
            CommandButton.Builder(CommandButton.ICON_SKIP_FORWARD_15)
                .setSessionCommand(SessionCommand(CMD_SEEK_FORWARD_15, Bundle.EMPTY))
                .setDisplayName(context.getString(R.string.media_action_forward_15))
                .setSlots(CommandButton.SLOT_FORWARD)
                .build(),
        ) + PLAYBACK_SPEEDS.map { option ->
            CommandButton.Builder(CommandButton.ICON_PLAYBACK_SPEED)
                .setSessionCommand(SessionCommand(option.command, Bundle.EMPTY))
                .setDisplayName(context.getString(R.string.media_action_playback_speed_value, option.label))
                .setSlots(CommandButton.SLOT_OVERFLOW)
                .build()
        }
    }

    companion object {
        const val CMD_SEEK_BACK_15 = "io.shelfdrive.app.media3.SEEK_BACK_15"
        const val CMD_SEEK_FORWARD_15 = "io.shelfdrive.app.media3.SEEK_FORWARD_15"

        private val PLAYBACK_SPEEDS = listOf(
            PlaybackSpeedOption(0.8f, "0.8x"),
            PlaybackSpeedOption(1.0f, "1.0x"),
            PlaybackSpeedOption(1.1f, "1.1x"),
            PlaybackSpeedOption(1.2f, "1.2x"),
            PlaybackSpeedOption(1.3f, "1.3x"),
            PlaybackSpeedOption(1.5f, "1.5x"),
            PlaybackSpeedOption(1.75f, "1.75x"),
            PlaybackSpeedOption(2.0f, "2.0x"),
        )

        fun speedForCommand(action: String): Float? {
            return PLAYBACK_SPEEDS.firstOrNull { it.command == action }?.speed
        }
    }
}

private data class PlaybackSpeedOption(
    val speed: Float,
    val label: String,
) {
    val command: String = "$PLAYBACK_SPEED_COMMAND_PREFIX$label"
}

private const val PLAYBACK_SPEED_COMMAND_PREFIX = "io.shelfdrive.app.media3.PLAYBACK_SPEED:"
