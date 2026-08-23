package com.kavach.app.core.model

/**
 * The result of running one DNS question through [com.kavach.app.core.policy.PolicyEngine].
 *
 * A verdict is deliberately verbose: the Activity screen shows [reason] and [detail]
 * verbatim so the user can always answer "why was this blocked?" without guessing.
 */
data class Verdict(
    val allowed: Boolean,
    val reason: Reason,
    /** Human-readable specifics, e.g. the blocklist rule that matched. */
    val detail: String? = null,
) {
    enum class Reason(val label: String) {
        /** App is in an unmanaged zone. */
        UNMANAGED("Unfiltered app"),

        /** No policy row existed; the global default applied. */
        DEFAULT_POLICY("Default policy"),

        /** A per-app rule the user created said allow. */
        USER_ALLOW("You allowed this domain"),

        /** A per-app rule the user created said deny. */
        USER_BLOCK("You blocked this domain"),

        /** A global rule the user created. */
        GLOBAL_ALLOW("Allowed everywhere"),

        /** A global rule the user created. */
        GLOBAL_BLOCK("Blocked everywhere"),

        /** Matched a compiled tracker blocklist. */
        TRACKER_LIST("Known tracker"),

        /** Matched a compiled advertising blocklist. */
        AD_LIST("Known ad network"),

        /** App is set to ALLOWLIST_ONLY and this domain is not on its allowlist. */
        NOT_ON_ALLOWLIST("Not on this app's allowlist"),

        /** App is set to BLOCK_ALL. */
        APP_OFFLINE("App has no network"),

        /** Learning mode records but never blocks. */
        LEARNING("Learning mode"),

        /** The tunnel could not attribute the packet to an app. Fail open, never fail shut. */
        UNATTRIBUTED("Unknown app"),
    }

    companion object {
        fun allow(reason: Reason, detail: String? = null) = Verdict(true, reason, detail)
        fun block(reason: Reason, detail: String? = null) = Verdict(false, reason, detail)
    }
}
