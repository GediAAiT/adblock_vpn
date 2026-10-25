package com.example.adblock_vpn

import java.util.Locale

/** Immutable settings for a single local VPN session. */
internal data class LocalDnsPolicy(
    val compatibility: Boolean = false,
    val allowedDomains: Set<String> = emptySet()
) {
    fun isAllowed(host: String): Boolean = normalize(host) in allowedDomains

    fun bypassBlocklist(host: String): Boolean {
        val normalized = normalize(host)
        if (normalized in allowedDomains) return true
        if (isEssentialStreamingHost(normalized)) return true
        return compatibility && normalized in ENCRYPTED_DNS_HOSTS
    }

    /**
     * Uses standard non-filtering upstream resolvers (Cloudflare & Google) so
     * apps and backend servers can never fingerprint AdGuard DNS (94.140.14.14)
     * as the active recursive resolver. All ad blocking is performed locally in
     * stealth mode.
     */
    fun resolvers(host: String): List<String> =
        if (compatibility || isAllowed(host)) listOf("1.1.1.1", "1.0.0.1")
        else listOf("8.8.8.8", "1.1.1.1")

    companion object {
        private val ENCRYPTED_DNS_HOSTS = setOf(
            "dns.google", "dns.google.com", "8888.google",
            "cloudflare-dns.com", "one.one.one.one", "1dot1dot1dot1.cloudflare-dns.com",
            "dns.quad9.net", "dns9.quad9.net", "dns10.quad9.net", "dns11.quad9.net",
            "doh.opendns.com", "dns.opendns.com", "familyshield.opendns.com",
            "dns.adguard-dns.com", "dns.adguard.com", "dns.nextdns.io",
            "doh.cleanbrowsing.org", "dot.cleanbrowsing.org",
            "doh.dns.sb", "dot.sb", "dns.alidns.com", "doh.pub", "dot.pub"
        )

        /**
         * Core video catalog, poster, subtitle, and remote-config hosts required
         * by HDO Box, MovieBox, and Playfy so video playback never breaks while
         * third-party ad networks are 100% blocked.
         */
        private val ESSENTIAL_STREAMING_EXACT_HOSTS = setOf(
            "themoviedb.org", "api.themoviedb.org", "tmdb.org", "image.tmdb.org",
            "aoneroom.com", "h5-static.aoneroom.com", "pbcdnw.aoneroom.com", "pacdn.aoneroom.com",
            "febbox.com", "www.febbox.com",
            "opensubtitles.com", "www.opensubtitles.com", "api.opensubtitles.com",
            "opensubtitles.org", "www.opensubtitles.org", "dl.opensubtitles.org", "vip.opensubtitles.org",
            "subdl.com", "api.subdl.com",
            "firebaseremoteconfig.googleapis.com", "firebaseinstallations.googleapis.com",
            "firebase.googleapis.com", "fcm.googleapis.com", "mtalk.google.com"
        )

        internal val AD_SUBDOMAIN_LABELS = setOf(
            "ad", "ads", "adserver", "adservice", "adservices", "adsystem",
            "adtrack", "adtracker", "adtracking", "adsdk", "adnetwork",
            "adexchange", "adbanner", "adbanners", "adclick", "adclicks",
            "adpopup", "popads", "vastads", "httpdns", "adlog", "adstat", "adstats"
        )

        fun isEssentialStreamingHost(rawHost: String): Boolean {
            val normalizedHost = normalize(rawHost)
            if (normalizedHost in ESSENTIAL_STREAMING_EXACT_HOSTS) return true
            if (normalizedHost.endsWith(".aoneroom.com") ||
                normalizedHost.endsWith(".themoviedb.org") ||
                normalizedHost.endsWith(".tmdb.org") ||
                normalizedHost.endsWith(".febbox.com")) {
                val firstLabel = normalizedHost.substringBefore('.')
                return firstLabel !in AD_SUBDOMAIN_LABELS &&
                    !firstLabel.startsWith("ad-") &&
                    !firstLabel.startsWith("ads-") &&
                    !firstLabel.startsWith("adx-")
            }
            return false
        }

        fun isHeuristicAdHost(rawHost: String): Boolean {
            val host = normalize(rawHost)
            if (host.isEmpty() || !host.contains('.') || isEssentialStreamingHost(host)) return false
            val labels = host.split('.')
            if (labels.size < 2) return false
            val subLabels = if (labels.size > 2) labels.dropLast(2) else listOf(labels.first())
            for (label in subLabels) {
                if (label in AD_SUBDOMAIN_LABELS) return true
                if (label.startsWith("ad-host-backup") ||
                    label.startsWith("httpdns-") ||
                    label.startsWith("ads-") ||
                    label.startsWith("adx-") ||
                    label.startsWith("adserver-") ||
                    label.startsWith("adtrack-")) {
                    return true
                }
            }
            return false
        }

        /**
         * Deterministic, non-private, non-loopback stealth sinkhole IPv4 in
         * 198.18.0.0/15 (RFC 2544) so ad domains resolve normally without
         * throwing UnknownHostException or returning 0.0.0.0 / 127.0.0.1.
         */
        fun stealthIpv4ForHost(rawHost: String): ByteArray {
            val h = normalize(rawHost).hashCode()
            val b1 = 18 + ((h ushr 16) and 1)
            val b2 = 1 + ((h ushr 8) and 0xFD)
            val b3 = 1 + (h and 0xFD)
            return byteArrayOf(198.toByte(), b1.toByte(), b2.toByte(), b3.toByte())
        }

        /**
         * Deterministic stealth sinkhole IPv6 in 2001:db8:ad::/48.
         */
        fun stealthIpv6ForHost(rawHost: String): ByteArray {
            val h = normalize(rawHost).hashCode()
            return byteArrayOf(
                0x20, 0x01, 0x0d, 0xb8.toByte(), 0x00, 0xad.toByte(), 0x00, 0x00,
                0x00, 0x00, 0x00, 0x00,
                ((h ushr 24) and 0xFF).toByte(),
                ((h ushr 16) and 0xFF).toByte(),
                ((h ushr 8) and 0xFF).toByte(),
                ((h and 0xFE) or 1).toByte()
            )
        }

        fun normalize(host: String): String = host.trim().trimEnd('.').lowercase(Locale.ROOT)

        fun parseAllowedDomains(domains: List<String>): Set<String> {
            require(domains.size <= 100) { "Use at most 100 domain exceptions." }
            return domains.map { raw ->
                val host = normalize(raw)
                require(host.length in 3..253 && host.contains('.') &&
                    host.split('.').all { label ->
                        label.length in 1..63 && label.first() != '-' && label.last() != '-' &&
                            label.all { it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '_' }
                    } && !host.all { it.isDigit() || it == '.' }) {
                    "Enter domain names only, without URLs, IP addresses or wildcards."
                }
                host
            }.toSet()
        }
    }
}
