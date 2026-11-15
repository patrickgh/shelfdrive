package io.audiobookshelf.aaos.settings

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.os.Bundle
import android.text.format.DateUtils
import android.text.format.Formatter
import android.text.method.PasswordTransformationMethod
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.annotation.IdRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.preference.PreferenceManager
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.radiobutton.MaterialRadioButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import io.audiobookshelf.aaos.BuildConfig
import io.audiobookshelf.aaos.R
import io.audiobookshelf.aaos.auth.AuthStatus
import io.audiobookshelf.aaos.auth.ServerUrlPolicy
import io.audiobookshelf.aaos.diagnostics.DiagnosticsUploadStatus
import io.audiobookshelf.aaos.diagnostics.PlaybackRestoreStatus
import io.audiobookshelf.aaos.playback.PlaybackPreferences
import io.audiobookshelf.aaos.playback.PlaybackSkipPolicy
import io.audiobookshelf.aaos.server.ServerType
import io.audiobookshelf.aaos.status.UserVisibleStatus
import io.audiobookshelf.aaos.sync.SyncStatus
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt

class SettingsFragment : Fragment(R.layout.fragment_settings) {
    private enum class Page(@get:StringRes val title: Int) {
        OVERVIEW(R.string.settings_title),
        SETUP(R.string.settings_setup_title),
        PRIVACY(R.string.settings_privacy_policy_title),
        LEGAL(R.string.settings_legal_notice_title),
        UPLOAD_URL(R.string.settings_diagnostics_upload_url_title),
        UPLOAD_DISCLOSURE(R.string.settings_diagnostics_upload_disclosure_title),
    }

    private val host get() = requireActivity() as SettingsActivity
    private var state = SettingsState()
    private var page = Page.OVERVIEW
    private var serverType = ServerType.AUDIOBOOKSHELF
    private var submitted = false
    private var overviewScroll = 0
    private var consecutiveVersionClicks = 0
    private var diagnosticsVisible = false
    private var confirmation: AlertDialog? = null
    private lateinit var backCallback: OnBackPressedCallback

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        page = savedInstanceState?.getString("page")?.let(Page::valueOf) ?: Page.OVERVIEW
        serverType = ServerType.fromStored(savedInstanceState?.getString("serverType"))
        overviewScroll = savedInstanceState?.getInt("overviewScroll") ?: 0
        diagnosticsVisible = savedInstanceState?.getBoolean("diagnosticsVisible") == true

        backCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() = showPage(Page.OVERVIEW)
        }
        host.onBackPressedDispatcher.addCallback(viewLifecycleOwner, backCallback)
        host.findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener {
            if (page == Page.OVERVIEW) host.finish() else showPage(Page.OVERVIEW)
        }

        configureLayout()
        configurePlayback(savedInstanceState?.getBoolean("skipExpanded") == true)
        configureSetup(savedInstanceState?.getBoolean("serverExpanded") == true)
        configureActions()
        renderPage()
        renderState(host.currentState())
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("page", page.name)
        outState.putString("serverType", serverType.name)
        outState.putInt("overviewScroll", overviewScroll)
        outState.putBoolean("diagnosticsVisible", diagnosticsVisible)
        view?.let {
            outState.putBoolean("skipExpanded", widget<RadioGroup>(R.id.settings_skip_choices).isVisible)
            outState.putBoolean("serverExpanded", widget<RadioGroup>(R.id.settings_server_choices).isVisible)
        }
    }

    override fun onDestroyView() {
        confirmation?.dismiss()
        confirmation = null
        host.findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener(null)
        super.onDestroyView()
    }

    internal fun renderState(newState: SettingsState) {
        state = newState
        if (view == null) return
        if (page == Page.SETUP && state.authStateKnown && state.authSnapshot.isAuthenticated) {
            showPage(Page.OVERVIEW)
        }
        if (page == Page.SETUP && submitted && !state.authActionInProgress &&
            state.authSnapshot.status == AuthStatus.LOGIN_FAILED &&
            state.authSnapshot.statusMessage !in listOf(
                UserVisibleStatus.SERVER_UNREACHABLE,
                UserVisibleStatus.SERVER_UNREACHABLE_OR_INVALID,
            ) &&
            !widget<TextInputEditText>(R.id.settings_password).text.isNullOrEmpty()
        ) {
            widget<TextInputEditText>(R.id.settings_password).text?.clear()
            submitted = true
        }
        renderAccount()
        renderSetup()
        renderCatalog()
        renderDiagnostics()
    }

    private fun configureLayout() {
        val configuration = resources.configuration
        val wide = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE &&
            configuration.screenWidthDp >= 1000 && configuration.fontScale <= 1.2f
        if (wide) {
            val left = widget<LinearLayout>(R.id.settings_left)
            val right = widget<LinearLayout>(R.id.settings_right)
            for (id in listOf(R.id.settings_playback, R.id.settings_about)) {
                val section = widget<View>(id)
                left.removeView(section)
                right.addView(section)
            }
            right.updateLayoutParams<LinearLayout.LayoutParams> { marginStart = dp(32) }
            right.isVisible = true
        }
        for (id in listOf(R.id.settings_setup, R.id.settings_detail)) {
            widget<View>(id).updateLayoutParams<ViewGroup.LayoutParams> {
                width = dp(minOf(configuration.screenWidthDp, 880))
            }
        }
        val passwordLayout = widget<TextInputLayout>(R.id.settings_password_layout)
        passwordLayout.updateLayoutParams<LinearLayout.LayoutParams> {
            width = 0
            weight = 1f
        }
        widget<MaterialButton>(R.id.settings_password_visibility).updateLayoutParams<LinearLayout.LayoutParams> {
            marginStart = dp(12)
            topMargin = (passwordLayout.layoutParams as LinearLayout.LayoutParams).topMargin + dp(9)
            marginEnd = 0
        }
        if (configuration.fontScale > 1.2f || configuration.screenWidthDp < 560) {
            for (id in listOf(R.id.settings_account_setup, R.id.settings_sync, R.id.settings_detail_cancel)) {
                (widget<View>(id).parent as LinearLayout).orientation = LinearLayout.VERTICAL
            }
            val progress = widget<LinearLayout>(R.id.settings_progress_row)
            progress.orientation = LinearLayout.VERTICAL
            progress.gravity = Gravity.START
            progress.getChildAt(0).updateLayoutParams<LinearLayout.LayoutParams> {
                width = ViewGroup.LayoutParams.MATCH_PARENT
                weight = 0f
            }
        }
    }

    private fun configurePlayback(expanded: Boolean) {
        widget<View>(R.id.settings_playback).isSaveFromParentEnabled = false
        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        val skipKey = getString(R.string.settings_key_skip_increment)
        val progressKey = getString(R.string.settings_key_progress_display)
        val rewindKey = getString(R.string.settings_key_rewind_on_pause)
        val labels = resources.getStringArray(R.array.settings_skip_increment_entries)
        val seconds = PlaybackSkipPolicy.supportedSkipIncrementSeconds
        val skip = widget<TextView>(R.id.settings_skip_value)
        val selected = seconds.indexOf(PlaybackPreferences.skipIncrementSeconds(requireContext()))
        skip.text = labels[selected]
        configureChoices(R.id.settings_skip_choices, labels.asList(), selected) { index ->
            prefs.edit { putString(skipKey, seconds[index].toString()) }
            skip.text = labels[index]
            setExpanded(R.id.settings_skip, R.id.settings_skip_value, R.id.settings_skip_choices, false)
        }
        configureDropdown(R.id.settings_skip, R.id.settings_skip_value, R.id.settings_skip_choices, expanded)

        widget<RadioGroup>(R.id.settings_progress).apply {
            check(if (PlaybackPreferences.showChapterProgress(requireContext())) R.id.settings_chapter else R.id.settings_book)
            setOnCheckedChangeListener { _, id ->
                prefs.edit { putString(progressKey, if (id == R.id.settings_chapter) "chapter" else "book") }
                resetDiagnosticsUnlockClicks()
            }
        }
        widget<MaterialSwitch>(R.id.settings_rewind).apply {
            isChecked = PlaybackPreferences.isRewindOnPauseEnabled(requireContext())
            setOnCheckedChangeListener { _, checked ->
                prefs.edit { putBoolean(rewindKey, checked) }
                resetDiagnosticsUnlockClicks()
            }
        }
    }

    private fun configureChoices(@IdRes id: Int, labels: List<String>, selected: Int, choose: (Int) -> Unit) {
        val group = widget<RadioGroup>(id)
        val ids = labels.map { label ->
            MaterialRadioButton(requireContext()).apply {
                this.id = View.generateViewId()
                text = label
                setTextColor(ContextCompat.getColor(context, R.color.abs_on_surface))
                textSize = 24f
                minHeight = dp(76)
                setPaddingRelative(dp(12), 0, dp(12), 0)
                buttonTintList = ContextCompat.getColorStateList(context, R.color.settings_checked)
                setBackgroundResource(R.drawable.bg_settings_control)
                group.addView(this, RadioGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(6)
                })
            }.id
        }
        group.check(ids[selected])
        // Click also closes the list when the already selected value is tapped.
        for ((index, viewId) in ids.withIndex()) {
            group.findViewById<View>(viewId).setOnClickListener { choose(index) }
        }
    }

    private fun configureDropdown(@IdRes row: Int, @IdRes value: Int, @IdRes choices: Int, expanded: Boolean) {
        ViewCompat.setAccessibilityDelegate(widget<View>(row), object : AccessibilityDelegateCompat() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = Button::class.java.name
            }
        })
        setExpanded(row, value, choices, expanded)
        click(row) { setExpanded(row, value, choices, !widget<View>(choices).isVisible) }
    }

    private fun setExpanded(@IdRes row: Int, @IdRes value: Int, @IdRes choices: Int, expanded: Boolean) {
        val selector = widget<View>(row)
        val group = widget<View>(choices)
        if (!expanded && group.hasFocus()) selector.requestFocus()
        group.isVisible = expanded
        widget<TextView>(value).setCompoundDrawablesRelativeWithIntrinsicBounds(
            0, 0, if (expanded) R.drawable.ic_settings_collapse else R.drawable.ic_settings_expand, 0,
        )
        ViewCompat.setStateDescription(selector, getString(if (expanded) R.string.settings_expanded else R.string.settings_collapsed))
        if (row == R.id.settings_skip) widget<View>(R.id.settings_skip_help).isVisible = expanded
    }

    private fun configureSetup(expanded: Boolean) {
        widget<TextView>(R.id.settings_server_type_value).text = serverType.label
        configureChoices(R.id.settings_server_choices, ServerType.entries.map { it.label }, serverType.ordinal) { index ->
            val selected = ServerType.entries[index]
            if (serverType != selected) {
                serverType = selected
                widget<TextInputEditText>(R.id.settings_password).text?.clear()
                setPasswordVisible(false)
                submitted = false
            }
            widget<TextView>(R.id.settings_server_type_value).text = serverType.label
            setExpanded(R.id.settings_server_type, R.id.settings_server_type_value, R.id.settings_server_choices, false)
            renderSetup()
        }
        configureDropdown(R.id.settings_server_type, R.id.settings_server_type_value, R.id.settings_server_choices, expanded)
        for (id in listOf(R.id.settings_server_url, R.id.settings_username, R.id.settings_password)) {
            widget<TextInputEditText>(id).doAfterTextChanged {
                submitted = false
                renderSetup()
            }
        }
        widget<TextInputEditText>(R.id.settings_server_url).setOnFocusChangeListener { _, _ -> renderSetup() }
        click(R.id.settings_password_visibility) {
            setPasswordVisible(widget<TextInputEditText>(R.id.settings_password).transformationMethod != null)
        }
        click(R.id.settings_connect, ::connect)
    }

    private fun setPasswordVisible(visible: Boolean) {
        widget<TextInputEditText>(R.id.settings_password).apply {
            transformationMethod = if (visible) null else PasswordTransformationMethod.getInstance()
            setSelection(text?.length ?: 0)
        }
        widget<Button>(R.id.settings_password_visibility).setText(
            if (visible) R.string.settings_password_hide else R.string.settings_password_show,
        )
    }

    private fun startSetup() {
        val auth = state.authSnapshot
        serverType = auth.serverType
        widget<TextView>(R.id.settings_server_type_value).text = serverType.label
        widget<RadioGroup>(R.id.settings_server_choices).apply { check(getChildAt(serverType.ordinal).id) }
        setExpanded(R.id.settings_server_type, R.id.settings_server_type_value, R.id.settings_server_choices, false)
        widget<TextInputEditText>(R.id.settings_server_url).setText(auth.baseUrl)
        widget<TextInputEditText>(R.id.settings_username).setText(auth.username)
        submitted = false
        showPage(Page.SETUP)
    }

    private fun connect() {
        val validation = ServerUrlPolicy.validate(input(R.id.settings_server_url))
        if (validation.normalizedUrl == null || !widget<Button>(R.id.settings_connect).isEnabled) return
        submitted = true
        setPasswordVisible(false)
        val scroll = widget<ScrollView>(R.id.settings_scroll)
        scroll.getChildAt(0).requestFocus()
        hideKeyboard()
        if (resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT) {
            scroll.scrollTo(0, 0)
        }
        host.performLogin(serverType, validation.normalizedUrl, input(R.id.settings_username).trim(), input(R.id.settings_password))
    }

    private fun renderSetup() {
        if (page != Page.SETUP) return
        val auth = state.authSnapshot
        val locked = auth.hasStoredPassword
        val pending = state.authActionInProgress
        val url = ServerUrlPolicy.validate(input(R.id.settings_server_url))
        setText(
            R.id.settings_setup_intro,
            if (locked) listOfNotNull(auth.username, accountInfo()).joinToString("\n") else getString(R.string.settings_setup_description),
        )
        widget<TextInputLayout>(R.id.settings_server_url_layout).apply {
            isVisible = !locked
            val urlError = url.errorCode?.takeUnless { editText?.hasFocus() == true }
            helperText = if (locked || urlError != null) null else getString(R.string.settings_setup_url_example)
            error = urlError?.let(::localizeStatusMessage)
            isEnabled = !pending && !locked
        }
        widget<TextInputLayout>(R.id.settings_username_layout).apply {
            isVisible = !locked
            isEnabled = !pending && !locked
        }
        widget<TextInputLayout>(R.id.settings_password_layout).apply {
            helperText = if (locked) getString(R.string.settings_password_reuse) else null
            isEnabled = !pending
        }
        widget<View>(R.id.settings_password_visibility).isEnabled = !pending
        widget<View>(R.id.settings_server_type).apply {
            isVisible = !locked
            isEnabled = !pending && !locked
        }
        if (locked && widget<View>(R.id.settings_server_choices).isVisible) {
            setExpanded(R.id.settings_server_type, R.id.settings_server_type_value, R.id.settings_server_choices, false)
        }
        widget<Button>(R.id.settings_connect).apply {
            isEnabled = state.commandChannelReady && state.authStateKnown && !pending &&
                url.normalizedUrl != null && input(R.id.settings_username).isNotBlank() &&
                (locked || input(R.id.settings_password).isNotBlank())
            setText(if (pending) R.string.settings_login_in_progress_title else R.string.settings_connect)
        }
        val failed = submitted && (state.authRequestFailed || auth.status == AuthStatus.LOGIN_FAILED)
        val title = when {
            pending -> R.string.settings_login_in_progress_title
            failed -> R.string.settings_auth_status_login_failed
            !state.commandChannelReady || !state.authStateKnown -> R.string.settings_connection_loading
            else -> null
        }
        val message = when {
            pending -> getString(R.string.settings_login_in_progress_summary)
            submitted && state.authRequestFailed -> getString(R.string.settings_auth_action_error)
            submitted && auth.status == AuthStatus.LOGIN_FAILED -> auth.statusMessage?.let(::localizeStatusMessage)
            else -> null
        }
        widget<TextView>(R.id.settings_setup_status).setTextColor(
            ContextCompat.getColor(requireContext(), if (failed && !pending) R.color.settings_error else R.color.abs_on_surface),
        )
        setText(R.id.settings_setup_status, title?.let { getString(it) })
        setText(R.id.settings_setup_message, message)
    }

    private fun renderAccount() {
        val auth = state.authSnapshot
        val canAct = state.commandChannelReady && state.authStateKnown && !state.authActionInProgress
        val hasAccount = auth.isAuthenticated || auth.hasStoredPassword
        val logoutPending = auth.statusMessage == UserVisibleStatus.PENDING_PROGRESS_LOGOUT
        val title = when {
            state.authActionInProgress && page == Page.OVERVIEW -> getString(
                if (state.authAction == SettingsAuthAction.LOGOUT) R.string.settings_logout_in_progress_summary
                else R.string.settings_login_in_progress_title,
            )
            logoutPending -> getString(R.string.settings_logout_pending)
            auth.isAuthenticated -> auth.username?.takeIf { it.isNotBlank() }?.let {
                getString(R.string.settings_account_signed_in, it)
            } ?: getString(R.string.settings_auth_status_authenticated)
            auth.status == AuthStatus.SESSION_EXPIRED -> getString(R.string.settings_auth_status_session_expired)
            auth.status == AuthStatus.LOGIN_FAILED -> getString(R.string.settings_auth_status_login_failed)
            !state.authStateKnown -> getString(R.string.settings_account_loading)
            else -> getString(R.string.settings_auth_status_logged_out)
        }
        setText(R.id.settings_account_status, title)
        setText(R.id.settings_account_info, if (hasAccount) accountInfo() else getString(R.string.settings_setup_description))
        val hint = when {
            state.authRequestFailed && page == Page.OVERVIEW -> getString(R.string.settings_auth_action_error)
            logoutPending -> localizeStatusMessage(UserVisibleStatus.PENDING_PROGRESS_LOGOUT)
            auth.statusMessage != null && auth.statusMessage != UserVisibleStatus.SERVER_VERSION_UNKNOWN &&
                auth.statusMessage != UserVisibleStatus.SESSION_EXPIRED -> localizeStatusMessage(auth.statusMessage)
            auth.isAuthenticated -> getString(R.string.settings_account_switch_hint)
            hasAccount -> getString(R.string.settings_account_reconnect_hint)
            else -> null
        }
        setText(R.id.settings_account_hint, hint)
        widget<Button>(R.id.settings_account_setup).apply {
            isVisible = !auth.isAuthenticated && !logoutPending
            isEnabled = canAct
            setText(if (hasAccount) R.string.settings_reconnect else R.string.settings_setup_title)
        }
        widget<Button>(R.id.settings_account_logout).apply {
            isVisible = hasAccount
            isEnabled = canAct
            setText(if (logoutPending) R.string.settings_logout_retry else R.string.settings_logout_title)
        }
    }

    private fun accountInfo(): String {
        val auth = state.authSnapshot
        val server = listOfNotNull(auth.serverType.label, auth.baseUrl).joinToString(" · ")
        val version = auth.serverVersion?.takeIf { it.isNotBlank() }
        return if (version == null) server else "$server\n${getString(R.string.settings_server_version_summary, version)}"
    }

    private fun renderCatalog() {
        val ready = state.commandChannelReady
        val signedIn = state.authSnapshot.isAuthenticated
        val syncing = state.syncSnapshot.status == SyncStatus.RUNNING
        val cleared = state.cacheSnapshot?.clearedAt != null && state.syncSnapshot.status == SyncStatus.IDLE
        val status = if (cleared || (!signedIn && state.syncSnapshot.lastSyncedAt == null)) {
            getString(R.string.settings_library_count_unknown)
        } else {
            buildSyncSummary()
        }
        setText(R.id.settings_sync_status, status)
        val catalog = when {
            !signedIn && state.authStateKnown && state.syncSnapshot.lastSyncedAt == null -> getString(R.string.settings_account_sign_in_hint)
            cleared -> getString(R.string.settings_sync_after_clear)
            else -> buildCatalogSummary().takeUnless { it == status }
        }
        setText(R.id.settings_catalog_info, catalog)
        setText(R.id.settings_cache_info, buildCacheSummary())
        widget<Button>(R.id.settings_sync).apply {
            isEnabled = ready && signedIn && !syncing && !state.cacheClearing && !state.authActionInProgress
            setText(if (syncing) R.string.settings_resync_summary_running else R.string.settings_resync_title)
        }
        widget<Button>(R.id.settings_cache).isEnabled = ready && !syncing && !state.cacheClearing && !state.authActionInProgress
    }

    private fun renderDiagnostics() {
        widget<View>(R.id.settings_diagnostics).isVisible = BuildConfig.DIAGNOSTICS_ENABLED && diagnosticsVisible
        if (!BuildConfig.DIAGNOSTICS_ENABLED || !diagnosticsVisible) return
        setText(R.id.settings_startup_status, buildStartupDiagnosticsSummary())
        val upload = state.diagnosticsUploadSnapshot
        setText(R.id.settings_upload_destination, upload.uploadUrl.ifBlank { getString(R.string.settings_diagnostics_upload_url_missing) })
        setText(R.id.settings_upload_status, buildDiagnosticsUploadSummary())
        val canUpload = upload.uploadUrl.isNotBlank() && upload.lastUploadStatus != DiagnosticsUploadStatus.RUNNING
        widget<Button>(R.id.settings_upload).isEnabled = canUpload
        if (page == Page.UPLOAD_DISCLOSURE) {
            widget<Button>(R.id.settings_detail_action).isEnabled = canUpload
            setText(
                R.id.settings_detail_text,
                getString(R.string.settings_diagnostics_destination, upload.uploadUrl) +
                    "\n\n" + getString(R.string.settings_diagnostics_upload_disclosure_text),
            )
        }
    }

    private fun configureActions() {
        click(R.id.settings_account_setup, ::startSetup)
        click(R.id.settings_account_logout) {
            confirm(R.string.settings_logout_confirm_title, R.string.settings_logout_summary, R.string.settings_logout_title, host::performLogout)
        }
        click(R.id.settings_sync, host::performResync)
        click(R.id.settings_cache) {
            confirm(R.string.settings_clear_cache_confirm_title, R.string.settings_clear_cache_summary_scope, R.string.settings_clear_cache_title, host::performClearCache)
        }
        widget<TextView>(R.id.settings_version).apply {
            text = getString(R.string.settings_version_label, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)
            if (BuildConfig.DIAGNOSTICS_ENABLED) {
                setOnClickListener {
                    if (++consecutiveVersionClicks >= 5 && !diagnosticsVisible) {
                        diagnosticsVisible = true
                        renderDiagnostics()
                    }
                }
            }
        }
        click(R.id.settings_homepage) {
            val intent = Intent(Intent.ACTION_VIEW, getString(R.string.settings_homepage_url).toUri())
            runCatching { startActivity(intent) }.onFailure {
                Toast.makeText(requireContext(), R.string.settings_homepage_unavailable, Toast.LENGTH_LONG).show()
            }
        }
        click(R.id.settings_privacy) { showPage(Page.PRIVACY) }
        click(R.id.settings_legal) { showPage(Page.LEGAL) }
        click(R.id.settings_upload_url) {
            widget<TextInputEditText>(R.id.settings_detail_url).setText(state.diagnosticsUploadSnapshot.uploadUrl)
            showPage(Page.UPLOAD_URL)
        }
        click(R.id.settings_upload) { showPage(Page.UPLOAD_DISCLOSURE) }
        click(R.id.settings_detail_cancel) { showPage(Page.OVERVIEW) }
        click(R.id.settings_detail_action) {
            when (page) {
                Page.UPLOAD_URL -> host.updateDiagnosticsUploadUrl(input(R.id.settings_detail_url).trim())
                Page.UPLOAD_DISCLOSURE -> host.performDiagnosticsUpload()
                else -> return@click
            }
            showPage(Page.OVERVIEW)
        }
    }

    private fun showPage(destination: Page) {
        val scroll = widget<ScrollView>(R.id.settings_scroll)
        if (page == Page.OVERVIEW) overviewScroll = scroll.scrollY
        hideKeyboard()
        scroll.getChildAt(0).requestFocus()
        if (page == Page.SETUP && destination != Page.SETUP) {
            widget<TextInputEditText>(R.id.settings_password).text?.clear()
            setPasswordVisible(false)
            submitted = false
        }
        page = destination
        renderPage()
        renderAccount()
        renderSetup()
        renderDiagnostics()
        scroll.post { scroll.scrollTo(0, if (page == Page.OVERVIEW) overviewScroll else 0) }
    }

    private fun renderPage() {
        widget<View>(R.id.settings_overview).isVisible = page == Page.OVERVIEW
        widget<View>(R.id.settings_setup).isVisible = page == Page.SETUP
        widget<View>(R.id.settings_detail).isVisible = page != Page.OVERVIEW && page != Page.SETUP
        backCallback.isEnabled = page != Page.OVERVIEW
        host.findViewById<MaterialToolbar>(R.id.toolbar).apply {
            setTitle(page.title)
            setNavigationIcon(if (page == Page.OVERVIEW) R.drawable.ic_settings_close else R.drawable.ic_arrow_back)
            setNavigationContentDescription(if (page == Page.OVERVIEW) R.string.settings_close else R.string.settings_back)
            for (index in 0 until childCount) {
                (getChildAt(index) as? ImageButton)?.apply {
                    minimumWidth = dp(76)
                    minimumHeight = dp(76)
                }
            }
        }
        widget<View>(R.id.settings_detail_url_layout).isVisible = page == Page.UPLOAD_URL
        widget<View>(R.id.settings_detail_actions).isVisible = page == Page.UPLOAD_URL || page == Page.UPLOAD_DISCLOSURE
        widget<View>(R.id.settings_detail_cancel).isVisible = page == Page.UPLOAD_DISCLOSURE
        widget<Button>(R.id.settings_detail_action).apply {
            setText(if (page == Page.UPLOAD_URL) R.string.settings_save else R.string.settings_diagnostics_upload_confirm)
            isEnabled = true
        }
        val detail = when (page) {
            Page.PRIVACY -> getString(R.string.settings_privacy_policy_text)
            Page.LEGAL -> getString(R.string.settings_legal_notice_text)
            else -> null
        }
        setText(R.id.settings_detail_text, detail)
    }

    private fun confirm(@StringRes title: Int, @StringRes message: Int, @StringRes action: Int, perform: () -> Unit) {
        if (confirmation?.isShowing == true) return
        val content = layoutInflater.inflate(R.layout.settings_confirmation, null)
        content.findViewById<TextView>(R.id.settings_confirm_title).setText(title)
        content.findViewById<TextView>(R.id.settings_confirm_message).setText(message)
        val dialog = MaterialAlertDialogBuilder(requireContext()).setView(content).create()
        content.findViewById<Button>(R.id.settings_confirm_cancel).setOnClickListener { dialog.dismiss() }
        content.findViewById<MaterialButton>(R.id.settings_confirm_action).apply {
            setText(action)
            backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.settings_danger))
            setOnClickListener {
                dialog.dismiss()
                perform()
            }
        }
        confirmation = dialog
        dialog.show()
    }

    private fun hideKeyboard() {
        val manager = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        manager.hideSoftInputFromWindow(requireView().windowToken, 0)
    }

    private fun resetDiagnosticsUnlockClicks() {
        if (!diagnosticsVisible) consecutiveVersionClicks = 0
    }

    private fun click(@IdRes id: Int, action: () -> Unit) {
        widget<View>(id).setOnClickListener {
            resetDiagnosticsUnlockClicks()
            action()
        }
    }

    private fun input(@IdRes id: Int): String = widget<TextInputEditText>(id).text?.toString().orEmpty()

    private fun setText(@IdRes id: Int, value: String?) {
        widget<TextView>(id).apply {
            if (text.toString() != value.orEmpty()) text = value
            isVisible = !value.isNullOrBlank()
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()

    private fun <T : View> widget(@IdRes id: Int): T = requireView().findViewById(id)

    private fun buildSyncSummary(): String {
        val base = when (state.syncSnapshot.status) {
            SyncStatus.IDLE -> getString(R.string.settings_resync_summary_idle)
            SyncStatus.RUNNING -> getString(R.string.settings_resync_summary_running)
            SyncStatus.SUCCESS -> getString(R.string.settings_resync_summary_success)
            SyncStatus.FAILED -> getString(R.string.settings_resync_summary_failed)
        }

        val parts = mutableListOf(base)
        if (state.syncSnapshot.status == SyncStatus.FAILED) {
            state.syncSnapshot.message
                ?.takeIf { it.isNotBlank() }
                ?.let { parts += localizeStatusMessage(it) }
        }
        return parts.joinToString("\n")
    }

    private fun buildCatalogSummary(): String {
        val parts = mutableListOf<String>()
        val hasCounts = state.syncSnapshot.libraryCount > 0 ||
            state.syncSnapshot.bookCount > 0 ||
            state.syncSnapshot.authorCount > 0 ||
            state.syncSnapshot.status == SyncStatus.SUCCESS
        if (hasCounts) {
            parts += getString(
                R.string.settings_catalog_counts,
                state.syncSnapshot.libraryCount,
                state.syncSnapshot.bookCount,
                state.syncSnapshot.authorCount,
            )
        } else {
            parts += getString(R.string.settings_library_count_unknown)
        }

        state.syncSnapshot.lastSyncedAt?.let { timestamp ->
            parts += getString(
                R.string.settings_catalog_updated_and_age,
                formatSyncTimestamp(timestamp),
                formatCacheAge(timestamp),
            )
        }
        if (state.syncSnapshot.status == SyncStatus.FAILED && state.syncSnapshot.lastSyncedAt != null) {
            parts += getString(R.string.settings_catalog_stale_after_failed_sync)
        }
        return parts.joinToString("\n")
    }

    private fun localizeStatusMessage(message: String): String {
        return when (message) {
            UserVisibleStatus.PENDING_PROGRESS_LOGOUT -> getString(R.string.status_pending_progress_logout)
            UserVisibleStatus.SERVER_UNREACHABLE ->
                getString(R.string.status_server_unreachable)
            UserVisibleStatus.CATALOG_SYNC_FAILED ->
                getString(R.string.status_catalog_sync_failed)
            UserVisibleStatus.SESSION_EXPIRED ->
                getString(R.string.status_session_expired)
            UserVisibleStatus.LOGIN_FIELDS_REQUIRED ->
                getString(R.string.status_login_fields_required)
            UserVisibleStatus.LOGIN_MISSING_ACCESS_TOKEN ->
                getString(R.string.status_login_missing_access_token)
            UserVisibleStatus.REFRESH_MISSING_ACCESS_TOKEN ->
                getString(R.string.status_refresh_missing_access_token)
            UserVisibleStatus.SERVER_UNREACHABLE_OR_INVALID ->
                getString(R.string.status_server_unreachable_or_invalid)
            UserVisibleStatus.SERVER_VERSION_UNPARSEABLE ->
                getString(
                    R.string.status_server_version_unparseable,
                    state.authSnapshot.serverVersion.orEmpty(),
                )
            UserVisibleStatus.SERVER_VERSION_UNSUPPORTED ->
                getString(
                    R.string.status_server_version_unsupported,
                    state.authSnapshot.serverVersion.orEmpty(),
                )
            UserVisibleStatus.SERVER_URL_INVALID ->
                getString(R.string.status_server_url_invalid)
            UserVisibleStatus.SERVER_URL_SCHEME_REQUIRED ->
                getString(R.string.status_server_url_scheme_required)
            UserVisibleStatus.SERVER_URL_HOST_REQUIRED ->
                getString(R.string.status_server_url_host_required)
            UserVisibleStatus.SERVER_URL_CREDENTIALS_FORBIDDEN ->
                getString(R.string.status_server_url_credentials_forbidden)
            UserVisibleStatus.SERVER_URL_QUERY_FORBIDDEN ->
                getString(R.string.status_server_url_query_forbidden)
            UserVisibleStatus.SERVER_URL_PUBLIC_HTTP_FORBIDDEN ->
                getString(R.string.status_server_url_public_http_forbidden)
            else -> message
        }
    }

    private fun buildCacheSummary(): String {
        if (state.cacheClearing) return getString(R.string.settings_cache_clearing)
        val snapshot = state.cacheSnapshot
        val status = when {
            state.cacheClearFailed -> getString(R.string.settings_cache_clear_failed)
            snapshot == null -> getString(R.string.settings_clear_cache_summary_initial)
            else -> null
        }
        if (snapshot == null) return requireNotNull(status)
        val parts = mutableListOf(
            getString(
                R.string.settings_clear_cache_summary_usage,
                Formatter.formatShortFileSize(requireContext(), snapshot.totalBytes),
            ),
        )
        snapshot.clearedAt?.let { timestamp ->
            parts += getString(R.string.settings_clear_cache_summary_cleared, formatSyncTimestamp(timestamp))
        }
        status?.let(parts::add)
        return parts.joinToString("\n")
    }

    private fun buildStartupDiagnosticsSummary(): String {
        val parts = mutableListOf<String>()
        state.diagnosticsSnapshot.lastServiceStartedAt?.let { timestamp ->
            parts += getString(R.string.settings_diagnostics_last_start, formatSyncTimestamp(timestamp))
        } ?: run {
            parts += getString(R.string.settings_diagnostics_no_start)
        }

        val restoreStatus = state.diagnosticsSnapshot.lastRestoreStatus
        if (restoreStatus != null) {
            parts += getString(
                R.string.settings_diagnostics_restore_status,
                localizeRestoreStatus(restoreStatus),
            )
        }
        state.diagnosticsSnapshot.lastRestoreFinishedAt?.let { timestamp ->
            parts += getString(R.string.settings_diagnostics_restore_finished, formatSyncTimestamp(timestamp))
        }
        state.diagnosticsSnapshot.lastRestoreMessage?.takeIf { it.isNotBlank() }?.let { message ->
            parts += getString(R.string.settings_diagnostics_restore_message, message)
        }
        return parts.joinToString("\n")
    }

    private fun buildDiagnosticsUploadSummary(): String {
        val status = state.diagnosticsUploadSnapshot.lastUploadStatus
        val parts = mutableListOf(
            when (status) {
                DiagnosticsUploadStatus.RUNNING -> getString(R.string.settings_diagnostics_upload_running)
                DiagnosticsUploadStatus.SUCCESS -> getString(R.string.settings_diagnostics_upload_success)
                DiagnosticsUploadStatus.FAILED -> getString(R.string.settings_diagnostics_upload_failed)
                null -> getString(R.string.settings_diagnostics_upload_summary)
            },
        )
        state.diagnosticsUploadSnapshot.lastUploadFinishedAt?.let { timestamp ->
            parts += getString(R.string.settings_diagnostics_upload_finished, formatSyncTimestamp(timestamp))
        }
        state.diagnosticsUploadSnapshot.lastUploadMessage?.takeIf { it.isNotBlank() }?.let { message ->
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
        return DateUtils.getRelativeTimeSpanString(
            timestamp,
            System.currentTimeMillis(),
            DateUtils.MINUTE_IN_MILLIS,
            DateUtils.FORMAT_ABBREV_RELATIVE,
        ).toString()
    }

}
