package com.example.adblock_vpn

import java.util.Locale

/** Immutable settings for a single local VPN session. */
internal data class LocalDnsPolicy(
    val compatibility: Boolean = false,
    val allowedDomains: Set<String> = emptySet()
) {
    fun isAllowed(host: String): Boolean = normalize(host) in allowedDomains

    fun bypassBlocklist(host: String): Boolean = isAllowed(host) ||
        (compatibility && normalize(host) in ENCRYPTED_DNS_HOSTS)

    fun resolvers(host: String): List<String> =
        if (compatibility || isAllowed(host)) listOf("1.1.1.1", "1.0.0.1")
        else listOf("94.140.14.14", "94.140.15.15")

    companion object {
        private val ENCRYPTED_DNS_HOSTS = setOf(
            "dns.google", "cloudflare-dns.com", "one.one.one.one", "dns.quad9.net",
            "doh.opendns.com", "dns.adguard-dns.com", "dns.nextdns.io"
        )

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
