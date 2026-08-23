package com.kavach.app.core.model

/**
 * A Zone is the coarse trust bucket an app is placed into.
 *
 * Zones exist so the user never has to reason about individual domains. Picking a
 * zone applies a sane default [NetworkMode] plus the tracker/ad switches; the user
 * can still override any single app afterwards.
 *
 * The zone identifiers are persisted in Room, so **never rename [id] values**.
 * Add new zones at the end of the enum instead.
 */
enum class Zone(
    val id: String,
    val label: String,
    val tagline: String,
    val defaultNetworkMode: NetworkMode,
    val defaultBlockTrackers: Boolean,
    val defaultBlockAds: Boolean,
) {
    /** Banking, health, government. Maximum lockdown: only first-party domains resolve. */
    VAULT(
        id = "vault",
        label = "Vault",
        tagline = "Banking and identity. Only the app's own servers are reachable.",
        defaultNetworkMode = NetworkMode.ALLOWLIST_ONLY,
        defaultBlockTrackers = true,
        defaultBlockAds = true,
    ),

    /** Social, shopping, news. Works normally, but trackers and ads are sinkholed. */
    SOCIAL(
        id = "social",
        label = "Social",
        tagline = "Works normally. Trackers and ad networks are sinkholed.",
        defaultNetworkMode = NetworkMode.FILTERED,
        defaultBlockTrackers = true,
        defaultBlockAds = true,
    ),

    /** Games, utilities, readers that have no business phoning home at all. */
    OFFLINE(
        id = "offline",
        label = "Offline",
        tagline = "No network at all. Every lookup is refused.",
        defaultNetworkMode = NetworkMode.BLOCK_ALL,
        defaultBlockTrackers = true,
        defaultBlockAds = true,
    ),

    /** Explicitly unmanaged. Kavach stays out of the way. */
    OPEN(
        id = "open",
        label = "Open",
        tagline = "Unfiltered. Kavach does not touch this app.",
        defaultNetworkMode = NetworkMode.ALLOW_ALL,
        defaultBlockTrackers = false,
        defaultBlockAds = false,
    );

    companion object {
        /** Never throws: unknown or legacy ids degrade to [OPEN] so a bad row cannot brick the tunnel. */
        fun fromId(id: String?): Zone = entries.firstOrNull { it.id == id } ?: OPEN

        /** The zone assigned to apps the user has not triaged yet. */
        val DEFAULT: Zone = SOCIAL
    }
}
