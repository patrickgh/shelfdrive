package io.audiobookshelf.aaos.absapi

import io.audiobookshelf.aaos.status.UserVisibleStatus

object ServerCompatibility {
    private val minimumSupportedVersion = ServerVersion.parse("2.31.0")
        ?: error("Minimum server version must be parseable.")

    fun evaluate(serverVersion: String?): CompatibilityReport {
        if (serverVersion.isNullOrBlank()) {
            return CompatibilityReport(
                serverVersion = null,
                isSupported = true,
                warningMessage = UserVisibleStatus.SERVER_VERSION_UNKNOWN,
            )
        }

        val parsedVersion = ServerVersion.parse(serverVersion)
            ?: return CompatibilityReport(
                serverVersion = serverVersion,
                isSupported = true,
                warningMessage = "Serverversion '$serverVersion' konnte nicht sauber ausgewertet werden.",
            )

        if (parsedVersion < minimumSupportedVersion) {
            return CompatibilityReport(
                serverVersion = serverVersion,
                isSupported = false,
                warningMessage = "Audiobookshelf $serverVersion wird in v1 nicht unterstützt. Benötigt wird mindestens 2.31.0.",
            )
        }

        return CompatibilityReport(
            serverVersion = serverVersion,
            isSupported = true,
            warningMessage = null,
        )
    }
}

data class CompatibilityReport(
    val serverVersion: String?,
    val isSupported: Boolean,
    val warningMessage: String?,
)

data class ServerVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
) : Comparable<ServerVersion> {
    override fun compareTo(other: ServerVersion): Int {
        return compareValuesBy(this, other, ServerVersion::major, ServerVersion::minor, ServerVersion::patch)
    }

    companion object {
        fun parse(rawVersion: String): ServerVersion? {
            val match = Regex("""(\d+)\.(\d+)\.(\d+)""").find(rawVersion) ?: return null
            return ServerVersion(
                major = match.groupValues[1].toInt(),
                minor = match.groupValues[2].toInt(),
                patch = match.groupValues[3].toInt(),
            )
        }
    }
}
