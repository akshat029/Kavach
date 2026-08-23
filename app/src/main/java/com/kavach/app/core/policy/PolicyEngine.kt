package com.kavach.app.core.policy

import com.kavach.app.core.blocklist.DomainMatcher
import com.kavach.app.core.model.NetworkMode
import com.kavach.app.core.model.Verdict
import com.kavach.app.core.model.Zone

/** One app's effective configuration, already resolved from its zone plus overrides. */
data class AppPolicy(
    val packageName: String,
    val zone: Zone,
    val networkMode: NetworkMode,
    val blockTrackers: Boolean,
    val blockAds: Boolean,
    val learningMode: Boolean,
) {
    companion object {
        fun defaultFor(packageName: String, zone: Zone = Zone.DEFAULT) = AppPolicy(
            packageName = packageName,
            zone = zone,
            networkMode = zone.defaultNetworkMode,
            blockTrackers = zone.defaultBlockTrackers,
            blockAds = zone.defaultBlockAds,
            learningMode = false,
        )
    }
}

/**
 * An immutable, self-contained picture of every rule in force.
 *
 * The tunnel thread reads a snapshot without any locking; when policy changes the
 * repository builds a brand new snapshot and swaps the reference. That is the whole
 * concurrency design, and it is why the hot path has no synchronisation at all.
 */
class PolicySnapshot(
    val policies: Map<String, AppPolicy>,
    /** package -> (domain -> allow). Domain keys are normalised and suffix-matched. */
    val perAppRules: Map<String, Map<String, Boolean>>,
    val globalRules: Map<String, Boolean>,
    val trackers: DomainMatcher,
    val ads: DomainMatcher,
    val defaultZone: Zone,
    /** Global kill switch. When true every question is allowed through untouched. */
    val paused: Boolean,
    val loggingEnabled: Boolean,
    val dohEndpointId: String,
) {
    fun policyFor(packageName: String): AppPolicy =
        policies[packageName] ?: AppPolicy.defaultFor(packageName, defaultZone)

    companion object {
        val EMPTY = PolicySnapshot(
            policies = emptyMap(),
            perAppRules = emptyMap(),
            globalRules = emptyMap(),
            trackers = DomainMatcher.EMPTY,
            ads = DomainMatcher.EMPTY,
            defaultZone = Zone.DEFAULT,
            paused = false,
            loggingEnabled = true,
            dohEndpointId = "cloudflare",
        )
    }
}

/**
 * Decides allow or block for one DNS question.
 *
 * Pure and stateless on purpose: every branch below is exercised by
 * `PolicyEngineTest`, because a firewall that is wrong in one branch is worse than
 * no firewall at all - the user believes they are protected when they are not.
 *
 * ### Precedence, highest first
 *  1. Global pause
 *  2. Unattributable traffic (fail **open**, never shut)
 *  3. Per-app rule the user wrote
 *  4. Global rule the user wrote
 *  5. Unmanaged app / unfiltered mode
 *  6. No-network mode
 *  7. Allowlist-only mode
 *  8. Ad blocklist
 *  9. Tracker blocklist
 * 10. Allow
 *
 * Learning mode is applied last: it downgrades any block to an allow while still
 * reporting what would have happened, so the user can switch an app to a strict
 * zone and watch it for a day before committing.
 */
object PolicyEngine {

    fun evaluate(snapshot: PolicySnapshot, packageName: String?, domain: String): Verdict {
        if (snapshot.paused) {
            return Verdict.allow(Verdict.Reason.UNMANAGED, "Kavach is paused")
        }
        if (packageName.isNullOrEmpty()) {
            // We could not tell which app asked. Blocking here would break the device
            // in ways the user cannot diagnose, so we always let it through and
            // surface it in the Activity log instead.
            return Verdict.allow(Verdict.Reason.UNATTRIBUTED, domain)
        }

        val host = domain.lowercase().trimEnd('.')
        val policy = snapshot.policyFor(packageName)

        // 3. Per-app override.
        snapshot.perAppRules[packageName]?.let { rules ->
            matchSuffix(rules, host)?.let { (matched, allow) ->
                return finish(
                    policy,
                    if (allow) {
                        Verdict.allow(Verdict.Reason.USER_ALLOW, matched)
                    } else {
                        Verdict.block(Verdict.Reason.USER_BLOCK, matched)
                    },
                )
            }
        }

        // 4. Global override.
        matchSuffix(snapshot.globalRules, host)?.let { (matched, allow) ->
            return finish(
                policy,
                if (allow) {
                    Verdict.allow(Verdict.Reason.GLOBAL_ALLOW, matched)
                } else {
                    Verdict.block(Verdict.Reason.GLOBAL_BLOCK, matched)
                },
            )
        }

        // 5. Explicitly unmanaged.
        if (policy.networkMode == NetworkMode.ALLOW_ALL) {
            return Verdict.allow(Verdict.Reason.UNMANAGED, policy.zone.label)
        }

        // 6. No network at all.
        if (policy.networkMode == NetworkMode.BLOCK_ALL) {
            return finish(policy, Verdict.block(Verdict.Reason.APP_OFFLINE, policy.zone.label))
        }

        // 7. Allowlist only. Step 3 already returned for anything on the allowlist.
        if (policy.networkMode == NetworkMode.ALLOWLIST_ONLY) {
            return finish(policy, Verdict.block(Verdict.Reason.NOT_ON_ALLOWLIST, host))
        }

        // 8 + 9. Compiled lists. Ads are checked first purely so the reason shown to
        // the user is the more recognisable one when a domain sits on both lists.
        if (policy.blockAds) {
            snapshot.ads.match(host)?.let {
                return finish(policy, Verdict.block(Verdict.Reason.AD_LIST, it))
            }
        }
        if (policy.blockTrackers) {
            snapshot.trackers.match(host)?.let {
                return finish(policy, Verdict.block(Verdict.Reason.TRACKER_LIST, it))
            }
        }

        return Verdict.allow(Verdict.Reason.DEFAULT_POLICY, policy.zone.label)
    }

    /** Learning mode turns a block into a reported near-miss. */
    private fun finish(policy: AppPolicy, verdict: Verdict): Verdict =
        if (policy.learningMode && !verdict.allowed) {
            Verdict.allow(
                Verdict.Reason.LEARNING,
                "would block: " + verdict.reason.label + (verdict.detail?.let { " ($it)" } ?: ""),
            )
        } else {
            verdict
        }

    /**
     * Finds the most specific rule covering [host].
     *
     * A rule on `example.com` covers `api.example.com`, but a rule on
     * `api.example.com` wins over one on `example.com` because the walk starts at the
     * full hostname and moves outwards.
     */
    private fun matchSuffix(rules: Map<String, Boolean>, host: String): Pair<String, Boolean>? {
        if (rules.isEmpty() || host.isEmpty()) return null
        rules[host]?.let { return host to it }
        var start = 0
        while (true) {
            val dot = host.indexOf('.', start)
            if (dot < 0 || dot + 1 >= host.length) return null
            val parent = host.substring(dot + 1)
            rules[parent]?.let { return parent to it }
            start = dot + 1
        }
    }
}
