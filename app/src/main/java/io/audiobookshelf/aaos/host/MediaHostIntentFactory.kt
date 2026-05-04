package io.audiobookshelf.aaos.host

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.app.PendingIntent
import io.audiobookshelf.aaos.media3.ShelfDriveMediaLibraryService
import io.audiobookshelf.aaos.settings.SettingsActivity

object MediaHostIntentFactory {
    fun createMediaHostIntent(context: Context): Intent? {
        val mediaComponent = ComponentName(context, ShelfDriveMediaLibraryService::class.java).flattenToString()

        val v2Intent = Intent(ACTION_MEDIA_TEMPLATE_V2)
            .putExtra(EXTRA_MEDIA_COMPONENT, mediaComponent)
            .putExtra(EXTRA_MEDIA_ID, ROOT_MEDIA_ID)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (context.canResolveSystemActivity(v2Intent)) {
            return v2Intent
        }

        val legacyIntent = Intent(ACTION_MEDIA_TEMPLATE)
            .putExtra(EXTRA_MEDIA_COMPONENT, mediaComponent)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (context.canResolveSystemActivity(legacyIntent) || context.canResolveAnyActivity(legacyIntent)) {
            return legacyIntent
        }

        return null
    }

    fun createMediaHostPendingIntent(context: Context): PendingIntent {
        val intent = createMediaHostIntent(context)
            ?: createSettingsIntent(context)
        return PendingIntent.getActivity(
            context,
            REQUEST_MEDIA_HOST,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    fun createSettingsPendingIntent(context: Context): PendingIntent {
        return PendingIntent.getActivity(
            context,
            REQUEST_SETTINGS,
            createSettingsIntent(context),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun createSettingsIntent(context: Context): Intent {
        return Intent(Intent.ACTION_APPLICATION_PREFERENCES)
            .setClass(context, SettingsActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun Context.canResolveSystemActivity(intent: Intent): Boolean {
        return packageManager.queryIntentActivities(
            intent,
            PackageManager.MATCH_DEFAULT_ONLY or PackageManager.MATCH_SYSTEM_ONLY,
        ).isNotEmpty()
    }

    private fun Context.canResolveAnyActivity(intent: Intent): Boolean {
        return packageManager.queryIntentActivities(
            intent,
            PackageManager.MATCH_DEFAULT_ONLY,
        ).isNotEmpty()
    }

    private const val ACTION_MEDIA_TEMPLATE = "android.car.intent.action.MEDIA_TEMPLATE"
    private const val ACTION_MEDIA_TEMPLATE_V2 = "androidx.car.app.mediaextensions.action.MEDIA_TEMPLATE_V2"
    private const val EXTRA_MEDIA_COMPONENT = "android.car.intent.extra.MEDIA_COMPONENT"
    private const val EXTRA_MEDIA_ID = "androidx.car.app.mediaextensions.extra.KEY_MEDIA_ID"
    private const val ROOT_MEDIA_ID = "root"
    private const val REQUEST_MEDIA_HOST = 1001
    private const val REQUEST_SETTINGS = 1002
}
