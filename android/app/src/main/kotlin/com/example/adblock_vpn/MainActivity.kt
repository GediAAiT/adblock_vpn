package com.example.adblock_vpn

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {

    private val CHANNEL = "com.example.adblock_vpn/vpn"
    private val VPN_REQUEST_CODE = 24601
    private var pendingResult: MethodChannel.Result? = null
    private lateinit var channel: MethodChannel

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        channel = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL)
        channel.setMethodCallHandler { call, result ->
            when (call.method) {
                "startVpn" -> {
                    pendingResult = result
                    requestVpnPermission()
                }
                "stopVpn" -> {
                    val intent = Intent(this, AdBlockVpnService::class.java)
                    intent.action = AdBlockVpnService.ACTION_DISCONNECT
                    startService(intent)
                    result.success(null)
                }
                "isRunning" -> {
                    result.success(AdBlockVpnService.isRunning)
                }
                else -> result.notImplemented()
            }
        }

        // Let the service call back into Dart with stats.
        AdBlockVpnService.statsChannel = channel
    }

    private fun requestVpnPermission() {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            startActivityForResult(intent, VPN_REQUEST_CODE)
        } else {
            // Permission already granted.
            onActivityResult(VPN_REQUEST_CODE, Activity.RESULT_OK, null)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == VPN_REQUEST_CODE) {
            if (resultCode == Activity.RESULT_OK) {
                val intent = Intent(this, AdBlockVpnService::class.java)
                intent.action = AdBlockVpnService.ACTION_CONNECT
                startService(intent)
                pendingResult?.success(null)
            } else {
                pendingResult?.error("PERMISSION_DENIED", "User denied VPN permission", null)
            }
            pendingResult = null
        } else {
            super.onActivityResult(requestCode, resultCode, data)
        }
    }
}
