package com.kavach.app.core.blocklist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The matcher parses lists written by other people, so the parsing tests below are
 * taken from the actual formats the upstream feeds ship in.
 */
class DomainMatcherTest {

    // ---- matching ----------------------------------------------------------

    @Test
    fun `matches an exact domain`() {
        val m = DomainMatcher.of(listOf("doubleclick.net"))
        assertEquals("doubleclick.net", m.match("doubleclick.net"))
    }

    @Test
    fun `matches a subdomain and reports the entry that fired`() {
        val m = DomainMatcher.of(listOf("doubleclick.net"))
        assertEquals("doubleclick.net", m.match("stats.g.doubleclick.net"))
        assertEquals("doubleclick.net", m.match("a.b.c.d.e.doubleclick.net"))
    }

    @Test
    fun `does not match a domain that merely ends with the same characters`() {
        val m = DomainMatcher.of(listOf("example.com"))
        assertNull(m.match("notexample.com"))
        assertNull(m.match("badexample.com"))
    }

    @Test
    fun `does not match a parent of the entry`() {
        val m = DomainMatcher.of(listOf("ads.example.com"))
        assertNull(m.match("example.com"))
        assertEquals("ads.example.com", m.match("eu.ads.example.com"))
    }

    @Test
    fun `normalises case trailing dots and wildcards`() {
        val m = DomainMatcher.of(listOf("*.Tracker.IO."))
        assertEquals("tracker.io", m.match("TRACKER.io"))
        assertEquals("tracker.io", m.match("sub.tracker.io."))
    }

    @Test
    fun `an empty matcher never matches`() {
        assertNull(DomainMatcher.EMPTY.match("anything.example"))
        assertEquals(0, DomainMatcher.EMPTY.size)
        assertFalse(DomainMatcher.EMPTY.contains("anything.example"))
    }

    @Test
    fun `handles empty and degenerate hosts without throwing`() {
        val m = DomainMatcher.of(listOf("example.com"))
        assertNull(m.match(""))
        assertNull(m.match("."))
        assertNull(m.match("com"))
    }

    @Test
    fun `deduplicates on construction`() {
        val m = DomainMatcher.of(listOf("a.com", "A.COM", "a.com.", "*.a.com"))
        assertEquals(1, m.size)
    }

    @Test
    fun `contains mirrors match`() {
        val m = DomainMatcher.of(listOf("example.com"))
        assertTrue(m.contains("api.example.com"))
        assertFalse(m.contains("example.org"))
    }

    // ---- parsing -----------------------------------------------------------

    @Test
    fun `parses a plain domain list line`() {
        assertEquals("doubleclick.net", DomainMatcher.parseLine("doubleclick.net"))
        assertEquals("doubleclick.net", DomainMatcher.parseLine("  doubleclick.net  "))
    }

    @Test
    fun `parses a hosts file line`() {
        assertEquals("ads.example.com", DomainMatcher.parseLine("0.0.0.0 ads.example.com"))
        assertEquals("ads.example.com", DomainMatcher.parseLine("127.0.0.1\tads.example.com"))
        assertEquals("ads.example.com", DomainMatcher.parseLine(":: ads.example.com"))
    }

    @Test
    fun `parses an AdBlock network rule`() {
        assertEquals("tracker.io", DomainMatcher.parseLine("||tracker.io^"))
        assertEquals("tracker.io", DomainMatcher.parseLine("||tracker.io^\$third-party"))
        assertEquals("tracker.io", DomainMatcher.parseLine("||tracker.io/path"))
    }

    @Test
    fun `skips comments and section markers`() {
        assertNull(DomainMatcher.parseLine("# a comment"))
        assertNull(DomainMatcher.parseLine("! adblock comment"))
        assertNull(DomainMatcher.parseLine("[Adblock Plus 2.0]"))
        assertNull(DomainMatcher.parseLine(""))
        assertNull(DomainMatcher.parseLine("   "))
    }

    @Test
    fun `skips cosmetic rules a DNS layer cannot honour`() {
        assertNull(DomainMatcher.parseLine("example.com##.ad-banner"))
        assertNull(DomainMatcher.parseLine("example.com#@#.ad-banner"))
        assertNull(DomainMatcher.parseLine("example.com#?#div:has(.ad)"))
    }

    @Test
    fun `skips exception rules`() {
        assertNull(DomainMatcher.parseLine("@@||example.com^"))
    }

    @Test
    fun `strips a trailing inline comment`() {
        assertEquals("ads.example.com", DomainMatcher.parseLine("ads.example.com # tracker"))
    }

    @Test
    fun `refuses entries that would break the device`() {
        assertNull(DomainMatcher.parseLine("127.0.0.1 localhost"))
        assertNull(DomainMatcher.parseLine("::1 ip6-localhost"))
        assertNull(DomainMatcher.parseLine("0.0.0.0 broadcasthost"))
        assertNull(DomainMatcher.parseLine("0.0.0.0 localhost.localdomain"))
    }

    @Test
    fun `refuses malformed entries`() {
        assertNull(DomainMatcher.parseLine("nodot"))
        assertNull(DomainMatcher.parseLine("has space.com extra"))
        assertNull(DomainMatcher.parseLine("bad!char.com"))
        assertNull(DomainMatcher.parseLine("a".repeat(300) + ".com"))
    }

    @Test
    fun `a parsed list round-trips into a working matcher`() {
        val lines = listOf(
            "# Kavach test list",
            "0.0.0.0 ads.example.com",
            "||tracker.io^",
            "analytics.vendor.net",
            "example.com##.banner",
            "127.0.0.1 localhost",
        )
        val matcher = DomainMatcher.of(lines.mapNotNull { DomainMatcher.parseLine(it) })

        assertEquals(3, matcher.size)
        assertTrue(matcher.contains("ads.example.com"))
        assertTrue(matcher.contains("eu.tracker.io"))
        assertTrue(matcher.contains("analytics.vendor.net"))
        assertFalse(matcher.contains("localhost"))
        assertFalse(matcher.contains("example.com"))
    }
}
