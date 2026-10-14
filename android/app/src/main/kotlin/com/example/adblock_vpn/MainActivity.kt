package com.example.adblock_vpn

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import androidx.core.content.ContextCompat
import de.blinkt.openvpn.VpnProfile
import de.blinkt.openvpn.core.ConfigParser
import de.blinkt.openvpn.core.OpenVPNService
import de.blinkt.openvpn.core.ProfileManager
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import java.io.StringReader
import java.util.concurrent.Executors

class MainActivity : FlutterActivity() {

    private val CHANNEL = "com.example.adblock_vpn/vpn"
    private val VPN_REQUEST_CODE = 24601
    private var pendingResult: MethodChannel.Result? = null
    private var pendingProfile: VpnProfile? = null
    private val executor = Executors.newSingleThreadExecutor()
    private var requestGeneration = 0
    private lateinit var channel: MethodChannel

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        OpenVPNService.setNotificationActivityClass(MainActivity::class.java)
        channel = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL)
        channel.setMethodCallHandler { call, result ->
            when (call.method) {
                "getCompatibilitySettings" -> {
                    val prefs = getSharedPreferences("local_dns", MODE_PRIVATE)
                    result.success(mapOf(
                        "compatibility" to prefs.getBoolean("compatibility", false),
                        "automaticHdoBypass" to prefs.getBoolean("automaticHdoBypass", true),
                        "allowedDomains" to prefs.getStringSet("allowedDomains", emptySet())!!.sorted(),
                        "excludedPackages" to prefs.getStringSet("excludedPackages", emptySet())!!.sorted(),
                        "recentBlockedDomains" to AdBlockVpnService.recentBlockedDomains()
                    ))
                }
                "getCompatibilityApps" -> executor.execute {
                    try {
                        val apps = packageManager.queryIntentActivities(
                            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
                        ).map { info ->
                            mapOf("packageName" to info.activityInfo.packageName,
                                "label" to info.loadLabel(packageManager).toString())
                        }.filter { it["packageName"] != packageName }
                            .distinctBy { it["packageName"] }
                            .sortedBy { it["label"]?.lowercase() }
                        runOnUiThread { result.success(apps) }
                    } catch (e: Exception) {
                        runOnUiThread { result.error("APPS_UNAVAILABLE", e.message, null) }
                    }
                }
                "clearRecentBlockedDomains" -> {
                    AdBlockVpnService.clearRecentBlockedDomains()
                    result.success(null)
                }
                "setCompatibilitySettings" -> {
                    if (pendingResult != null || AdBlockVpnService.state != "disconnected" ||
                        RemoteVpnService.state !in listOf("disconnected", "error")) {
                        result.error("BUSY", "Stop protection before changing compatibility settings.", null)
                    } else try {
                        val domains = LocalDnsPolicy.parseAllowedDomains(
                            call.argument<List<String>>("allowedDomains") ?: emptyList()
                        )
                        val excluded = (call.argument<List<String>>("excludedPackages") ?: emptyList()).toSet()
                        require(excluded.size <= 100 && excluded.all {
                            it != packageName && it.length <= 255 &&
                                it.matches(Regex("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+"))
                        }) { "Select up to 100 apps to bypass protection." }
                        getSharedPreferences("local_dns", MODE_PRIVATE).edit()
                            .putBoolean("compatibility", call.argument<Boolean>("compatibility") ?: false)
                            .putBoolean("automaticHdoBypass", call.argument<Boolean>("automaticHdoBypass") ?: true)
                            .putStringSet("allowedDomains", domains)
                            .putStringSet("excludedPackages", excluded).apply()
                        result.success(null)
                    } catch (e: IllegalArgumentException) {
                        result.error("INVALID_DOMAIN", e.message, null)
                    }
                }
                "startVpn" -> {
                    if (pendingResult != null) result.error("BUSY", "VPN request pending", null)
                    else {
                        pendingResult = result
                        pendingProfile = null
                        requestVpnPermission()
                    }
                }
                "startRemoteVpn" -> {
                    val config = call.argument<String>("config")
                    val name = call.argument<String>("name") ?: "VPN Gate"
                    if (pendingResult != null) result.error("BUSY", "VPN request pending", null)
                    else if (config == null || config.length > 256 * 1024) {
                        result.error("INVALID_CONFIG", "Invalid VPN profile", null)
                    } else {
                        pendingResult = result
                        val generation = ++requestGeneration
                        executor.execute {
                            try {
                                val parser = ConfigParser()
                                parser.parseConfig(StringReader(config))
                                val profile = parser.convertProfile()
                                profile.mName = name
                                profile.mProfileCreator = packageName
                                profile.mUsername = "vpn"
                                profile.mPassword = "vpn"
                                profile.mOverrideDNS = true
                                profile.mDNS1 = "94.140.14.14"
                                profile.mDNS2 = "94.140.15.15"
                                profile.mUseDefaultRoute = true
                                profile.mUseDefaultRoutev6 = true
                                profile.mBlockUnusedAddressFamilies = true
                                profile.mAllowAppVpnBypass = false
                                profile.mAllowedAppsVpnAreDisallowed = true
                                profile.mAllowedAppsVpn = HashSet(AppCompatibility.excludedPackages(this))
                                val error = profile.checkProfile(this)
                                require(error == de.blinkt.openvpn.R.string.no_error_found) { getString(error) }
                                runOnUiThread {
                                    if (pendingResult != null && generation == requestGeneration) {
                                        pendingProfile = profile
                                        requestVpnPermission()
                                    }
                                }
                            } catch (e: Exception) {
                                runOnUiThread {
                                    if (generation == requestGeneration) finishRequest("INVALID_CONFIG", e.message)
                                }
                            }
                        }
                    }
                }
                "stopVpn" -> {
                    finishRequest("CANCELLED", "VPN request cancelled")
                    disconnectLocalVpn()
                    if (RemoteVpnService.state != "disconnected") {
                        RemoteVpnService.state = "disconnecting"
                        startService(Intent(this, RemoteVpnService::class.java).apply {
                            action = OpenVPNService.DISCONNECT_VPN
                        })
                    }
                    result.success(null)
                }
                "isRunning" -> {
                    result.success(AdBlockVpnService.isRunning)
                }
                "getState" -> result.success(mapOf(
                    "localState" to AdBlockVpnService.state,
                    "blockedCount" to AdBlockVpnService.totalBlocked,
                    "localError" to AdBlockVpnService.lastError,
                    "remoteState" to RemoteVpnService.state,
                    "remoteError" to RemoteVpnService.lastError,
                    "serverName" to RemoteVpnService.serverName,
                    "bytesIn" to RemoteVpnService.bytesIn,
                    "bytesOut" to RemoteVpnService.bytesOut
                ))
                else -> result.notImplemented()
            }
        }

        // Let the service call back into Dart with stats.
        AdBlockVpnService.statsChannel = channel
    }

    private fun requestVpnPermission() {
        try {
            val intent = VpnService.prepare(this)
            if (intent != null) startActivityForResult(intent, VPN_REQUEST_CODE)
            else launchVpn()
        } catch (e: Exception) {
            finishRequest("START_FAILED", e.message)
        }
    }

    private fun launchVpn() {
        if (pendingResult == null) return
        try {
            val profile = pendingProfile
            if (profile == null) {
                ContextCompat.startForegroundService(this,
                    Intent(this, AdBlockVpnService::class.java).apply {
                        action = AdBlockVpnService.ACTION_CONNECT
                    })
            } else {
                disconnectLocalVpn()
                ProfileManager.setTemporaryProfile(this, profile)
                val intent = profile.prepareStartService(this)
                    ?: throw IllegalStateException("Unable to prepare OpenVPN service")
                intent.setClass(this, RemoteVpnService::class.java)
                RemoteVpnService.serverName = profile.mName
                RemoteVpnService.lastError = ""
                RemoteVpnService.bytesIn = 0
                RemoteVpnService.bytesOut = 0
                RemoteVpnService.state = "connecting"
                ContextCompat.startForegroundService(this, intent)
            }
            // Accepted, not yet connected. Dart polls actual tunnel readiness.
            pendingResult?.success(null)
            pendingResult = null
            pendingProfile = null
        } catch (e: Exception) {
            RemoteVpnService.state = "error"
            RemoteVpnService.lastError = e.message ?: "Unable to start OpenVPN"
            finishRequest("START_FAILED", e.message)
        }
    }

    private fun finishRequest(code: String, message: String?) {
        requestGeneration++
        if (code == "CANCELLED") {
            // Stopping a pending permission/profile request is an expected
            // user action, not a failed start operation.
            pendingResult?.success(null)
        } else {
            pendingResult?.error(code, message ?: code, null)
        }
        pendingResult = null
        pendingProfile = null
    }

    private fun disconnectLocalVpn() {
        // VpnService may remain bound to Android after stopService(). Deliver
        // an explicit command so the TUN and DNS sockets close immediately.
        startService(Intent(this, AdBlockVpnService::class.java).apply {
            action = AdBlockVpnService.ACTION_DISCONNECT
        })
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == VPN_REQUEST_CODE) {
            if (resultCode == Activity.RESULT_OK) {
                launchVpn()
            } else {
                finishRequest("PERMISSION_DENIED", "VPN permission was denied")
            }
            pendingResult = null
        } else {
            super.onActivityResult(requestCode, resultCode, data)
        }
    }

    override fun onDestroy() {
        finishRequest("CANCELLED", "Activity was closed")
        AdBlockVpnService.statsChannel = null
        executor.shutdownNow()
        super.onDestroy()
    }
}
