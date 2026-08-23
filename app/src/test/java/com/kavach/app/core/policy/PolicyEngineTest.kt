package com.kavach.app.core.policy

import com.kavach.app.core.blocklist.DomainMatcher
import com.kavach.app.core.model.NetworkMode
import com.kavach.app.core.model.Verdict
import com.kavach.app.core.model.Zone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every precedence branch in [PolicyEngine] has a test.
 *
 * This is the one class where a silent mistake is unacceptable: a wrong branch does
 * not crash, it just quietly lets traffic through while the UI reports that the app
 * is protected. That failure mode is worse than no firewall, so the coverage here is
 * exhaustive rather than representative.
 */
class PolicyEngineTest {

    private val pkg = "com.example.app"

    private fun snapshot(
        policies: Map<String, AppPolicy> = emptyMap(),
        perAppRules: Map<String, Map<String, Boolean>> = emptyMap(),
        globalRules: Map<String, Boolean> = emptyMap(),
        trackers: Set<String> = emptySet(),
        ads: Set<String> = emptySet(),
        defaultZone: Zone = Zone.DEFAULT,
        paused: Boolean = false,
    ) = PolicySnapshot(
        policies = policies,
        perAppRules = perAppRules,
        globalRules = globalRules,
        trackers = DomainMatcher.of(trackers),
        ads = DomainMatcher.of(ads),
        defaultZone = defaultZone,
        paused = paused,
        loggingEnabled = true,
        dohEndpointId = "cloudflare",
    )

    private fun policy(
        mode: NetworkMode = NetworkMode.FILTERED,
        zone: Zone = Zone.SOCIAL,
        trackers: Boolean = true,
        ads: Boolean = true,
        learning: Boolean = false,
    ) = mapOf(
        pkg to AppPolicy(
            packageName = pkg,
            zone = zone,
            networkMode = mode,
            blockTrackers = trackers,
            blockAds = ads,
            learningMode = learning,
        )
    )

    // 1. Global pause.

    @Test
    fun `pause allows everything including known ads`() {
        val s = snapshot(policies = policy(), ads = setOf("ads.example.com"), paused = true)
        val v = PolicyEngine.evaluate(s, pkg, "ads.example.com")
        assertTrue(v.allowed)
        assertEquals(Verdict.Reason.UNMANAGED, v.reason)
    }

    // 2. Unattributable traffic must fail open.

    @Test
    fun `unknown package is allowed and marked unattributed`() {
        val s = snapshot(ads = setOf("ads.example.com"))
        val nullPkg = PolicyEngine.evaluate(s, null, "ads.example.com")
        val emptyPkg = PolicyEngine.evaluate(s, "", "ads.example.com")

        assertTrue(nullPkg.allowed)
        assertEquals(Verdict.Reason.UNATTRIBUTED, nullPkg.reason)
        assertTrue(emptyPkg.allowed)
        assertEquals(Verdict.Reason.UNATTRIBUTED, emptyPkg.reason)
    }

    // 3. Per-app rules.

    @Test
    fun `per-app allow beats the ad list`() {
        val s = snapshot(
            policies = policy(),
            perAppRules = mapOf(pkg to mapOf("ads.example.com" to true)),
            ads = setOf("ads.example.com"),
        )
        val v = PolicyEngine.evaluate(s, pkg, "ads.example.com")
        assertTrue(v.allowed)
        assertEquals(Verdict.Reason.USER_ALLOW, v.reason)
    }

    @Test
    fun `per-app block beats an otherwise clean domain`() {
        val s = snapshot(
            policies = policy(),
            perAppRules = mapOf(pkg to mapOf("cdn.example.com" to false)),
        )
        val v = PolicyEngine.evaluate(s, pkg, "cdn.example.com")
        assertFalse(v.allowed)
        assertEquals(Verdict.Reason.USER_BLOCK, v.reason)
    }

    @Test
    fun `a rule on a parent domain covers its subdomains`() {
        val s = snapshot(
            policies = policy(),
            perAppRules = mapOf(pkg to mapOf("example.com" to false)),
        )
        val v = PolicyEngine.evaluate(s, pkg, "deep.api.example.com")
        assertFalse(v.allowed)
        assertEquals("example.com", v.detail)
    }

    @Test
    fun `the more specific rule wins`() {
        val s = snapshot(
            policies = policy(),
            perAppRules = mapOf(
                pkg to mapOf("example.com" to false, "api.example.com" to true),
            ),
        )
        val v = PolicyEngine.evaluate(s, pkg, "api.example.com")
        assertTrue(v.allowed)
        assertEquals("api.example.com", v.detail)
    }

