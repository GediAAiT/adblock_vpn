package com.example.adblock_vpn

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager

/** Explicit app routing, independent of DNS response tricks. */
internal object AppBypassPolicy {
    const val HDO_PACKAGE = "com.hdoapp.hdo_box"
    const val DEFAULT_AUTOMATIC_HDO_BYPASS = false

    fun resolve(automatic: Boolean, manual: Set<String>, installed: Set<String>,
                ownPackage: String): Set<String> {
        val requested = manual + ownPackage + if (automatic) setOf(HDO_PACKAGE) else emptySet()
        return requested.intersect(installed)
    }
}

internal object AppCompatibility {
    @Synchronized fun prepareSettings(context: Context): SharedPreferences {
        val prefs = context.getSharedPreferences("local_dns", Context.MODE_PRIVATE)
        if (prefs.getInt("protectionDefaultsVersion", 0) < 2) {
            // Earlier builds enabled HDO bypass by default, which also allowed
            // its ads. Restore filtering once; users can opt back into bypass.
            val manual = prefs.getStringSet("excludedPackages", emptySet())!!.toSet()
            prefs.edit()
                .putBoolean("automaticHdoBypass", false)
                .putBoolean("compatibility", false)
                .putStringSet("excludedPackages", manual - AppBypassPolicy.HDO_PACKAGE)
                .putInt("protectionDefaultsVersion", 2)
                .apply()
        }
        return prefs
    }

    fun excludedPackages(context: Context): Set<String> {
        val prefs = prepareSettings(context)
        val manual = prefs.getStringSet("excludedPackages", emptySet())!!.toSet()
        val candidates = manual + context.packageName + AppBypassPolicy.HDO_PACKAGE
        val installed = candidates.filter { name ->
            try {
                context.packageManager.getApplicationInfo(name, 0)
                true
            } catch (_: PackageManager.NameNotFoundException) {
                false
            }
        }.toSet()
        return AppBypassPolicy.resolve(prefs.getBoolean("automaticHdoBypass",
            AppBypassPolicy.DEFAULT_AUTOMATIC_HDO_BYPASS),
            manual, installed, context.packageName)
    }
}
