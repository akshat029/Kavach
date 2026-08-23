package com.kavach.app.core.model

/**
 * A single installed application as shown in the Apps screen.
 *
 * Deliberately holds no Drawable: icons are loaded lazily by the UI layer so this
 * type stays cheap enough to keep a few hundred of them in a StateFlow.
 */
data class AppInfo(
    val packageName: String,
    val label: String,
    val uid: Int,
    val isSystemApp: Boolean,
    val hasInternetPermission: Boolean,
    val installedAt: Long,
) {
    /** Apps without INTERNET can never generate traffic, so Kavach hides them by default. */
    val isRelevant: Boolean get() = hasInternetPermission
}

/** An [AppInfo] joined with its effective policy, ready to render. */
data class ManagedApp(
    val info: AppInfo,
    val zone: Zone,
    val networkMode: NetworkMode,
    val blockTrackers: Boolean,
    val blockAds: Boolean,
    val learningMode: Boolean,
    val blockedCount: Int = 0,
    val allowedCount: Int = 0,
)
