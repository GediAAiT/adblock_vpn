package com.example.adblock_vpn

import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import de.blinkt.openvpn.core.ConnectionStatus
import de.blinkt.openvpn.core.OpenVPNService

/** Compatibility adapter for the pinned engine on Android 14 and newer. */
class RemoteVpnService : OpenVPNService() {
    companion object {
        @Volatile var state = "disconnected"
        @Volatile var serverName = ""
        @Volatile var bytesIn = 0L
        @Volatile var bytesOut = 0L
    }

    override fun registerReceiver(receiver: BroadcastReceiver?, filter: IntentFilter): Intent? {
        return if (Build.VERSION.SDK_INT >= 33) {
            super.registerReceiver(receiver, filter, RECEIVER_EXPORTED)
        } else {
            super.registerReceiver(receiver, filter)
        }
    }

    override fun updateState(engineState: String?, message: String?, resourceId: Int,
                             level: ConnectionStatus?, intent: Intent?) {
        super.updateState(engineState, message, resourceId, level, intent)
        state = when (level) {
            ConnectionStatus.LEVEL_CONNECTED -> "connected"
            ConnectionStatus.LEVEL_AUTH_FAILED -> "error"
            ConnectionStatus.LEVEL_NOTCONNECTED -> "disconnected"
            ConnectionStatus.LEVEL_NONETWORK -> "no_network"
            ConnectionStatus.LEVEL_VPNPAUSED -> "paused"
            else -> "connecting"
        }
    }

    override fun updateByteCount(received: Long, sent: Long, diffIn: Long, diffOut: Long) {
        super.updateByteCount(received, sent, diffIn, diffOut)
        bytesIn = received
        bytesOut = sent
    }

    override fun onDestroy() {
        super.onDestroy()
        state = "disconnected"
    }
}
