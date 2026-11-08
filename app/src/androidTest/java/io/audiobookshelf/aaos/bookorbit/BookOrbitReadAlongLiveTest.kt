package io.audiobookshelf.aaos.bookorbit

import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.graphics.BitmapFactory
import io.audiobookshelf.aaos.artwork.ArtworkUriFactory
import io.audiobookshelf.aaos.auth.AuthRepository
import io.audiobookshelf.aaos.auth.AuthStorage
import io.audiobookshelf.aaos.auth.AuthenticatedRequestRunner
import io.audiobookshelf.aaos.absapi.PlaybackSessionSummary
import io.audiobookshelf.aaos.absapi.PlaybackSessionUpdateRequest
import io.audiobookshelf.aaos.absapi.RetryProfile
import io.audiobookshelf.aaos.server.ServerType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Opt-in only: uses the emulator's signed-in account and saves its existing narration position. */
@RunWith(AndroidJUnit4::class)
class BookOrbitReadAlongLiveTest {
    @Test
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun signedInAccountCanStreamAndSaveItsReadAlongPosition() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("bookorbitLive") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val storage = AuthStorage(context)
        assertEquals("The emulator needs a signed-in BookOrbit account", ServerType.BOOKORBIT, storage.load().serverType)
        val runner = AuthenticatedRequestRunner(storage, AuthRepository(storage))
        runner.execute { account ->
            val api = BookOrbitApiClient()
            val books = api.getLibraries(account.baseUrl, account.accessToken).flatMap {
                api.getLibraryItems(account.baseUrl, account.accessToken, it.id)
            }
            val authors = books.flatMap { it.authors }.distinctBy { it.id }
            assertTrue("The test account needs an author portrait", authors.isNotEmpty())
            for (author in authors) {
                val uri = ArtworkUriFactory.authorImage(author.id, ArtworkUriFactory.signatureFor(author.imagePath))
                context.contentResolver.openInputStream(uri).use { stream ->
                    val bitmap = BitmapFactory.decodeStream(requireNotNull(stream))
                    assertNotNull("Author portrait could not be decoded: ${author.id}", bitmap)
                    bitmap?.recycle()
                }
            }
            var narrated: PlaybackSessionSummary? = null
            for (book in books) {
                val candidate = api.createPlaybackSession(account.baseUrl, account.accessToken, book.id)
                if (candidate.sourceId != null) { narrated = candidate; break }
            }
            assertNotNull("The test account needs a Read-Aloud EPUB", narrated)
            val session = requireNotNull(narrated)
            val source = requireNotNull(session.sourceId)
            android.util.Log.i("ReadAlongLiveTest", "Selected Read-Aloud book ${session.bookId}: ${session.title}")
            val error = AtomicReference<PlaybackException?>()
            val ready = CountDownLatch(1)
            lateinit var player: ExoPlayer
            instrumentation.runOnMainSync {
                val http = DefaultHttpDataSource.Factory().setDefaultRequestProperties(mapOf("Authorization" to "Bearer ${account.accessToken}"))
                player = ExoPlayer.Builder(context).setMediaSourceFactory(DefaultMediaSourceFactory(http)).build()
                player.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) { if (state == Player.STATE_READY) ready.countDown() }
                    override fun onPlayerError(failure: PlaybackException) { error.set(failure); ready.countDown() }
                })
                val track = session.audioTracks.first()
                player.setMediaItem(MediaItem.Builder().setUri(account.baseUrl.trimEnd('/') + track.contentUrl)
                    .setMimeType(track.mimeType).setClippingConfiguration(MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(track.clipStartMs).apply { track.clipEndMs?.let(::setEndPositionMs) }.build()).build())
                player.prepare()
            }
            try {
                assertTrue("Read-Aloud stream did not become ready", ready.await(40, TimeUnit.SECONDS))
                assertNull("Read-Aloud audio failed: ${error.get()?.errorCodeName}", error.get())
                instrumentation.runOnMainSync { assertTrue(player.duration > 0) }
            } finally {
                instrumentation.runOnMainSync { player.release() }
            }
            val position = requireNotNull(session.currentTimeMs)
            repeat(3) {
                api.updateProgress(account.baseUrl, account.accessToken, session.bookId, null,
                    PlaybackSessionUpdateRequest(position, requireNotNull(session.durationMs), 0,
                        System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(5), source), false)
            }
            val saved = api.getMediaProgress(account.baseUrl, account.accessToken, session.bookId, RetryProfile.NONE, source)
            assertEquals(source, saved.sourceId)
            assertEquals(position, saved.currentTimeMs)
        }
    }
}
