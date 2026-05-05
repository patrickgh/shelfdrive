package io.audiobookshelf.aaos.diagnostics

import android.content.Context
import android.net.Uri
import android.os.Build
import io.audiobookshelf.aaos.BuildConfig
import io.audiobookshelf.aaos.auth.AuthSnapshot
import io.audiobookshelf.aaos.cache.CacheSnapshot
import io.audiobookshelf.aaos.sync.SyncSnapshot
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DiagnosticsPackageBuilder(
    private val context: Context,
    private val eventLogger: DiagnosticEventLogger = DiagnosticEventLogger(context),
) {
    fun build(
        authSnapshot: AuthSnapshot,
        syncSnapshot: SyncSnapshot,
        cacheSnapshot: CacheSnapshot,
        startupSnapshot: StartupDiagnosticsSnapshot,
        uploadSnapshot: DiagnosticsUploadSnapshot,
    ): File {
        val outputDir = File(context.cacheDir, DIRECTORY_NAME).apply { mkdirs() }
        val outputFile = File(outputDir, "shelfdrive-diagnostics-${System.currentTimeMillis()}.zip")

        ZipOutputStream(outputFile.outputStream().buffered()).use { zip ->
            zip.writeEntry("manifest.json", buildManifestJson())
            zip.writeEntry("state.json", buildStateJson(authSnapshot, syncSnapshot, cacheSnapshot, startupSnapshot, uploadSnapshot))
            zip.writeEntry("events.log", eventLogger.readLogText())
        }

        return outputFile
    }

    private fun buildManifestJson(): String {
        return JSONObject().apply {
            put("createdAt", System.currentTimeMillis())
            put("appVersionName", BuildConfig.VERSION_NAME)
            put("appVersionCode", BuildConfig.VERSION_CODE)
            put("buildType", BuildConfig.BUILD_TYPE)
            put("packageName", BuildConfig.APPLICATION_ID)
            put("androidSdk", Build.VERSION.SDK_INT)
            put("deviceManufacturer", Build.MANUFACTURER)
            put("deviceBrand", Build.BRAND)
            put("deviceModel", Build.MODEL)
            put("deviceProduct", Build.PRODUCT)
        }.toString(2)
    }

    private fun buildStateJson(
        authSnapshot: AuthSnapshot,
        syncSnapshot: SyncSnapshot,
        cacheSnapshot: CacheSnapshot,
        startupSnapshot: StartupDiagnosticsSnapshot,
        uploadSnapshot: DiagnosticsUploadSnapshot,
    ): String {
        return JSONObject().apply {
            put(
                "auth",
                JSONObject().apply {
                    put("status", authSnapshot.status.name)
                    put("baseUrl", sanitizeBaseUrl(authSnapshot.baseUrl))
                    put("hasUsername", !authSnapshot.username.isNullOrBlank())
                    put("serverVersion", authSnapshot.serverVersion)
                    put("statusMessage", authSnapshot.statusMessage)
                    put("hasStoredPassword", authSnapshot.hasStoredPassword)
                },
            )
            put(
                "sync",
                JSONObject().apply {
                    put("status", syncSnapshot.status.name)
                    put("libraryCount", syncSnapshot.libraryCount)
                    put("bookCount", syncSnapshot.bookCount)
                    put("authorCount", syncSnapshot.authorCount)
                    put("lastSyncedAt", syncSnapshot.lastSyncedAt)
                    put("serverVersion", syncSnapshot.serverVersion)
                    put("message", syncSnapshot.message)
                },
            )
            put(
                "cache",
                JSONObject().apply {
                    put("totalBytes", cacheSnapshot.totalBytes)
                    put("catalogBytes", cacheSnapshot.catalogBytes)
                    put("artworkBytes", cacheSnapshot.artworkBytes)
                    put("fileCount", cacheSnapshot.fileCount)
                    put("clearedAt", cacheSnapshot.clearedAt)
                },
            )
            put(
                "startup",
                JSONObject().apply {
                    put("lastServiceStartedAt", startupSnapshot.lastServiceStartedAt)
                    put("lastRestoreStartedAt", startupSnapshot.lastRestoreStartedAt)
                    put("lastRestoreFinishedAt", startupSnapshot.lastRestoreFinishedAt)
                    put("lastRestoreStatus", startupSnapshot.lastRestoreStatus?.name)
                    put("hasLastRestoreBookId", !startupSnapshot.lastRestoreBookId.isNullOrBlank())
                    put("lastRestoreMessage", startupSnapshot.lastRestoreMessage)
                },
            )
            put(
                "upload",
                JSONObject().apply {
                    put("hasUploadUrl", uploadSnapshot.uploadUrl.isNotBlank())
                    put("lastUploadStartedAt", uploadSnapshot.lastUploadStartedAt)
                    put("lastUploadFinishedAt", uploadSnapshot.lastUploadFinishedAt)
                    put("lastUploadStatus", uploadSnapshot.lastUploadStatus?.name)
                    put("lastUploadMessage", uploadSnapshot.lastUploadMessage)
                },
            )
        }.toString(2)
    }

    private fun ZipOutputStream.writeEntry(name: String, content: String) {
        putNextEntry(ZipEntry(name))
        write(content.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    private fun sanitizeBaseUrl(baseUrl: String?): String? {
        val uri = Uri.parse(baseUrl ?: return null)
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
        val host = uri.host ?: return null
        val port = uri.port.takeIf { it != -1 }?.let { ":$it" }.orEmpty()
        return "$scheme://$host$port"
    }

    companion object {
        private const val DIRECTORY_NAME = "diagnostics-packages"
    }
}
