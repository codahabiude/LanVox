package com.example.wifip2pchat

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlin.coroutines.coroutineContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Always-on standby listener — lets LANVOX receive calls and messages
 * even when the app is NOT open.
 *
 * How it works
 * ────────────
 *  • Runs as a foreground service with a quiet persistent notification
 *    ("LANVOX is ready to receive calls"). Started on boot by
 *    [BootReceiver] and whenever the app opens.
 *
 *  • While the main app is alive (MainActivity static instance exists)
 *    the standby sleeps — the app's own ChatServerService / ChatClient
 *    own the chat channel.
 *
 *  • Once the app process is gone, the standby takes over the chat port:
 *      - Group Owner role → binds port [Constants.CHAT_PORT] and accepts
 *        the peer's connection.
 *      - Client role → actively connects out to the group owner, because
 *        a client can only receive data over its own outgoing socket.
 *    The live WiFi Direct group state is re-checked through
 *    WifiP2pManager.requestConnectionInfo().
 *
 *  • A line arriving on the standby channel is handled like this:
 *      - App alive        → forwarded to MainActivity (normal in-app flow)
 *      - CALL_REQ:x       → ring (sound + vibration), wake the screen and
 *                           post a full-screen incoming-call notification;
 *                           timeout after 45 s → "missed call"
 *      - CALL_END:x       → cancel ringing (caller gave up)
 *      - plain text       → saved to phone storage + message notification
 *
 *  • Answers (accept / decline) are written back over the very same
 *    standby socket via [sendControl], so the caller hears the reply even
 *    though the app was closed.
 *
 * Limitation: if the user force-stops the app from system settings,
 * Android will not restart it — the listener resumes after the app is
 * opened once (or the phone is rebooted with the app NOT force-stopped).
 */
class CallListenerService : Service() {

