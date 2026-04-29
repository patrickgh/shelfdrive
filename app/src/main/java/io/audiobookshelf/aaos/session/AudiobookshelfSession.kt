package io.audiobookshelf.aaos.session

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.media.utils.MediaConstants
import androidx.media.session.MediaButtonReceiver
import io.audiobookshelf.aaos.R
import io.audiobookshelf.aaos.account.AudiobookshelfAccountContract
import io.audiobookshelf.aaos.artwork.ArtworkUriFactory
import io.audiobookshelf.aaos.browser.BrowseNodeId
import io.audiobookshelf.aaos.catalog.persistence.BookEntity
import io.audiobookshelf.aaos.host.MediaHostIntentFactory
import io.audiobookshelf.aaos.playback.ResolvedAudiobookPlayback

@Suppress("DEPRECATION")
class AudiobookshelfSession(
    context: Context,
) {
    private val appContext = context.applicationContext
    private var currentMetadata: MediaMetadataCompat? = null
    private var currentPlaybackState: PlaybackStateCompat? = AudiobookshelfSessionCallback.initialPlaybackState()
    private var stateObserver: ((MediaMetadataCompat?, PlaybackStateCompat?) -> Unit)? = null
    private var authResolutionErrorActive = false

    private val mediaSession = MediaSessionCompat(context, SESSION_TAG).apply {
        setFlags(
            MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS,
        )
        setPlaybackToLocal(AudioManager.STREAM_MUSIC)
        setMediaButtonReceiver(createMediaButtonPendingIntent(context))
        setPlaybackState(currentPlaybackState)
        setSessionActivity(MediaHostIntentFactory.createMediaHostPendingIntent(context))
        isActive = false
    }

    val sessionToken get() = mediaSession.sessionToken

    fun setCallback(callback: AudiobookshelfSessionCallback) {
        mediaSession.setCallback(callback)
    }

    fun setStateObserver(observer: (MediaMetadataCompat?, PlaybackStateCompat?) -> Unit) {
        stateObserver = observer
        observer(currentMetadata, currentPlaybackState)
    }

    fun republishCurrentState() {
        currentMetadata?.let(mediaSession::setMetadata)
        currentPlaybackState?.let(mediaSession::setPlaybackState)
        notifyStateObserver()
    }

    fun handleMediaButtonIntent(intent: Intent?) {
        MediaButtonReceiver.handleIntent(mediaSession, intent)
    }

    fun setPlaybackState(state: PlaybackStateCompat) {
        currentPlaybackState = state
        mediaSession.setPlaybackState(state)
        notifyStateObserver()
    }

    fun setActive(active: Boolean) {
        mediaSession.isActive = active
    }

    fun buildPlaybackState(
        state: Int,
        positionMs: Long,
        playbackSpeed: Float,
        errorMessage: String? = null,
        errorCode: Int = PlaybackStateCompat.ERROR_CODE_UNKNOWN_ERROR,
        activeQueueItemId: Long = MediaSessionCompat.QueueItem.UNKNOWN_ID.toLong(),
        bufferedPositionMs: Long = positionMs,
        mediaId: String? = null,
        extras: Bundle? = null,
    ): PlaybackStateCompat {
        val playbackExtras = Bundle().apply {
            extras?.let(::putAll)
            if (!mediaId.isNullOrBlank()) {
                putString(MediaConstants.PLAYBACK_STATE_EXTRAS_KEY_MEDIA_ID, mediaId)
            }
        }
        return PlaybackStateCompat.Builder()
            .setActions(DEFAULT_ACTIONS)
            .setState(hostSafeState(state), positionMs, playbackSpeed)
            .setActiveQueueItemId(activeQueueItemId)
            .apply {
                if (bufferedPositionMs >= 0L) {
                    setBufferedPosition(bufferedPositionMs)
                }
                if (!errorMessage.isNullOrBlank()) {
                    setErrorMessage(errorCode, errorMessage)
                }
                if (playbackExtras.keySet().isNotEmpty()) {
                    setExtras(playbackExtras)
                }
            }
            .build()
    }

    fun setMetadata(metadata: MediaMetadataCompat?) {
        currentMetadata = metadata
        mediaSession.setMetadata(metadata)
        notifyStateObserver()
    }

    fun setAccount(username: String?) {
        mediaSession.setExtras(
            Bundle().apply {
                if (!username.isNullOrBlank()) {
                    putString(MediaConstants.SESSION_EXTRAS_KEY_ACCOUNT_NAME, username)
                    putString(MediaConstants.SESSION_EXTRAS_KEY_ACCOUNT_TYPE, AudiobookshelfAccountContract.ACCOUNT_TYPE)
                }
            },
        )
    }

    fun updateQueue(playback: ResolvedAudiobookPlayback) {
        val description = MediaDescriptionCompat.Builder()
            .setMediaId(BrowseNodeId.Book(playback.bookId).serialize())
            .setTitle(playback.title)
            .setSubtitle(playback.author)
            .setIconUri(playback.artworkUri)
            .build()
        val queueItems = listOf(MediaSessionCompat.QueueItem(description, ACTIVE_BOOK_QUEUE_ID))
        mediaSession.isActive = true
        mediaSession.setQueue(queueItems)
        mediaSession.setQueueTitle(playback.title)
    }

    fun publishCatalogBook(book: BookEntity) {
        val artworkUri = ArtworkUriFactory.bookCover(book.id, ArtworkUriFactory.signatureFor(book.coverPath))
        val description = MediaDescriptionCompat.Builder()
            .setMediaId(BrowseNodeId.Book(book.id).serialize())
            .setTitle(book.title)
            .setSubtitle(book.authorDisplay ?: book.subtitle)
            .setIconUri(artworkUri)
            .build()
        mediaSession.isActive = true
        mediaSession.setQueue(listOf(MediaSessionCompat.QueueItem(description, ACTIVE_BOOK_QUEUE_ID)))
        mediaSession.setQueueTitle(book.title)
        setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, BrowseNodeId.Book(book.id).serialize())
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, book.title)
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, book.title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, book.authorDisplay)
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, book.title)
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, book.authorDisplay ?: book.subtitle)
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_DESCRIPTION, book.description)
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON_URI, artworkUri.toString())
                .putString(MediaMetadataCompat.METADATA_KEY_ART_URI, artworkUri.toString())
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI, artworkUri.toString())
                .apply {
                    book.durationMs?.takeIf { it > 0L }?.let { durationMs ->
                        putLong(MediaMetadataCompat.METADATA_KEY_DURATION, durationMs)
                    }
                }
                .build(),
        )
    }

    fun clearQueue() {
        mediaSession.setQueue(emptyList())
        mediaSession.setQueueTitle(null)
        setMetadata(null)
        mediaSession.isActive = false
    }

    fun publishAuthenticationRequired(message: String) {
        val resolutionExtras = Bundle().apply {
            putString(
                MediaConstants.PLAYBACK_STATE_EXTRAS_KEY_ERROR_RESOLUTION_ACTION_LABEL,
                appContext.getString(R.string.media_open_settings_action),
            )
            putParcelable(
                MediaConstants.PLAYBACK_STATE_EXTRAS_KEY_ERROR_RESOLUTION_ACTION_INTENT,
                MediaHostIntentFactory.createSettingsPendingIntent(appContext),
            )
        }
        val currentState = currentPlaybackState
        val currentStateCode = currentState?.state
        val hasActivePlayback = currentMetadata != null &&
            currentStateCode != null &&
            currentStateCode in ACTIVE_PLAYBACK_STATES
        val state = if (hasActivePlayback) currentStateCode else PlaybackStateCompat.STATE_ERROR

        authResolutionErrorActive = true
        mediaSession.isActive = true
        setPlaybackState(
            buildPlaybackState(
                state = state,
                positionMs = currentState?.position ?: PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN,
                playbackSpeed = currentState?.playbackSpeed ?: 0f,
                errorMessage = message,
                errorCode = PlaybackStateCompat.ERROR_CODE_AUTHENTICATION_EXPIRED,
                activeQueueItemId = currentState?.activeQueueItemId
                    ?: MediaSessionCompat.QueueItem.UNKNOWN_ID.toLong(),
                bufferedPositionMs = currentState?.bufferedPosition
                    ?: PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN,
                mediaId = currentState?.extras?.getString(MediaConstants.PLAYBACK_STATE_EXTRAS_KEY_MEDIA_ID),
                extras = resolutionExtras,
            ),
        )
    }

    fun clearAuthenticationRequired() {
        if (!authResolutionErrorActive) {
            return
        }
        authResolutionErrorActive = false
        if (currentMetadata == null) {
            mediaSession.isActive = false
            setPlaybackState(AudiobookshelfSessionCallback.initialPlaybackState())
        }
    }

    fun release() {
        mediaSession.release()
    }

    private fun notifyStateObserver() {
        stateObserver?.invoke(currentMetadata, currentPlaybackState)
    }

    companion object {
        private const val SESSION_TAG = "AudiobookshelfSession"
        const val ACTIVE_BOOK_QUEUE_ID = 1L
        private val ACTIVE_PLAYBACK_STATES = setOf(
            PlaybackStateCompat.STATE_CONNECTING,
            PlaybackStateCompat.STATE_BUFFERING,
            PlaybackStateCompat.STATE_PLAYING,
            PlaybackStateCompat.STATE_PAUSED,
        )
        private const val DEFAULT_ACTIONS =
                PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_PLAY_PAUSE or
                PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID or
                PlaybackStateCompat.ACTION_PREPARE_FROM_MEDIA_ID or
                PlaybackStateCompat.ACTION_PLAY_FROM_SEARCH or
                PlaybackStateCompat.ACTION_PREPARE_FROM_SEARCH or
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_STOP or
                PlaybackStateCompat.ACTION_REWIND or
                PlaybackStateCompat.ACTION_FAST_FORWARD or
                PlaybackStateCompat.ACTION_SEEK_TO or
                PlaybackStateCompat.ACTION_SET_PLAYBACK_SPEED or
                PlaybackStateCompat.ACTION_SKIP_TO_QUEUE_ITEM or
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS

        private fun hostSafeState(state: Int): Int {
            return if (state == PlaybackStateCompat.STATE_NONE) {
                PlaybackStateCompat.STATE_STOPPED
            } else {
                state
            }
        }

        private fun createMediaButtonPendingIntent(context: Context): PendingIntent {
            val mediaButtonIntent = Intent(Intent.ACTION_MEDIA_BUTTON)
                .setClass(context, MediaButtonReceiver::class.java)
            return PendingIntent.getBroadcast(
                context,
                0,
                mediaButtonIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }
    }
}
