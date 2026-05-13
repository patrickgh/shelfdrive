package io.audiobookshelf.aaos.diagnostics

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.Build
import io.audiobookshelf.aaos.BuildConfig
import io.audiobookshelf.aaos.auth.AuthSnapshot
import io.audiobookshelf.aaos.cache.CacheSnapshot
import io.audiobookshelf.aaos.sync.SyncSnapshot
import org.json.JSONArray
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
            zip.writeEntry("environment.json", buildEnvironmentJson())
            zip.writeEntry("events.log", eventLogger.readLogText())
            zip.writeEntry("events.previous.log", eventLogger.readPreviousLogText())
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

    private fun buildEnvironmentJson(): String {
        val packageManager = context.packageManager
        return JSONObject().apply {
            put(
                "packages",
                JSONArray().apply {
                    DIAGNOSTIC_PACKAGES.forEach { packageName ->
                        put(packageManager.describePackage(packageName))
                    }
                },
            )
            put(
                "mediaHostIntents",
                JSONArray().apply {
                    put(packageManager.describeIntent(ACTION_MEDIA_TEMPLATE_V2, EXTRA_MEDIA_COMPONENT, EXTRA_MEDIA_ID))
                    put(packageManager.describeIntent(ACTION_MEDIA_TEMPLATE, EXTRA_MEDIA_COMPONENT, EXTRA_LEGACY_MEDIA_ID))
                },
            )
        }.toString(2)
    }

    private fun PackageManager.describePackage(packageName: String): JSONObject {
        val packageInfo = getPackageInfoCompat(packageName)
        val applicationInfo = packageInfo?.applicationInfo
        return JSONObject().apply {
            put("packageName", packageName)
            put("installed", packageInfo != null)
            put("versionName", packageInfo?.versionName)
            put("versionCode", packageInfo?.longVersionCodeCompat())
            put("enabled", applicationInfo?.enabled)
            put("systemApp", applicationInfo?.isSystemApp())
            put("updatedSystemApp", applicationInfo?.isUpdatedSystemApp())
        }
    }

    private fun PackageManager.describeIntent(
        action: String,
        componentExtra: String,
        mediaIdExtra: String,
    ): JSONObject {
        val mediaComponent = "${BuildConfig.APPLICATION_ID}/io.audiobookshelf.aaos.media3.ShelfDriveMediaLibraryService"
        val intent = Intent(action)
            .putExtra(componentExtra, mediaComponent)
            .putExtra(mediaIdExtra, ROOT_MEDIA_ID)
        val defaultMatches = queryIntentActivitiesCompat(intent, PackageManager.MATCH_DEFAULT_ONLY)
        val systemMatches = queryIntentActivitiesCompat(
            intent,
            PackageManager.MATCH_DEFAULT_ONLY or PackageManager.MATCH_SYSTEM_ONLY,
        )
        return JSONObject().apply {
            put("action", action)
            put("defaultMatchCount", defaultMatches.size)
            put("systemMatchCount", systemMatches.size)
            put("defaultMatches", defaultMatches.toJson())
            put("systemMatches", systemMatches.toJson())
        }
    }

    private fun PackageManager.getPackageInfoCompat(packageName: String): PackageInfo? {
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                getPackageInfo(packageName, 0)
            }
        }.getOrNull()
    }

    private fun PackageManager.queryIntentActivitiesCompat(intent: Intent, flags: Int): List<ResolveInfo> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            queryIntentActivities(intent, flags)
        }
    }

    private fun List<ResolveInfo>.toJson(): JSONArray {
        return JSONArray().apply {
            this@toJson.forEach { resolveInfo ->
                val activityInfo = resolveInfo.activityInfo
                put(
                    JSONObject().apply {
                        put("packageName", activityInfo?.packageName)
                        put("name", activityInfo?.name)
                        put("exported", activityInfo?.exported)
                    },
                )
            }
        }
    }

    private fun PackageInfo.longVersionCodeCompat(): Long {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            longVersionCode
        } else {
            @Suppress("DEPRECATION")
            versionCode.toLong()
        }
    }

    private fun ApplicationInfo.isSystemApp(): Boolean = flags and ApplicationInfo.FLAG_SYSTEM != 0

    private fun ApplicationInfo.isUpdatedSystemApp(): Boolean = flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0

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
        private const val ACTION_MEDIA_TEMPLATE = "android.car.intent.action.MEDIA_TEMPLATE"
        private const val ACTION_MEDIA_TEMPLATE_V2 = "androidx.car.app.mediaextensions.action.MEDIA_TEMPLATE_V2"
        private const val EXTRA_MEDIA_COMPONENT = "android.car.intent.extra.MEDIA_COMPONENT"
        private const val EXTRA_MEDIA_ID = "androidx.car.app.mediaextensions.extra.KEY_MEDIA_ID"
        private const val EXTRA_LEGACY_MEDIA_ID = "com.android.car.media.intent.extra.MEDIA_ID"
        private const val ROOT_MEDIA_ID = "root"
        private val DIAGNOSTIC_PACKAGES = listOf(
            "com.android.car.media",
            "com.google.android.apps.automotive.templates.host",
        )
    }
}
