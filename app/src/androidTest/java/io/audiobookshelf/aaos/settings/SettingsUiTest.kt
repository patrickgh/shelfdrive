package io.audiobookshelf.aaos.settings

import android.content.Intent
import android.view.View
import android.widget.Button
import android.widget.RadioGroup
import android.widget.TextView
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import io.audiobookshelf.aaos.R
import io.audiobookshelf.aaos.auth.AuthSnapshot
import io.audiobookshelf.aaos.auth.AuthStatus
import io.audiobookshelf.aaos.playback.PlaybackPreferences
import io.audiobookshelf.aaos.server.ServerType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsUiTest {
    @Test
    fun serverTypeSurvivesStateRefreshesAndSuccessfulLoginReturnsToReadOnlyAccount() = withSettings { activity, fragment ->
        val ready = SettingsState(commandChannelReady = true, authStateKnown = true)
        fragment.renderState(ready)
        activity.findViewById<View>(R.id.settings_account_setup).performClick()
        val selector = activity.findViewById<View>(R.id.settings_server_type)
        val value = activity.findViewById<TextView>(R.id.settings_server_type_value)
        assertEquals(ServerType.AUDIOBOOKSHELF.label, value.text.toString())
        selector.performClick()
        val choices = activity.findViewById<RadioGroup>(R.id.settings_server_choices)
        assertTrue(choices.isShown)
        choices.getChildAt(ServerType.BOOKORBIT.ordinal).performClick()
        assertFalse(choices.isShown)
        fragment.renderState(ready)
        assertEquals(ServerType.BOOKORBIT.label, value.text.toString())

        fragment.renderState(ready.copy(authSnapshot = AuthSnapshot(
            status = AuthStatus.AUTHENTICATED,
            serverType = ServerType.BOOKORBIT,
            baseUrl = "https://books.example",
            username = "reader",
            hasStoredPassword = true,
        )))
        assertFalse(selector.isShown)
        assertFalse(activity.findViewById<View>(R.id.settings_account_setup).isShown)
        assertTrue(activity.findViewById<View>(R.id.settings_account_logout).isShown)
        assertTrue(activity.findViewById<TextView>(R.id.settings_account_info).text.contains("BookOrbit"))
    }

    @Test
    fun validInputEnablesConnectWithoutSubmittingAndAnInvalidUrlHasAFieldError() = withSettings { activity, fragment ->
        fragment.renderState(SettingsState(commandChannelReady = true, authStateKnown = true))
        activity.findViewById<View>(R.id.settings_account_setup).performClick()
        val connect = activity.findViewById<Button>(R.id.settings_connect)
        val url = activity.findViewById<TextInputEditText>(R.id.settings_server_url)
        val urlField = activity.findViewById<TextInputLayout>(R.id.settings_server_url_layout)
        val username = activity.findViewById<TextInputEditText>(R.id.settings_username)
        val password = activity.findViewById<TextInputEditText>(R.id.settings_password)
        assertFalse(connect.isEnabled)
        url.setText("http://books.example")
        username.setText("reader")
        password.setText("test-password")
        assertFalse(connect.isEnabled)
        assertEquals(activity.getString(R.string.status_server_url_public_http_forbidden), urlField.error.toString())
        url.setText("https://books.example")
        assertTrue(connect.isEnabled)
        assertFalse(activity.currentState().authActionInProgress)
        assertFalse(password.isSaveEnabled)
        val visibility = activity.findViewById<View>(R.id.settings_password_visibility)
        visibility.performClick()
        assertNull(password.transformationMethod)
        visibility.performClick()
        assertNotNull(password.transformationMethod)
    }

    @Test
    fun expiredSessionAllowsReconnectWithoutEditableServerOrAccount() = withSettings { activity, fragment ->
        fragment.renderState(SettingsState(
            commandChannelReady = true,
            authStateKnown = true,
            authSnapshot = AuthSnapshot(
                status = AuthStatus.SESSION_EXPIRED,
                serverType = ServerType.BOOKORBIT,
                baseUrl = "https://books.example",
                username = "reader",
                hasStoredPassword = true,
            ),
        ))
        assertTrue(activity.findViewById<View>(R.id.settings_account_logout).isShown)
        activity.findViewById<View>(R.id.settings_account_setup).performClick()
        assertFalse(activity.findViewById<View>(R.id.settings_server_type).isShown)
        assertFalse(activity.findViewById<View>(R.id.settings_server_url).isShown)
        assertFalse(activity.findViewById<View>(R.id.settings_username).isShown)
        assertTrue(activity.findViewById<View>(R.id.settings_password).isShown)
        assertTrue(activity.findViewById<Button>(R.id.settings_connect).isEnabled)
    }

    @Test
    fun playbackControlsReadAndWriteExistingPreferences() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val skipKey = context.getString(R.string.settings_key_skip_increment)
        val progressKey = context.getString(R.string.settings_key_progress_display)
        val rewindKey = context.getString(R.string.settings_key_rewind_on_pause)
        val previousSkip = prefs.getString(skipKey, null)
        val previousProgress = prefs.getString(progressKey, null)
        val previousRewind = if (prefs.contains(rewindKey)) prefs.getBoolean(rewindKey, false) else null
        try {
            prefs.edit {
                putString(skipKey, "30")
                putString(progressKey, "chapter")
                putBoolean(rewindKey, true)
            }
            withSettings { activity, _ ->
                assertEquals(context.getString(R.string.settings_skip_increment_30), activity.findViewById<TextView>(R.id.settings_skip_value).text)
                assertEquals(R.id.settings_chapter, activity.findViewById<RadioGroup>(R.id.settings_progress).checkedRadioButtonId)
                val rewind = activity.findViewById<MaterialSwitch>(R.id.settings_rewind)
                assertTrue(rewind.isChecked)
                activity.findViewById<View>(R.id.settings_skip).performClick()
                activity.findViewById<RadioGroup>(R.id.settings_skip_choices).getChildAt(0).performClick()
                activity.findViewById<View>(R.id.settings_book).performClick()
                rewind.performClick()
                assertEquals(5L, PlaybackPreferences.skipIncrementSeconds(context))
                assertFalse(PlaybackPreferences.showChapterProgress(context))
                assertFalse(PlaybackPreferences.isRewindOnPauseEnabled(context))
            }
        } finally {
            prefs.edit {
                putString(skipKey, previousSkip)
                putString(progressKey, previousProgress)
                if (previousRewind == null) remove(rewindKey) else putBoolean(rewindKey, previousRewind)
            }
        }
    }

    private fun withSettings(block: (SettingsActivity, SettingsFragment) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as SettingsActivity
        try {
            instrumentation.runOnMainSync {
                val fragment = activity.supportFragmentManager.findFragmentById(R.id.settings_container) as SettingsFragment
                block(activity, fragment)
            }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
