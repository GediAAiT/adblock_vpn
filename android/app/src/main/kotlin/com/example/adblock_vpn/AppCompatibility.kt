package com.example.adblock_vpn

import android.content.Context
import android.content.pm.PackageManager

/** Explicit app routing, independent of DNS response tricks. */
internal object AppBypassPolicy {
    const val HDO_PACKAGE = "com.hdoapp.hdo_box"

    fun resolve(automatic: Boolean = false, manual: Set<String>, installed: Set<String>,
                ownPackage: String): Set<String> {
        val requested = manual + ownPackage + if (automatic) setOf(HDO_PACKAGE) else emptySet()
        return requested.intersect(installed)
    }
}

internal object AppCompatibility {
    const val PREF_HDO_BYPASS_MIGRATED = "hdoBypassDefaultOffMigrated"

    fun isAutomaticHdoBypassEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences("local_dns", Context.MODE_PRIVATE)
        if (!prefs.getBoolean(PREF_HDO_BYPASS_MIGRATED, false)) {
            prefs.edit()
                .putBoolean("automaticHdoBypass", false)
                .putBoolean(PREF_HDO_BYPASS_MIGRATED, true)
                .apply()
            return false
        }
        return prefs.getBoolean("automaticHdoBypass", false)
    }

    fun excludedPackages(context: Context): Set<String> {
        val prefs = context.getSharedPreferences("local_dns", Context.MODE_PRIVATE)
        val automatic = isAutomaticHdoBypassEnabled(context)
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
        return AppBypassPolicy.resolve(automatic, manual, installed, context.packageName)
    }
}
