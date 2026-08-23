package com.kavach.app.core.blocklist

/**
 * Suffix-aware domain set.
 *
 * A blocklist entry of `doubleclick.net` must also match `stats.g.doubleclick.net`,
 * so lookups walk the label boundaries from most to least specific. Using a plain
 * HashSet of parent domains keeps this O(number of labels) - about three hash
 * probes for a typical hostname - which matters because this runs on every single
 * DNS question the device makes.
 *
 * Instances are immutable, which is what makes it safe for the tunnel thread to
 * read one while a background worker builds the next.
 */
class DomainMatcher private constructor(private val domains: Set<String>) {

    val size: Int get() = domains.size

    /**
     * Returns the entry that matched, or null.
     *
     * The matched entry rather than a boolean is returned so the Activity screen
     * can tell the user *which* rule fired.
     */
    fun match(host: String): String? {
        if (domains.isEmpty()) return null
        val h = normalise(host)
        if (h.isEmpty()) return null
        if (domains.contains(h)) return h

        var start = 0
        while (true) {
            val dot = h.indexOf('.', start)
            if (dot < 0 || dot + 1 >= h.length) return null
            val parent = h.substring(dot + 1)
            if (domains.contains(parent)) return parent
            start = dot + 1
        }
    }

    fun contains(host: String): Boolean = match(host) != null

    companion object {
        val EMPTY = DomainMatcher(emptySet())

        fun of(domains: Collection<String>): DomainMatcher {
            if (domains.isEmpty()) return EMPTY
            val set = HashSet<String>(domains.size * 2)
            for (raw in domains) {
                val d = normalise(raw)
                if (d.isNotEmpty()) set.add(d)
            }
            return if (set.isEmpty()) EMPTY else DomainMatcher(set)
        }

        /**
         * Parses one line of a blocklist file.
         *
         * Accepts the three formats real-world lists ship in:
         *  - plain domain lists  `doubleclick.net`
         *  - hosts files         `0.0.0.0 doubleclick.net`
         *  - AdBlock network     `||doubleclick.net^`
         *
         * Cosmetic AdBlock rules (`##`, `#@#`, `#?#`) and comments are skipped: they
         * hide page elements, which a DNS layer fundamentally cannot do.
         */
        fun parseLine(line: String): String? {
            var s = line.trim()
            if (s.isEmpty()) return null
            if (s.startsWith("#") || s.startsWith("!") || s.startsWith("[")) return null
            if (s.contains("##") || s.contains("#@#") || s.contains("#?#")) return null
            // @@ is an AdBlock exception rule; Kavach models exceptions as user rules.
            if (s.startsWith("@@")) return null

            val hash = s.indexOf('#')
            if (hash > 0) s = s.substring(0, hash).trim()

            if (s.startsWith("||")) {
                s = s.removePrefix("||")
                s = s.substringBefore('^').substringBefore('$').substringBefore('/')
            } else if (s.contains(' ') || s.contains('\t')) {
                val parts = s.split(' ', '\t').filter { it.isNotBlank() }
                if (parts.size < 2) return null
                val first = parts[0]
                if (first != "0.0.0.0" && first != "127.0.0.1" && first != "::" && first != "::1") {
                    return null
                }
                s = parts[1]
            }

            val d = normalise(s)
            if (d.isEmpty()) return null
            // localhost entries in hosts files are noise, and blocking them would
            // break the device.
            if (d == "localhost" || d == "localhost.localdomain" ||
                d == "local" || d == "ip6-localhost" || d == "broadcasthost"
            ) {
                return null
            }
            if (!isPlausibleDomain(d)) return null
            return d
        }

        private fun normalise(host: String): String {
            var h = host.trim().lowercase()
            while (h.endsWith(".")) h = h.dropLast(1)
            if (h.startsWith("*.")) h = h.substring(2)
            return h
        }

        private fun isPlausibleDomain(d: String): Boolean {
            if (d.length > 253 || !d.contains('.')) return false
            for (c in d) {
                val ok = (c in 'a'..'z') || (c in '0'..'9') || c == '.' || c == '-' || c == '_'
                if (!ok) return false
            }
            return true
        }
    }
}
