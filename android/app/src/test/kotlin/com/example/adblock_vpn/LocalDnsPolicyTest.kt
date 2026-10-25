package com.example.adblock_vpn

import java.net.InetAddress
import org.junit.Assert.*
import org.junit.Test

class LocalDnsPolicyTest {
    @Test fun exceptionsAreExactAndUseNonAdGuardDnsInAllModes() {
        val policy = LocalDnsPolicy(allowedDomains = LocalDnsPolicy.parseAllowedDomains(
            listOf(" API.Example.com. ", "api.example.com")
        ))
        assertEquals(1, policy.allowedDomains.size)
        assertTrue(policy.bypassBlocklist("API.example.com."))
        assertFalse(policy.bypassBlocklist("sub.api.example.com"))
        assertFalse(policy.bypassBlocklist("example.com"))
        assertFalse(policy.bypassBlocklist("api.example.com.evil.test"))
        assertEquals(listOf("1.1.1.1", "1.0.0.1"), policy.resolvers("api.example.com"))
        assertEquals(listOf("8.8.8.8", "1.1.1.1"), policy.resolvers("ads.example.com"))
    }

    @Test fun compatibilityPermitsResolverHostsButDoesNotExemptAdHosts() {
        val policy = LocalDnsPolicy(compatibility = true)
        assertTrue(policy.bypassBlocklist("dns.google"))
        assertFalse(policy.bypassBlocklist("doubleclick.net"))
        assertFalse(LocalDnsPolicy().bypassBlocklist("dns.google"))
        assertEquals(listOf("1.1.1.1", "1.0.0.1"), policy.resolvers("example.com"))
    }

    @Test fun stealthSinkholeIpsPassStandardInetAddressChecks() {
        val ipv4 = InetAddress.getByAddress(LocalDnsPolicy.stealthIpv4ForHost("googleads.g.doubleclick.net"))
        assertFalse(ipv4.isAnyLocalAddress)
        assertFalse(ipv4.isLoopbackAddress)
        assertFalse(ipv4.isLinkLocalAddress)
        assertFalse(ipv4.isSiteLocalAddress)
        assertFalse(ipv4.isMulticastAddress)
        assertNotEquals("0.0.0.0", ipv4.hostAddress)
        assertNotEquals("127.0.0.1", ipv4.hostAddress)

        val ipv6 = InetAddress.getByAddress(LocalDnsPolicy.stealthIpv6ForHost("googleads.g.doubleclick.net"))
        assertFalse(ipv6.isAnyLocalAddress)
        assertFalse(ipv6.isLoopbackAddress)
        assertFalse(ipv6.isLinkLocalAddress)
        assertFalse(ipv6.isSiteLocalAddress)
        assertFalse(ipv6.isMulticastAddress)
    }

    @Test fun protectsEssentialStreamingHostsWhileBlockingAdSubdomains() {
        val policy = LocalDnsPolicy()
        assertTrue(policy.bypassBlocklist("h5-static.aoneroom.com"))
        assertTrue(policy.bypassBlocklist("pbcdnw.aoneroom.com"))
        assertTrue(policy.bypassBlocklist("api.themoviedb.org"))
        assertTrue(policy.bypassBlocklist("image.tmdb.org"))
        assertFalse(policy.bypassBlocklist("ads.aoneroom.com"))
        assertTrue(LocalDnsPolicy.isHeuristicAdHost("ads.aoneroom.com"))
        assertTrue(LocalDnsPolicy.isHeuristicAdHost("adserver.playfy.tv"))
        assertTrue(LocalDnsPolicy.isHeuristicAdHost("ad-host-backup-asia.oss-ap-southeast-1.aliyuncs.com"))
        assertTrue(LocalDnsPolicy.isHeuristicAdHost("httpdns-api.example.com"))
        assertFalse(LocalDnsPolicy.isHeuristicAdHost("h5-static.aoneroom.com"))
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
