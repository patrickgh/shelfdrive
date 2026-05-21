package io.audiobookshelf.aaos.mediacompat

import android.content.Context
import android.support.v4.media.session.PlaybackStateCompat
import io.audiobookshelf.aaos.R

internal class ShelfDriveSessionPolicy(
    private val context: Context,
) {
    fun playbackActions(): Long {
        return PlaybackStateCompat.ACTION_PLAY or
            PlaybackStateCompat.ACTION_PAUSE or
            PlaybackStateCompat.ACTION_PLAY_PAUSE or
            PlaybackStateCompat.ACTION_PREPARE or
            PlaybackStateCompat.ACTION_PREPARE_FROM_MEDIA_ID or
            PlaybackStateCompat.ACTION_PREPARE_FROM_SEARCH or
            PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID or
            PlaybackStateCompat.ACTION_PLAY_FROM_SEARCH or
            PlaybackStateCompat.ACTION_STOP or
            PlaybackStateCompat.ACTION_SEEK_TO
    }

    fun customActions(playbackSpeed: Float): List<PlaybackStateCompat.CustomAction> {
        return listOf(
            // The AAOS media host on the target emulator renders three custom
            // actions around Play/Pause as third, first, second.
            customAction(
                ShelfDriveCompatConstants.CMD_SEEK_BACK_15,
                context.getString(R.string.media_action_rewind_15),
                R.drawable.ic_action_back_15,
            ),
            customAction(
                ShelfDriveCompatConstants.CMD_SEEK_FORWARD_15,
                context.getString(R.string.media_action_forward_15),
                R.drawable.ic_action_forward_15,
            ),
            customAction(
                ShelfDriveCompatConstants.CMD_CYCLE_PLAYBACK_SPEED,
                context.getString(
                    R.string.media_action_playback_speed_value,
                    playbackSpeedLabel(playbackSpeed),
                ),
                playbackSpeedIcon(playbackSpeed),
            ),
        )
    }

    private fun customAction(
        action: String,
        name: String,
        icon: Int,
    ): PlaybackStateCompat.CustomAction {
        return PlaybackStateCompat.CustomAction.Builder(action, name, icon).build()
    }

    companion object {
        private val PLAYBACK_SPEEDS = listOf(
            PlaybackSpeedOption(0.8f, "0.8x", R.drawable.ic_action_speed_08),
            PlaybackSpeedOption(1.0f, "1.0x", R.drawable.ic_action_speed_10),
            PlaybackSpeedOption(1.2f, "1.2x", R.drawable.ic_action_speed_12),
            PlaybackSpeedOption(1.5f, "1.5x", R.drawable.ic_action_speed_15),
            PlaybackSpeedOption(1.8f, "1.8x", R.drawable.ic_action_speed_18),
            PlaybackSpeedOption(2.0f, "2.0x", R.drawable.ic_action_speed_20),
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
            return playbackSpeedOption(playbackSpeed)?.label
                ?: "${playbackSpeed}x"
        }

        private fun playbackSpeedIcon(playbackSpeed: Float): Int {
            return playbackSpeedOption(playbackSpeed)?.iconRes ?: R.drawable.ic_action_speed_10
        }

        private fun playbackSpeedOption(playbackSpeed: Float): PlaybackSpeedOption? {
            return PLAYBACK_SPEEDS.firstOrNull { it.speed.isApproximately(playbackSpeed) }
        }

        private fun Float.isApproximately(other: Float): Boolean {
            return kotlin.math.abs(this - other) < 0.01f
        }
    }
}

private data class PlaybackSpeedOption(
    val speed: Float,
    val label: String,
    val iconRes: Int,
)
