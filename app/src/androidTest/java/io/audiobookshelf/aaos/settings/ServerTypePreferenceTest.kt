package io.audiobookshelf.aaos.settings

import android.content.Intent
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.preference.ListPreference
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.audiobookshelf.aaos.R
import io.audiobookshelf.aaos.auth.AuthSnapshot
import io.audiobookshelf.aaos.auth.AuthStatus
import io.audiobookshelf.aaos.auth.AuthStorage
import io.audiobookshelf.aaos.server.ServerType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ServerTypePreferenceTest {
    @Test
    fun selectingBookOrbitInTheDialogSurvivesLoggedOutStateRefreshes() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        AuthStorage(context).clear()
        val activity = instrumentation.startActivitySync(
            Intent(context, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        ) as SettingsActivity
        try {
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                val fragment = activity.supportFragmentManager.findFragmentById(R.id.settings_container) as SettingsFragment
                val preference = requireNotNull(fragment.findPreference<ListPreference>("server_type"))
                fragment.renderState(SettingsState())
                assertEquals("AUDIOBOOKSHELF", preference.value)
                assertTrue(preference.isEnabled)
                fragment.onDisplayPreferenceDialog(preference)
                activity.supportFragmentManager.executePendingTransactions()
                val dialog = activity.supportFragmentManager.fragments.filterIsInstance<DialogFragment>().single().dialog as AlertDialog
                val index = preference.entryValues.indexOf("BOOKORBIT")
                assertTrue(index >= 0)
                dialog.listView.performItemClick(dialog.listView.getChildAt(index), index, index.toLong())
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                val fragment = activity.supportFragmentManager.findFragmentById(R.id.settings_container) as SettingsFragment
                val preference = requireNotNull(fragment.findPreference<ListPreference>("server_type"))
                fragment.renderState(SettingsState())
                assertEquals("BOOKORBIT", preference.value)
                assertEquals("BookOrbit", preference.summary.toString())
                fragment.renderState(SettingsState(authSnapshot = AuthSnapshot(
                    status = AuthStatus.AUTHENTICATED, serverType = ServerType.BOOKORBIT,
                    baseUrl = "https://books.example", username = "reader", hasStoredPassword = true,
                )))
                assertFalse(preference.isEnabled)
                assertEquals("BOOKORBIT", preference.value)
            }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            AuthStorage(context).clear()
        }
    }
}
