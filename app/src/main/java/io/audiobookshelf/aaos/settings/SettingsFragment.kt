package io.audiobookshelf.aaos.settings

import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
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
import io.audiobookshelf.aaos.BuildConfig
import io.audiobookshelf.aaos.R
import io.audiobookshelf.aaos.auth.AuthSnapshot
import io.audiobookshelf.aaos.auth.AuthStatus
import io.audiobookshelf.aaos.cache.CacheSnapshot
import io.audiobookshelf.aaos.diagnostics.DiagnosticsUploadSnapshot
import io.audiobookshelf.aaos.diagnostics.DiagnosticsUploadStatus
import io.audiobookshelf.aaos.diagnostics.PlaybackRestoreStatus
import io.audiobookshelf.aaos.diagnostics.StartupDiagnosticsSnapshot
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
    private var currentDiagnosticsSnapshot: StartupDiagnosticsSnapshot = StartupDiagnosticsSnapshot()
    private var currentDiagnosticsUploadSnapshot: DiagnosticsUploadSnapshot = DiagnosticsUploadSnapshot()
    private var isCommandChannelReady: Boolean = false
    private var isLoginInProgress: Boolean = false
    private var consecutiveVersionClicks: Int = 0
    private var areDiagnosticsVisible: Boolean = false

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
            diagnosticsSnapshot = (activity as? SettingsActivity)?.currentDiagnosticsSnapshot()
                ?: StartupDiagnosticsSnapshot(),
            diagnosticsUploadSnapshot = (activity as? SettingsActivity)?.currentDiagnosticsUploadSnapshot()
                ?: DiagnosticsUploadSnapshot(),
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
            diagnosticsSnapshot = (activity as? SettingsActivity)?.currentDiagnosticsSnapshot()
                ?: StartupDiagnosticsSnapshot(),
            diagnosticsUploadSnapshot = (activity as? SettingsActivity)?.currentDiagnosticsUploadSnapshot()
                ?: DiagnosticsUploadSnapshot(),
            loginInProgress = (activity as? SettingsActivity)?.isLoginInProgress() == true,
        )
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_SERVER_URL, serverUrlInput)
        outState.putString(STATE_USERNAME, usernameInput)
    }

    override fun onPreferenceTreeClick(preference: Preference): Boolean {
        if (preference.key != KEY_APP_VERSION) {
            resetDiagnosticsUnlockClicks()
        }
        return super.onPreferenceTreeClick(preference)
    }

    fun renderState(
        commandChannelReady: Boolean,
        authSnapshot: AuthSnapshot,
        syncSnapshot: SyncSnapshot,
        cacheSnapshot: CacheSnapshot,
        diagnosticsSnapshot: StartupDiagnosticsSnapshot,
        diagnosticsUploadSnapshot: DiagnosticsUploadSnapshot,
        loginInProgress: Boolean,
    ) {
        isCommandChannelReady = commandChannelReady
        isLoginInProgress = loginInProgress
        currentAuthSnapshot = authSnapshot
        currentSyncSnapshot = syncSnapshot
        currentCacheSnapshot = cacheSnapshot
        currentDiagnosticsSnapshot = diagnosticsSnapshot
        currentDiagnosticsUploadSnapshot = diagnosticsUploadSnapshot

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

        findPreference<EditTextPreference>(KEY_DIAGNOSTICS_UPLOAD_URL)?.apply {
            text = (activity as? SettingsActivity)?.currentDiagnosticsUploadSnapshot()?.uploadUrl.orEmpty()
            setOnBindEditTextListener { editText ->
                editText.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                styleEditText(editText)
            }
            setOnPreferenceChangeListener { preference, newValue ->
                val uploadUrl = (newValue as? String).orEmpty().trim()
                (preference as EditTextPreference).text = uploadUrl
                (activity as? SettingsActivity)?.updateDiagnosticsUploadUrl(uploadUrl)
                true
            }
        }
    }

    private fun configureActions() {
        findPreference<Preference>(KEY_ACCOUNT_ACTION)?.setOnPreferenceClickListener {
            resetDiagnosticsUnlockClicks()
            if (currentAuthSnapshot.status == AuthStatus.AUTHENTICATED) {
                (activity as? SettingsActivity)?.performLogout()
            } else {
                startLogin()
            }
            true
        }

        findPreference<Preference>(KEY_RESYNC_ACTION)?.setOnPreferenceClickListener {
            resetDiagnosticsUnlockClicks()
            (activity as? SettingsActivity)?.performResync()
            true
        }

        findPreference<Preference>(KEY_CLEAR_CACHE_ACTION)?.setOnPreferenceClickListener {
            resetDiagnosticsUnlockClicks()
            (activity as? SettingsActivity)?.performClearCache()
            true
        }

        findPreference<Preference>(KEY_APP_VERSION)?.setOnPreferenceClickListener {
            if (!areDiagnosticsVisible) {
                consecutiveVersionClicks += 1
                if (consecutiveVersionClicks >= DIAGNOSTICS_UNLOCK_CLICK_COUNT) {
                    areDiagnosticsVisible = true
                    consecutiveVersionClicks = 0
                    renderDiagnosticsVisibility()
                }
            }
            true
        }

        findPreference<Preference>(KEY_HOMEPAGE)?.setOnPreferenceClickListener {
            resetDiagnosticsUnlockClicks()
            openHomepage()
            true
        }

        findPreference<Preference>(KEY_DIAGNOSTICS_UPLOAD_ACTION)?.setOnPreferenceClickListener {
            resetDiagnosticsUnlockClicks()
            (activity as? SettingsActivity)?.performDiagnosticsUpload()
            true
        }
    }

    private fun renderFieldState() {
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
        findPreference<Preference>(KEY_STARTUP_DIAGNOSTICS)?.summary = buildStartupDiagnosticsSummary()
        findPreference<Preference>(KEY_DIAGNOSTICS_UPLOAD_URL)?.summary =
            currentDiagnosticsUploadSnapshot.uploadUrl.ifBlank { getString(R.string.settings_diagnostics_upload_url_missing) }
        findPreference<Preference>(KEY_DIAGNOSTICS_UPLOAD_ACTION)?.summary = buildDiagnosticsUploadSummary()
        findPreference<Preference>(KEY_APP_VERSION)?.summary = getString(
            R.string.settings_app_version_summary,
            BuildConfig.VERSION_NAME,
            BuildConfig.VERSION_CODE,
        )
        renderDiagnosticsVisibility()
    }

    private fun renderDiagnosticsVisibility() {
        findPreference<PreferenceCategory>(KEY_DIAGNOSTICS_CATEGORY)?.isVisible = areDiagnosticsVisible
    }

    private fun resetDiagnosticsUnlockClicks() {
        if (!areDiagnosticsVisible) {
            consecutiveVersionClicks = 0
        }
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

        findPreference<Preference>(KEY_DIAGNOSTICS_UPLOAD_ACTION)?.isEnabled =
            currentDiagnosticsUploadSnapshot.uploadUrl.isNotBlank() &&
                currentDiagnosticsUploadSnapshot.lastUploadStatus != DiagnosticsUploadStatus.RUNNING
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
            ?.takeUnless { isServerVersionVerificationWarning(it) }
            ?.let { parts += localizeStatusMessage(it) }
        currentAuthSnapshot.serverVersion
            ?.takeIf { it.isNotBlank() }
            ?.takeUnless { isServerVersionVerificationWarning(it) }
            ?.let { version ->
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
        val parts = mutableListOf(buildAuthStatusSummary())
        val hasCounts = currentSyncSnapshot.libraryCount > 0 ||
            currentSyncSnapshot.bookCount > 0 ||
            currentSyncSnapshot.authorCount > 0 ||
            currentSyncSnapshot.status == SyncStatus.SUCCESS
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
            parts += getString(
                R.string.settings_catalog_updated_and_age,
                formatSyncTimestamp(timestamp),
                formatCacheAge(timestamp),
            )
        }
        if (currentSyncSnapshot.status == SyncStatus.FAILED && currentSyncSnapshot.lastSyncedAt != null) {
            parts += getString(R.string.settings_catalog_stale_after_failed_sync)
        }
        return parts.joinToString("\n")
    }

    private fun openHomepage() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.settings_homepage_url)))
        runCatching { startActivity(intent) }
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

    private fun isServerVersionVerificationWarning(message: String): Boolean {
        return message == "Serverversion konnte nicht verifiziert werden." ||
            message == "Server version could not be verified."
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

    private fun buildStartupDiagnosticsSummary(): String {
        val parts = mutableListOf<String>()
        currentDiagnosticsSnapshot.lastServiceStartedAt?.let { timestamp ->
            parts += getString(R.string.settings_diagnostics_last_start, formatSyncTimestamp(timestamp))
        } ?: run {
            parts += getString(R.string.settings_diagnostics_no_start)
        }

        val restoreStatus = currentDiagnosticsSnapshot.lastRestoreStatus
        if (restoreStatus != null) {
            parts += getString(
                R.string.settings_diagnostics_restore_status,
                localizeRestoreStatus(restoreStatus),
            )
        }
        currentDiagnosticsSnapshot.lastRestoreFinishedAt?.let { timestamp ->
            parts += getString(R.string.settings_diagnostics_restore_finished, formatSyncTimestamp(timestamp))
        }
        currentDiagnosticsSnapshot.lastRestoreMessage?.takeIf { it.isNotBlank() }?.let { message ->
            parts += getString(R.string.settings_diagnostics_restore_message, message)
        }
        return parts.joinToString("\n")
    }

    private fun buildDiagnosticsUploadSummary(): String {
        val status = currentDiagnosticsUploadSnapshot.lastUploadStatus
        val parts = mutableListOf(
            when (status) {
                DiagnosticsUploadStatus.RUNNING -> getString(R.string.settings_diagnostics_upload_running)
                DiagnosticsUploadStatus.SUCCESS -> getString(R.string.settings_diagnostics_upload_success)
                DiagnosticsUploadStatus.FAILED -> getString(R.string.settings_diagnostics_upload_failed)
                null -> getString(R.string.settings_diagnostics_upload_summary)
            },
        )
        currentDiagnosticsUploadSnapshot.lastUploadFinishedAt?.let { timestamp ->
            parts += getString(R.string.settings_diagnostics_upload_finished, formatSyncTimestamp(timestamp))
        }
        currentDiagnosticsUploadSnapshot.lastUploadMessage?.takeIf { it.isNotBlank() }?.let { message ->
            parts += message
        }
        return parts.joinToString("\n")
    }

    private fun localizeRestoreStatus(status: PlaybackRestoreStatus): String {
        return when (status) {
            PlaybackRestoreStatus.RUNNING -> getString(R.string.settings_diagnostics_restore_running)
            PlaybackRestoreStatus.SKIPPED -> getString(R.string.settings_diagnostics_restore_skipped)
            PlaybackRestoreStatus.SUCCESS -> getString(R.string.settings_diagnostics_restore_success)
            PlaybackRestoreStatus.FAILED -> getString(R.string.settings_diagnostics_restore_failed)
            PlaybackRestoreStatus.TIMED_OUT -> getString(R.string.settings_diagnostics_restore_timed_out)
        }
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
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_USERNAME = "username"
        private const val KEY_PASSWORD = "password"
        private const val KEY_LIBRARY_COUNT = "library_count"
        private const val KEY_ACCOUNT_ACTION = "account_action"
        private const val KEY_RESYNC_ACTION = "resync_action"
        private const val KEY_CLEAR_CACHE_ACTION = "clear_cache_action"
        private const val KEY_DIAGNOSTICS_CATEGORY = "diagnostics_category"
        private const val KEY_STARTUP_DIAGNOSTICS = "startup_diagnostics"
        private const val KEY_DIAGNOSTICS_UPLOAD_URL = "diagnostics_upload_url"
        private const val KEY_DIAGNOSTICS_UPLOAD_ACTION = "diagnostics_upload_action"
        private const val KEY_APP_VERSION = "app_version"
        private const val KEY_HOMEPAGE = "homepage"
        private const val DIAGNOSTICS_UNLOCK_CLICK_COUNT = 5

        private const val STATE_SERVER_URL = "state_server_url"
        private const val STATE_USERNAME = "state_username"
    }
}
