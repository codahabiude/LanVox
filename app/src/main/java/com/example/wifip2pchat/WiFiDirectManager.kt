package com.example.wifip2pchat

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Centralized manager for all Wi-Fi Direct operations:
 *  - Initialization of WifiP2pManager and channel
 *  - Peer discovery (start/stop)
 *  - Connecting to and disconnecting from peers
 *  - Tracking connection state and group owner info
 *
 * The [listener] callback is used to push UI updates (peer list, connection status, etc.).
 */
class WiFiDirectManager(
    private val context: Context,
    private val listener: WiFiDirectListener
) {
    companion object {
        private const val TAG = "WiFiDirectManager"

        /**
         * Returns true if the runtime permission required for Wi-Fi Direct peer
         * discovery & connection is granted for the current Android version:
         *  - Android 13+ (API 33+): NEARBY_WIFI_DEVICES
         *  - Android 12 and below:  ACCESS_FINE_LOCATION
         */
        fun hasWifiP2pPermission(context: Context): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.NEARBY_WIFI_DEVICES
                ) == PackageManager.PERMISSION_GRANTED
            } else {
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
            }
        }
    }

    interface WiFiDirectListener {
        fun onP2pEnabled(enabled: Boolean)
        fun onPeersUpdated(peers: List<WifiP2pDevice>)
        fun onDeviceChanged(device: WifiP2pDevice?)
        fun onConnected(info: WifiP2pInfo?)
        fun onDisconnected()
        fun onConnectionFailed(reason: String)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    var manager: WifiP2pManager? = null
        private set
    var channel: WifiP2pManager.Channel? = null
        private set

    var isP2pEnabled = false
        private set
    var isConnected = false
        private set
    var isGroupOwner = false
        private set
    var groupOwnerAddress: String? = null
        private set
    var thisDevice: WifiP2pDevice? = null
        private set

    /**
     * The peer device we are currently connected to (resolved from the
     * peers list by CONNECTED status). Used as the chat thread identity.
     */
    var connectedDevice: WifiP2pDevice? = null
        private set

    private var peers: List<WifiP2pDevice> = emptyList()

    /**
     * Initialize the WiFi P2P manager and channel. Must be called before any
     * other operations, typically in Activity.onCreate().
     */
    fun initialize() {
        try {
            manager = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
            if (manager == null) {
                Log.e(TAG, "Wi-Fi P2P is not supported on this device")
                return
            }
            channel = manager?.initialize(context, context.mainLooper, null)
            Log.d(TAG, "WiFi Direct manager initialized")
            restoreConnectionState()
        } catch (e: Exception) {
            Log.e(TAG, "WiFi Direct initialization failed: ${e.message}")
        }
    }

    /**
     * Re-query the current WiFi Direct connection. The system keeps a
     * formed group alive even when the app process is gone, so when the
     * app is re-opened the broadcasts may never fire again — this restores
     * the session (chat server / client + open chat) from live state.
     */
    @SuppressLint("MissingPermission")
    fun restoreConnectionState() {
        val mgr = manager ?: return
        val ch = channel ?: return
        if (!hasWifiP2pPermission(context)) {
            Log.d(TAG, "Skipping restoreConnectionState — Wi-Fi P2P permission not yet granted")
            return
        }
        try {
            mgr.requestConnectionInfo(ch) { info ->
                if (info != null && info.groupFormed) {
                    Log.d(TAG, "Restored existing P2P group — GO=${info.isGroupOwner}")
                    onConnectionInfoAvailable(info)
                } else {
                    Log.d(TAG, "No existing P2P group")
                }
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "restoreConnectionState permission error: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "restoreConnectionState failed: ${e.message}")
        }
    }

    /**
     * Start discovering peers. The system will send
     * WIFI_P2P_PEERS_CHANGED_ACTION broadcasts as peers are found.
     */
    @SuppressLint("MissingPermission")
    fun startDiscovery() {
        val mgr = manager ?: run {
            listener.onConnectionFailed("Wi-Fi Direct is not supported on this device")
            return
        }
        val ch = channel ?: run {
            listener.onConnectionFailed("Wi-Fi Direct channel unavailable")
            return
        }
        if (!hasWifiP2pPermission(context)) {
            listener.onConnectionFailed("Nearby Wi-Fi / Location permission required")
            return
        }

        try {
            mgr.discoverPeers(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.d(TAG, "Peer discovery started")
                }

                override fun onFailure(reasonCode: Int) {
                    val reason = reasonToString(reasonCode)
                    Log.e(TAG, "Peer discovery failed: $reason")
                    listener.onConnectionFailed("Discovery failed: $reason")
                }
            })
        } catch (e: SecurityException) {
            Log.e(TAG, "discoverPeers permission error: ${e.message}")
            listener.onConnectionFailed("Discovery permission denied")
        }
    }

    /**
     * Stop peer discovery.
     */
    fun stopDiscovery() {
        val mgr = manager ?: return
        val ch = channel ?: return
        if (!hasWifiP2pPermission(context)) return

        try {
            mgr.stopPeerDiscovery(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.d(TAG, "Peer discovery stopped")
                }

                override fun onFailure(reasonCode: Int) {
                    Log.e(TAG, "Stop discovery failed: ${reasonToString(reasonCode)}")
                }
            })
        } catch (e: Exception) {
            Log.w(TAG, "stopDiscovery error: ${e.message}")
        }
    }

    /**
     * Connect to the specified peer device.
     */
    @SuppressLint("MissingPermission")
    fun connectToDevice(device: WifiP2pDevice) {
        val mgr = manager ?: run {
            listener.onConnectionFailed("Wi-Fi Direct unavailable")
            return
        }
        val ch = channel ?: run {
            listener.onConnectionFailed("Wi-Fi Direct channel unavailable")
            return
        }
        if (!hasWifiP2pPermission(context)) {
            listener.onConnectionFailed("Nearby Wi-Fi / Location permission required")
            return
        }

        val config = WifiP2pConfig().apply {
            deviceAddress = device.deviceAddress
            // Let the system decide the group owner
            groupOwnerIntent = 0
        }

        try {
            mgr.connect(ch, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.d(TAG, "Connection initiated to ${device.deviceName}")
                }

                override fun onFailure(reasonCode: Int) {
                    val reason = reasonToString(reasonCode)
                    Log.e(TAG, "Connection failed: $reason")
                    listener.onConnectionFailed("Connection failed: $reason")
                }
            })
        } catch (e: SecurityException) {
            Log.e(TAG, "connect permission error: ${e.message}")
            listener.onConnectionFailed("Connection permission denied")
        }
    }

    /**
     * Form an autonomous Wi-Fi Direct group with this device acting as Group Owner
     * (used during host failover when the previous Group Owner disappears).
     */
    @SuppressLint("MissingPermission")
    fun createAutonomousGroup(onResult: (Boolean) -> Unit = {}) {
        val mgr = manager ?: run {
            onResult(false)
            return
        }
        val ch = channel ?: run {
            onResult(false)
            return
        }
        if (!hasWifiP2pPermission(context)) {
            onResult(false)
            return
        }
        try {
            mgr.createGroup(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.d(TAG, "Autonomous P2P group created (failover host)")
                    onResult(true)
                }

                override fun onFailure(reasonCode: Int) {
                    Log.w(TAG, "createAutonomousGroup failed: ${reasonToString(reasonCode)}")
                    onResult(false)
                }
            })
        } catch (e: SecurityException) {
            Log.e(TAG, "createAutonomousGroup permission error: ${e.message}")
            onResult(false)
        }
    }

    /**
     * Disconnect from the current peer.
     */
    fun disconnect() {
        val mgr = manager ?: return
        val ch = channel ?: return

        mgr.removeGroup(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "Disconnected from group")
                isConnected = false
                isGroupOwner = false
                groupOwnerAddress = null
                connectedDevice = null
                listener.onDisconnected()
            }

            override fun onFailure(reasonCode: Int) {
                Log.e(TAG, "Disconnect failed: ${reasonToString(reasonCode)}")
            }
        })
    }

    /**
     * Ask the framework for a fresh peer list. Used right after a group is
     * formed to resolve the connected peer's identity (MAC + name).
     */
    @SuppressLint("MissingPermission")
    fun refreshPeers() {
        val mgr = manager ?: return
        val ch = channel ?: return
        if (!hasWifiP2pPermission(context)) return
        try {
            mgr.requestPeers(ch) { list ->
                onPeersChanged(list.deviceList.toList())
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "refreshPeers permission error: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "refreshPeers failed: ${e.message}")
        }
    }

    // --- Broadcast receiver callbacks ---

    fun onP2pStateChanged(enabled: Boolean) {
        isP2pEnabled = enabled
        listener.onP2pEnabled(enabled)
    }

    fun onPeersChanged(updatedPeers: List<WifiP2pDevice>) {
        peers = updatedPeers
        connectedDevice = updatedPeers.firstOrNull { it.status == WifiP2pDevice.CONNECTED }
        listener.onPeersUpdated(peers)
    }

    fun onConnectionChanged(info: WifiP2pInfo?, device: WifiP2pDevice?) {
        if (info != null && info.groupFormed) {
            val newAddr = info.groupOwnerAddress?.hostAddress
            val changed = !isConnected || isGroupOwner != info.isGroupOwner || groupOwnerAddress != newAddr
            isConnected = true
            isGroupOwner = info.isGroupOwner
            groupOwnerAddress = newAddr
            if (device != null && device.status == WifiP2pDevice.CONNECTED) {
                connectedDevice = device
            }
            if (changed) {
                listener.onConnected(info)
            }
        } else {
            val wasConnected = isConnected
            isConnected = false
            isGroupOwner = false
            groupOwnerAddress = null
            connectedDevice = null
            if (wasConnected) {
                listener.onDisconnected()
            }
        }
    }

    fun onConnectionInfoAvailable(info: WifiP2pInfo) {
        if (info.groupFormed) {
            val newAddr = info.groupOwnerAddress?.hostAddress
            val changed = !isConnected || isGroupOwner != info.isGroupOwner || groupOwnerAddress != newAddr
            isConnected = true
            isGroupOwner = info.isGroupOwner
            groupOwnerAddress = newAddr
            Log.d(TAG, "Group formed — GO=$isGroupOwner, addr=$groupOwnerAddress")
            // Peer identity is not in WifiP2pInfo — pull a fresh peer list
            if (connectedDevice == null) refreshPeers()
            if (changed) {
                listener.onConnected(info)
            }
        }
    }

    fun onThisDeviceChanged(device: WifiP2pDevice?) {
        thisDevice = device
        listener.onDeviceChanged(device)
    }

    private fun reasonToString(code: Int): String = when (code) {
        WifiP2pManager.ERROR -> "Internal error"
        WifiP2pManager.P2P_UNSUPPORTED -> "P2P unsupported"
        WifiP2pManager.BUSY -> "Framework busy"
        else -> "Unknown ($code)"
    }
}