    @Test
    fun `a sibling domain is not matched by a partial string`() {
        // "notexample.com" must not be caught by a rule on "example.com".
        val s = snapshot(
            policies = policy(),
            perAppRules = mapOf(pkg to mapOf("example.com" to false)),
        )
        val v = PolicyEngine.evaluate(s, pkg, "notexample.com")
        assertTrue(v.allowed)
        assertEquals(Verdict.Reason.DEFAULT_POLICY, v.reason)
    }

    // 4. Global rules.

    @Test
    fun `global rule applies when there is no per-app rule`() {
        val s = snapshot(policies = policy(), globalRules = mapOf("tracker.io" to false))
        val v = PolicyEngine.evaluate(s, pkg, "tracker.io")
        assertFalse(v.allowed)
        assertEquals(Verdict.Reason.GLOBAL_BLOCK, v.reason)
    }

    @Test
    fun `per-app rule overrides the global rule`() {
        val s = snapshot(
            policies = policy(),
            perAppRules = mapOf(pkg to mapOf("tracker.io" to true)),
            globalRules = mapOf("tracker.io" to false),
        )
        val v = PolicyEngine.evaluate(s, pkg, "tracker.io")
        assertTrue(v.allowed)
        assertEquals(Verdict.Reason.USER_ALLOW, v.reason)
    }

    @Test
    fun `global allow is reported with its own reason`() {
        val s = snapshot(
            policies = policy(),
            globalRules = mapOf("example.com" to true),
            ads = setOf("example.com"),
        )
        val v = PolicyEngine.evaluate(s, pkg, "example.com")
        assertTrue(v.allowed)
        assertEquals(Verdict.Reason.GLOBAL_ALLOW, v.reason)
    }

    // 5. Unfiltered mode.

    @Test
    fun `allow all mode ignores both blocklists`() {
        val s = snapshot(
            policies = policy(mode = NetworkMode.ALLOW_ALL, zone = Zone.OPEN),
            ads = setOf("ads.example.com"),
            trackers = setOf("ads.example.com"),
        )
        val v = PolicyEngine.evaluate(s, pkg, "ads.example.com")
        assertTrue(v.allowed)
        assertEquals(Verdict.Reason.UNMANAGED, v.reason)
    }

    // 6. No network.

    @Test
    fun `block all refuses even a harmless domain`() {
        val s = snapshot(policies = policy(mode = NetworkMode.BLOCK_ALL, zone = Zone.OFFLINE))
        val v = PolicyEngine.evaluate(s, pkg, "example.com")
        assertFalse(v.allowed)
        assertEquals(Verdict.Reason.APP_OFFLINE, v.reason)
    }

    // 7. Allowlist only.

    @Test
    fun `allowlist only refuses anything not explicitly allowed`() {
        val s = snapshot(policies = policy(mode = NetworkMode.ALLOWLIST_ONLY, zone = Zone.VAULT))
        val v = PolicyEngine.evaluate(s, pkg, "analytics.vendor.com")
        assertFalse(v.allowed)
        assertEquals(Verdict.Reason.NOT_ON_ALLOWLIST, v.reason)
    }

    @Test
    fun `allowlist only permits a domain on the allowlist`() {
        val s = snapshot(
            policies = policy(mode = NetworkMode.ALLOWLIST_ONLY, zone = Zone.VAULT),
            perAppRules = mapOf(pkg to mapOf("mybank.example" to true)),
        )
        val v = PolicyEngine.evaluate(s, pkg, "api.mybank.example")
        assertTrue(v.allowed)
        assertEquals(Verdict.Reason.USER_ALLOW, v.reason)
    }

    // 8 + 9. Compiled lists.

    @Test
    fun `ad list blocks and reports the matched suffix`() {
        val s = snapshot(policies = policy(), ads = setOf("doubleclick.net"))
        val v = PolicyEngine.evaluate(s, pkg, "pagead2.doubleclick.net")
        assertFalse(v.allowed)
        assertEquals(Verdict.Reason.AD_LIST, v.reason)
        assertEquals("doubleclick.net", v.detail)
    }

    @Test
    fun `tracker list blocks`() {
        val s = snapshot(policies = policy(), trackers = setOf("graph.facebook.com"))
        val v = PolicyEngine.evaluate(s, pkg, "graph.facebook.com")
        assertFalse(v.allowed)
        assertEquals(Verdict.Reason.TRACKER_LIST, v.reason)
    }

