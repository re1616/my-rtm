package com.taskly.server.sync

/**
 * SYNC-7: Sync protocol versioning via the `Taskly-Sync-Version` header.
 *
 * The server supports the current major version and the previous major version.
 * Clients sending an older version receive HTTP 426 Upgrade Required with instructions.
 */
object SyncVersion {

    /** Current wire protocol version (incremented on breaking changes). */
    const val CURRENT_VERSION = 2

    /** Previous major version still supported (clients on this version keep working). */
    const val PREVIOUS_VERSION = 1

    /** Minimum version accepted (anything below this → 426). */
    private const val MIN_VERSION = 1

    private val SUPPORTED = setOf(CURRENT_VERSION, PREVIOUS_VERSION)

    /** Parsed version from the header. Returns null and a detail message when invalid. */
    data class VersionCheck(val version: Int? = null, val error: String? = null) {
        val accepted: Boolean get() = version != null
    }

    /**
     * Parses and validates the Taskly-Sync-Version header.
     * Returns a VersionCheck: either accepted (version is set) or rejected (error is set).
     */
    fun validate(header: String?): VersionCheck {
        if (header == null) return VersionCheck(version = CURRENT_VERSION)

        val parsed = try {
            header.trim().toInt()
        } catch (e: NumberFormatException) {
            return VersionCheck(error = "Unrecognized Taskly-Sync-Version: '$header'")
        }

        if (parsed < MIN_VERSION) {
            return VersionCheck(
                error = "Your client version ($parsed) is too old. " +
                        "Please update to continue syncing. " +
                        "Current version: $CURRENT_VERSION, minimum: $MIN_VERSION."
            )
        }

        if (parsed !in SUPPORTED) {
            return VersionCheck(
                error = "Taskly-Sync-Version $parsed is not supported " +
                        "(supported: ${SUPPORTED.joinToString()}). " +
                        "Please update your client. Current version: $CURRENT_VERSION."
            )
        }

        return VersionCheck(version = parsed)
    }
}