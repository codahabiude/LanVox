package com.example.wifip2pchat

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Main Activity for LANVOX — the connection hub.
 *
 * Responsibilities:
 *  - Wi-Fi Direct peer discovery & connection (radar + device list)
 *  - Hosting the chat server (Group Owner) / client socket
 *  - Persisting every incoming message to phone storage (SQLite)
 *  - Opening the per-contact ChatActivity automatically on connection
 *  - Audio & video calling with incoming call screen
 *  - Chat history export / clear-all
 *
 * Flow: Discover → Connect → ChatActivity (per-contact thread)
 */
class MainActivity : AppCompatActivity(), WiFiDirectManager.WiFiDirectListener {

    companion object {
        private const val TAG = "MainActivity"

        // Static reference for IncomingCallActivity to send control messages
        private var staticInstance: MainActivity? = null

        // Identity of the currently connected peer (chat thread key)
        var currentPeerId: String? = null
            private set
        var currentPeerName: String? = null
            private set

        fun sendControlMessageStatic(message: String) {
            staticInstance?.sendControlMessage(message)
        }

        /** True when a WiFi Direct connection is live. */
        fun isConnectedStatic(): Boolean =
            staticInstance?.wifiManager?.isConnected == true

        /** True when the currently connected peer IS the given contact. */
        fun currentPeerMatches(peerId: String?): Boolean =
            currentPeerId != null && currentPeerId == peerId

        /**
         * Transmit a text message over the live chat channel.
         * Defaults to the currently connected peer (1:1).
         */
        fun transmitMessage(text: String): Boolean {
            val instance = staticInstance ?: return false
            val recipient = currentPeerId ?: return false
            return instance.transmitText(recipient, text)
        }

        /**
         * Transmit a text message addressed to a specific peer.
         * Use "*" for group broadcast.
         */
        fun transmitMessageTo(recipientId: String, text: String): Boolean {
            val instance = staticInstance ?: return false
            return instance.transmitText(recipientId, text)
        }

        fun startAudioCallStatic(): Boolean {
            val instance = staticInstance ?: return false
            if (!instance.wifiManager.isConnected) return false
            instance.startAudioCall()
            return true
        }

        fun startVideoCallStatic(): Boolean {
            val instance = staticInstance ?: return false
            if (!instance.wifiManager.isConnected) return false
            instance.startVideoCall()
            return true
        }

        /** True while the connection hub UI exists — the standby listener sleeps then. */
        fun isAppUiAlive(): Boolean = staticInstance != null

        /**
         * Route a line received by the standby [CallListenerService] into the
         * live app (normal in-app handling). Returns false when this activity
         * is gone so the standby handles the line itself.
         */
        fun handleStandbyLine(line: String): Boolean {
            val inst = staticInstance ?: return false
            inst.runOnUiThread { inst.dispatchIncomingLine(line) }
            return true
        }

        /** Send a control line through the live app channel, if it exists. */
        fun sendControlIfPossible(message: String): Boolean {
            val inst = staticInstance ?: return false
            if (!inst.wifiManager.isConnected) return false
            inst.sendControlMessage(message)
            return true
        }
    }

    // --- UI ---
    private lateinit var rootLayout: LinearLayout
    private lateinit var statusIndicator: View
    private lateinit var tvStatus: TextView
    private lateinit var tvDeviceInfo: TextView
    private lateinit var btnDiscover: MaterialButton
    private lateinit var btnToggleDevices: MaterialButton
    private lateinit var btnDisconnect: MaterialButton
    private lateinit var btnAudioCall: MaterialButton
    private lateinit var btnVideoCall: MaterialButton
    private lateinit var scannerPanel: LinearLayout
    private lateinit var tvScanHint: TextView
    private lateinit var rvPeers: RecyclerView
    private lateinit var tvPeersEmpty: TextView
    private lateinit var progressConnecting: View
    private lateinit var tvStoragePill: TextView
    private lateinit var btnChats: ImageView
    private lateinit var btnExportChat: ImageView
    private lateinit var btnClearHistory: ImageView
    private lateinit var tvActiveChatTitle: TextView
    private lateinit var tvActiveChatSubtitle: TextView
    private lateinit var rvMessages: RecyclerView
    private lateinit var llMessagesEmpty: LinearLayout
    private lateinit var etMessage: EditText
    private lateinit var btnSend: MaterialButton

    // --- Radar ---
    private var radarView: RadarView? = null
    private var isScannerExpanded = false
    private var latestPeers: List<WifiP2pDevice> = emptyList()
    private var peerPickerSheet: PeerPickerBottomSheet? = null

    // --- Adapters ---
    private val peerAdapter = PeerAdapter(
        onPeerClick = { device -> onPeerClicked(device) },
        onCallClick = { device -> onPeerCallClicked(device) },
        onVideoClick = { device -> onPeerVideoClicked(device) }
    )
    private val messageAdapter = MessageAdapter()

