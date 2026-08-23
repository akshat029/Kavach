package com.kavach.app.core.model

/**
 * How aggressively Kavach treats one app's DNS traffic.
 *
 * Persisted by [id]; **never rename existing ids.**
 */
enum class NetworkMode(val id: String, val label: String) {
    /** Pass every lookup straight through. No blocklist consulted. */
    ALLOW_ALL("allow_all", "Unfiltered"),

    /** Pass lookups unless the domain is on an enabled blocklist or a user deny rule. */
    FILTERED("filtered", "Filtered"),

    /**
     * Refuse every lookup **except** domains explicitly allowed for this app.
     * Kavach seeds the allowlist by watching the app in Learning mode.
     */
    ALLOWLIST_ONLY("allowlist_only", "Allowlist only"),

    /** Refuse every lookup. */
    BLOCK_ALL("block_all", "No network");

    companion object {
        fun fromId(id: String?): NetworkMode = entries.firstOrNull { it.id == id } ?: FILTERED
    }
}
