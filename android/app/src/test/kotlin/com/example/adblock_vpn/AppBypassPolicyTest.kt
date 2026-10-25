package com.example.adblock_vpn

import org.junit.Assert.*
import org.junit.Test

class AppBypassPolicyTest {
    private val own = "com.example.adblock_vpn"
    private val hdo = AppBypassPolicy.HDO_PACKAGE

    @Test fun hdoIsProtectedByDefaultWhenAutomaticBypassIsOff() {
        assertEquals(setOf(own), AppBypassPolicy.resolve(
            manual = emptySet(),
            installed = setOf(own, hdo, "com.example.browser"),
            ownPackage = own
        ))
    }

    @Test fun hdoBypassesOnlyWhenExplicitlyEnabled() {
        assertEquals(setOf(own, hdo), AppBypassPolicy.resolve(true, emptySet(),
            setOf(own, hdo, "com.example.browser"), own))
    }

    @Test fun disablingAutomaticBypassRestoresHdoFiltering() {
        assertEquals(setOf(own), AppBypassPolicy.resolve(false, emptySet(), setOf(own, hdo), own))
    }

    @Test fun manualExceptionsSurviveAndUninstalledAppsAreIgnored() {
        assertEquals(setOf(own, hdo), AppBypassPolicy.resolve(false,
            setOf(hdo, "com.example.removed"), setOf(own, hdo), own))
        assertEquals(setOf(own), AppBypassPolicy.resolve(true, emptySet(), setOf(own), own))
    }
}
