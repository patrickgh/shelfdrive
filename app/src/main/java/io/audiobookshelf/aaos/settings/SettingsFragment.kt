package io.audiobookshelf.aaos.settings

import android.content.res.ColorStateList
import android.os.Bundle
import android.text.format.Formatter
import android.text.InputType
import android.util.TypedValue
import android.view.View
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceGroup
import androidx.core.content.ContextCompat
import io.audiobookshelf.aaos.R
import io.audiobookshelf.aaos.auth.AuthSnapshot
import io.audiobookshelf.aaos.auth.AuthStatus
import io.audiobookshelf.aaos.cache.CacheSnapshot
import io.audiobookshelf.aaos.sync.SyncSnapshot
import io.audiobookshelf.aaos.sync.SyncStatus
import java.text.DateFormat
import java.util.Date

class SettingsFragment : PreferenceFragmentCompat() {
    private var serverUrlInput: String = ""
    private var usernameInput: String = ""
    private var passwordInput: String = ""
    private var currentAuthSnapshot: AuthSnapshot = AuthSnapshot(status = AuthStatus.LOGGED_OUT)
    private var currentSyncSnapshot: SyncSnapshot = SyncSnapshot(status = SyncStatus.IDLE)
    private var currentCacheSnapshot: CacheSnapshot = CacheSnapshot()
    private var isCommandChannelReady: Boolean = false
    private var isLoginInProgress: Boolean = false

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences, rootKey)
        restoreDrafts(savedInstanceState)
        applyAutomotivePreferenceLayouts()
        configureEditablePreferences()
        configureActions()
        renderState(
            commandChannelReady = (activity as? SettingsActivity)?.isCommandChannelReady() == true,
            authSnapshot = (activity as? SettingsActivity)?.currentAuthSnapshot()
                ?: AuthSnapshot(status = AuthStatus.LOGGED_OUT),
            syncSnapshot = (activity as? SettingsActivity)?.currentSyncSnapshot()
                ?: SyncSnapshot(status = SyncStatus.IDLE),
            cacheSnapshot = (activity as? SettingsActivity)?.currentCacheSnapshot()
                ?: CacheSnapshot(),
            loginInProgress = (activity as? SettingsActivity)?.isLoginInProgress() == true,
        )
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val background = ContextCompat.getColor(requireContext(), R.color.abs_background)
        view.setBackgroundColor(background)
        listView.setBackgroundColor(background)
        listView.clipToPadding = false
        listView.setPaddingRelative(
            resources.getDimensionPixelSize(R.dimen.settings_list_horizontal_padding),
            resources.getDimensionPixelSize(R.dimen.settings_list_vertical_padding),
            resources.getDimensionPixelSize(R.dimen.settings_list_horizontal_padding),
            resources.getDimensionPixelSize(R.dimen.settings_list_vertical_padding),
        )
    }

    override fun onResume() {
        super.onResume()
        renderState(
            commandChannelReady = (activity as? SettingsActivity)?.isCommandChannelReady() == true,
            authSnapshot = (activity as? SettingsActivity)?.currentAuthSnapshot()
                ?: AuthSnapshot(status = AuthStatus.LOGGED_OUT),
            syncSnapshot = (activity as? SettingsActivity)?.currentSyncSnapshot()
                ?: SyncSnapshot(status = SyncStatus.IDLE),
            cacheSnapshot = (activity as? SettingsActivity)?.currentCacheSnapshot()
                ?: CacheSnapshot(),
            loginInProgress = (activity as? SettingsActivity)?.isLoginInProgress() == true,
        )
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_SERVER_URL, serverUrlInput)
        outState.putString(STATE_USERNAME, usernameInput)
    }

    fun renderState(
        commandChannelReady: Boolean,
        authSnapshot: AuthSnapshot,
        syncSnapshot: SyncSnapshot,
        cacheSnapshot: CacheSnapshot,
        loginInProgress: Boolean,
    ) {
        isCommandChannelReady = commandChannelReady
        isLoginInProgress = loginInProgress
        currentAuthSnapshot = authSnapshot
        currentSyncSnapshot = syncSnapshot
        currentCacheSnapshot = cacheSnapshot

        if (!authSnapshot.baseUrl.isNullOrBlank()) {
            serverUrlInput = authSnapshot.baseUrl
        }
        if (!authSnapshot.username.isNullOrBlank()) {
            usernameInput = authSnapshot.username
        }
        if (authSnapshot.status == AuthStatus.AUTHENTICATED || authSnapshot.status == AuthStatus.LOGGED_OUT) {
            passwordInput = ""
        }

        renderFieldState()
        renderActionAvailability()
    }

    private fun restoreDrafts(savedInstanceState: Bundle?) {
        serverUrlInput = savedInstanceState?.getString(STATE_SERVER_URL).orEmpty()
        usernameInput = savedInstanceState?.getString(STATE_USERNAME).orEmpty()
        passwordInput = ""
    }

    private fun applyAutomotivePreferenceLayouts() {
        preferenceScreen?.let(::applyAutomotivePreferenceLayout)
    }

    private fun applyAutomotivePreferenceLayout(preference: Preference) {
        preference.layoutResource = if (preference is PreferenceCategory) {
            R.layout.preference_category_settings
        } else {
            R.layout.preference_item_settings
        }

        if (preference is PreferenceGroup) {
            for (index in 0 until preference.preferenceCount) {
                applyAutomotivePreferenceLayout(preference.getPreference(index))
            }
        }
    }

    private fun configureEditablePreferences() {
        findPreference<EditTextPreference>(KEY_SERVER_URL)?.apply {
            text = serverUrlInput
            setOnBindEditTextListener { editText ->
                editText.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                styleEditText(editText)
            }
            setOnPreferenceChangeListener { preference, newValue ->
                serverUrlInput = (newValue as? String).orEmpty().trim()
                (preference as EditTextPreference).text = serverUrlInput
                renderFieldState()
                renderActionAvailability()
                maybeStartLoginAfterRequiredFieldsChanged()
                true
            }
        }

        findPreference<EditTextPreference>(KEY_USERNAME)?.apply {
            text = usernameInput
            setOnBindEditTextListener { editText ->
                editText.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_NORMAL
                styleEditText(editText)
            }
            setOnPreferenceChangeListener { preference, newValue ->
                usernameInput = (newValue as? String).orEmpty().trim()
                (preference as EditTextPreference).text = usernameInput
                renderFieldState()
                renderActionAvailability()
                maybeStartLoginAfterRequiredFieldsChanged()
                true
            }
        }

        findPreference<EditTextPreference>(KEY_PASSWORD)?.apply {
            text = passwordInput
            setOnBindEditTextListener { editText ->
                editText.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                styleEditText(editText)
            }
            setOnPreferenceChangeListener { preference, newValue ->
                passwordInput = (newValue as? String).orEmpty()
                (preference as EditTextPreference).text = passwordInput
                renderFieldState()
                renderActionAvailability()
                maybeStartLoginAfterRequiredFieldsChanged()
                true
            }
        }
    }

    private fun configureActions() {
        findPreference<Preference>(KEY_ACCOUNT_ACTION)?.setOnPreferenceClickListener {
            if (currentAuthSnapshot.status == AuthStatus.AUTHENTICATED) {
                (activity as? SettingsActivity)?.performLogout()
            } else {
                startLogin()
            }
            true
        }

        findPreference<Preference>(KEY_RESYNC_ACTION)?.setOnPreferenceClickListener {
            (activity as? SettingsActivity)?.performResync()
            true
        }

        findPreference<Preference>(KEY_CLEAR_CACHE_ACTION)?.setOnPreferenceClickListener {
            (activity as? SettingsActivity)?.performClearCache()
            true
        }
    }

    private fun renderFieldState() {
        findPreference<Preference>(KEY_AUTH_STATUS)?.summary = buildAuthStatusSummary()
        findPreference<Preference>(KEY_SERVER_URL)?.summary =
            serverUrlInput.ifBlank { getString(R.string.settings_not_configured) }
        findPreference<Preference>(KEY_USERNAME)?.summary =
            usernameInput.ifBlank { getString(R.string.settings_not_configured) }
        findPreference<Preference>(KEY_PASSWORD)?.summary = when {
            passwordInput.isNotBlank() -> getString(R.string.settings_password_summary_saved)
            currentAuthSnapshot.hasStoredPassword -> getString(R.string.settings_password_summary_saved)
            else -> getString(R.string.settings_password_summary_missing)
        }
        findPreference<Preference>(KEY_LIBRARY_COUNT)?.summary = buildCatalogSummary()
        findPreference<Preference>(KEY_RESYNC_ACTION)?.summary = buildSyncSummary()
        findPreference<Preference>(KEY_CLEAR_CACHE_ACTION)?.summary = buildCacheSummary()
    }

    private fun renderActionAvailability() {
        findPreference<Preference>(KEY_ACCOUNT_ACTION)?.apply {
            val isLoggedIn = currentAuthSnapshot.status == AuthStatus.AUTHENTICATED
            when {
                isLoginInProgress -> {
                    title = getString(R.string.settings_login_in_progress_title)
                    summary = getString(R.string.settings_login_in_progress_summary)
                    isEnabled = false
                }
                isLoggedIn -> {
                    title = getString(R.string.settings_logout_title)
                    summary = getString(R.string.settings_logout_summary)
                    isEnabled = isCommandChannelReady
                }
                else -> {
                    title = getString(R.string.settings_login_title)
                    summary = getString(R.string.settings_login_summary)
                    isEnabled = canStartLogin(allowStoredPassword = true)
                }
            }
        }

        findPreference<Preference>(KEY_RESYNC_ACTION)?.isEnabled =
            isCommandChannelReady && currentAuthSnapshot.status == AuthStatus.AUTHENTICATED

        findPreference<Preference>(KEY_CLEAR_CACHE_ACTION)?.isEnabled = isCommandChannelReady
    }

    private fun maybeStartLoginAfterRequiredFieldsChanged() {
        if (canStartLogin(allowStoredPassword = false)) {
            startLogin()
        }
    }

    private fun canStartLogin(allowStoredPassword: Boolean): Boolean {
        val passwordAvailable = passwordInput.isNotBlank() ||
            (allowStoredPassword && currentAuthSnapshot.hasStoredPassword)
        return isCommandChannelReady &&
            !isLoginInProgress &&
            currentAuthSnapshot.status != AuthStatus.AUTHENTICATED &&
            serverUrlInput.isNotBlank() &&
            usernameInput.isNotBlank() &&
            passwordAvailable
    }

    private fun startLogin() {
        if (!canStartLogin(allowStoredPassword = true)) {
            return
        }
        (activity as? SettingsActivity)?.performLogin(
            serverUrl = serverUrlInput,
            username = usernameInput,
            password = passwordInput,
        )
        passwordInput = ""
        findPreference<EditTextPreference>(KEY_PASSWORD)?.text = ""
        renderFieldState()
        renderActionAvailability()
    }

    private fun buildAuthStatusSummary(): String {
        val base = when (currentAuthSnapshot.status) {
            AuthStatus.AUTHENTICATED -> getString(R.string.settings_auth_status_authenticated)
            AuthStatus.SESSION_EXPIRED -> getString(R.string.settings_auth_status_session_expired)
            AuthStatus.LOGIN_FAILED -> getString(R.string.settings_auth_status_login_failed)
            AuthStatus.LOGGED_OUT -> getString(R.string.settings_auth_status_logged_out)
        }
        val parts = mutableListOf(base)
        currentAuthSnapshot.statusMessage
            ?.takeIf { it.isNotBlank() }
            ?.let { parts += localizeStatusMessage(it) }
        currentAuthSnapshot.serverVersion?.takeIf { it.isNotBlank() }?.let { version ->
            parts += getString(R.string.settings_server_version_summary, version)
        }
        return parts.joinToString("\n")
    }

    private fun buildSyncSummary(): String {
        val base = when (currentSyncSnapshot.status) {
            SyncStatus.IDLE -> getString(R.string.settings_resync_summary_idle)
            SyncStatus.RUNNING -> getString(R.string.settings_resync_summary_running)
            SyncStatus.SUCCESS -> getString(R.string.settings_resync_summary_success)
            SyncStatus.FAILED -> getString(R.string.settings_resync_summary_failed)
        }

        val parts = mutableListOf(base)
        if (currentSyncSnapshot.status == SyncStatus.FAILED) {
            currentSyncSnapshot.message
                ?.takeIf { it.isNotBlank() }
                ?.let { parts += localizeStatusMessage(it) }
        }
        return parts.joinToString("\n")
    }

    private fun buildCatalogSummary(): String {
        val hasCounts = currentSyncSnapshot.libraryCount > 0 ||
            currentSyncSnapshot.bookCount > 0 ||
            currentSyncSnapshot.authorCount > 0 ||
            currentSyncSnapshot.status == SyncStatus.SUCCESS
        val parts = mutableListOf<String>()
        if (hasCounts) {
            parts += getString(
                R.string.settings_catalog_counts,
                currentSyncSnapshot.libraryCount,
                currentSyncSnapshot.bookCount,
                currentSyncSnapshot.authorCount,
            )
        } else {
            parts += getString(R.string.settings_library_count_unknown)
        }

        currentSyncSnapshot.lastSyncedAt?.let { timestamp ->
            parts += getString(R.string.settings_catalog_last_updated, formatSyncTimestamp(timestamp))
            parts += getString(R.string.settings_catalog_age, formatCacheAge(timestamp))
        }
        if (currentSyncSnapshot.status == SyncStatus.FAILED && currentSyncSnapshot.lastSyncedAt != null) {
            parts += getString(R.string.settings_catalog_stale_after_failed_sync)
        }
        return parts.joinToString("\n")
    }

    private fun localizeStatusMessage(message: String): String {
        Regex("""Serverversion '(.+)' konnte nicht sauber ausgewertet werden\.""")
            .matchEntire(message)
            ?.let { return getString(R.string.status_server_version_unparseable, it.groupValues[1]) }
        Regex("""Audiobookshelf (.+) wird in v1 nicht unterstützt\. Benötigt wird mindestens 2\.31\.0\.""")
            .matchEntire(message)
            ?.let { return getString(R.string.status_server_version_unsupported, it.groupValues[1]) }

        return when (message) {
            "Server momentan nicht erreichbar. Inhalte können veraltet sein." ->
                getString(R.string.status_server_unreachable)
            "Server nicht erreichbar. Der letzte Katalogstand bleibt verfügbar." ->
                getString(R.string.status_catalog_sync_failed)
            "Sitzung abgelaufen. Bitte erneut anmelden." ->
                getString(R.string.status_session_expired)
            "Serverversion konnte nicht verifiziert werden." ->
                getString(R.string.status_server_version_unknown)
            "URL, Benutzername und Passwort werden benötigt." ->
                getString(R.string.status_login_fields_required)
            "Login-Antwort enthält kein Zugriffstoken.",
            "Login-Antwort enthaelt kein Zugriffstoken." ->
                getString(R.string.status_login_missing_access_token)
            "Refresh-Antwort enthaelt kein Zugriffstoken." ->
                getString(R.string.status_refresh_missing_access_token)
            "Server nicht erreichbar oder Antwort ungueltig." ->
                getString(R.string.status_server_unreachable_or_invalid)
            "Server-URL ist ungueltig." ->
                getString(R.string.status_server_url_invalid)
            "Server-URL muss mit https:// oder http:// beginnen." ->
                getString(R.string.status_server_url_scheme_required)
            "Server-URL enthaelt keinen Host." ->
                getString(R.string.status_server_url_host_required)
            "Server-URL darf keine Zugangsdaten enthalten." ->
                getString(R.string.status_server_url_credentials_forbidden)
            "Server-URL darf keine Query-Parameter oder Fragmente enthalten." ->
                getString(R.string.status_server_url_query_forbidden)
            "HTTP ist nur fuer lokale oder private Server erlaubt. Fuer oeffentliche Server bitte HTTPS verwenden." ->
                getString(R.string.status_server_url_public_http_forbidden)
            else -> message
        }
    }

    private fun buildCacheSummary(): String {
        val parts = mutableListOf(
            getString(
                R.string.settings_clear_cache_summary_usage,
                Formatter.formatShortFileSize(requireContext(), currentCacheSnapshot.totalBytes),
            ),
            getString(R.string.settings_clear_cache_summary_scope),
        )
        currentCacheSnapshot.clearedAt?.let { timestamp ->
            parts += getString(R.string.settings_clear_cache_summary_cleared, formatSyncTimestamp(timestamp))
        }
        return parts.joinToString("\n")
    }

    private fun formatSyncTimestamp(timestamp: Long): String {
        return DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
            .format(Date(timestamp))
    }

    private fun formatCacheAge(timestamp: Long): String {
        val ageMs = (System.currentTimeMillis() - timestamp).coerceAtLeast(0L)
        val minutes = ageMs / 60_000L
        val hours = minutes / 60L
        val days = hours / 24L
        return when {
            minutes < 1L -> getString(R.string.settings_cache_age_now)
            minutes < 60L -> getString(R.string.settings_cache_age_minutes, minutes)
            hours < 24L -> getString(R.string.settings_cache_age_hours, hours)
            else -> getString(R.string.settings_cache_age_days, days)
        }
    }

    private fun styleEditText(editText: android.widget.EditText) {
        editText.setTextColor(ContextCompat.getColor(requireContext(), R.color.abs_on_surface))
        editText.setHintTextColor(ContextCompat.getColor(requireContext(), R.color.abs_on_surface_variant))
        editText.setTextSize(
            TypedValue.COMPLEX_UNIT_PX,
            resources.getDimension(R.dimen.settings_preference_title_size),
        )
        editText.minHeight = resources.getDimensionPixelSize(R.dimen.settings_preference_min_height)
        editText.setPadding(
            editText.paddingLeft,
            resources.getDimensionPixelSize(R.dimen.settings_preference_padding_vertical),
            editText.paddingRight,
            resources.getDimensionPixelSize(R.dimen.settings_preference_padding_vertical),
        )
        editText.backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(requireContext(), R.color.abs_accent),
        )
    }

    companion object {
        private const val KEY_AUTH_STATUS = "auth_status"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_USERNAME = "username"
        private const val KEY_PASSWORD = "password"
        private const val KEY_LIBRARY_COUNT = "library_count"
        private const val KEY_ACCOUNT_ACTION = "account_action"
        private const val KEY_RESYNC_ACTION = "resync_action"
        private const val KEY_CLEAR_CACHE_ACTION = "clear_cache_action"

        private const val STATE_SERVER_URL = "state_server_url"
        private const val STATE_USERNAME = "state_username"
    }
}
