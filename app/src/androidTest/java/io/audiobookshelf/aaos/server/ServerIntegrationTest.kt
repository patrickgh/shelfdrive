@file:Suppress("DEPRECATION")

package io.audiobookshelf.aaos.server

import android.content.ContextWrapper
import android.content.Context
import android.content.SharedPreferences
import io.audiobookshelf.aaos.progress.PlaybackProgressSnapshot
import io.audiobookshelf.aaos.progress.PlaybackProgressReason
import androidx.room.Room
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.audiobookshelf.aaos.absapi.*
import io.audiobookshelf.aaos.auth.*
import io.audiobookshelf.aaos.catalog.persistence.*
import io.audiobookshelf.aaos.progress.ProgressSyncRepository
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class ServerIntegrationTest {
    private val context = object : ContextWrapper(InstrumentationRegistry.getInstrumentation().targetContext) {
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            super.getSharedPreferences("server-integration-test-$name", mode)
    }
    private lateinit var storage: AuthStorage
    private lateinit var database: CatalogDatabase
    private lateinit var bookOrbit: RecordingServer
    private lateinit var abs: RecordingServer
    private lateinit var auth: AuthRepository
    private lateinit var progress: ProgressSyncRepository

    @Before
    fun setUp() {
        storage = AuthStorage(context)
        storage.clear()
        database = Room.inMemoryDatabaseBuilder(context, CatalogDatabase::class.java).build()
        bookOrbit = RecordingServer()
        abs = RecordingServer()
        val apis = ServerApis(abs, bookOrbit)
        auth = AuthRepository(storage, apis)
        progress = ProgressSyncRepository(database, AuthenticatedRequestRunner(storage, auth), apis)
    }

    @After
    fun tearDown() {
        storage.clear()
        database.close()
    }

    @Test
    @Suppress("DEPRECATION")
    fun existingAccountsWithoutServerTypeRemainAudiobookshelf() = runBlocking {
        storage.save(account(ServerType.AUDIOBOOKSHELF))
        val legacy = EncryptedSharedPreferences.create(
            context, "auth_store", MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
        legacy.edit().remove("server_type").commit()
        val snapshot = auth.bootstrap()
        assertEquals(ServerType.AUDIOBOOKSHELF, snapshot.serverType)
        assertTrue(snapshot.isAuthenticated)
        assertEquals(1, abs.authorizations)
        assertEquals(0, bookOrbit.authorizations)
        assertEquals("password", storage.load().password)
    }

    @Test
    fun loginRefreshAndIpcPreserveBookOrbitSelection() = runBlocking {
        val login = auth.login(ServerType.BOOKORBIT, "https://books.example", "reader", "password")
        assertTrue(login.isAuthenticated)
        assertEquals(ServerType.BOOKORBIT, storage.load().serverType)
        assertEquals(ServerType.BOOKORBIT, AuthSnapshot.fromBundle(login.toBundle())?.serverType)
        bookOrbit.acceptToken = false
        val recovered = auth.bootstrap()
        assertTrue(recovered.isAuthenticated)
        assertEquals(ServerType.BOOKORBIT, recovered.serverType)
        assertEquals("refreshed", storage.load().accessToken)
        assertEquals(1, bookOrbit.refreshes)
        assertEquals(0, abs.refreshes)
        val logout = auth.logout()
        assertEquals(ServerType.BOOKORBIT, logout.serverType)
        assertNull(storage.load().accessToken)
        assertNull(storage.load().password)
    }

    @Test
    fun changingServerNeverReusesTheOtherServersPasswordOrRefreshToken() = runBlocking {
        storage.save(account(ServerType.AUDIOBOOKSHELF))
        val failed = auth.login(ServerType.BOOKORBIT, "https://other.example", "reader", null)
        assertFalse(failed.isAuthenticated)
        assertEquals(0, bookOrbit.logins)
        assertEquals(ServerType.AUDIOBOOKSHELF, storage.load().serverType)
        assertEquals("access", storage.load().accessToken)
    }

    @Test
    fun pendingProgressIsRetainedOfflineAndFlushedThroughTheCorrectBackend() = runBlocking {
        storage.save(account(ServerType.BOOKORBIT))
        seedPending(60_000)
        bookOrbit.offline = true
        assertFalse(progress.flushPending())
        assertEquals(60_000L, database.mediaProgressDao().getPending().single().currentTimeMs)
        assertEquals("access", storage.load().accessToken)
        bookOrbit.offline = false
        assertTrue(progress.flushPending())
        assertTrue(database.mediaProgressDao().getPending().isEmpty())
        assertEquals(60_000L, bookOrbit.upload?.currentTimeMs)
        assertEquals("1", bookOrbit.uploadedBook)
        assertNull(bookOrbit.uploadedSession)
        assertNull(abs.upload)
    }

    @Test
    fun newerServerProgressIsAcceptedBeforeLoggingOut() = runBlocking {
        storage.save(account(ServerType.BOOKORBIT))
        seedPending(60_000)
        bookOrbit.serverPosition = 100_000
        assertTrue(progress.flushPending())
        assertNull(bookOrbit.upload)
        assertEquals(100_000L, database.mediaProgressDao().getByBookId("1")?.currentTimeMs)
        assertTrue(database.mediaProgressDao().getPending().isEmpty())
    }

    @Test
    fun parallelRequestsFinishBeforeAnAccountChangeCanClearTheirData() = runBlocking {
        storage.save(account(ServerType.BOOKORBIT))
        val runner = AuthenticatedRequestRunner(storage, auth)
        val firstStarted = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val first = async { runner.execute { firstStarted.complete(Unit); release.await() } }
        val second = async { runner.execute { secondStarted.complete(Unit); release.await() } }
        withTimeout(5000) { firstStarted.await(); secondStarted.await() }
        var changed = false
        val change = async { runner.withSessionChange { changed = true; storage.clear() } }
        yield()
        assertFalse(changed)
        release.complete(Unit)
        withTimeout(5000) { first.await(); second.await(); change.await() }
        assertTrue(changed)
        assertNull(storage.load().accessToken)
    }

    @Test
    fun pendingNarrationKeepsItsEditionAndCannotBeReplacedByAudio() = runBlocking {
        storage.save(account(ServerType.BOOKORBIT))
        seedPending(60_000)
        val source = "epub:20:revision"
        val pending = requireNotNull(database.mediaProgressDao().getByBookId("1")).copy(sourceId = source)
        database.mediaProgressDao().upsert(pending)
        val otherEdition = PlaybackProgressSnapshot("1", null, 5000, 180_000, 0, false, PlaybackProgressReason.STARTED)
        assertFalse(progress.storePendingProgress(otherEdition))
        assertEquals(pending, database.mediaProgressDao().getByBookId("1"))
        assertTrue(progress.flushPending())
        assertEquals(source, bookOrbit.requestedSource)
        assertEquals(source, bookOrbit.upload?.sourceId)
    }

    @Test
    fun supersededNarrationAcceptsTheNewerServerPositionEvenWhenBehind() = runBlocking {
        storage.save(account(ServerType.BOOKORBIT))
        seedPending(60_000)
        val source = "epub:20:revision"
        database.mediaProgressDao().upsert(requireNotNull(database.mediaProgressDao().getByBookId("1")).copy(sourceId = source))
        bookOrbit.superseded = MediaProgressSummary("1", 2000, 180_000, false, false, null, null, 3000, source)
        assertTrue(progress.flushPending())
        assertTrue(database.mediaProgressDao().getPending().isEmpty())
        assertEquals(2000L, database.mediaProgressDao().getByBookId("1")?.currentTimeMs)
        assertEquals(source, database.mediaProgressDao().getByBookId("1")?.sourceId)
    }

    @Test
    fun readerMovedBackBeforeTheProgressCheckIsAcceptedWithoutAnUpload() = runBlocking {
        storage.save(account(ServerType.BOOKORBIT))
        seedPending(60_000)
        val source = "epub:20:revision"
        database.mediaProgressDao().upsert(requireNotNull(database.mediaProgressDao().getByBookId("1")).copy(sourceId = source))
        bookOrbit.serverPosition = 2000
        bookOrbit.serverChanged = true
        assertTrue(progress.flushPending())
        assertNull(bookOrbit.upload)
        assertEquals(2000L, database.mediaProgressDao().getByBookId("1")?.currentTimeMs)
    }

    private suspend fun seedPending(position: Long) {
        database.libraryDao().upsertAll(listOf(LibraryEntity("1")))
        database.bookDao().upsertAll(listOf(BookEntity("1", "1", "Book", "Book", null, null, null, 180_000, "Author", true)))
        database.mediaProgressDao().upsert(MediaProgressEntity("1", position, 180_000, false, false, 1000, null, null, true))
    }

    private fun account(type: ServerType) = StoredAuthState(type, "https://books.example", "reader", "password", "access", "refresh")

    private class RecordingServer : AudiobookServerApi by AudiobookshelfApiClient() {
        var logins = 0
        var authorizations = 0
        var refreshes = 0
        var acceptToken = true
        var offline = false
        var serverPosition: Long? = null
        var requestedSource: String? = null
        var serverChanged = false
        var superseded: MediaProgressSummary? = null
        var upload: PlaybackSessionUpdateRequest? = null
        var uploadedBook: String? = null
        var uploadedSession: String? = null
        private fun session(token: String = "access") = AuthenticatedSession("reader", token, "refresh", null, null, true)
        override suspend fun login(baseUrl: String, username: String, password: String): AuthenticatedSession {
            logins++
            return session()
        }
        override suspend fun authorize(baseUrl: String, accessToken: String, fallbackUsername: String?): AuthenticatedSession? {
            authorizations++
            return if (acceptToken) session() else null
        }
        override suspend fun refreshAccessToken(baseUrl: String, refreshToken: String, fallbackUsername: String?): AuthenticatedSession {
            refreshes++
            return session("refreshed")
        }
        override suspend fun getMediaProgress(baseUrl: String, accessToken: String, itemId: String, retryProfile: RetryProfile, sourceId: String?): MediaProgressSummary {
            requestedSource = sourceId
            if (offline) throw IOException("Offline fixture")
            val position = serverPosition ?: throw ApiException(404, "No progress")
            return MediaProgressSummary(itemId, position, 180_000, false, false, null, null, 2000, sourceId?.takeUnless { it == "audio" }, serverChanged)
        }
        override suspend fun updateProgress(baseUrl: String, accessToken: String, bookId: String, sessionId: String?, sessionUpdate: PlaybackSessionUpdateRequest, close: Boolean): String? {
            superseded?.let { throw ServerProgressSupersededException(it) }
            uploadedBook = bookId
            uploadedSession = sessionId
            upload = sessionUpdate
            return null
        }
    }
}
