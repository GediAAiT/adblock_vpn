package com.example.adblock_vpn

import org.junit.Assert.*
import org.junit.Test

class LocalDnsPolicyTest {
    @Test fun exceptionsAreExactAndUseUnfilteredDnsEvenInStrictMode() {
        val policy = LocalDnsPolicy(allowedDomains = LocalDnsPolicy.parseAllowedDomains(
            listOf(" API.Example.com. ", "api.example.com")
        ))
        assertEquals(1, policy.allowedDomains.size)
        assertTrue(policy.bypassBlocklist("API.example.com."))
        assertFalse(policy.bypassBlocklist("sub.api.example.com"))
        assertFalse(policy.bypassBlocklist("example.com"))
        assertFalse(policy.bypassBlocklist("api.example.com.evil.test"))
        assertEquals(listOf("1.1.1.1", "1.0.0.1"), policy.resolvers("api.example.com"))
        assertEquals(listOf("94.140.14.14", "94.140.15.15"), policy.resolvers("ads.example.com"))
    }

    @Test fun compatibilityPermitsResolverHostsButDoesNotExemptAdHosts() {
        val policy = LocalDnsPolicy(compatibility = true)
        assertTrue(policy.bypassBlocklist("dns.google"))
        assertFalse(policy.bypassBlocklist("doubleclick.net"))
        assertFalse(LocalDnsPolicy().bypassBlocklist("dns.google"))
        assertEquals(listOf("1.1.1.1", "1.0.0.1"), policy.resolvers("example.com"))
    }

    @Test fun rejectsUrlsWildcardsIpsAndMalformedDomains() {
        for (host in listOf("https://example.com", "*.example.com", "1.2.3.4",
            "example.com/path", "bad..example.com", "-bad.example.com", "localhost")) {
            assertThrows(IllegalArgumentException::class.java) {
                LocalDnsPolicy.parseAllowedDomains(listOf(host))
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            LocalDnsPolicy.parseAllowedDomains((0..100).map { "host$it.example.com" })
        }
    }
}
