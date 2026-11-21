package com.example.wifip2pchat

import android.content.Context
import android.content.Intent
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

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
        manager = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
        if (manager == null) {
            Log.e(TAG, "Wi-Fi P2P is not supported on this device")
            return
        }
        channel = manager!!.initialize(context, context.mainLooper, null)
        Log.d(TAG, "WiFi Direct manager initialized")
        restoreConnectionState()
    }

    /**
     * Re-query the current WiFi Direct connection. The system keeps a
     * formed group alive even when the app process is gone, so when the
     * app is re-opened the broadcasts may never fire again — this restores
     * the session (chat server / client + open chat) from live state.
     */
    fun restoreConnectionState() {
        val mgr = manager ?: return
        val ch = channel ?: return
        try {
            mgr.requestConnectionInfo(ch) { info ->
                if (info != null && info.groupFormed) {
                    Log.d(TAG, "Restored existing P2P group — GO=${info.isGroupOwner}")
                    onConnectionInfoAvailable(info)
                } else {
                    Log.d(TAG, "No existing P2P group")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "restoreConnectionState failed: ${e.message}")
        }
    }

    /**
     * Start discovering peers. The system will send
     * WIFI_P2P_PEERS_CHANGED_ACTION broadcasts as peers are found.
     */
    fun startDiscovery() {
        val mgr = manager ?: return
        val ch = channel ?: return

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
    }

    /**
     * Stop peer discovery.
     */
    fun stopDiscovery() {
        val mgr = manager ?: return
        val ch = channel ?: return

        mgr.stopPeerDiscovery(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "Peer discovery stopped")
            }

            override fun onFailure(reasonCode: Int) {
                Log.e(TAG, "Stop discovery failed: ${reasonToString(reasonCode)}")
            }
        })
    }

    /**
     * Connect to the specified peer device.
     */
    fun connectToDevice(device: WifiP2pDevice) {
        val mgr = manager ?: return
        val ch = channel ?: return

        val config = WifiP2pConfig().apply {
            deviceAddress = device.deviceAddress
            // Let the system decide the group owner
            groupOwnerIntent = 0
        }

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
    fun refreshPeers() {
        val mgr = manager ?: return
        val ch = channel ?: return
        try {
            mgr.requestPeers(ch) { list ->
                onPeersChanged(list.deviceList.toList())
            }
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
            isConnected = true
            isGroupOwner = info.isGroupOwner
            groupOwnerAddress = info.groupOwnerAddress.hostAddress
            if (device != null && device.status == WifiP2pDevice.CONNECTED) {
                connectedDevice = device
            }
            listener.onConnected(info)
        } else {
            isConnected = false
            isGroupOwner = false
            groupOwnerAddress = null
            connectedDevice = null
            listener.onDisconnected()
        }
    }

    fun onConnectionInfoAvailable(info: WifiP2pInfo) {
        if (info.groupFormed) {
            isConnected = true
            isGroupOwner = info.isGroupOwner
            groupOwnerAddress = info.groupOwnerAddress.hostAddress
            Log.d(TAG, "Group formed — GO=$isGroupOwner, addr=$groupOwnerAddress")
            // Peer identity is not in WifiP2pInfo — pull a fresh peer list
            if (connectedDevice == null) refreshPeers()
            listener.onConnected(info)
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
