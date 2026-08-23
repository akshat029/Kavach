package com.kavach.app.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One row per app the user has triaged. Absence of a row means "never configured",
 * which the policy engine treats as [com.kavach.app.core.model.Zone.DEFAULT].
 *
 * Keyed by packageName rather than uid because uids are reassigned on reinstall.
 */
@Entity(tableName = "app_policy")
data class AppPolicyEntity(
    @PrimaryKey val packageName: String,
    /** Cached for fast reverse lookup from the tunnel. Refreshed on package events. */
    val uid: Int,
    val zoneId: String,
    val networkModeId: String,
    val blockTrackers: Boolean,
    val blockAds: Boolean,
    /** When true the engine records verdicts but never actually blocks. */
    val learningMode: Boolean,
    val updatedAt: Long,
)

/**
 * A user-authored allow/deny override.
 *
 * [packageName] == [GLOBAL] makes the rule apply to every app. Per-app rules always
 * win over global rules; see PolicyEngine for the full precedence ladder.
 */
@Entity(
    tableName = "domain_rule",
    primaryKeys = ["packageName", "domain"],
    indices = [Index("domain")],
)
data class DomainRuleEntity(
    val packageName: String,
    /** Always stored lowercase with the trailing dot stripped. */
    val domain: String,
    val allow: Boolean,
    /** True when Kavach added this itself during Learning mode, false when the user did. */
    val autoLearned: Boolean,
    val createdAt: Long,
) {
    companion object {
        const val GLOBAL = "*"
    }
}

/**
 * A single resolved (or refused) DNS question.
 *
 * This table is capped by [KavachDao.trimLog]; it is a rolling window, not an archive.
 * It never leaves the device and is excluded from backup (see data_extraction_rules.xml).
 */
@Entity(
    tableName = "connection_log",
    indices = [Index("timestamp"), Index("packageName"), Index("domain")],
)
data class ConnectionLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val timestamp: Long,
    val packageName: String,
    val uid: Int,
    val domain: String,
    /** "A", "AAAA", "HTTPS", ... */
    val queryType: String,
    val blocked: Boolean,
    val reasonId: String,
    val detail: String?,
)

/** Aggregated counts used by the Apps list, produced by a GROUP BY query. */
data class AppTrafficCounts(
    val packageName: String,
    val blockedCount: Int,
    val allowedCount: Int,
)

/** One row of the "top blocked domains" dashboard widget. */
data class DomainCount(
    val domain: String,
    val hits: Int,
)
