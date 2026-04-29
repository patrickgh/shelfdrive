package io.audiobookshelf.aaos.playback

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.pm.ServiceInfo
import android.os.Build
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.media.session.MediaButtonReceiver
import io.audiobookshelf.aaos.R
import io.audiobookshelf.aaos.host.MediaHostIntentFactory
import io.audiobookshelf.aaos.session.AudiobookshelfSession

class AudiobookshelfPlaybackNotificationController(
    private val service: Service,
    private val session: AudiobookshelfSession,
) {
    private val notificationManager =
        service.getSystemService(NotificationManager::class.java)
    private var isForeground = false

    fun onSessionStateChanged(
        metadata: MediaMetadataCompat?,
        playbackState: PlaybackStateCompat?,
    ) {
        if (playbackState == null) {
            stopAndRemove()
            return
        }

        val notification = buildNotification(metadata, playbackState)
        when {
            shouldRunForeground(metadata, playbackState) -> startForeground(notification)
            shouldShowDetached(metadata, playbackState) -> showDetached(notification)
            else -> stopAndRemove()
        }
    }

    fun release() {
        stopAndRemove()
    }

    @SuppressLint("MissingPermission")
    private fun startForeground(notification: Notification) {
        ensureNotificationChannel()
        runCatching {
            ServiceCompat.startForeground(
                service,
                NOTIFICATION_ID,
                notification,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                } else {
                    0
                },
            )
            isForeground = true
        }.onFailure { exception ->
            Log.w(TAG, "Could not move playback service to foreground.", exception)
            runCatching {
                notificationManager.notify(NOTIFICATION_ID, notification)
            }.onFailure { notifyException ->
                Log.w(TAG, "Could not publish playback notification.", notifyException)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun showDetached(notification: Notification) {
        ensureNotificationChannel()
        if (isForeground) {
            ServiceCompat.stopForeground(service, ServiceCompat.STOP_FOREGROUND_DETACH)
            isForeground = false
        }
        runCatching {
            notificationManager.notify(NOTIFICATION_ID, notification)
        }.onFailure { exception ->
            Log.w(TAG, "Could not update detached playback notification.", exception)
        }
    }

    private fun stopAndRemove() {
        if (isForeground) {
            ServiceCompat.stopForeground(service, ServiceCompat.STOP_FOREGROUND_REMOVE)
            isForeground = false
        } else {
            notificationManager.cancel(NOTIFICATION_ID)
        }
    }

    private fun buildNotification(
        metadata: MediaMetadataCompat?,
        playbackState: PlaybackStateCompat,
    ): Notification {
        ensureNotificationChannel()
        val isPlaying = playbackState.state == PlaybackStateCompat.STATE_PLAYING
        val mediaStyle = androidx.media.app.NotificationCompat.MediaStyle()
            .setMediaSession(session.sessionToken)
            .setShowActionsInCompactView(0, 1, 2)

        return NotificationCompat.Builder(service, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_attribution_icon)
            .setContentTitle(metadata.titleOrAppName())
            .setContentText(metadata.subtitleOrEmpty())
            .setSubText(metadata.descriptionOrEmpty())
            .setContentIntent(MediaHostIntentFactory.createMediaHostPendingIntent(service))
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setSilent(true)
            .setOngoing(shouldRunForeground(metadata, playbackState))
            .setDeleteIntent(
                MediaButtonReceiver.buildMediaButtonPendingIntent(
                    service,
                    PlaybackStateCompat.ACTION_STOP,
                ),
            )
            .addAction(
                R.drawable.ic_notification_skip_previous,
                service.getString(R.string.playback_notification_previous),
                MediaButtonReceiver.buildMediaButtonPendingIntent(
                    service,
                    PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS,
                ),
            )
            .addAction(
                if (isPlaying) R.drawable.ic_notification_pause else R.drawable.ic_notification_play,
                service.getString(
                    if (isPlaying) {
                        R.string.playback_notification_pause
                    } else {
                        R.string.playback_notification_play
                    },
                ),
                MediaButtonReceiver.buildMediaButtonPendingIntent(
                    service,
                    if (isPlaying) PlaybackStateCompat.ACTION_PAUSE else PlaybackStateCompat.ACTION_PLAY,
                ),
            )
            .addAction(
                R.drawable.ic_notification_skip_next,
                service.getString(R.string.playback_notification_next),
                MediaButtonReceiver.buildMediaButtonPendingIntent(
                    service,
                    PlaybackStateCompat.ACTION_SKIP_TO_NEXT,
                ),
            )
            .setStyle(mediaStyle)
            .build()
    }

    private fun ensureNotificationChannel() {
        if (notificationManager.getNotificationChannel(CHANNEL_ID) != null) {
            return
        }
        val channel = NotificationChannel(
            CHANNEL_ID,
            service.getString(R.string.playback_notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = service.getString(R.string.playback_notification_channel_description)
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun shouldRunForeground(
        metadata: MediaMetadataCompat?,
        playbackState: PlaybackStateCompat,
    ): Boolean {
        if (metadata == null) {
            return false
        }
        return playbackState.state in setOf(
            PlaybackStateCompat.STATE_CONNECTING,
            PlaybackStateCompat.STATE_BUFFERING,
            PlaybackStateCompat.STATE_PLAYING,
        )
    }

    private fun shouldShowDetached(
        metadata: MediaMetadataCompat?,
        playbackState: PlaybackStateCompat,
    ): Boolean {
        if (metadata == null) {
            return false
        }
        return playbackState.state in setOf(
            PlaybackStateCompat.STATE_PAUSED,
            PlaybackStateCompat.STATE_ERROR,
        )
    }

    private fun MediaMetadataCompat?.titleOrAppName(): String {
        return firstNonBlank(
            this?.getString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE),
            this?.getString(MediaMetadataCompat.METADATA_KEY_TITLE),
        ) ?: service.getString(R.string.app_name)
    }

    private fun MediaMetadataCompat?.subtitleOrEmpty(): String {
        return firstNonBlank(
            this?.getString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE),
            this?.getString(MediaMetadataCompat.METADATA_KEY_ARTIST),
        ).orEmpty()
    }

    private fun MediaMetadataCompat?.descriptionOrEmpty(): String {
        return firstNonBlank(
            this?.getString(MediaMetadataCompat.METADATA_KEY_DISPLAY_DESCRIPTION),
            this?.getString(MediaMetadataCompat.METADATA_KEY_ALBUM),
        ).orEmpty()
    }

    private fun firstNonBlank(vararg values: String?): String? {
        return values.firstOrNull { !it.isNullOrBlank() }
    }

    companion object {
        private const val TAG = "AbsPlaybackNotif"
        private const val CHANNEL_ID = "abs_playback"
        private const val NOTIFICATION_ID = 42
    }
}