    @Test
    fun `ads are reported before trackers when a domain is on both lists`() {
        val s = snapshot(
            policies = policy(),
            ads = setOf("both.example.com"),
            trackers = setOf("both.example.com"),
        )
        assertEquals(
            Verdict.Reason.AD_LIST,
            PolicyEngine.evaluate(s, pkg, "both.example.com").reason,
        )
    }

    @Test
    fun `turning off the ad toggle stops ad blocking only`() {
        val s = snapshot(
            policies = policy(ads = false),
            ads = setOf("ads.example.com"),
            trackers = setOf("tracker.example.com"),
        )
        assertTrue(PolicyEngine.evaluate(s, pkg, "ads.example.com").allowed)
        assertFalse(PolicyEngine.evaluate(s, pkg, "tracker.example.com").allowed)
    }

    @Test
    fun `turning off the tracker toggle stops tracker blocking only`() {
        val s = snapshot(
            policies = policy(trackers = false),
            ads = setOf("ads.example.com"),
            trackers = setOf("tracker.example.com"),
        )
        assertFalse(PolicyEngine.evaluate(s, pkg, "ads.example.com").allowed)
        assertTrue(PolicyEngine.evaluate(s, pkg, "tracker.example.com").allowed)
    }

    // 10. Default allow.

    @Test
    fun `a clean domain is allowed under the default policy`() {
        val s = snapshot(policies = policy())
        val v = PolicyEngine.evaluate(s, pkg, "www.wikipedia.org")
        assertTrue(v.allowed)
        assertEquals(Verdict.Reason.DEFAULT_POLICY, v.reason)
    }

    @Test
    fun `an app with no policy row falls back to the default zone`() {
        val vault = snapshot(policies = emptyMap(), defaultZone = Zone.VAULT)
        val v = PolicyEngine.evaluate(vault, "com.unknown.app", "anything.example")
        assertFalse(v.allowed)
        assertEquals(Verdict.Reason.NOT_ON_ALLOWLIST, v.reason)
    }

    // Learning mode.

    @Test
    fun `learning mode downgrades a block to an allow and says what it would have done`() {
        val s = snapshot(
            policies = policy(learning = true),
            ads = setOf("doubleclick.net"),
        )
        val v = PolicyEngine.evaluate(s, pkg, "pagead2.doubleclick.net")
        assertTrue(v.allowed)
        assertEquals(Verdict.Reason.LEARNING, v.reason)
        assertTrue(v.detail!!.startsWith("would block:"))
        assertTrue(v.detail!!.contains(Verdict.Reason.AD_LIST.label))
    }

    @Test
    fun `learning mode does not change an allow`() {
        val s = snapshot(policies = policy(learning = true))
        val v = PolicyEngine.evaluate(s, pkg, "www.wikipedia.org")
        assertTrue(v.allowed)
        assertEquals(Verdict.Reason.DEFAULT_POLICY, v.reason)
    }

    @Test
    fun `learning mode also softens allowlist only and offline`() {
        val allowlist = snapshot(
            policies = policy(mode = NetworkMode.ALLOWLIST_ONLY, learning = true),
        )
        val offline = snapshot(
            policies = policy(mode = NetworkMode.BLOCK_ALL, learning = true),
        )
        assertTrue(PolicyEngine.evaluate(allowlist, pkg, "x.example").allowed)
        assertTrue(PolicyEngine.evaluate(offline, pkg, "x.example").allowed)
    }

    // Normalisation.

    @Test
    fun `hostnames are matched case-insensitively and without a trailing dot`() {
        val s = snapshot(policies = policy(), ads = setOf("doubleclick.net"))
        val v = PolicyEngine.evaluate(s, pkg, "PageAd2.DoubleClick.NET.")
        assertFalse(v.allowed)
        assertEquals(Verdict.Reason.AD_LIST, v.reason)
    }

    @Test
    fun `zone defaults are what the product promises`() {
        assertEquals(NetworkMode.ALLOWLIST_ONLY, Zone.VAULT.defaultNetworkMode)
        assertEquals(NetworkMode.FILTERED, Zone.SOCIAL.defaultNetworkMode)
        assertEquals(NetworkMode.BLOCK_ALL, Zone.OFFLINE.defaultNetworkMode)
        assertEquals(NetworkMode.ALLOW_ALL, Zone.OPEN.defaultNetworkMode)
        assertEquals(Zone.SOCIAL, Zone.DEFAULT)
        // An unrecognised persisted id must never silently become a stricter zone.
        assertEquals(Zone.OPEN, Zone.fromId("nonsense"))
    }
}