    // --- WiFi Direct ---
    private val wifiManager: WiFiDirectManager by lazy { WiFiDirectManager(this, this) }
    private var broadcastReceiver: WiFiDirectBroadcastReceiver? = null

    // --- Chat channel ---
    private var chatClient: ChatClient? = null
    private var isDiscovering = false

    // --- Peer resolution / chat opening ---
    private var pendingChatOpen = false
    private var resolvingPeer = false
    private var pendingDiscoveryAfterPermission = false
    private var pendingConnectDeviceAfterPermission: WifiP2pDevice? = null

    // --- Runtime permission launcher ---
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (WiFiDirectManager.hasWifiP2pPermission(this)) {
            wifiManager.restoreConnectionState()
            if (pendingDiscoveryAfterPermission) {
                pendingDiscoveryAfterPermission = false
                startDiscoveryIfReady()
            }
            pendingConnectDeviceAfterPermission?.let { device ->
                pendingConnectDeviceAfterPermission = null
                connectToPeer(device)
            }
        } else {
            val wasPendingP2p = pendingDiscoveryAfterPermission || pendingConnectDeviceAfterPermission != null
            pendingDiscoveryAfterPermission = false
            pendingConnectDeviceAfterPermission = null
            if (wasPendingP2p || results.any { (perm, granted) ->
                    !granted && (perm == Manifest.permission.NEARBY_WIFI_DEVICES ||
                        perm == Manifest.permission.ACCESS_FINE_LOCATION)
                }
            ) {
                showSnackbar(
                    getString(R.string.permission_rationale),
                    "Settings"
                ) { openAppPermissionSettings() }
            } else if (results.values.any { !it }) {
                showSnackbar("Some permissions denied — features may not work")
            }
        }
    }

    // --- Persistent storage (Room) ---
    private val chatRepository by lazy { ChatRepository.get(this) }
    private val activeThreadPeerIdFlow = MutableStateFlow(Constants.GROUP_PEER_ID)
    private var storedCount = 0

    // --- PeerRegistry listener token (so we can unsubscribe in onDestroy) ---
    private var peerRegistryListener: (() -> Unit)? = null

    // ======================== Lifecycle ========================

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        staticInstance = this

        initViews()
        initWiFiDirect()
        checkPermissions()

        // Observe Room-persisted messages and message count reactively
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    chatRepository.observeTotalCount().collect { count ->
                        storedCount = count
                        updateStoragePill()
                    }
                }
                launch {
                    activeThreadPeerIdFlow
                        .flatMapLatest { threadId -> chatRepository.observeHistory(threadId) }
                        .collect { history ->
                            messageAdapter.setMessages(history)
                            updateEmptyMessagesState()
                            scrollMessagesToBottom()
                        }
                }
            }
        }

        // Whenever the peer registry changes (a new client HELLOs, a client
        // drops, or the GO broadcasts a PEERS list), refresh the empty-state
        // hint so the user knows the group size.
        peerRegistryListener = PeerRegistry.addListener {
            val groupSize = PeerRegistry.snapshotIncludingSelf().size
            tvPeersEmpty.text = if (groupSize > 1) {
                "Group has $groupSize members — open Chats to talk"
            } else {
                "No devices nearby yet.\nTap Discover to scan."
            }
        }

        // Hand the standby listener over: while this screen is alive the app
        // owns the chat channel; when it is destroyed the listener resumes.
        CallListenerService.notifyAppActive(this)
        maybePromptBatteryOptimization()
    }

    override fun onResume() {
        super.onResume()
        staticInstance = this
        registerReceiver()
        updateCallButtons()
        updateActiveChatHeader()
        loadMainChatHistory()
        refreshStoredCount()
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver()
    }

    override fun onDestroy() {
        staticInstance = null
        peerRegistryListener?.let { PeerRegistry.removeListener(it) }
        peerRegistryListener = null
        peerPickerSheet?.dismiss()
        peerPickerSheet = null
        super.onDestroy()
        wifiManager.stopDiscovery()
        chatClient?.disconnect()
        stopChatServer()
    }

    // ======================== Init ========================

    private fun initViews() {
        rootLayout = findViewById(R.id.rootLayout)
        statusIndicator = findViewById(R.id.statusIndicator)
        tvStatus = findViewById(R.id.tvStatus)
        tvDeviceInfo = findViewById(R.id.tvDeviceInfo)
        btnDiscover = findViewById(R.id.btnDiscover)
        btnToggleDevices = findViewById(R.id.btnToggleDevices)
        btnDisconnect = findViewById(R.id.btnDisconnect)
        btnAudioCall = findViewById(R.id.btnAudioCall)
        btnVideoCall = findViewById(R.id.btnVideoCall)
        scannerPanel = findViewById(R.id.scannerPanel)
        tvScanHint = findViewById(R.id.tvScanHint)
        rvPeers = findViewById(R.id.rvPeers)
        tvPeersEmpty = findViewById(R.id.tvPeersEmpty)
        progressConnecting = findViewById(R.id.progressConnecting)
        tvStoragePill = findViewById(R.id.tvStoragePill)
        btnChats = findViewById(R.id.btnChats)
        btnExportChat = findViewById(R.id.btnExportChat)
        btnClearHistory = findViewById(R.id.btnClearHistory)
        tvActiveChatTitle = findViewById(R.id.tvActiveChatTitle)
        tvActiveChatSubtitle = findViewById(R.id.tvActiveChatSubtitle)
        rvMessages = findViewById(R.id.rvMessages)
        llMessagesEmpty = findViewById(R.id.llMessagesEmpty)
        etMessage = findViewById(R.id.etMessage)
        btnSend = findViewById(R.id.btnSend)

        rvPeers.layoutManager = LinearLayoutManager(this)
        rvPeers.adapter = peerAdapter

        val chatLayoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true
        }
        rvMessages.layoutManager = chatLayoutManager
        rvMessages.adapter = messageAdapter

        // Radar view
        radarView = findViewById(R.id.radarView)
        radarView?.startSweeping()

        btnChats.setOnClickListener {
            startActivity(Intent(this, ChatsActivity::class.java))
            overridePendingTransition(R.anim.slide_up, android.R.anim.fade_out)
        }
        btnDiscover.setOnClickListener { toggleDiscovery() }
        btnToggleDevices.setOnClickListener { showPeerPickerBottomSheet() }
        btnDisconnect.setOnClickListener { disconnect() }
        btnAudioCall.setOnClickListener { startAudioCall() }
        btnVideoCall.setOnClickListener { startVideoCall() }
        btnExportChat.setOnClickListener { exportChatToFile() }
        btnClearHistory.setOnClickListener { confirmClearHistory() }
        btnSend.setOnClickListener { sendCurrentMessage() }
        etMessage.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendCurrentMessage()
                true
            } else {
                false
            }
        }

        setScannerExpanded(false)
        updateCallButtons()
        updateActiveChatHeader()
        loadMainChatHistory()
        refreshStoredCount()
    }

    private fun initWiFiDirect() {
        wifiManager.initialize()
    }

    // ======================== Permissions ========================

    private fun requiredWifiP2pPermissions(): List<String> {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        return permissions
    }

    private fun checkPermissions() {
        val needed = mutableListOf<String>()

        requiredWifiP2pPermissions().forEach { perm ->
            if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
                needed.add(perm)
            }
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) needed.add(Manifest.permission.RECORD_AUDIO)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) needed.add(Manifest.permission.CAMERA)

        // Needed to show incoming-call & message notifications on Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    private fun ensureWifiP2pPermissions(onGranted: () -> Unit): Boolean {
        if (WiFiDirectManager.hasWifiP2pPermission(this)) {
            onGranted()
            return true
        }

        val missing = requiredWifiP2pPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            onGranted()
            return true
        }

        val shouldShowRationale = missing.any {
            ActivityCompat.shouldShowRequestPermissionRationale(this, it)
        }
        if (shouldShowRationale) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Permissions required")
                .setMessage(getString(R.string.permission_rationale))
                .setNegativeButton("Cancel") { _, _ ->
                    pendingDiscoveryAfterPermission = false
                    pendingConnectDeviceAfterPermission = null
                }
                .setPositiveButton("Allow") { _, _ ->
                    permissionLauncher.launch(missing.toTypedArray())
                }
                .show()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
        return false
    }

    private fun openAppPermissionSettings() {
        try {
            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", packageName, null)
                )
            )
        } catch (_: Exception) {
            showSnackbar("Open Settings → Apps → LANVOX → Permissions")
        }
    }

    // ======================== Snackbar ========================

    private fun showSnackbar(message: String, actionLabel: String? = null, action: (() -> Unit)? = null) {
        val snackbar = Snackbar.make(rootLayout, message, Snackbar.LENGTH_LONG)
        if (actionLabel != null && action != null) {
            snackbar.setAction(actionLabel) { action() }
        }
        snackbar.show()
    }

    // ======================== Broadcast Receiver ========================

    private fun registerReceiver() {
        val mgr = wifiManager.manager ?: return
        val ch = wifiManager.channel ?: return

        broadcastReceiver = WiFiDirectBroadcastReceiver(mgr, ch, wifiManager)

        val intentFilter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }

        try {
            ContextCompat.registerReceiver(
                this,
                broadcastReceiver,
                intentFilter,
                ContextCompat.RECEIVER_EXPORTED
            )
        } catch (e: Exception) {
            Log.w(TAG, "Could not register Wi-Fi Direct receiver: ${e.message}")
        }
    }

    private fun unregisterReceiver() {
        broadcastReceiver?.let {
            try { unregisterReceiver(it) } catch (_: Exception) {}
        }
        broadcastReceiver = null
    }

    // ======================== Discovery ========================

    private fun toggleDiscovery() {
        if (isDiscovering) {
            wifiManager.stopDiscovery()
            isDiscovering = false
            btnDiscover.text = getString(R.string.btn_discover)
            tvStatus.text = if (wifiManager.isP2pEnabled) {
                getString(R.string.status_enabled)
            } else {
                getString(R.string.status_disabled)
            }
            tvScanHint.text = tvStatus.text
            statusIndicator.setBackgroundResource(
                if (wifiManager.isP2pEnabled) R.drawable.circle_green else R.drawable.circle_red
            )
            refreshPeerPickerBottomSheet()
            return
        }

        pendingDiscoveryAfterPermission = true
        ensureWifiP2pPermissions {
            pendingDiscoveryAfterPermission = false
            startDiscoveryIfReady()
        }
    }

    private fun startDiscoveryIfReady() {
        if (!wifiManager.isP2pEnabled) {
            showSnackbar("Enable Wi-Fi first", "Open Settings") {
                startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
            }
            return
        }

        wifiManager.startDiscovery()
        isDiscovering = true
        btnDiscover.text = "Stop"
        tvStatus.text = getString(R.string.status_discovering)
        tvScanHint.text = getString(R.string.status_discovering)
        statusIndicator.setBackgroundResource(R.drawable.circle_orange)
        showPeerPickerBottomSheet()
    }

    private fun showPeerPickerBottomSheet() {
        val sheet = peerPickerSheet ?: PeerPickerBottomSheet(
            context = this,
            onPeerSelected = { device -> onPeerClicked(device) },
            onPeerAudioCall = { device -> onPeerCallClicked(device) },
            onPeerVideoCall = { device -> onPeerVideoClicked(device) },
            onToggleScan = { toggleDiscovery() },
            onDisconnect = { disconnect() }
        ).also { peerPickerSheet = it }
        sheet.show(latestPeers, isDiscovering, wifiManager.isConnected)
    }

    private fun refreshPeerPickerBottomSheet() {
        peerPickerSheet?.takeIf { it.isShowing() }
            ?.updateState(latestPeers, isDiscovering, wifiManager.isConnected)
    }

    private fun setScannerExpanded(expanded: Boolean) {
        isScannerExpanded = expanded
        scannerPanel.visibility = if (expanded) View.VISIBLE else View.GONE
        updateToggleDevicesLabel()
    }

    private fun updateToggleDevicesLabel() {
        val peerCount = peerAdapter.itemCount
        btnToggleDevices.text = when {
            isScannerExpanded -> "Hide"
            peerCount > 0 -> "Devices ($peerCount)"
            else -> getString(R.string.btn_devices)
        }
    }

    // ======================== Peer Connection ========================

    private fun onPeerClicked(device: WifiP2pDevice) {
        val name = device.deviceName.ifBlank { device.deviceAddress }

        // Tapping the device we are already connected to re-opens its chat
        if (wifiManager.isConnected) {
            if (device.deviceAddress == currentPeerId) {
                openChat(device.deviceAddress, name)
            } else {
                showSnackbar("Already connected — disconnect first")
            }
            return
        }

        pendingConnectDeviceAfterPermission = device
        ensureWifiP2pPermissions {
            pendingConnectDeviceAfterPermission = null
            connectToPeer(device)
        }
    }

    private fun connectToPeer(device: WifiP2pDevice) {
        val name = device.deviceName.ifBlank { device.deviceAddress }
        progressConnecting.visibility = View.VISIBLE
        showSnackbar("Connecting to $name…")
        wifiManager.connectToDevice(device)
    }

    private fun onPeerCallClicked(device: WifiP2pDevice) {
        if (!wifiManager.isConnected) {
            onPeerClicked(device)
            return
        }
        startAudioCall()
    }

    private fun onPeerVideoClicked(device: WifiP2pDevice) {
        if (!wifiManager.isConnected) {
            onPeerClicked(device)
            return
        }
        startVideoCall()
    }

    private fun disconnect() {
        wifiManager.disconnect()
        chatClient?.disconnect()
        chatClient = null
        stopChatServer()
        clearCurrentPeer()
        LastSession.clearRole(this)
        updateCallButtons()
        showSnackbar("Disconnected — chat history saved on this phone")
    }

    private fun clearCurrentPeer() {
        currentPeerId = null
        currentPeerName = null
        pendingChatOpen = false
        resolvingPeer = false
        ChatBus.notifyConnectionChanged(false)
        updateActiveChatHeader()
        loadMainChatHistory()
    }

    /**
     * Ask once (after onboarding) for the battery-optimization exemption —
     * aggressive OEM battery managers kill background listeners, which is
     * exactly what receives calls when the app is closed.
     */
    private fun maybePromptBatteryOptimization() {
        val prefs = getSharedPreferences("lanvox_session", MODE_PRIVATE)
        if (prefs.getBoolean("battery_prompted", false)) return
        prefs.edit().putBoolean("battery_prompted", true).apply()

        val pm = getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) return

        MaterialAlertDialogBuilder(this)
            .setTitle("Receive calls in background")
            .setMessage(
                "To receive LANVOX calls and messages even when the app is closed, " +
                    "allow LANVOX to run without battery restrictions."
            )
            .setNegativeButton("Not now", null)
            .setPositiveButton("Allow") { _, _ ->
                try {
                    startActivity(
                        android.content.Intent(
                            android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            android.net.Uri.parse("package:$packageName")
                        )
                    )
                } catch (e: Exception) {
                    showSnackbar("Open Settings → Battery to allow background running")
                }
            }
            .show()
    }

    // ======================== Chat channel ========================

    private fun startChatServer() {
        val intent = Intent(this, ChatServerService::class.java)
        startService(intent)
        ChatServerService.onMessageReceived = { env ->
            dispatchIncomingEnvelope(env)
        }
    }

    /**
     * Single multiplexer for every envelope arriving on the chat channel
     * (GO server, client socket, or forwarded by the standby listener).
     */
    private fun dispatchIncomingEnvelope(env: Envelope) {
        val payload = env.payload
        when {
            payload.startsWith(Constants.MSG_TYPE_CALL_REQUEST) -> {
                val callType = payload.removePrefix(Constants.MSG_TYPE_CALL_REQUEST)
                handleIncomingCallRequest(callType, env.senderName)
            }
            payload.startsWith(Constants.MSG_TYPE_CALL_ACCEPT) -> {
                Log.d(TAG, "Call accepted by ${env.senderName}")
            }
            payload.startsWith(Constants.MSG_TYPE_CALL_REJECT) -> {
                showSnackbar("Call declined by ${env.senderName}")
            }
            payload.startsWith(Constants.MSG_TYPE_CALL_END) -> {
                Log.d(TAG, "${env.senderName} ended the call")
            }
            payload.startsWith(Constants.MSG_TYPE_TEXT) -> {
                val text = payload.removePrefix(Constants.MSG_TYPE_TEXT)
                onIncomingText(text, env.senderId, env.senderName, env.recipientId)
            }
            else -> {
                Log.d(TAG, "Unhandled envelope payload: $payload")
            }
        }
    }

    /**
     * Legacy raw-line dispatcher — only used by the standby listener
     * (CallListenerService) which may receive lines from a v4 (pre-envelope)
     * peer. Wraps the line as a broadcast envelope so the new dispatcher
     * can still handle it.
     */
    private fun dispatchIncomingLine(msg: String) {
        val env = Envelope(
            senderId = currentPeerId ?: "legacy",
            senderName = currentPeerName ?: "Unknown",
            recipientId = Constants.RECIPIENT_BROADCAST,
            payload = msg
        )
        dispatchIncomingEnvelope(env)
    }

    private fun stopChatServer() {
        ChatServerService.onMessageReceived = null
        stopService(Intent(this, ChatServerService::class.java))
    }

    private fun connectAsClient(hostAddress: String) {
        chatClient?.disconnect()
        chatClient = ChatClient(this).also { client ->
            client.onEnvelopeReceived = { env ->
                dispatchIncomingEnvelope(env)
            }
            client.connect(hostAddress)
        }
    }

    /**
     * A plain text message arrived from another group member: persist it to
     * the right conversation thread (1:1 with the sender, or the group chat
     * thread if it was a broadcast) and notify any open chat screen.
     */
    private fun onIncomingText(
        text: String,
        senderId: String,
        senderName: String,
        recipientId: String
    ) {
        val threadPeerId = if (recipientId == Constants.RECIPIENT_BROADCAST) {
            Constants.GROUP_PEER_ID
        } else {
            senderId
        }
        val threadPeerName = if (recipientId == Constants.RECIPIENT_BROADCAST) {
            Constants.GROUP_PEER_NAME
        } else {
            senderName
        }
        val displaySender = if (recipientId == Constants.RECIPIENT_BROADCAST) senderName else null
        val message = ChatMessage(text, isSentByMe = false, senderName = displaySender)

        lifecycleScope.launch {
            chatRepository.insertMessage(message, threadPeerId, threadPeerName)
        }

        ChatBus.notifyMessageSaved(message, threadPeerId)

        if (ChatBus.activeChatPeerId != threadPeerId &&
            threadPeerId != activeThreadPeerId() &&
            lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        ) {
            val from = if (recipientId == Constants.RECIPIENT_BROADCAST) {
                "$senderName in group"
            } else {
                senderName
            }
            showSnackbar("New message from $from")
        }
    }

    // ======================== MainActivity Chat UI ========================

    private fun activeThreadPeerId(): String = currentPeerId ?: Constants.GROUP_PEER_ID

    private fun activeThreadPeerName(): String = currentPeerName ?: getString(R.string.label_group_chat)

    private fun loadMainChatHistory() {
        activeThreadPeerIdFlow.value = activeThreadPeerId()
    }

    private fun sendCurrentMessage() {
        val text = etMessage.text.toString().trim()
        if (text.isEmpty()) return

        val threadPeerId = activeThreadPeerId()
        val threadPeerName = activeThreadPeerName()
        val recipient = if (threadPeerId == Constants.GROUP_PEER_ID) {
            Constants.RECIPIENT_BROADCAST
        } else {
            threadPeerId
        }

        if (wifiManager.isConnected) {
            val sent = transmitText(recipient, text)
            if (!sent) {
                showSnackbar("Failed to transmit — saved locally")
            }
        }

        val message = ChatMessage(text, isSentByMe = true)
        etMessage.text.clear()

        lifecycleScope.launch {
            chatRepository.insertMessage(message, threadPeerId, threadPeerName)
        }

        ChatBus.notifyMessageSaved(message, threadPeerId)
    }

    private fun updateEmptyMessagesState() {
        llMessagesEmpty.visibility = if (messageAdapter.itemCount == 0) View.VISIBLE else View.GONE
    }

    private fun scrollMessagesToBottom() {
        val count = messageAdapter.itemCount
        if (count > 0) {
            rvMessages.post {
                rvMessages.smoothScrollToPosition(count - 1)
            }
        }
    }

    private fun updateActiveChatHeader() {
        tvActiveChatTitle.text = activeThreadPeerName()
        tvActiveChatSubtitle.text = if (wifiManager.isConnected) {
            getString(R.string.label_online_encrypted)
        } else {
            getString(R.string.label_offline_local)
        }
    }

    /**
     * Send text over the live channel.
     * If [recipientId] is "*" the message goes to the group chat (broadcast).
     * Otherwise it goes to that specific peer.
     */
    private fun transmitText(recipientId: String, text: String): Boolean {
        val payload = Constants.MSG_TYPE_TEXT + text
        return try {
            if (wifiManager.isGroupOwner && ChatServerService.running) {
                if (recipientId == Constants.RECIPIENT_BROADCAST) {
                    ChatServerService.sendBroadcast(payload)
                } else {
                    ChatServerService.sendTo(recipientId, payload)
                }
                true
            } else {
                val client = chatClient
                if (client != null) {
                    if (recipientId == Constants.RECIPIENT_BROADCAST) {
                        client.sendBroadcast(payload)
                    } else {
                        client.sendTo(recipientId, payload)
                    }
                    true
                } else false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Transmit failed: ${e.message}")
            false
        }
    }

    /**
     * Send a control message (CALL_REQ / CALL_ACCEPT / etc.) addressed to a
     * specific peer. Falls back to broadcast when no recipient is given.
     */
    private fun sendControlMessage(message: String, recipientId: String? = null) {
        val target = recipientId ?: currentPeerId ?: Constants.RECIPIENT_BROADCAST
        if (wifiManager.isGroupOwner && ChatServerService.running) {
            if (target == Constants.RECIPIENT_BROADCAST) {
                ChatServerService.sendBroadcast(message)
            } else {
                ChatServerService.sendTo(target, message)
            }
        } else {
            val client = chatClient ?: return
            if (target == Constants.RECIPIENT_BROADCAST) {
                client.sendBroadcast(message)
            } else {
                client.sendTo(target, message)
            }
        }
    }

    // ======================== Per-contact chat ========================

    /** Open the conversation thread for a contact. */
    private fun openChat(peerId: String, peerName: String) {
        startActivity(
            Intent(this, ChatActivity::class.java)
                .putExtra(ChatActivity.EXTRA_PEER_ID, peerId)
                .putExtra(ChatActivity.EXTRA_PEER_NAME, peerName)
        )
        overridePendingTransition(R.anim.slide_up, android.R.anim.fade_out)
    }

    /**
     * Called after a group forms (and on every peers refresh while a chat
     * open is pending): resolve the connected peer's identity and open
     * its conversation thread automatically.
     */
    private fun tryOpenChatForConnectedPeer() {
        val peer = wifiManager.connectedDevice
        if (peer != null) {
            pendingChatOpen = false
            resolvingPeer = false
            currentPeerId = peer.deviceAddress
            currentPeerName = peer.deviceName.ifBlank { peer.deviceAddress }
            LastSession.savePeer(this, currentPeerId, currentPeerName)

            updateActiveChatHeader()
            loadMainChatHistory()
            setScannerExpanded(false)
            ChatBus.notifyConnectionChanged(true)
        } else if (resolvingPeer) {
            // refreshPeers() already in flight — wait for it
            return
        } else {
            resolvingPeer = true
            wifiManager.refreshPeers()
        }
    }

    // ======================== Persistent storage ========================

    /** Refresh the "N messages saved" pill (covers sends made in ChatActivity). */
    private fun refreshStoredCount() {
        lifecycleScope.launch {
            storedCount = chatRepository.count()
            updateStoragePill()
        }
    }

    /** Update the "N messages saved" pill in the top bar. */
    private fun updateStoragePill() {
        tvStoragePill.text = if (storedCount == 1) {
            "1 message saved • Offline Mode"
        } else {
            "$storedCount messages saved • Offline Mode"
        }
    }

    /** Enable/disable the call buttons based on connection state. */
    private fun updateCallButtons() {
        val connected = wifiManager.isConnected
        btnAudioCall.isEnabled = connected
        btnVideoCall.isEnabled = connected
        btnAudioCall.alpha = if (connected) 1.0f else 0.4f
        btnVideoCall.alpha = if (connected) 1.0f else 0.4f
    }

    /** Ask before wiping all locally stored messages. */
    private fun confirmClearHistory() {
        if (storedCount == 0) {
            showSnackbar("No saved messages to delete")
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Clear chat history?")
            .setMessage("All $storedCount messages stored on this phone will be permanently deleted.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch {
                    chatRepository.clearAll()
                    storedCount = 0
                    messageAdapter.clear()
                    updateEmptyMessagesState()
                    updateStoragePill()
                    showSnackbar("Chat history deleted")
                }
            }
            .show()
    }

    /**
     * Export all stored conversations to a .txt file:
     *  - Android 10+  → Downloads/LANVOX_chat_<timestamp>.txt via MediaStore
     *  - Android 5–9  → app Documents folder (no permission needed)
     */
    private fun exportChatToFile() {
        lifecycleScope.launch {
            val conversations = chatRepository.getConversations()
            if (conversations.isEmpty()) {
                showSnackbar("Nothing to export yet")
                return@launch
            }
            val result = writeExportFile(conversations)
            result.onSuccess { path ->
                showSnackbar("Chats saved to $path", "Open") {
                    openStorageSettings()
                }
            }.onFailure { e ->
                showSnackbar("Export failed: ${e.message}")
            }
        }
    }

    private suspend fun writeExportFile(conversations: List<Conversation>): Result<String> {
        val fullFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fileName = "LANVOX_chats_$stamp.txt"

        val content = buildString {
            appendLine("LANVOX — Chat Export")
            appendLine("Exported: ${fullFormat.format(Date())}")
            appendLine("Conversations: ${conversations.size}")
            var total = 0
            conversations.forEach { total += it.messageCount }
            appendLine("Total messages: $total")
            conversations.forEach { conversation ->
                val name = conversation.peerName?.takeIf { it.isNotBlank() } ?: conversation.peerId
                appendLine()
                appendLine("===== $name =====")
                val history = chatRepository.loadHistory(conversation.peerId, Int.MAX_VALUE)
                history.forEach { m ->
                    val who = if (m.isSentByMe) "Me" else name
                    appendLine("[${fullFormat.format(Date(m.timestamp))}] $who: ${m.text}")
                }
            }
        }

        return withContext(Dispatchers.IO) {
            try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Scoped storage — write straight into the public Downloads folder
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: return Result.failure(IllegalStateException("Could not create file"))
                contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(content.toByteArray())
                } ?: return Result.failure(IllegalStateException("Could not open file"))
                Result.success("Downloads/$fileName")
            } else {
                // Legacy devices — app-private Documents folder (visible via file manager)
                val dir = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
                    ?: return Result.failure(IllegalStateException("Storage unavailable"))
                val file = File(dir, fileName)
                file.writeText(content)
                Result.success(file.absolutePath)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
        }
    }

    private fun openStorageSettings() {
        try {
            startActivity(
                Intent(android.provider.Settings.ACTION_INTERNAL_STORAGE_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Exception) {
            // Settings screen not available on this device — ignore
        }
    }

    // ======================== Calling ========================

    private fun startAudioCall() {
        if (!wifiManager.isConnected) {
            showSnackbar("Connect to a peer first")
            return
        }

        sendControlMessage(Constants.MSG_TYPE_CALL_REQUEST + Constants.CALL_TYPE_AUDIO)

        val intent = Intent(this, AudioCallActivity::class.java).apply {
            putExtra(AudioCallActivity.EXTRA_IS_GROUP_OWNER, wifiManager.isGroupOwner)
            putExtra(AudioCallActivity.EXTRA_HOST_ADDRESS, wifiManager.groupOwnerAddress)
        }
        startActivity(intent)
        overridePendingTransition(R.anim.slide_up, android.R.anim.fade_out)
    }

    private fun startVideoCall() {
        if (!wifiManager.isConnected) {
            showSnackbar("Connect to a peer first")
            return
        }

        sendControlMessage(Constants.MSG_TYPE_CALL_REQUEST + Constants.CALL_TYPE_VIDEO)

        val intent = Intent(this, VideoCallActivity::class.java).apply {
            putExtra(VideoCallActivity.EXTRA_IS_GROUP_OWNER, wifiManager.isGroupOwner)
            putExtra(VideoCallActivity.EXTRA_HOST_ADDRESS, wifiManager.groupOwnerAddress)
        }
        startActivity(intent)
        overridePendingTransition(R.anim.slide_up, android.R.anim.fade_out)
    }

    /**
     * Handle an incoming call — show the IncomingCallActivity
     * with accept/decline UI instead of auto-accepting.
     */
    private fun handleIncomingCallRequest(callType: String, callerName: String) {
        runOnUiThread {
            val intent = Intent(this, IncomingCallActivity::class.java).apply {
                putExtra(IncomingCallActivity.EXTRA_CALL_TYPE, callType)
                putExtra(IncomingCallActivity.EXTRA_IS_GROUP_OWNER, wifiManager.isGroupOwner)
                putExtra(IncomingCallActivity.EXTRA_HOST_ADDRESS, wifiManager.groupOwnerAddress)
                putExtra(
                    IncomingCallActivity.EXTRA_CALLER_NAME,
                    callerName.ifBlank { currentPeerName ?: "Peer" }
                )
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            startActivity(intent)
            overridePendingTransition(R.anim.slide_up, android.R.anim.fade_out)
        }
    }

    // ======================== WiFiDirectListener Callbacks ========================

    override fun onP2pEnabled(enabled: Boolean) {
        if (enabled) {
            tvStatus.text = getString(R.string.status_enabled)
            statusIndicator.setBackgroundResource(R.drawable.circle_green)
        } else {
            tvStatus.text = getString(R.string.status_disabled)
            statusIndicator.setBackgroundResource(R.drawable.circle_red)
            isDiscovering = false
            btnDiscover.text = getString(R.string.btn_discover)
            refreshPeerPickerBottomSheet()
        }
    }

    override fun onPeersUpdated(peers: List<WifiP2pDevice>) {
        latestPeers = peers
        peerAdapter.updatePeers(peers)
        tvPeersEmpty.visibility = if (peers.isEmpty()) View.VISIBLE else View.GONE
        radarView?.setBlipCount(peers.size)
        updateToggleDevicesLabel()
        refreshPeerPickerBottomSheet()

        // Peer identity may have just been resolved — finish opening the chat
        if (pendingChatOpen) {
            tryOpenChatForConnectedPeer()
        }
        Log.d(TAG, "Found ${peers.size} peer(s)")
    }

    override fun onDeviceChanged(device: WifiP2pDevice?) {
        device?.let {
            tvDeviceInfo.text = it.deviceName.ifBlank { it.deviceAddress }
            // Register our own identity in the peer registry so envelopes can
            // be addressed FROM us and the UI can show "you" correctly.
            PeerRegistry.selfId = it.deviceAddress
            PeerRegistry.selfName = it.deviceName.ifBlank { "This device" }
            Log.d(TAG, "Self identity: ${PeerRegistry.selfId} (${PeerRegistry.selfName})")
        }
    }

    override fun onConnected(info: WifiP2pInfo?) {
        progressConnecting.visibility = View.GONE
        tvStatus.text = getString(R.string.status_connected)
        statusIndicator.setBackgroundResource(R.drawable.circle_green)
        updateCallButtons()

        info?.let { connectionInfo ->
            if (connectionInfo.isGroupOwner) {
                Log.d(TAG, "Group Owner — starting server")
                startChatServer()
            } else {
                val hostAddress = connectionInfo.groupOwnerAddress.hostAddress
                Log.d(TAG, "Client — connecting to GO at $hostAddress")
                if (hostAddress != null) {
                    connectAsClient(hostAddress)
                }
            }

            // Snapshot the session so the standby listener can keep receiving
            // calls/messages after this process dies
            LastSession.save(
                this,
                connectionInfo.isGroupOwner,
                connectionInfo.groupOwnerAddress?.hostAddress,
                currentPeerId,
                currentPeerName
            )
        }

        // Resolve who we are talking to, then open their chat thread
        pendingChatOpen = true
        tryOpenChatForConnectedPeer()

        if (isDiscovering) {
            wifiManager.stopDiscovery()
            isDiscovering = false
            btnDiscover.text = getString(R.string.btn_discover)
        }

        refreshPeerPickerBottomSheet()
        showSnackbar("Connected to peer")
    }

    override fun onDisconnected() {
        progressConnecting.visibility = View.GONE
        tvStatus.text = getString(R.string.status_disconnected)
        statusIndicator.setBackgroundResource(R.drawable.circle_orange)
        updateCallButtons()
        chatClient?.disconnect()
        chatClient = null
        clearCurrentPeer()
        LastSession.clearRole(this)
        refreshPeerPickerBottomSheet()
    }

    override fun onConnectionFailed(reason: String) {
        progressConnecting.visibility = View.GONE
        if (isDiscovering) {
            isDiscovering = false
            btnDiscover.text = getString(R.string.btn_discover)
            tvStatus.text = if (wifiManager.isP2pEnabled) {
                getString(R.string.status_enabled)
            } else {
                getString(R.string.status_disabled)
            }
            tvScanHint.text = tvStatus.text
            statusIndicator.setBackgroundResource(
                if (wifiManager.isP2pEnabled) R.drawable.circle_green else R.drawable.circle_red
            )
        }
        refreshPeerPickerBottomSheet()
        showSnackbar(reason, "Retry") { toggleDiscovery() }
    }
}
