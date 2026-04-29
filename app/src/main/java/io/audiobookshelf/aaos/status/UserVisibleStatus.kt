package io.audiobookshelf.aaos.status

object UserVisibleStatus {
    const val SERVER_UNREACHABLE = "Server momentan nicht erreichbar. Inhalte können veraltet sein."
    const val CATALOG_SYNC_FAILED = "Server nicht erreichbar. Der letzte Katalogstand bleibt verfügbar."
    const val SESSION_EXPIRED = "Sitzung abgelaufen. Bitte erneut anmelden."
    const val PLAYBACK_START_FAILED = "Hörbuch konnte nicht gestartet werden. Bitte Verbindung prüfen."
}
