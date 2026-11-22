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
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
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
import kotlinx.coroutines.flow.combine
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

        /** True when a Wi-Fi Direct or LAN mesh connection is live. */
        fun isConnectedStatic(): Boolean =
            staticInstance?.isNetworkConnected() == true

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

        fun sendReadReceiptStatic(threadPeerId: String?) {
            val instance = staticInstance ?: return
            instance.sendReadReceipt(threadPeerId)
        }

        fun startAudioCallStatic(
            targetPeerId: String? = null,
            targetPeerName: String? = null
        ): Boolean {
            val instance = staticInstance ?: return false
            if (!instance.isNetworkConnected()) return false
            instance.startAudioCall(targetPeerId, targetPeerName)
            return true
        }

        fun startVideoCallStatic(
            targetPeerId: String? = null,
            targetPeerName: String? = null
        ): Boolean {
            val instance = staticInstance ?: return false
            if (!instance.isNetworkConnected()) return false
            instance.startVideoCall(targetPeerId, targetPeerName)
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
            if (!inst.isNetworkConnected()) return false
            inst.sendControlMessage(message)
            return true
        }

        /** Send a control line addressed to a specific peer (e.g. CALL_ACCEPT / CALL_REJECT / CALL_END). */
        fun sendControlToPeerStatic(peerId: String?, message: String): Boolean {
            val inst = staticInstance ?: return false
            if (!inst.isNetworkConnected()) return false
            inst.sendControlMessage(message, peerId)
            return true
        }

        /** Send a picked file URI from any chat thread screen to the specified peer or group. */
        fun sendFileToStatic(
            recipientId: String,
            threadPeerId: String,
            threadPeerName: String,
            uri: Uri
        ): Boolean {
            val inst = staticInstance ?: return false
            if (!inst.isNetworkConnected()) return false
            inst.sendFileFromUri(uri, recipientId, threadPeerId, threadPeerName)
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
    private lateinit var llInlinePeersEmpty: View
    private lateinit var tvPeersEmpty: TextView
    private lateinit var tvPeersEmptySub: TextView
    private lateinit var btnInlineEmptyScan: MaterialButton
    private lateinit var progressConnecting: View
    private lateinit var tvStoragePill: TextView
    private lateinit var btnChats: ImageView
    private lateinit var btnMoreOptions: ImageView
    private lateinit var activeChatHeader: View
    private lateinit var tvActiveChatTitle: TextView
    private lateinit var tvActiveChatSubtitle: TextView
    private lateinit var topOverlayContainer: LinearLayout
    private lateinit var btnSearchMessages: ImageView
    private lateinit var searchBarContainer: LinearLayout
    private lateinit var etSearchMessages: EditText
    private lateinit var tvSearchCount: TextView
    private lateinit var btnCloseSearch: ImageView
    private lateinit var llSearchEmpty: LinearLayout
    private lateinit var rvMessages: RecyclerView
    private lateinit var llMessagesEmpty: LinearLayout
    private lateinit var etMessage: EditText
    private lateinit var btnAttach: ImageView
    private lateinit var btnSend: MaterialButton

    // --- Radar ---
    private var radarView: RadarView? = null
    private var inlineRadarPulse: View? = null
    private var isScannerExpanded = false
    private var latestPeers: List<WifiP2pDevice> = emptyList()
    private var peerPickerSheet: PeerPickerBottomSheet? = null

    // --- Adapters ---
    private val peerAdapter = PeerAdapter(
        onPeerClick = { device -> onPeerClicked(device) },
        onCallClick = { device -> onPeerCallClicked(device) },
        onVideoClick = { device -> onPeerVideoClicked(device) }
    )
    private val messageAdapter = MessageAdapter { msg -> openChatAttachment(msg) }

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        if (!isNetworkConnected()) {
            showSnackbar("Connect to a peer first before sending files")
            return@registerForActivityResult
        }
        val threadPeerId = activeThreadPeerId()
        val threadPeerName = activeThreadPeerName()
        val recipient = if (threadPeerId == Constants.GROUP_PEER_ID) {
            Constants.RECIPIENT_BROADCAST
        } else {
            threadPeerId
        }
        sendFileFromUri(uri, recipient, threadPeerId, threadPeerName)
    }

    // --- WiFi Direct & Local Mesh ---
    private val wifiManager: WiFiDirectManager by lazy { WiFiDirectManager(this, this) }
    private var broadcastReceiver: WiFiDirectBroadcastReceiver? = null
    private var localMeshDiscovery: LocalMeshDiscovery? = null

    // --- Discovery & Failover state ---
    private var isDiscovering = false
    @Volatile private var userInitiatedDisconnect = false
    private var pendingFailoverTargetId: String? = null
    private var pendingFailoverTargetName: String? = null
    private var lastP2pNonGoPeers: List<PeerRegistry.Peer> = emptyList()

    // --- Peer resolution / chat opening ---
    private var pendingChatOpen = false
    private var resolvingPeer = false
    private var pendingDiscoveryAfterPermission = false
    private var pendingConnectDeviceAfterPermission: WifiP2pDevice? = null
    private var pendingCallVideoAfterPermission: Boolean? = null
    private var pendingCallTargetPeerId: String? = null
    private var pendingCallTargetPeerName: String? = null

    // --- Runtime permission launcher ---
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        // Handle pending call permission grant/denial first
        pendingCallVideoAfterPermission?.let { needsVideo ->
            val hasAudio = ContextCompat.checkSelfPermission(
                this, Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            val hasCamera = !needsVideo || ContextCompat.checkSelfPermission(
                this, Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED

            if (hasAudio && hasCamera) {
                val targetId = pendingCallTargetPeerId
                val targetName = pendingCallTargetPeerName
                pendingCallVideoAfterPermission = null
                pendingCallTargetPeerId = null
                pendingCallTargetPeerName = null
                if (needsVideo) launchVideoCall(targetId, targetName)
                else launchAudioCall(targetId, targetName)
            } else if (results.keys.any {
                    it == Manifest.permission.RECORD_AUDIO || it == Manifest.permission.CAMERA
                }
            ) {
                pendingCallVideoAfterPermission = null
                pendingCallTargetPeerId = null
                pendingCallTargetPeerName = null
                val neededLabel = if (needsVideo) "microphone and camera" else "microphone"
                showSnackbar(
                    "Allow $neededLabel permission to place calls",
                    "Settings"
                ) { openAppPermissionSettings() }
            }
        }

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
    private val searchQueryFlow = MutableStateFlow("")
    private var totalThreadMessages = 0
    private var storedCount = 0

    private val searchBackCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            setSearchOpen(false)
        }
    }

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

        onBackPressedDispatcher.addCallback(this, searchBackCallback)

        // Observe Room-persisted messages, search filter, and message count reactively
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    chatRepository.observeTotalCount().collect { count ->
                        storedCount = count
                        updateStoragePill()
                    }
                }
                launch {
                    combine(
                        activeThreadPeerIdFlow.flatMapLatest { threadId ->
                            chatRepository.observeHistory(threadId)
                        },
                        searchQueryFlow
                    ) { history, query ->
                        Pair(history, query)
                    }.collect { (history, query) ->
                        totalThreadMessages = history.size
                        val trimmed = query.trim()
                        val filtered = if (trimmed.isEmpty()) {
                            history
                        } else {
                            ChatSearchHelper.filter(this@MainActivity, history, trimmed)
                        }
                        val prevCount = messageAdapter.itemCount
                        messageAdapter.submitMessages(filtered, trimmed)
                        updateSearchAndEmptyUi(history.size, filtered.size, trimmed)
                        if (trimmed.isEmpty() && filtered.size > prevCount) {
                            scrollMessagesToBottom()
                        }
                    }
                }
            }
        }

        // Initialize stable collision-free identity and start symmetric mesh + NSD discovery
        PeerRegistry.selfId = DeviceIdentity.getId(this)
        PeerRegistry.selfName = DeviceIdentity.defaultDeviceName()
        PeerRegistry.selfIpAddress = DeviceIdentity.resolveLocalIp()
        startChatServer()
        FileTransferManager.ensureServerRunning(applicationContext)
        startLocalMeshDiscovery()

        peerRegistryListener = PeerRegistry.addListener {
            val allPeers = PeerRegistry.snapshot()
            lastP2pNonGoPeers = allPeers.filter { !it.isGroupOwner && it.transport == "p2p" }
            val groupSize = PeerRegistry.snapshotIncludingSelf().size
            tvPeersEmpty.text = if (groupSize > 1) {
                "Group has $groupSize members — open Chats to talk"
            } else {
                "No devices nearby yet.\nTap Discover to scan."
            }
            refreshNetworkConnectionUi()
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
        sendReadReceipt(activeThreadPeerId())
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
        localMeshDiscovery?.stop()
        localMeshDiscovery = null
        super.onDestroy()
        FileTransferManager.stopSession()
        wifiManager.stopDiscovery()
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
        llInlinePeersEmpty = findViewById(R.id.llInlinePeersEmpty)
        tvPeersEmpty = findViewById(R.id.tvPeersEmpty)
        tvPeersEmptySub = findViewById(R.id.tvPeersEmptySub)
        btnInlineEmptyScan = findViewById(R.id.btnInlineEmptyScan)
        progressConnecting = findViewById(R.id.progressConnecting)
        tvStoragePill = findViewById(R.id.tvStoragePill)
        btnChats = findViewById(R.id.btnChats)
        btnMoreOptions = findViewById(R.id.btnMoreOptions)
        activeChatHeader = findViewById(R.id.activeChatHeader)
        tvActiveChatTitle = findViewById(R.id.tvActiveChatTitle)
        tvActiveChatSubtitle = findViewById(R.id.tvActiveChatSubtitle)
        topOverlayContainer = findViewById(R.id.topOverlayContainer)
        btnSearchMessages = findViewById(R.id.btnSearchMessages)
        searchBarContainer = findViewById(R.id.searchBarContainer)
        etSearchMessages = findViewById(R.id.etSearchMessages)
        tvSearchCount = findViewById(R.id.tvSearchCount)
        btnCloseSearch = findViewById(R.id.btnCloseSearch)
        llSearchEmpty = findViewById(R.id.llSearchEmpty)
        rvMessages = findViewById(R.id.rvMessages)
        llMessagesEmpty = findViewById(R.id.llMessagesEmpty)
        etMessage = findViewById(R.id.etMessage)
        btnAttach = findViewById(R.id.btnAttach)
        btnSend = findViewById(R.id.btnSend)

        rvPeers.layoutManager = LinearLayoutManager(this)
        rvPeers.adapter = peerAdapter

        val chatLayoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true
        }
        rvMessages.layoutManager = chatLayoutManager
        rvMessages.adapter = messageAdapter
        MessageSwipeHelper.attach(rvMessages, messageAdapter) { deletedMessage ->
            val threadPeerId = activeThreadPeerId()
            val threadPeerName = activeThreadPeerName()
            updateSearchAndEmptyUi(
                totalCount = (totalThreadMessages - 1).coerceAtLeast(0),
                visibleCount = messageAdapter.itemCount,
                query = searchQueryFlow.value.trim()
            )
            lifecycleScope.launch {
                chatRepository.deleteMessage(deletedMessage)
            }
            showSnackbar("Message deleted", "Undo") {
                lifecycleScope.launch {
                    chatRepository.insertMessage(deletedMessage, threadPeerId, threadPeerName)
                }
            }
        }

        // Keep top padding of message list synchronized with the floating search overlay height
        topOverlayContainer.addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
            val overlayHeight = (bottom - top).coerceAtLeast(0)
            val baseTopPx = (12 * resources.displayMetrics.density).toInt()
            val desiredTopPadding = if (overlayHeight > 0) {
                overlayHeight + (18 * resources.displayMetrics.density).toInt()
            } else {
                baseTopPx
            }
            if (rvMessages.paddingTop != desiredTopPadding) {
                rvMessages.setPadding(
                    rvMessages.paddingLeft,
                    desiredTopPadding,
                    rvMessages.paddingRight,
                    rvMessages.paddingBottom
                )
            }
        }

        // Radar view
        radarView = findViewById(R.id.radarView)
        inlineRadarPulse = findViewById(R.id.inlineRadarPulse)

        // Glassmorphism backdrop blur & luminous HUD glow
        GlassEffects.applyAmbientBlur(findViewById(R.id.ambientOrb1), 64f)
        GlassEffects.applyAmbientBlur(findViewById(R.id.ambientOrb2), 72f)
        GlassEffects.applyAmbientBlur(findViewById(R.id.ambientOrb3), 64f)
        GlassEffects.applyGlowElevation(searchBarContainer, R.color.glow_bubble_sent, 10f)
        GlassEffects.applyGlowElevation(findViewById(R.id.composerDock), R.color.glow_bubble_sent, 12f)

        btnSearchMessages.setOnClickListener {
            val opening = searchBarContainer.visibility != View.VISIBLE
            setSearchOpen(opening)
        }
        btnCloseSearch.setOnClickListener {
            if (etSearchMessages.text?.isNotEmpty() == true) {
                etSearchMessages.text?.clear()
            } else {
                setSearchOpen(false)
            }
        }
        etSearchMessages.doAfterTextChanged { editable ->
            searchQueryFlow.value = editable?.toString().orEmpty()
        }
        etSearchMessages.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                hideKeyboard(etSearchMessages)
                true
            } else {
                false
            }
        }

        val openChannelsScreen: () -> Unit = {
            startActivity(Intent(this, ChatsActivity::class.java))
            overridePendingTransition(R.anim.slide_up, android.R.anim.fade_out)
        }
        btnChats.setOnClickListener { openChannelsScreen() }
        activeChatHeader.setOnClickListener { openChannelsScreen() }
        btnMoreOptions.setOnClickListener { showMoreActionsSheet() }
        btnDiscover.setOnClickListener { toggleDiscovery() }
        btnInlineEmptyScan.setOnClickListener { toggleDiscovery() }
        btnToggleDevices.setOnClickListener { showPeerPickerBottomSheet() }
        btnDisconnect.setOnClickListener { disconnect() }
        btnAudioCall.setOnClickListener { startAudioCall() }
        btnVideoCall.setOnClickListener { startVideoCall() }
        btnAttach.setOnClickListener {
            if (!wifiManager.isConnected) {
                showSnackbar("Connect to a peer first to send files")
            } else {
                filePickerLauncher.launch(arrayOf("*/*"))
            }
        }
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
            stopDiscoveryUiState(updateStatusText = !wifiManager.isConnected)
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
        btnDiscover.setIconResource(R.drawable.ic_close)
        radarView?.startSweeping()
        inlineRadarPulse?.let { pulseView ->
            pulseView.visibility = View.VISIBLE
            if (pulseView.animation == null) {
                pulseView.startAnimation(
                    android.view.animation.AnimationUtils.loadAnimation(this, R.anim.pulse_scan)
                )
            }
        }
        tvStatus.text = getString(R.string.status_discovering)
        tvScanHint.text = getString(R.string.status_discovering)
        statusIndicator.setBackgroundResource(R.drawable.circle_orange)
        updateInlinePeersEmptyState()
        showPeerPickerBottomSheet()
    }

    private fun stopDiscoveryUiState(updateStatusText: Boolean) {
        isDiscovering = false
        btnDiscover.text = getString(R.string.btn_scan_short)
        btnDiscover.setIconResource(R.drawable.ic_search)
        btnDiscover.clearAnimation()
        radarView?.stopSweeping()
        inlineRadarPulse?.clearAnimation()
        inlineRadarPulse?.visibility = View.INVISIBLE

        if (updateStatusText) {
            tvStatus.text = if (wifiManager.isP2pEnabled) {
                getString(R.string.status_enabled)
            } else {
                getString(R.string.status_disabled)
            }
            tvScanHint.text = tvStatus.text
            statusIndicator.setBackgroundResource(
                if (wifiManager.isP2pEnabled) R.drawable.circle_green else R.drawable.circle_red
            )
        } else {
            tvScanHint.text = if (isNetworkConnected()) {
                getString(R.string.status_connected)
            } else if (wifiManager.isP2pEnabled) {
                getString(R.string.status_enabled)
            } else {
                getString(R.string.status_disabled)
            }
        }
        updateInlinePeersEmptyState()
        refreshPeerPickerBottomSheet()
    }

    private fun updateInlinePeersEmptyState() {
        val showEmpty = latestPeers.isEmpty()
        llInlinePeersEmpty.visibility = if (showEmpty) View.VISIBLE else View.GONE
        if (!showEmpty) return
        if (isDiscovering) {
            tvPeersEmpty.setText(R.string.sheet_peers_scanning_empty_title)
            tvPeersEmptySub.setText(R.string.sheet_peers_scanning_empty_subtitle)
            btnInlineEmptyScan.visibility = View.GONE
        } else {
            tvPeersEmpty.setText(R.string.sheet_peers_empty_title)
            tvPeersEmptySub.setText(R.string.sheet_peers_empty_subtitle)
            btnInlineEmptyScan.visibility = View.VISIBLE
        }
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
        sheet.show(latestPeers, isDiscovering, isNetworkConnected())
    }

    private fun refreshPeerPickerBottomSheet() {
        peerPickerSheet?.takeIf { it.isShowing() }
            ?.updateState(latestPeers, isDiscovering, isNetworkConnected())
    }

    private fun showMoreActionsSheet() {
        MainActionsBottomSheet.show(
            context = this,
            storedMessageCount = storedCount,
            isConnected = isNetworkConnected(),
            isScannerExpanded = isScannerExpanded,
            onExportChats = { exportChatToFile() },
            onToggleRadar = { setScannerExpanded(!isScannerExpanded) },
            onDisconnect = { disconnect() },
            onClearAllHistory = { confirmClearHistory() }
        )
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

    private fun isNetworkConnected(): Boolean =
        wifiManager.isConnected || ChatServerService.hasConnectedPeers()

    private fun refreshNetworkConnectionUi() {
        val connected = isNetworkConnected()
        if (connected) {
            tvStatus.text = getString(R.string.status_connected)
            statusIndicator.setBackgroundResource(R.drawable.circle_green)
        } else if (!isDiscovering) {
            tvStatus.text = if (wifiManager.isP2pEnabled) {
                getString(R.string.status_enabled)
            } else {
                getString(R.string.status_disconnected)
            }
            statusIndicator.setBackgroundResource(
                if (wifiManager.isP2pEnabled) R.drawable.circle_green else R.drawable.circle_orange
            )
        }
        updateCallButtons()
        updateActiveChatHeader()
        ChatBus.notifyConnectionChanged(connected)
        refreshPeerPickerBottomSheet()
    }

    private fun onPeerClicked(device: WifiP2pDevice) {
        val name = device.deviceName.ifBlank { device.deviceAddress }

        if (isNetworkConnected()) {
            if (device.deviceAddress == currentPeerId || PeerRegistry.nameFor(device.deviceAddress) != null) {
                openChat(device.deviceAddress, name)
                return
            }
            // If we are Group Owner, we can invite additional peers into the existing group
            if (wifiManager.isGroupOwner) {
                connectToPeer(device)
                return
            }
        }

        pendingConnectDeviceAfterPermission = device
        ensureWifiP2pPermissions {
            pendingConnectDeviceAfterPermission = null
            connectToPeer(device)
        }
    }

    private fun connectToPeer(device: WifiP2pDevice) {
        userInitiatedDisconnect = false
        val name = device.deviceName.ifBlank { device.deviceAddress }
        progressConnecting.visibility = View.VISIBLE
        showSnackbar("Connecting to $name…")
        wifiManager.connectToDevice(device)
    }

    private fun onPeerCallClicked(device: WifiP2pDevice) {
        if (!isNetworkConnected()) {
            onPeerClicked(device)
            return
        }
        val name = device.deviceName.ifBlank { device.deviceAddress }
        startAudioCall(device.deviceAddress, name)
    }

    private fun onPeerVideoClicked(device: WifiP2pDevice) {
        if (!isNetworkConnected()) {
            onPeerClicked(device)
            return
        }
        val name = device.deviceName.ifBlank { device.deviceAddress }
        startVideoCall(device.deviceAddress, name)
    }

    private fun disconnect() {
        userInitiatedDisconnect = true
        pendingFailoverTargetId = null
        pendingFailoverTargetName = null
        wifiManager.disconnect()
        ChatServerService.disconnectTransport("p2p")
        clearCurrentPeer()
        LastSession.clearRole(this)
        refreshNetworkConnectionUi()
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
        ChatServerService.onMeshPeersChanged = {
            refreshNetworkConnectionUi()
        }
    }

    private fun startLocalMeshDiscovery() {
        if (localMeshDiscovery != null) return
        localMeshDiscovery = LocalMeshDiscovery(
            context = applicationContext,
            onPeerDiscovered = { peerId, peerName, hostIp, _ ->
                if (peerId != PeerRegistry.selfId) {
                    PeerRegistry.upsert(
                        PeerRegistry.Peer(
                            id = peerId,
                            name = peerName,
                            isGroupOwner = false,
                            ipAddress = hostIp,
                            transport = "lan"
                        )
                    )
                    if (PeerRegistry.selfId < peerId) {
                        ChatServerService.connectToPeer(
                            hostIp = hostIp,
                            peerIdHint = peerId,
                            peerNameHint = peerName,
                            isGroupOwnerUplink = false,
                            transport = "lan"
                        )
                    }
                    runOnUiThread { refreshNetworkConnectionUi() }
                }
            },
            onPeerLost = { peerId ->
                PeerRegistry.remove(peerId)
                runOnUiThread { refreshNetworkConnectionUi() }
            }
        ).also {
            it.start(PeerRegistry.selfId, PeerRegistry.selfName)
        }
    }

    /**
     * Single multiplexer for every envelope arriving on the mesh chat channel
     * or forwarded by the standby listener.
     */
    private fun dispatchIncomingEnvelope(env: Envelope) {
        val payload = env.payload
        when {
            payload.startsWith(Constants.MSG_TYPE_CALL_REQUEST) -> {
                val rawReq = payload.removePrefix(Constants.MSG_TYPE_CALL_REQUEST)
                handleIncomingCallRequest(rawReq, env.senderId, env.senderName)
            }
            payload.startsWith(Constants.MSG_TYPE_CALL_ACCEPT) -> {
                Log.d(TAG, "Call accepted by ${env.senderName}")
                CallSessionBus.notifyAccepted()
            }
            payload.startsWith(Constants.MSG_TYPE_CALL_REJECT) -> {
                CallSessionBus.notifyEnded("Call declined by ${env.senderName}")
                showSnackbar("Call declined by ${env.senderName}")
            }
            payload.startsWith(Constants.MSG_TYPE_CALL_END) -> {
                Log.d(TAG, "${env.senderName} ended the call")
                CallRingtoneManager.stopRinging()
                IncomingCallActivity.dismiss()
                CallSessionBus.notifyEnded("${env.senderName} ended the call")
            }
            payload.startsWith(Constants.MSG_TYPE_TEXT) -> {
                val text = payload.removePrefix(Constants.MSG_TYPE_TEXT)
                onIncomingText(text, env.senderId, env.senderName, env.recipientId)
            }
            payload.startsWith(Constants.MSG_TYPE_FILE) -> {
                onIncomingFile(payload, env.senderId, env.senderName, env.recipientId)
            }
            payload.startsWith(Constants.MSG_TYPE_ACK) -> {
                val scope = payload.removePrefix(Constants.MSG_TYPE_ACK)
                val targetThread = if (scope == Constants.GROUP_PEER_ID) {
                    Constants.GROUP_PEER_ID
                } else {
                    env.senderId
                }
                lifecycleScope.launch {
                    chatRepository.markThreadDelivered(targetThread)
                }
            }
            payload.startsWith(Constants.MSG_TYPE_READ) -> {
                val scope = payload.removePrefix(Constants.MSG_TYPE_READ)
                val targetThread = if (scope == Constants.GROUP_PEER_ID) {
                    Constants.GROUP_PEER_ID
                } else {
                    env.senderId
                }
                lifecycleScope.launch {
                    chatRepository.markThreadRead(targetThread)
                }
            }
            else -> {
                Log.d(TAG, "Unhandled envelope payload: $payload")
            }
        }
    }

    /**
     * Legacy raw-line dispatcher — only used by the standby listener
     * (CallListenerService) which may receive lines from a pre-envelope peer.
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
        ChatServerService.onMeshPeersChanged = null
        stopService(Intent(this, ChatServerService::class.java))
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

        // Emit delivery receipt immediately; emit read receipt if this thread is actively viewed
        if (envIsDirectOrKnown(senderId)) {
            val scopeSuffix = if (recipientId == Constants.RECIPIENT_BROADCAST) Constants.GROUP_PEER_ID else ""
            sendControlMessage(Constants.MSG_TYPE_ACK + scopeSuffix, senderId)
            val isActivelyViewing = ChatBus.activeChatPeerId == threadPeerId ||
                (threadPeerId == activeThreadPeerId() && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
            if (isActivelyViewing) {
                sendControlMessage(Constants.MSG_TYPE_READ + scopeSuffix, senderId)
            }
        }

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

    private fun onIncomingFile(
        payload: String,
        senderId: String,
        senderName: String,
        recipientId: String
    ) {
        val offer = FileTransferManager.FileOffer.fromPayload(payload) ?: return
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
        val summary = FileTransferManager.formatSummaryText(offer.fileName, offer.fileSize)

        val message = ChatMessage(
            text = summary,
            isSentByMe = false,
            senderName = displaySender,
            attachmentName = offer.fileName,
            attachmentMime = offer.mimeType,
            attachmentSize = offer.fileSize,
            attachmentState = AttachmentState.TRANSFERRING,
            transferId = offer.transferId
        )

        lifecycleScope.launch {
            chatRepository.insertMessage(message, threadPeerId, threadPeerName)
            ChatBus.notifyMessageSaved(message, threadPeerId)

            val savedFile = FileTransferManager.downloadOffer(applicationContext, offer, senderId)
            val newState = if (savedFile != null) AttachmentState.COMPLETED else AttachmentState.FAILED
            chatRepository.updateAttachmentState(offer.transferId, savedFile?.absolutePath, newState)
            if (savedFile == null) {
                runOnUiThread {
                    showSnackbar("Failed to receive ${offer.fileName}")
                }
            }
        }

        if (envIsDirectOrKnown(senderId)) {
            val scopeSuffix = if (recipientId == Constants.RECIPIENT_BROADCAST) Constants.GROUP_PEER_ID else ""
            sendControlMessage(Constants.MSG_TYPE_ACK + scopeSuffix, senderId)
            val isActivelyViewing = ChatBus.activeChatPeerId == threadPeerId ||
                (threadPeerId == activeThreadPeerId() && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
            if (isActivelyViewing) {
                sendControlMessage(Constants.MSG_TYPE_READ + scopeSuffix, senderId)
            }
        }

        if (ChatBus.activeChatPeerId != threadPeerId &&
            threadPeerId != activeThreadPeerId() &&
            lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        ) {
            showSnackbar("Incoming file from $senderName: ${offer.fileName}")
        }
    }

    private fun openChatAttachment(message: ChatMessage) {
        val error = FileTransferManager.openAttachment(this, message)
        if (error != null) {
            showSnackbar(error)
        }
    }

    private fun sendFileFromUri(
        uri: Uri,
        recipientId: String,
        threadPeerId: String,
        threadPeerName: String
    ) {
        lifecycleScope.launch {
            val stagedResult = FileTransferManager.stageFromUri(applicationContext, uri)
            val staged = stagedResult.getOrElse { err ->
                showSnackbar(err.message ?: "Could not attach selected file")
                return@launch
            }
            val offer = staged.offer
            val summary = FileTransferManager.formatSummaryText(offer.fileName, offer.fileSize)
            val msg = ChatMessage(
                text = summary,
                isSentByMe = true,
                attachmentName = offer.fileName,
                attachmentMime = offer.mimeType,
                attachmentSize = offer.fileSize,
                attachmentPath = staged.localFile.absolutePath,
                attachmentState = AttachmentState.TRANSFERRING,
                transferId = offer.transferId
            )

            chatRepository.insertMessage(msg, threadPeerId, threadPeerName)
            ChatBus.notifyMessageSaved(msg, threadPeerId)

            val uploaded = FileTransferManager.pushStagedFileToGroup(staged)
            val signaled = if (uploaded) transmitPayload(recipientId, offer.toPayload()) else false
            val finalState = if (uploaded && signaled) {
                AttachmentState.COMPLETED
            } else {
                AttachmentState.FAILED
            }
            chatRepository.updateAttachmentState(offer.transferId, staged.localFile.absolutePath, finalState)
            if (finalState == AttachmentState.FAILED) {
                showSnackbar("Failed to send ${offer.fileName}")
            }
        }
    }

    private fun envIsDirectOrKnown(senderId: String): Boolean =
        isNetworkConnected() && senderId.isNotBlank() && senderId != "legacy"

    private fun sendReadReceipt(threadPeerId: String?) {
        if (!isNetworkConnected() || threadPeerId.isNullOrBlank()) return
        if (threadPeerId == Constants.GROUP_PEER_ID) {
            sendControlMessage(Constants.MSG_TYPE_READ + Constants.GROUP_PEER_ID, Constants.RECIPIENT_BROADCAST)
        } else {
            sendControlMessage(Constants.MSG_TYPE_READ, threadPeerId)
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

        if (isNetworkConnected()) {
            val sent = transmitText(recipient, text)
            if (!sent) {
                showSnackbar("Failed to transmit — saved locally")
            }
        }

        val message = ChatMessage(text, isSentByMe = true)
        etMessage.text.clear()
        if (searchQueryFlow.value.isNotBlank()) {
            setSearchOpen(false)
        }

        lifecycleScope.launch {
            chatRepository.insertMessage(message, threadPeerId, threadPeerName)
        }

        ChatBus.notifyMessageSaved(message, threadPeerId)
    }

    private fun setSearchOpen(open: Boolean) {
        searchBarContainer.visibility = if (open) View.VISIBLE else View.GONE
        searchBackCallback.isEnabled = open
        if (open) {
            etSearchMessages.requestFocus()
            etSearchMessages.post {
                val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.showSoftInput(etSearchMessages, InputMethodManager.SHOW_IMPLICIT)
            }
        } else {
            etSearchMessages.text?.clear()
            searchQueryFlow.value = ""
            hideKeyboard(etSearchMessages)
        }
    }

    private fun hideKeyboard(view: View) {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(view.windowToken, 0)
    }

    private fun updateSearchAndEmptyUi(totalCount: Int, visibleCount: Int, query: String) {
        val searching = query.isNotEmpty()
        if (searching) {
            tvSearchCount.visibility = View.VISIBLE
            tvSearchCount.text = if (visibleCount == 1) "1 hit" else "$visibleCount hits"
            llMessagesEmpty.visibility = View.GONE
            llSearchEmpty.visibility = if (visibleCount == 0) View.VISIBLE else View.GONE
        } else {
            tvSearchCount.visibility = View.GONE
            llSearchEmpty.visibility = View.GONE
            llMessagesEmpty.visibility = if (totalCount == 0) View.VISIBLE else View.GONE
        }
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
        tvActiveChatSubtitle.text = if (isNetworkConnected()) {
            getString(R.string.label_online_encrypted)
        } else {
            getString(R.string.label_offline_local)
        }
    }

    /**
     * Send text over the live mesh channel.
     * If [recipientId] is "*" the message goes to the group chat (broadcast).
     * Otherwise it routes directly to that peer (with relay fallback).
     */
    private fun transmitText(recipientId: String, text: String): Boolean =
        transmitPayload(recipientId, Constants.MSG_TYPE_TEXT + text)

    private fun transmitPayload(recipientId: String, payload: String): Boolean {
        return try {
            if (!ChatServerService.running) return false
            if (recipientId == Constants.RECIPIENT_BROADCAST) {
                ChatServerService.sendBroadcast(payload)
            } else {
                ChatServerService.sendTo(recipientId, payload)
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
        if (!ChatServerService.running) return
        val target = recipientId ?: currentPeerId ?: Constants.RECIPIENT_BROADCAST
        if (target == Constants.RECIPIENT_BROADCAST) {
            ChatServerService.sendBroadcast(message)
        } else {
            ChatServerService.sendTo(target, message)
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

    /** Update the compact stored-message counter in the channel context strip. */
    private fun updateStoragePill() {
        tvStoragePill.text = if (storedCount == 1) "1 saved" else "$storedCount saved"
    }

    /** Enable/disable the call buttons based on P2P or LAN mesh connection state. */
    private fun updateCallButtons() {
        val connected = isNetworkConnected()
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
                    totalThreadMessages = 0
                    messageAdapter.clear()
                    updateSearchAndEmptyUi(0, 0, searchQueryFlow.value.trim())
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
                        ?: return@withContext Result.failure(IllegalStateException("Could not create file"))
                    contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(content.toByteArray())
                    } ?: return@withContext Result.failure(IllegalStateException("Could not open file"))
                    Result.success("Downloads/$fileName")
                } else {
                    // Legacy devices — app-private Documents folder (visible via file manager)
                    val dir = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
                        ?: return@withContext Result.failure(IllegalStateException("Storage unavailable"))
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

    // ======================== Calling (Direct & Role-Agnostic) ========================

    private fun startAudioCall(
        targetPeerId: String? = null,
        targetPeerName: String? = null
    ) {
        if (!isNetworkConnected()) {
            showSnackbar("Connect to a peer first")
            return
        }
        resolveCallTarget(targetPeerId, targetPeerName) { resolvedId, resolvedName ->
            pendingCallTargetPeerId = resolvedId
            pendingCallTargetPeerName = resolvedName
            ensureCallPermissions(video = false) {
                launchAudioCall(resolvedId, resolvedName)
            }
        }
    }

    private fun startVideoCall(
        targetPeerId: String? = null,
        targetPeerName: String? = null
    ) {
        if (!isNetworkConnected()) {
            showSnackbar("Connect to a peer first")
            return
        }
        resolveCallTarget(targetPeerId, targetPeerName) { resolvedId, resolvedName ->
            pendingCallTargetPeerId = resolvedId
            pendingCallTargetPeerName = resolvedName
            ensureCallPermissions(video = true) {
                launchVideoCall(resolvedId, resolvedName)
            }
        }
    }

    private fun resolveCallTarget(
        explicitPeerId: String?,
        explicitPeerName: String?,
        onResolved: (String?, String) -> Unit
    ) {
        if (!explicitPeerId.isNullOrBlank() && explicitPeerId != Constants.GROUP_PEER_ID) {
            onResolved(
                explicitPeerId,
                explicitPeerName ?: PeerRegistry.nameFor(explicitPeerId) ?: explicitPeerId
            )
            return
        }
        val current = currentPeerId?.takeIf { it != Constants.GROUP_PEER_ID }
        if (current != null) {
            onResolved(current, currentPeerName ?: PeerRegistry.nameFor(current) ?: current)
            return
        }
        val onlinePeers = PeerRegistry.snapshot()
        when {
            onlinePeers.isEmpty() -> onResolved(null, activeThreadPeerName())
            onlinePeers.size == 1 -> onResolved(onlinePeers[0].id, onlinePeers[0].name)
            else -> {
                val labels = onlinePeers.map { it.name.ifBlank { it.id } }.toTypedArray()
                MaterialAlertDialogBuilder(this)
                    .setTitle("Select peer to call")
                    .setItems(labels) { _, idx ->
                        val chosen = onlinePeers[idx]
                        onResolved(chosen.id, chosen.name)
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
    }

    private fun launchAudioCall(
        targetPeerId: String? = pendingCallTargetPeerId ?: currentPeerId,
        targetPeerName: String? = pendingCallTargetPeerName ?: currentPeerName ?: activeThreadPeerName()
    ) {
        if (!isNetworkConnected()) return
        val localIp = ChatServerService.localIpForPeer(targetPeerId)
            ?: DeviceIdentity.resolveLocalIp(PeerRegistry.ipFor(targetPeerId))
        val callPayload = if (!localIp.isNullOrBlank()) {
            "${Constants.MSG_TYPE_CALL_REQUEST}${Constants.CALL_TYPE_AUDIO}|$localIp"
        } else {
            Constants.MSG_TYPE_CALL_REQUEST + Constants.CALL_TYPE_AUDIO
        }
        sendControlMessage(callPayload, targetPeerId)

        // Caller hosts the WebRTC signaling socket directly (no Group Owner hub needed!)
        val intent = Intent(this, AudioCallActivity::class.java).apply {
            putExtra(AudioCallActivity.EXTRA_IS_GROUP_OWNER, true)
            putExtra(AudioCallActivity.EXTRA_HOST_ADDRESS, localIp)
            putExtra(AudioCallActivity.EXTRA_PEER_ID, targetPeerId)
            putExtra(AudioCallActivity.EXTRA_PEER_NAME, targetPeerName ?: "Peer")
            putExtra(AudioCallActivity.EXTRA_IS_OUTGOING, true)
        }
        startActivity(intent)
        overridePendingTransition(R.anim.slide_up, android.R.anim.fade_out)
    }

    private fun launchVideoCall(
        targetPeerId: String? = pendingCallTargetPeerId ?: currentPeerId,
        targetPeerName: String? = pendingCallTargetPeerName ?: currentPeerName ?: activeThreadPeerName()
    ) {
        if (!isNetworkConnected()) return
        val localIp = ChatServerService.localIpForPeer(targetPeerId)
            ?: DeviceIdentity.resolveLocalIp(PeerRegistry.ipFor(targetPeerId))
        val callPayload = if (!localIp.isNullOrBlank()) {
            "${Constants.MSG_TYPE_CALL_REQUEST}${Constants.CALL_TYPE_VIDEO}|$localIp"
        } else {
            Constants.MSG_TYPE_CALL_REQUEST + Constants.CALL_TYPE_VIDEO
        }
        sendControlMessage(callPayload, targetPeerId)

        // Caller hosts direct Camera2 + WebRTC call sockets regardless of P2P group role
        val intent = Intent(this, VideoCallActivity::class.java).apply {
            putExtra(VideoCallActivity.EXTRA_IS_GROUP_OWNER, true)
            putExtra(VideoCallActivity.EXTRA_HOST_ADDRESS, localIp)
            putExtra(VideoCallActivity.EXTRA_PEER_ID, targetPeerId)
            putExtra(VideoCallActivity.EXTRA_PEER_NAME, targetPeerName ?: "Peer")
            putExtra(VideoCallActivity.EXTRA_IS_OUTGOING, true)
        }
        startActivity(intent)
        overridePendingTransition(R.anim.slide_up, android.R.anim.fade_out)
    }

    private fun ensureCallPermissions(video: Boolean, onGranted: () -> Unit) {
        val needed = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (video) needed.add(Manifest.permission.CAMERA)

        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            onGranted()
            return
        }

        pendingCallVideoAfterPermission = video
        val shouldShowRationale = missing.any {
            ActivityCompat.shouldShowRequestPermissionRationale(this, it)
        }
        if (shouldShowRationale) {
            val detail = if (video) {
                "LANVOX needs microphone and camera permissions to place a video call."
            } else {
                "LANVOX needs microphone permission to place an audio call."
            }
            MaterialAlertDialogBuilder(this)
                .setTitle("Call permission required")
                .setMessage(detail)
                .setNegativeButton("Cancel") { _, _ ->
                    pendingCallVideoAfterPermission = null
                    pendingCallTargetPeerId = null
                    pendingCallTargetPeerName = null
                }
                .setPositiveButton("Allow") { _, _ ->
                    permissionLauncher.launch(missing.toTypedArray())
                }
                .show()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    /**
     * Handle an incoming call — show the IncomingCallActivity with accept/decline UI
     * and route direct peer-to-peer connection to the caller's IP (no central host required).
     */
    private fun handleIncomingCallRequest(rawRequest: String, callerId: String, callerName: String) {
        val callType = rawRequest.substringBefore('|')
        val explicitCallerIp = rawRequest.substringAfter('|', "").takeIf { it.isNotBlank() }
        val resolvedHost = explicitCallerIp
            ?: PeerRegistry.ipFor(callerId)
            ?: wifiManager.groupOwnerAddress
        val actAsCallHost = if (!explicitCallerIp.isNullOrBlank() || PeerRegistry.ipFor(callerId) != null) {
            false
        } else {
            wifiManager.isGroupOwner
        }

        runOnUiThread {
            if (IncomingCallActivity.isShowing() || CallSessionBus.isCallActive()) {
                Log.d(TAG, "Ignoring incoming call while another call UI is active")
                return@runOnUiThread
            }
            val intent = Intent(this, IncomingCallActivity::class.java).apply {
                putExtra(IncomingCallActivity.EXTRA_CALL_TYPE, callType)
                putExtra(IncomingCallActivity.EXTRA_IS_GROUP_OWNER, actAsCallHost)
                putExtra(IncomingCallActivity.EXTRA_HOST_ADDRESS, resolvedHost)
                putExtra(IncomingCallActivity.EXTRA_PEER_ID, callerId)
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
            refreshNetworkConnectionUi()
        } else {
            stopDiscoveryUiState(updateStatusText = true)
        }
    }

    override fun onPeersUpdated(peers: List<WifiP2pDevice>) {
        latestPeers = peers
        peerAdapter.updatePeers(peers)
        updateInlinePeersEmptyState()
        radarView?.setBlipCount(peers.size)
        updateToggleDevicesLabel()
        refreshPeerPickerBottomSheet()

        // Automatic failover rejoin: if the previous Group Owner vanished and another
        // surviving peer was elected, reconnect to it automatically.
        val targetId = pendingFailoverTargetId
        val targetName = pendingFailoverTargetName
        if (!wifiManager.isConnected && (!targetId.isNullOrBlank() || !targetName.isNullOrBlank())) {
            val match = peers.firstOrNull { p ->
                p.status == WifiP2pDevice.AVAILABLE &&
                    ((targetId != null && p.deviceAddress == targetId) ||
                        (targetName != null && p.deviceName.equals(targetName, ignoreCase = true)) ||
                        p.isGroupOwner)
            }
            if (match != null) {
                pendingFailoverTargetId = null
                pendingFailoverTargetName = null
                connectToPeer(match)
            }
        }

        // Peer identity may have just been resolved — finish opening the chat
        if (pendingChatOpen) {
            tryOpenChatForConnectedPeer()
        }
        Log.d(TAG, "Found ${peers.size} peer(s)")
    }

    override fun onDeviceChanged(device: WifiP2pDevice?) {
        device?.let {
            val name = it.deviceName.ifBlank { DeviceIdentity.defaultDeviceName() }
            tvDeviceInfo.text = name
            PeerRegistry.selfId = DeviceIdentity.getId(this, it.deviceAddress)
            PeerRegistry.selfName = name
            PeerRegistry.selfIpAddress = DeviceIdentity.resolveLocalIp()
            Log.d(TAG, "Self identity: ${PeerRegistry.selfId} (${PeerRegistry.selfName})")
        }
    }

    override fun onConnected(info: WifiP2pInfo?) {
        progressConnecting.visibility = View.GONE
        userInitiatedDisconnect = false
        pendingFailoverTargetId = null
        pendingFailoverTargetName = null

        startChatServer()
        info?.let { connectionInfo ->
            PeerRegistry.isSelfGroupOwner = connectionInfo.isGroupOwner
            PeerRegistry.selfIpAddress = DeviceIdentity.resolveLocalIp(
                connectionInfo.groupOwnerAddress?.hostAddress
            )
            val hostAddress = connectionInfo.groupOwnerAddress?.hostAddress
            if (!connectionInfo.isGroupOwner && !hostAddress.isNullOrBlank()) {
                Log.d(TAG, "Joining mesh via P2P link to $hostAddress")
                ChatServerService.connectToPeer(
                    hostIp = hostAddress,
                    isGroupOwnerUplink = true,
                    transport = "p2p"
                )
            }

            FileTransferManager.configureSession(
                this,
                connectionInfo.isGroupOwner,
                hostAddress
            )

            // Snapshot the session so the standby listener can keep receiving
            // calls/messages after this process dies
            LastSession.save(
                this,
                connectionInfo.isGroupOwner,
                hostAddress,
                currentPeerId,
                currentPeerName
            )
        }

        refreshNetworkConnectionUi()

        // Resolve who we are talking to, then open their chat thread
        pendingChatOpen = true
        tryOpenChatForConnectedPeer()

        if (isDiscovering) {
            wifiManager.stopDiscovery()
            stopDiscoveryUiState(updateStatusText = false)
        } else {
            refreshPeerPickerBottomSheet()
        }
        showSnackbar("Connected to peer")
    }

    override fun onDisconnected() {
        progressConnecting.visibility = View.GONE
        val survivingCandidates = lastP2pNonGoPeers
        lastP2pNonGoPeers = emptyList()

        ChatServerService.disconnectTransport("p2p")
        PeerRegistry.isSelfGroupOwner = false
        LastSession.clearRole(this)

        if (!ChatServerService.hasConnectedPeers()) {
            FileTransferManager.stopSession()
            clearCurrentPeer()
        } else {
            // LAN/NSD mesh peers remain active — keep FileTransferManager server alive!
            FileTransferManager.ensureServerRunning(applicationContext)
        }
        refreshNetworkConnectionUi()

        // Automatic host failover election when Group Owner unexpectedly drops off-grid
        if (!userInitiatedDisconnect &&
            survivingCandidates.isNotEmpty() &&
            !ChatServerService.hasConnectedPeers() &&
            WiFiDirectManager.hasWifiP2pPermission(this)
        ) {
            val allCandidateIds = (survivingCandidates.map { it.id } + PeerRegistry.selfId).sorted()
            val winnerId = allCandidateIds.firstOrNull()
            if (winnerId == PeerRegistry.selfId) {
                showSnackbar("Group host left — promoting this phone as backup host…")
                wifiManager.createAutonomousGroup { ok ->
                    if (!ok) startDiscoveryIfReady()
                }
            } else {
                val winnerPeer = survivingCandidates.firstOrNull { it.id == winnerId }
                pendingFailoverTargetId = winnerId
                pendingFailoverTargetName = winnerPeer?.name
                showSnackbar("Group host left — reconnecting to ${winnerPeer?.name ?: "remaining peer"}…")
                startDiscoveryIfReady()
            }
        }
    }

    override fun onConnectionFailed(reason: String) {
        progressConnecting.visibility = View.GONE
        if (isDiscovering) {
            stopDiscoveryUiState(updateStatusText = true)
        } else {
            refreshPeerPickerBottomSheet()
        }
        showSnackbar(reason, "Retry") { toggleDiscovery() }
    }
}
