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
                warningCode = UserVisibleStatus.SERVER_VERSION_UNKNOWN,
            )
        }

        val parsedVersion = ServerVersion.parse(serverVersion)
            ?: return CompatibilityReport(
                serverVersion = serverVersion,
                isSupported = true,
                warningCode = UserVisibleStatus.SERVER_VERSION_UNPARSEABLE,
            )

        if (parsedVersion < minimumSupportedVersion) {
            return CompatibilityReport(
                serverVersion = serverVersion,
                isSupported = false,
                warningCode = UserVisibleStatus.SERVER_VERSION_UNSUPPORTED,
            )
        }

        return CompatibilityReport(
            serverVersion = serverVersion,
            isSupported = true,
            warningCode = null,
        )
    }
}

data class CompatibilityReport(
    val serverVersion: String?,
    val isSupported: Boolean,
    val warningCode: String?,
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
