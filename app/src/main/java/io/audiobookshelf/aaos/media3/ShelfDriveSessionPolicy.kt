package io.audiobookshelf.aaos.media3

import android.content.Context
import android.os.Bundle
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
import io.audiobookshelf.aaos.playback.PlaybackPreferences
import io.audiobookshelf.aaos.sync.SyncCommands

@OptIn(UnstableApi::class)
internal class ShelfDriveSessionPolicy(
    private val context: Context,
) {
    fun availableSessionCommands(controller: MediaSession.ControllerInfo): SessionCommands {
        val builder = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS
            .buildUpon()
            .add(SessionCommand(CMD_SEEK_BACK, Bundle.EMPTY))
            .add(SessionCommand(CMD_SEEK_FORWARD, Bundle.EMPTY))
            .add(SessionCommand(CMD_CYCLE_PLAYBACK_SPEED, Bundle.EMPTY))
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
            .remove(Player.COMMAND_SEEK_TO_MEDIA_ITEM)
            .remove(Player.COMMAND_SET_SPEED_AND_PITCH)
            .build()
    }

    fun mediaButtonPreferences(playbackSpeed: Float = DEFAULT_PLAYBACK_SPEED): List<CommandButton> {
        return listOf(
            rewindButton(),
            forwardButton(),
            playbackSpeedButton(playbackSpeed),
        )
    }

    fun customLayout(playbackSpeed: Float = DEFAULT_PLAYBACK_SPEED): List<CommandButton> {
        return listOf(
            rewindButton(),
            playbackSpeedButton(playbackSpeed),
            forwardButton(),
        )
    }

    private fun rewindButton(): CommandButton {
        val skipIncrementSeconds = PlaybackPreferences.skipIncrementSeconds(context)
        return CommandButton.Builder(skipBackIcon(skipIncrementSeconds))
            .setSessionCommand(SessionCommand(CMD_SEEK_BACK, Bundle.EMPTY))
            .setDisplayName(context.getString(R.string.media_action_rewind, skipIncrementSeconds))
            .setSlots(CommandButton.SLOT_BACK)
            .build()
    }

    private fun forwardButton(): CommandButton {
        val skipIncrementSeconds = PlaybackPreferences.skipIncrementSeconds(context)
        return CommandButton.Builder(skipForwardIcon(skipIncrementSeconds))
            .setSessionCommand(SessionCommand(CMD_SEEK_FORWARD, Bundle.EMPTY))
            .setDisplayName(context.getString(R.string.media_action_forward, skipIncrementSeconds))
            .setSlots(CommandButton.SLOT_FORWARD)
            .build()
    }

    private fun skipBackIcon(skipIncrementSeconds: Long): Int {
        return when (skipIncrementSeconds) {
            5L -> CommandButton.ICON_SKIP_BACK_5
            10L -> CommandButton.ICON_SKIP_BACK_10
            15L -> CommandButton.ICON_SKIP_BACK_15
            30L -> CommandButton.ICON_SKIP_BACK_30
            else -> CommandButton.ICON_SKIP_BACK
        }
    }

    private fun skipForwardIcon(skipIncrementSeconds: Long): Int {
        return when (skipIncrementSeconds) {
            5L -> CommandButton.ICON_SKIP_FORWARD_5
            10L -> CommandButton.ICON_SKIP_FORWARD_10
            15L -> CommandButton.ICON_SKIP_FORWARD_15
            30L -> CommandButton.ICON_SKIP_FORWARD_30
            else -> CommandButton.ICON_SKIP_FORWARD
        }
    }

    private fun playbackSpeedButton(playbackSpeed: Float): CommandButton {
        return CommandButton.Builder(playbackSpeedIcon(playbackSpeed))
            .setSessionCommand(SessionCommand(CMD_CYCLE_PLAYBACK_SPEED, Bundle.EMPTY))
            .setDisplayName(
                context.getString(
                    R.string.media_action_playback_speed_value,
                    playbackSpeedLabel(playbackSpeed),
                ),
            )
            .setSlots(CommandButton.SLOT_BACK_SECONDARY, CommandButton.SLOT_OVERFLOW)
            .build()
    }

    private fun isAppController(controller: MediaSession.ControllerInfo): Boolean =
        controller.uid == context.applicationInfo.uid && controller.packageName == context.packageName

    companion object {
        const val CMD_SEEK_BACK = "io.shelfdrive.app.media3.SEEK_BACK"
        const val CMD_SEEK_FORWARD = "io.shelfdrive.app.media3.SEEK_FORWARD"
        const val CMD_CYCLE_PLAYBACK_SPEED = "io.shelfdrive.app.media3.CYCLE_PLAYBACK_SPEED"

        private val PLAYBACK_SPEEDS = listOf(
            PlaybackSpeedOption(0.8f, "0.8x"),
            PlaybackSpeedOption(1.0f, "1.0x"),
            PlaybackSpeedOption(1.2f, "1.2x"),
            PlaybackSpeedOption(1.5f, "1.5x"),
            PlaybackSpeedOption(1.8f, "1.8x"),
            PlaybackSpeedOption(2.0f, "2.0x"),
        )

        fun nextPlaybackSpeed(currentSpeed: Float): Float {
            val currentIndex = PLAYBACK_SPEEDS.indexOfFirst { option ->
                option.speed.isApproximately(currentSpeed)
            }
            val nextIndex = if (currentIndex == -1) {
                PLAYBACK_SPEEDS.indexOfFirst { option -> option.speed > currentSpeed }.takeIf { it != -1 } ?: 0
            } else {
                (currentIndex + 1) % PLAYBACK_SPEEDS.size
            }
            return PLAYBACK_SPEEDS[nextIndex].speed
        }

        private fun playbackSpeedLabel(playbackSpeed: Float): String {
            return PLAYBACK_SPEEDS.firstOrNull { it.speed.isApproximately(playbackSpeed) }?.label
                ?: "${playbackSpeed}x"
        }

        private fun playbackSpeedIcon(playbackSpeed: Float): Int {
            val speed = PLAYBACK_SPEEDS.firstOrNull { it.speed.isApproximately(playbackSpeed) }?.speed
                ?: playbackSpeed
            return when {
                speed.isApproximately(0.8f) -> CommandButton.ICON_PLAYBACK_SPEED_0_8
                speed.isApproximately(1.0f) -> CommandButton.ICON_PLAYBACK_SPEED_1_0
                speed.isApproximately(1.2f) -> CommandButton.ICON_PLAYBACK_SPEED_1_2
                speed.isApproximately(1.5f) -> CommandButton.ICON_PLAYBACK_SPEED_1_5
                speed.isApproximately(1.8f) -> CommandButton.ICON_PLAYBACK_SPEED_1_8
                speed.isApproximately(2.0f) -> CommandButton.ICON_PLAYBACK_SPEED_2_0
                else -> CommandButton.ICON_PLAYBACK_SPEED
            }
        }

        private fun Float.isApproximately(other: Float): Boolean {
            return kotlin.math.abs(this - other) < 0.01f
        }
    }
}

private data class PlaybackSpeedOption(
    val speed: Float,
    val label: String,
)

private const val DEFAULT_PLAYBACK_SPEED = 1.0f