    companion object {
        private const val TAG = "CallListenerService"

        // Notification channels / ids
        private const val CHANNEL_STANDBY = "lanvox_standby"
        private const val CHANNEL_CALLS = "lanvox_calls"
        private const val CHANNEL_MESSAGES = "lanvox_messages"
        private const val NOTIF_STANDBY_ID = 3001
        private const val NOTIF_CALL_ID = 3002
        private const val NOTIF_MISSED_ID = 3003
        private const val NOTIF_MSG_ID_BASE = 4000

        // Intent actions
        const val ACTION_MAIN_APP_ACTIVE = "com.example.lanvox.APP_ACTIVE"
        private const val ACTION_DECLINE_CALL = "com.example.lanvox.DECLINE_CALL"

        private const val RING_TIMEOUT_MS = 45_000L
        private const val APP_GONE_GRACE_MS = 4_000L

        // ---- shared live state ----
        private var serviceInstance: CallListenerService? = null

        @Volatile
        private var pendingCallType: String? = null
        @Volatile
        private var pendingCallerIp: String? = null

        /**
         * Called by MainActivity the moment the connection hub opens so the
         * standby instantly hands the chat port over to the app.
         */
        fun notifyAppActive(context: Context) {
            try {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, CallListenerService::class.java)
                        .setAction(ACTION_MAIN_APP_ACTIVE)
                )
            } catch (e: Exception) {
                Log.w(TAG, "Could not signal app-active: ${e.message}")
            }
        }

        /** True while the standby is ringing for an incoming call. */
        fun hasPendingCall(): Boolean = pendingCallType != null

        /**
         * Send a control line (CALL_ACCEPT / CALL_REJECT / …) back to the
         * peer over the standby channel. Used by IncomingCallActivity when
         * the main app is not alive.
         */
        fun sendControl(text: String, recipientId: String? = null): Boolean {
            val inst = serviceInstance ?: return false
            val target = recipientId ?: LastSession.peerMac(inst) ?: Constants.RECIPIENT_BROADCAST
            val line = if (PeerRegistry.selfId != "UNKNOWN" && !text.startsWith(Constants.ENVELOPE_PREFIX + Constants.ENVELOPE_DELIM)) {
                Envelope.build(
                    senderId = PeerRegistry.selfId,
                    senderName = PeerRegistry.selfName,
                    recipientId = target,
                    payload = text
                )
            } else {
                text
            }
            return inst.writeLine(line)
        }

        /** The incoming call was answered or declined — stop ringing UI. */
        fun onIncomingCallHandled() {
            serviceInstance?.endIncomingCallLocally()
        }
    }

    // ---- sockets ----
    private var serverSocket: ServerSocket? = null
    private var controlSocket: Socket? = null
    private var controlWriter: OutputStreamWriter? = null
    private val writeLock = Any()

    // ---- infra ----
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var ringTimeoutJob: Job? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var p2pManager: WifiP2pManager? = null
    private var p2pChannel: WifiP2pManager.Channel? = null
    private var notificationManager: NotificationManager? = null

    @Volatile
    private var lastAppAliveAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        serviceInstance = this
        notificationManager = getSystemService(NotificationManager::class.java)
        createNotificationChannels()
        startForeground(NOTIF_STANDBY_ID, buildStandbyNotification())
        acquireWifiLock()
        initP2p()
        startWatchdog()
        Log.d(TAG, "Standby listener running — calls can arrive while app is closed")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_MAIN_APP_ACTIVE -> {
                Log.d(TAG, "Main app active — releasing standby channel")
                closeStandbySockets()
            }
            ACTION_DECLINE_CALL -> {
                Log.d(TAG, "Call declined from the notification")
                writeLine(Constants.MSG_TYPE_CALL_REJECT + (pendingCallType ?: Constants.CALL_TYPE_AUDIO))
                endIncomingCallLocally()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        closeStandbySockets()
        CallRingtoneManager.stopRinging()
        notificationManager?.cancel(NOTIF_CALL_ID)
        try { wifiLock?.release() } catch (_: Exception) {}
        wifiLock = null
        serviceInstance = null
        super.onDestroy()
        Log.d(TAG, "Standby listener destroyed")
    }

    // ======================== Watchdog ========================

    /**
     * Master loop: decides what the standby should be doing based on
     * whether the app UI is alive and on the live WiFi Direct state.
     */
    private fun startWatchdog() {
        scope.launch {
            while (isActive) {
                try {
                    if (MainActivity.isAppUiAlive()) {
                        lastAppAliveAt = System.currentTimeMillis()
                        delay(1_500)
                        continue
                    }

                    // Small grace period so an activity recreation (rotation)
                    // is not mistaken for the app being gone.
                    if (System.currentTimeMillis() - lastAppAliveAt < APP_GONE_GRACE_MS) {
                        delay(1_000)
                        continue
                    }

                    if (!LastSession.hasRole(this@CallListenerService)) {
                        delay(4_000)
                        continue
                    }

                    val info = queryConnectionInfo()
                    when {
                        info == null || !info.groupFormed -> {
                            closeStandbySockets()
                            delay(3_000)
                        }
                        info.isGroupOwner -> serverCycleOnce()
                        else -> clientCycleOnce(info.groupOwnerAddress?.hostAddress)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Watchdog error: ${e.message}")
                    delay(3_000)
                }
            }
        }
    }

    /** Live group state from the WiFi Direct framework (null = unknown). */
    private suspend fun queryConnectionInfo(): WifiP2pInfo? =
        withTimeoutOrNull(3_000) {
            suspendCancellableCoroutine { cont ->
                val mgr = p2pManager
                val ch = p2pChannel
                if (mgr == null || ch == null) {
                    if (cont.isActive) cont.resume(null, null)
                    return@suspendCancellableCoroutine
                }
                try {
                    mgr.requestConnectionInfo(ch) { info ->
                        if (cont.isActive) cont.resume(info, null)
                    }
                } catch (e: Exception) {
                    if (cont.isActive) cont.resume(null, null)
                }
            }
        }

    // ======================== GO mode ========================

    /** Bind the chat port and wait for the peer to connect (we are GO). */
    private suspend fun serverCycleOnce() {
        val server = try {
            ServerSocket().apply { bind(InetSocketAddress(Constants.CHAT_PORT)) }
        } catch (e: Exception) {
            // Port busy — the app's own server is probably starting
            Log.w(TAG, "Standby bind failed (${e.message}) — retrying soon")
            delay(3_000)
            return
        }
        serverSocket = server
        FileTransferManager.configureSession(applicationContext, isGroupOwner = true, hostAddress = null)
        Log.d(TAG, "Standby listening on port ${Constants.CHAT_PORT} (group owner)")

        // Safety: unblock as soon as the app becomes alive
        val guard = startAppAliveGuard()

        try {
            while (coroutineContext.isActive) {
                val sock = try {
                    server.accept()
                } catch (e: Exception) {
                    break
                }
                Log.d(TAG, "Standby: peer connected (${sock.inetAddress?.hostAddress})")
                adoptSocket(sock)
                readFrom(sock) // blocks until the peer drops or we close
            }
        } finally {
            guard.cancel()
            closeStandbySockets()
        }
    }

    // ======================== Client mode ========================

    /**
     * Connect out to the group owner (we are client) — required because a
     * client only receives data over its own outgoing socket.
     */
    private suspend fun clientCycleOnce(goAddress: String?) {
        if (goAddress.isNullOrBlank()) {
            delay(2_000)
            return
        }

        val guard = startAppAliveGuard()

        try {
            val sock = Socket()
            sock.connect(InetSocketAddress(goAddress, Constants.CHAT_PORT), 4_000)
            FileTransferManager.configureSession(applicationContext, isGroupOwner = false, hostAddress = goAddress)
            Log.d(TAG, "Standby: connected to group owner at $goAddress")
            adoptSocket(sock)
            readFrom(sock) // blocks until EOF
        } catch (e: Exception) {
            Log.d(TAG, "Standby client connect failed (${e.message})")
        } finally {
            guard.cancel()
            closeStandbySockets()
        }
    }

    /** Closes the standby channel the moment the main app comes alive. */
    private fun startAppAliveGuard(): Job = scope.launch {
        while (isActive) {
            if (MainActivity.isAppUiAlive()) {
                closeStandbySockets()
                return@launch
            }
            delay(1_000)
        }
    }

    // ======================== Socket plumbing ========================

    private fun adoptSocket(sock: Socket) {
        try { controlSocket?.close() } catch (_: Exception) {}
        controlSocket = sock
        controlWriter = try {
            OutputStreamWriter(sock.getOutputStream(), "UTF-8")
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun readFrom(sock: Socket) {
        try {
            val reader = BufferedReader(InputStreamReader(sock.getInputStream(), "UTF-8"))
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                line?.let { dispatchLine(it) }
            }
            Log.d(TAG, "Standby: peer stream ended")
        } catch (e: Exception) {
            Log.d(TAG, "Standby read ended: ${e.message}")
        } finally {
            if (controlSocket === sock) {
                controlSocket = null
                controlWriter = null
            }
            try { sock.close() } catch (_: Exception) {}
        }
    }

    private fun writeLine(text: String): Boolean {
        val writer = controlWriter ?: return false
        return try {
            synchronized(writeLock) {
                writer.write(text + "\n")
                writer.flush()
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "Standby write failed: ${e.message}")
            false
        }
    }

    private fun closeStandbySockets() {
        try { serverSocket?.close() } catch (_: Exception) {}
        try { controlSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        controlSocket = null
        controlWriter = null
    }

    // ======================== Message dispatch ========================

    private fun dispatchLine(line: String) {
        // Give the live app the first chance to consume the line
        if (MainActivity.handleStandbyLine(line)) return

        val env = Envelope.parse(line)
        val payload = env?.payload ?: line
        val senderId = env?.senderId?.takeIf { it.isNotBlank() && it != "legacy" }
            ?: LastSession.peerMac(this)?.takeIf { it.isNotBlank() }
            ?: "unknown"
        val senderName = env?.senderName?.takeIf { it.isNotBlank() && it != "Unknown" }
            ?: LastSession.peerName(this)?.takeIf { it.isNotBlank() }
            ?: "LanVox contact"
        val recipientId = env?.recipientId ?: Constants.RECIPIENT_BROADCAST

        when {
            payload == Constants.PAYLOAD_HELLO || payload == Constants.PAYLOAD_BYE -> {
                if (payload == Constants.PAYLOAD_HELLO && env != null) {
                    PeerRegistry.upsert(PeerRegistry.Peer(senderId, senderName, isGroupOwner = false))
                }
            }
            payload.startsWith(Constants.PAYLOAD_PEERS) -> {
                if (env != null) {
                    PeerRegistry.replaceAll(
                        PeerRegistry.parsePeersPayload(payload, env.senderId, env.senderName)
                    )
                }
            }
            payload.startsWith(Constants.MSG_TYPE_CALL_REQUEST) -> {
                val rawReq = payload.removePrefix(Constants.MSG_TYPE_CALL_REQUEST)
                val callType = rawReq.substringBefore('|')
                val callerIp = rawReq.substringAfter('|', "").takeIf { it.isNotBlank() }
                onIncomingCall(
                    callType = callType,
                    peerId = senderId,
                    callerName = senderName,
                    callerIp = callerIp
                )
            }
            payload.startsWith(Constants.MSG_TYPE_CALL_END) -> {
                if (pendingCallType != null) endIncomingCallLocally()
            }
            payload.startsWith(Constants.MSG_TYPE_CALL_ACCEPT) -> {
                Log.d(TAG, "Peer accepted (app closed) — ignoring")
            }
            payload.startsWith(Constants.MSG_TYPE_CALL_REJECT) -> {
                Log.d(TAG, "Peer declined (app closed) — ignoring")
            }
            payload.startsWith(Constants.MSG_TYPE_ACK) -> {
                val rawAck = payload.removePrefix(Constants.MSG_TYPE_ACK)
                val scopeSuffix = rawAck.substringBefore('|')
                val msgIds = rawAck.substringAfter('|', "")
                    .split(',')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                val targetThread = if (scopeSuffix == Constants.GROUP_PEER_ID) {
                    Constants.GROUP_PEER_ID
                } else {
                    senderId
                }
                scope.launch {
                    runCatching {
                        ChatRepository.get(applicationContext).markMessagesDelivered(targetThread, msgIds)
                    }
                }
            }
            payload.startsWith(Constants.MSG_TYPE_READ) -> {
                val rawRead = payload.removePrefix(Constants.MSG_TYPE_READ)
                val scopeSuffix = rawRead.substringBefore('|')
                val msgIds = rawRead.substringAfter('|', "")
                    .split(',')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                val targetThread = if (scopeSuffix == Constants.GROUP_PEER_ID) {
                    Constants.GROUP_PEER_ID
                } else {
                    senderId
                }
                scope.launch {
                    runCatching {
                        ChatRepository.get(applicationContext).markMessagesRead(targetThread, msgIds)
                    }
                }
            }
            payload.startsWith(Constants.MSG_TYPE_TYPING) -> {
                // Ephemeral typing indicator — ignored while UI is in standby
            }
            payload.startsWith(Constants.MSG_TYPE_REACT) -> {
                val rawReact = payload.removePrefix(Constants.MSG_TYPE_REACT)
                val parts = rawReact.split('|', limit = 4)
                if (parts.size >= 4) {
                    val scopeSuffix = parts[0]
                    val targetMsgId = parts[1].trim()
                    val emoji = parts[2]
                    val add = parts[3].trim() == "1"
                    val targetThread = if (scopeSuffix == Constants.GROUP_PEER_ID) {
                        Constants.GROUP_PEER_ID
                    } else {
                        senderId
                    }
                    scope.launch {
                        runCatching {
                            ChatRepository.get(applicationContext)
                                .applyRemoteReaction(targetThread, targetMsgId, emoji, senderId, add)
                        }
                    }
                }
            }
            payload.startsWith(Constants.MSG_TYPE_REPLY) -> {
                val decoded = ChatMessage.decodeReplyPayload(payload)
                if (decoded != null) {
                    onIncomingText(
                        text = decoded.text,
                        senderId = senderId,
                        senderName = senderName,
                        recipientId = recipientId,
                        msgId = env?.msgId,
                        replyToId = decoded.replyToId,
                        replyToSender = decoded.replySender,
                        replyToText = decoded.replySnippet
                    )
                }
            }
            payload.startsWith(Constants.MSG_TYPE_TEXT) -> {
                val cleanText = payload.removePrefix(Constants.MSG_TYPE_TEXT)
                onIncomingText(cleanText, senderId, senderName, recipientId, env?.msgId)
            }
            payload.startsWith(Constants.MSG_TYPE_FILE) -> {
                onIncomingFile(payload, senderId, senderName, recipientId, env?.msgId)
            }
            payload.startsWith(Constants.MSG_TYPE_FILE_DONE) -> {
                val transferId = payload.removePrefix(Constants.MSG_TYPE_FILE_DONE).trim()
                if (transferId.isNotEmpty()) {
                    FileTransferTracker.markPeerCompleted(transferId, senderId, senderName)
                    val snap = FileTransferTracker.snapshotFor(transferId)
                    if (snap == null || snap.isFinished) {
                        scope.launch {
                            runCatching {
                                ChatRepository.get(applicationContext)
                                    .updateAttachmentStateOnly(transferId, AttachmentState.COMPLETED)
                            }
                        }
                    }
                }
            }
            payload.startsWith(Constants.MSG_TYPE_FILE_FAIL) -> {
                val transferId = payload.removePrefix(Constants.MSG_TYPE_FILE_FAIL).trim()
                if (transferId.isNotEmpty()) {
                    FileTransferTracker.markPeerFailed(transferId, senderId, senderName)
                    val snap = FileTransferTracker.snapshotFor(transferId)
                    if (snap != null && snap.isFinished) {
                        val allFailed = snap.peerStatuses.values.all {
                            it.state == FileTransferTracker.PeerTransferStatus.FAILED
                        }
                        val finalState = if (allFailed) AttachmentState.FAILED else AttachmentState.COMPLETED
                        scope.launch {
                            runCatching {
                                ChatRepository.get(applicationContext)
                                    .updateAttachmentStateOnly(transferId, finalState)
                            }
                        }
                    }
                }
            }
            else -> {
                if (env == null) {
                    onIncomingText(payload, senderId, senderName, recipientId)
                } else {
                    Log.d(TAG, "Unhandled standby payload: $payload")
                }
            }
        }
    }

    // ======================== Incoming call ========================

    private fun onIncomingCall(
        callType: String,
        peerId: String,
        callerName: String,
        callerIp: String?
    ) {
        if (pendingCallType != null) {
            Log.d(TAG, "Already ringing — ignoring second call request")
            return
        }
        pendingCallType = callType
        pendingCallerIp = callerIp
        val connectHost = callerIp
            ?: PeerRegistry.ipFor(peerId)
            ?: LastSession.groupOwnerAddress(this@CallListenerService)
        // When callerIp is explicitly given, caller acts as call host & callee connects directly.
        val actAsCallHost = if (!callerIp.isNullOrBlank()) false else LastSession.isGroupOwner(this@CallListenerService)

        val callLabel =
            if (callType == Constants.CALL_TYPE_VIDEO) "video call" else "audio call"

        Log.d(TAG, "Incoming $callLabel from $callerName (host=$connectHost) — ringing (app closed)")

        // Ring with sound + vibration (also wakes the user up)
        CallRingtoneManager.startRinging(this)

        val answerIntent = PendingIntent.getActivity(
            this, 1001,
            Intent(this, IncomingCallActivity::class.java).apply {
                putExtra(IncomingCallActivity.EXTRA_CALL_TYPE, callType)
                putExtra(IncomingCallActivity.EXTRA_IS_GROUP_OWNER, actAsCallHost)
                putExtra(IncomingCallActivity.EXTRA_HOST_ADDRESS, connectHost)
                putExtra(IncomingCallActivity.EXTRA_CALLER_NAME, callerName)
                putExtra(IncomingCallActivity.EXTRA_PEER_ID, peerId)
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val declineIntent = PendingIntent.getService(
            this, 1002,
            Intent(this, CallListenerService::class.java).setAction(ACTION_DECLINE_CALL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_CALLS)
            .setSmallIcon(android.R.drawable.sym_action_call)
            .setContentTitle("Incoming ${callLabel.replaceFirstChar { it.uppercase() }}")
            .setContentText("$callerName is calling on LanVox")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setFullScreenIntent(answerIntent, true)
            .addAction(0, "Answer", answerIntent)
            .addAction(0, "Decline", declineIntent)
            .setOngoing(true)
            .build()

        try {
            notificationManager?.notify(NOTIF_CALL_ID, notification)
        } catch (e: Exception) {
            // POST_NOTIFICATIONS not granted — the full-screen activity
            // still attempts to launch; nothing else to do.
            Log.w(TAG, "Could not post call notification: ${e.message}")
        }

        ringTimeoutJob?.cancel()
        ringTimeoutJob = scope.launch {
            delay(RING_TIMEOUT_MS)
            if (pendingCallType != null) {
                Log.d(TAG, "Call missed — nobody answered")
                writeLine(Constants.MSG_TYPE_CALL_REJECT + (pendingCallType ?: callType))
                showMissedCallNotification(callerName, callLabel)
                endIncomingCallLocally()
            }
        }
    }

    private fun showMissedCallNotification(callerName: String, callLabel: String) {
        val openApp = PendingIntent.getActivity(
            this, 1003,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_MESSAGES)
            .setSmallIcon(android.R.drawable.sym_action_call)
            .setContentTitle("Missed $callLabel")
            .setContentText(callerName)
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MISSED_CALL)
            .build()
        try {
            notificationManager?.notify(NOTIF_MISSED_ID, notification)
        } catch (_: Exception) {}
    }

    /** Stop every ringing artifact (called on accept/decline/timeout/cancel). */
    private fun endIncomingCallLocally() {
        pendingCallType = null
        ringTimeoutJob?.cancel()
        ringTimeoutJob = null
        CallRingtoneManager.stopRinging()
        notificationManager?.cancel(NOTIF_CALL_ID)
        IncomingCallActivity.dismiss()
    }

    // ======================== Incoming text ========================

    private fun onIncomingText(
        text: String,
        senderId: String,
        senderName: String,
        recipientId: String,
        msgId: String? = null,
        replyToId: String? = null,
        replyToSender: String? = null,
        replyToText: String? = null
    ) {
        val threadId = if (recipientId == Constants.RECIPIENT_BROADCAST && senderId != "unknown") {
            Constants.GROUP_PEER_ID
        } else {
            senderId
        }
        val threadName = if (threadId == Constants.GROUP_PEER_ID) {
            Constants.GROUP_PEER_NAME
        } else {
            senderName
        }
        val displaySender = if (threadId == Constants.GROUP_PEER_ID) senderName else null
        val scopeSuffix = if (threadId == Constants.GROUP_PEER_ID) Constants.GROUP_PEER_ID else ""
        val idSuffix = msgId?.takeIf { it.isNotBlank() }?.let { "|$it" } ?: ""

        // Persist via Room so the conversation is complete when the app is opened
        scope.launch {
            try {
                ChatRepository.get(applicationContext)
                    .insertMessage(
                        ChatMessage(
                            text = text,
                            isSentByMe = false,
                            senderName = displaySender,
                            messageId = msgId,
                            replyToMessageId = replyToId,
                            replyToSender = replyToSender,
                            replyToText = replyToText
                        ),
                        threadId,
                        threadName
                    )
                if (senderId != "unknown") {
                    sendControl(Constants.MSG_TYPE_ACK + scopeSuffix + idSuffix, senderId)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Could not persist standby message: ${e.message}")
            }
        }

        val openThread = PendingIntent.getActivity(
            this, 1004,
            Intent(this, ChatActivity::class.java)
                .putExtra(ChatActivity.EXTRA_PEER_ID, threadId)
                .putExtra(ChatActivity.EXTRA_PEER_NAME, threadName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_MESSAGES)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle(senderName)
            .setContentText(text)
            .setContentIntent(openThread)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .build()
        try {
            notificationManager?.notify(NOTIF_MSG_ID_BASE + (threadId.hashCode() and 0x3FF), notification)
        } catch (_: Exception) {}
    }

    private fun onIncomingFile(
        payload: String,
        senderId: String,
        senderName: String,
        recipientId: String,
        msgId: String? = null
    ) {
        val offer = FileTransferManager.FileOffer.fromPayload(payload) ?: return
        val threadId = if (recipientId == Constants.RECIPIENT_BROADCAST && senderId != "unknown") {
            Constants.GROUP_PEER_ID
        } else {
            senderId
        }
        val threadName = if (threadId == Constants.GROUP_PEER_ID) {
            Constants.GROUP_PEER_NAME
        } else {
            senderName
        }
        val displaySender = if (threadId == Constants.GROUP_PEER_ID) senderName else null
        val summary = FileTransferManager.formatSummaryText(offer.fileName, offer.fileSize)
        val scopeSuffix = if (threadId == Constants.GROUP_PEER_ID) Constants.GROUP_PEER_ID else ""
        val idSuffix = msgId?.takeIf { it.isNotBlank() }?.let { "|$it" } ?: ""

        scope.launch {
            try {
                val repo = ChatRepository.get(applicationContext)
                repo.insertMessage(
                    ChatMessage(
                        text = summary,
                        isSentByMe = false,
                        senderName = displaySender,
                        attachmentName = offer.fileName,
                        attachmentMime = offer.mimeType,
                        attachmentSize = offer.fileSize,
                        attachmentState = AttachmentState.TRANSFERRING,
                        transferId = offer.transferId,
                        messageId = msgId
                    ),
                    threadId,
                    threadName
                )
                if (senderId != "unknown") {
                    sendControl(Constants.MSG_TYPE_ACK + scopeSuffix + idSuffix, senderId)
                }
                val savedFile = FileTransferManager.downloadOffer(
                    applicationContext,
                    offer,
                    senderId,
                    senderName
                )
                val state = if (savedFile != null) AttachmentState.COMPLETED else AttachmentState.FAILED
                repo.updateAttachmentState(offer.transferId, savedFile?.absolutePath, state)
                val receipt = if (savedFile != null) {
                    Constants.MSG_TYPE_FILE_DONE + offer.transferId
                } else {
                    Constants.MSG_TYPE_FILE_FAIL + offer.transferId
                }
                sendControl(receipt, senderId)
            } catch (e: Exception) {
                Log.e(TAG, "Could not handle standby file offer: ${e.message}")
            }
        }

        val openThread = PendingIntent.getActivity(
            this, 1004,
            Intent(this, ChatActivity::class.java)
                .putExtra(ChatActivity.EXTRA_PEER_ID, threadId)
                .putExtra(ChatActivity.EXTRA_PEER_NAME, threadName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_MESSAGES)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle(senderName)
            .setContentText(summary)
            .setContentIntent(openThread)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .build()
        try {
            notificationManager?.notify(NOTIF_MSG_ID_BASE + (threadId.hashCode() and 0x3FF), notification)
        } catch (_: Exception) {}
    }

    // ======================== Notification infra ========================

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = notificationManager ?: return

        mgr.createNotificationChannel(
            NotificationChannel(
                CHANNEL_STANDBY, "Background listener",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "Keeps LanVox ready to receive calls"
                setShowBadge(false)
            }
        )
        mgr.createNotificationChannel(
            NotificationChannel(
                CHANNEL_CALLS, "Incoming calls",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Ringing notifications for incoming LanVox calls"
            }
        )
        mgr.createNotificationChannel(
            NotificationChannel(
                CHANNEL_MESSAGES, "Messages",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Messages received while the app is closed"
            }
        )
    }

    private fun buildStandbyNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this, 1005,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_STANDBY)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("LanVox")
            .setContentText("Ready to receive calls — app closed mode")
            .setContentIntent(openApp)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setShowWhen(false)
            .build()
    }

    // ======================== Infra helpers ========================

    private fun acquireWifiLock() {
        try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val lock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "lanvox:listener")
            lock.setReferenceCounted(false)
            lock.acquire()
            wifiLock = lock
        } catch (e: Exception) {
            Log.w(TAG, "WifiLock unavailable: ${e.message}")
        }
    }

    private fun initP2p() {
        try {
            p2pManager = getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
            p2pChannel = p2pManager?.initialize(this, mainLooper, null)
        } catch (e: Exception) {
            Log.w(TAG, "P2P init failed: ${e.message}")
        }
    }
}
