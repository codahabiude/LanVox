package com.example.wifip2pchat

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pDeviceList
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.util.Log

/**
 * BroadcastReceiver that listens for Wi-Fi P2P system events:
 *  - P2P state changed (Wi-Fi Direct enabled/disabled)
 *  - Peers changed (discovered devices list updated)
 *  - Connection changed (connected/disconnected)
 *  - This device changed (our device info updated)
 *
 * Delegates all events to [WiFiDirectManager] for centralized handling.
 */
class WiFiDirectBroadcastReceiver(
    private val manager: WifiP2pManager,
    private val channel: WifiP2pManager.Channel,
    private val wifiDirectManager: WiFiDirectManager
) : BroadcastReceiver() {

    companion object {
        private const val TAG = "WiFiP2PReceiver"
    }

    @SuppressLint("MissingPermission")
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                val isEnabled = state == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                Log.d(TAG, "Wi-Fi P2P state changed: enabled=$isEnabled")
                wifiDirectManager.onP2pStateChanged(isEnabled)
            }

            WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                Log.d(TAG, "Peers changed — requesting peer list")
                if (!WiFiDirectManager.hasWifiP2pPermission(context)) {
                    Log.w(TAG, "Cannot request peers — missing Wi-Fi P2P permission")
                    return
                }
                try {
                    manager.requestPeers(channel) { peers: WifiP2pDeviceList ->
                        wifiDirectManager.onPeersChanged(peers.deviceList.toList())
                    }
                } catch (e: SecurityException) {
                    Log.e(TAG, "requestPeers permission error: ${e.message}")
                }
            }

            WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                Log.d(TAG, "Connection changed")
                val info = intent.getParcelableExtraCompat<WifiP2pInfo>(
                    WifiP2pManager.EXTRA_WIFI_P2P_INFO
                )
                val device = intent.getParcelableExtraCompat<WifiP2pDevice>(
                    WifiP2pManager.EXTRA_WIFI_P2P_DEVICE
                )
                if (info != null) {
                    wifiDirectManager.onConnectionChanged(info, device)
                }
                if (info == null || info.groupFormed) {
                    try {
                        manager.requestConnectionInfo(channel) { connectionInfo ->
                            if (connectionInfo != null) {
                                wifiDirectManager.onConnectionInfoAvailable(connectionInfo)
                            }
                        }
                    } catch (e: SecurityException) {
                        Log.e(TAG, "requestConnectionInfo permission error: ${e.message}")
                    }
                }
            }

            WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                val device = intent.getParcelableExtraCompat<WifiP2pDevice>(
                    WifiP2pManager.EXTRA_WIFI_P2P_DEVICE
                )
                Log.d(TAG, "This device changed: ${device?.deviceName}")
                wifiDirectManager.onThisDeviceChanged(device)
            }
        }
    }

    @Suppress("DEPRECATION")
    private inline fun <reified T : android.os.Parcelable> Intent.getParcelableExtraCompat(key: String): T? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(key, T::class.java)
        } else {
            getParcelableExtra(key)
        }
    }
}
