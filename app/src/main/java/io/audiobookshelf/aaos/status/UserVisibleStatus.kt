package io.audiobookshelf.aaos.status

object UserVisibleStatus {
    const val SERVER_UNREACHABLE = "Server momentan nicht erreichbar. Inhalte können veraltet sein."
    const val CATALOG_SYNC_FAILED = "Server nicht erreichbar. Der letzte Katalogstand bleibt verfügbar."
    const val CATALOG_SYNC_RUNNING = "Synchronisierung läuft."
    const val SESSION_EXPIRED = "Sitzung abgelaufen. Bitte erneut anmelden."
    const val PLAYBACK_START_FAILED = "Hörbuch konnte nicht gestartet werden. Bitte Verbindung prüfen."
    const val LOGIN_FIELDS_REQUIRED = "URL, Benutzername und Passwort werden benötigt."
    const val LOGIN_MISSING_ACCESS_TOKEN = "Login-Antwort enthält kein Zugriffstoken."
    const val REFRESH_MISSING_ACCESS_TOKEN = "Refresh-Antwort enthaelt kein Zugriffstoken."
    const val SERVER_UNREACHABLE_OR_INVALID = "Server nicht erreichbar oder Antwort ungueltig."
    const val NO_ACTIVE_SESSION = "Keine aktive Audiobookshelf-Sitzung verfuegbar."
    const val SERVER_VERSION_UNKNOWN = "Serverversion konnte nicht verifiziert werden."
}
