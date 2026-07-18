package io.audiobookshelf.aaos.status

object UserVisibleStatus {
    const val SERVER_UNREACHABLE = "server_unreachable"
    const val CATALOG_SYNC_FAILED = "catalog_sync_failed"
    const val CATALOG_SYNC_RUNNING = "catalog_sync_running"
    const val SESSION_EXPIRED = "session_expired"
    const val LOGIN_FIELDS_REQUIRED = "login_fields_required"
    const val LOGIN_MISSING_ACCESS_TOKEN = "login_missing_access_token"
    const val REFRESH_MISSING_ACCESS_TOKEN = "refresh_missing_access_token"
    const val SERVER_UNREACHABLE_OR_INVALID = "server_unreachable_or_invalid"
    const val NO_ACTIVE_SESSION = "no_active_session"
    const val SERVER_VERSION_UNKNOWN = "server_version_unknown"
    const val SERVER_VERSION_UNPARSEABLE = "server_version_unparseable"
    const val SERVER_VERSION_UNSUPPORTED = "server_version_unsupported"
    const val SERVER_URL_INVALID = "server_url_invalid"
    const val SERVER_URL_SCHEME_REQUIRED = "server_url_scheme_required"
    const val SERVER_URL_HOST_REQUIRED = "server_url_host_required"
    const val SERVER_URL_CREDENTIALS_FORBIDDEN = "server_url_credentials_forbidden"
    const val SERVER_URL_QUERY_FORBIDDEN = "server_url_query_forbidden"
    const val SERVER_URL_PUBLIC_HTTP_FORBIDDEN = "server_url_public_http_forbidden"
}
