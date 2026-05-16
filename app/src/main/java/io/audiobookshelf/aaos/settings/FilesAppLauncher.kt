package io.audiobookshelf.aaos.settings

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.util.Log

internal class FilesAppLauncher(
    private val activity: Activity,
) {
    fun launch(): FilesAppLaunchResult {
        val errors = mutableListOf<String>()
        for (candidate in CANDIDATES) {
            val resolvedIntent = candidate.launchIntent(errors) ?: candidate.explicitIntent(errors)
            if (resolvedIntent == null) {
                continue
            }

            runCatching {
                activity.startActivity(resolvedIntent.intent)
            }.onSuccess {
                val launchedCandidate = resolvedIntent.candidate
                Log.i(TAG, "Launched Files app with ${launchedCandidate.packageName}/${launchedCandidate.activityClassName}.")
                return FilesAppLaunchResult.Launched(launchedCandidate, errors)
            }.onFailure { exception ->
                if (exception is ActivityNotFoundException) {
                    Log.i(TAG, "Files activity not found: ${candidate.packageName}/${candidate.activityClassName}.")
                } else {
                    val message = exception.message ?: exception::class.java.simpleName
                    errors += "startActivity failed for ${candidate.packageName}/${candidate.activityClassName}: $message"
                    Log.w(TAG, "Failed to launch Files activity.", exception)
                }
            }
        }

        return FilesAppLaunchResult.Failed(CANDIDATES.toList(), errors)
    }

    private fun FilesAppLaunchCandidate.launchIntent(errors: MutableList<String>): ResolvedFilesIntent? {
        return try {
            val intent = activity.packageManager.getLaunchIntentForPackage(packageName)
            val component = intent?.component
            if (intent != null && component != null) {
                val resolvedCandidate = copy(activityClassName = component.className)
                Log.i(
                    TAG,
                    "PackageManager returned Files launch component " +
                        "${resolvedCandidate.packageName}/${resolvedCandidate.activityClassName}.",
                )
                ResolvedFilesIntent(intent, resolvedCandidate)
            } else {
                null
            }
        } catch (exception: Exception) {
            val message = exception.message ?: exception::class.java.simpleName
            errors += "getLaunchIntentForPackage failed for $packageName: $message"
            Log.w(TAG, "Failed to resolve Files launch intent for $packageName.", exception)
            null
        }
    }

    private fun FilesAppLaunchCandidate.explicitIntent(errors: MutableList<String>): ResolvedFilesIntent? {
        return try {
            ResolvedFilesIntent(
                intent = Intent()
                    .setClassName(packageName, activityClassName)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                candidate = this,
            )
        } catch (exception: Exception) {
            val message = exception.message ?: exception::class.java.simpleName
            errors += "Explicit intent failed for $packageName/$activityClassName: $message"
            Log.w(TAG, "Failed to build explicit Files intent.", exception)
            null
        }
    }

    private companion object {
        private const val TAG = "FilesAppLauncher"

        private val CANDIDATES = arrayOf(
            FilesAppLaunchCandidate(
                packageName = "com.android.documentsui",
                activityClassName = "com.android.documentsui.files.FilesActivity",
            ),
            FilesAppLaunchCandidate(
                packageName = "com.android.documentsui",
                activityClassName = "com.android.documentsui.LauncherActivity",
            ),
            FilesAppLaunchCandidate(
                packageName = "com.google.android.documentsui",
                activityClassName = "com.android.documentsui.LauncherActivity",
            ),
        )
    }
}

private data class ResolvedFilesIntent(
    val intent: Intent,
    val candidate: FilesAppLaunchCandidate,
)

internal data class FilesAppLaunchCandidate(
    val packageName: String,
    val activityClassName: String,
)

internal sealed class FilesAppLaunchResult {
    data class Launched(
        val candidate: FilesAppLaunchCandidate,
        val warnings: List<String>,
    ) : FilesAppLaunchResult()

    data class Failed(
        val candidates: List<FilesAppLaunchCandidate>,
        val errors: List<String>,
    ) : FilesAppLaunchResult()
}
