package com.example.wifip2pchat

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.LinkedHashSet
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Symmetric P2P Mesh node service running on EVERY device (no central host required).
 *
 * Capabilities:
 *  1. Listens on [Constants.SERVER_PORT] (8988) on EVERY device — Group Owner, P2P client,
 *     or standalone Wi-Fi/Hotspot LAN node.
 *  2. Maintains outbound direct peer links to the P2P Group Owner (when acting as client),
 *     other P2P clients directly by IP (`selfId < peer.id` tie-breaker), and LAN peers
 *     discovered via [LocalMeshDiscovery].
 *  3. Routes 1:1 messages directly to the target peer's socket whenever a direct link exists,
 *     falling back to any relay/Group Owner link only when direct client-to-client routing
 *     is isolated.
 *  4. Deduplicates broadcast/gossip envelopes by [Envelope.msgId] so multi-path mesh
 *     topologies never loop or duplicate messages.
 */
class ChatServerService : Service() {

    companion object {
        private const val TAG = "ChatServerService"
        private const val CONNECT_TIMEOUT_MS = 4_000
        private const val RECONNECT_DELAY_MS = 2_000L
        private const val MAX_SEEN_IDS = 512

        @Volatile
        var running = false
            private set

        /** Fired on the main thread when a message arrives addressed to us or broadcast. */
        var onMessageReceived: ((Envelope) -> Unit)? = null

        /** Fired on the main thread whenever the count of live mesh peer links changes. */
        var onMeshPeersChanged: (() -> Unit)? = null

        /** Fired on the main thread when a peer completes initial HELLO handshake. */
        var onPeerHandshakeReady: ((String) -> Unit)? = null

        private var serviceInstance: ChatServerService? = null

        fun hasConnectedPeers(): Boolean = serviceInstance?.peers?.isNotEmpty() == true

        fun connectedPeerCount(): Int = serviceInstance?.peers?.size ?: 0

        fun localIpForPeer(peerId: String?): String? {
            val inst = serviceInstance ?: return DeviceIdentity.resolveLocalIp()
            if (!peerId.isNullOrBlank()) {
                inst.peers[peerId]?.socket?.localAddress?.hostAddress?.takeIf { it.isNotBlank() }
                    ?.let { return it }
            }
            inst.peers.values.firstOrNull()?.socket?.localAddress?.hostAddress?.takeIf { it.isNotBlank() }
                ?.let { return it }
            return DeviceIdentity.resolveLocalIp(PeerRegistry.ipFor(peerId))
        }

        /** Initiate an outbound direct connection to a peer or P2P Group Owner. */
        fun connectToPeer(
            hostIp: String,
            peerIdHint: String? = null,
            peerNameHint: String? = null,
            isGroupOwnerUplink: Boolean = false,
            transport: String = "p2p"
        ) {
            serviceInstance?.connectToPeerInternal(
                hostIp = hostIp,
                peerIdHint = peerIdHint,
                peerNameHint = peerNameHint,
                isGroupOwnerUplink = isGroupOwnerUplink,
                transport = transport
            )
        }

        /** Disconnect outbound/all links of a given transport (e.g. on P2P group leave). */
        fun disconnectTransport(transport: String) {
            serviceInstance?.disconnectTransportInternal(transport)
        }

        /** Send a message addressed to one specific peer (direct first, relay fallback). */
        fun sendTo(recipientId: String, payload: String, msgId: String? = null): Boolean =
            serviceInstance?.sendToInternal(recipientId, payload, msgId) == true

        /** Send a broadcast message to all directly connected peers (with dedup msgId). */
        fun sendBroadcast(payload: String, msgId: String? = null): Boolean =
            serviceInstance?.sendBroadcastInternal(payload, msgId) == true

        /** Re-broadcast HELLO with updated [PeerRegistry.selfName] to all connected peers. */
        fun reannounceSelf() {
            serviceInstance?.reannounceSelfInternal()
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null

    private data class OutboundTarget(
        val transport: String,
        val job: Job
    )

    private val peers = ConcurrentHashMap<String, PeerConn>()
    private val pendingIncoming = ConcurrentHashMap<Int, PeerConn>()
    private val activeOutboundTargets = ConcurrentHashMap<String, OutboundTarget>()

    private val seenLock = Any()
    private val seenMsgIds = LinkedHashSet<String>()

    private inner class PeerConn(
        val socket: Socket,
        val writer: OutputStreamWriter,
        @Volatile var peerId: String? = null,
        @Volatile var peerName: String = "Unknown",
        @Volatile var hostIp: String? = socket.inetAddress?.hostAddress,
        @Volatile var isGroupOwnerUplink: Boolean = false,
        @Volatile var transport: String = "p2p",
        val sentHello: AtomicBoolean = AtomicBoolean(false)
    ) {
        private val writeLock = Any()

        fun writeLine(line: String) {
            synchronized(writeLock) {
                writer.write(line)
                writer.flush()
            }
        }
    }

    private val keyxCooldown = ConcurrentHashMap<String, Long>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        CryptoManager.ensureInitialized(applicationContext)
        running = true
        serviceInstance = this
        Log.d(TAG, "Symmetric mesh node service created (E2EE active)")
        startServer()
    }

    override fun onDestroy() {
        running = false
        serviceInstance = null
        closeAll()
        scope.cancel()
        super.onDestroy()
        Log.d(TAG, "Symmetric mesh node service destroyed")
    }

    // ======================== Server accept loop ========================

    private fun startServer() {
        serverJob = scope.launch {
            var bound = false
            repeat(12) {
                try {
                    serverSocket = ServerSocket().apply {
                        reuseAddress = true
                        bind(InetSocketAddress(Constants.SERVER_PORT))
                    }
                    bound = true
                    return@repeat
                } catch (e: Exception) {
                    Log.w(TAG, "Mesh bind busy (${e.message}) — retrying in 1s")
                    delay(1_000)
                }
            }
            if (!bound) {
                Log.e(TAG, "Could not bind mesh port ${Constants.SERVER_PORT}")
                return@launch
            }
            Log.d(TAG, "Mesh server listening on port ${Constants.SERVER_PORT}")

            while (isActive) {
                val socket = try {
                    serverSocket?.accept() ?: break
                } catch (e: Exception) {
                    if (isActive) Log.e(TAG, "Accept error: ${e.message}")
                    break
                }
                val remoteIp = socket.inetAddress?.hostAddress
                Log.d(TAG, "Incoming mesh connection from $remoteIp")
                launch {
                    handleSocketLoop(
                        socket = socket,
                        initialPeerId = null,
                        initialPeerName = "Peer",
                        hostIp = remoteIp,
                        isGroupOwnerUplink = false,
                        transport = if (remoteIp?.startsWith("192.168.49.") == true) "p2p" else "lan",
                        sendHelloImmediately = false
                    )
                }
            }
        }
    }

    // ======================== Outbound direct connects ========================

    private fun connectToPeerInternal(
        hostIp: String,
        peerIdHint: String?,
        peerNameHint: String?,
        isGroupOwnerUplink: Boolean,
        transport: String
    ) {
        if (hostIp.isBlank()) return
        if (!peerIdHint.isNullOrBlank()) {
            if (peerIdHint == PeerRegistry.selfId) return
            val existing = peers[peerIdHint]
            if (existing != null && !existing.socket.isClosed) {
                if (isGroupOwnerUplink) existing.isGroupOwnerUplink = true
                return
            }
        }

        val targetKey = peerIdHint?.takeIf { it.isNotBlank() } ?: hostIp
        if (activeOutboundTargets[targetKey]?.job?.isActive == true) return

        val job = scope.launch {
            val attempts = if (isGroupOwnerUplink) 20 else 3
            repeat(attempts) { attempt ->
                if (!isActive) return@launch
                if (!peerIdHint.isNullOrBlank() && peers[peerIdHint]?.socket?.isClosed == false) {
                    return@launch
                }
                val socket = Socket()
                try {
                    socket.tcpNoDelay = true
                    socket.keepAlive = true
                    socket.connect(InetSocketAddress(hostIp, Constants.SERVER_PORT), CONNECT_TIMEOUT_MS)
                    Log.d(TAG, "Connected outbound to $hostIp:${Constants.SERVER_PORT} ($transport)")
                    handleSocketLoop(
                        socket = socket,
                        initialPeerId = peerIdHint,
                        initialPeerName = peerNameHint ?: "Peer",
                        hostIp = hostIp,
                        isGroupOwnerUplink = isGroupOwnerUplink,
                        transport = transport,
                        sendHelloImmediately = true
                    )
                    return@launch
                } catch (e: Exception) {
                    try { socket.close() } catch (_: Exception) {}
                    Log.d(TAG, "Outbound connect attempt ${attempt + 1} to $hostIp failed: ${e.message}")
                    delay(RECONNECT_DELAY_MS)
                }
            }
        }
        val entry = OutboundTarget(transport, job)
        activeOutboundTargets[targetKey] = entry
        job.invokeOnCompletion {
            activeOutboundTargets.remove(targetKey, entry)
        }
    }

    private suspend fun handleSocketLoop(
        socket: Socket,
        initialPeerId: String?,
        initialPeerName: String,
        hostIp: String?,
        isGroupOwnerUplink: Boolean,
        transport: String,
        sendHelloImmediately: Boolean
    ) {
        val conn = try {
            socket.tcpNoDelay = true
            socket.keepAlive = true
            PeerConn(
                socket = socket,
                writer = OutputStreamWriter(socket.getOutputStream(), "UTF-8"),
                peerId = initialPeerId,
                peerName = initialPeerName,
                hostIp = hostIp,
                isGroupOwnerUplink = isGroupOwnerUplink,
                transport = transport
            )
        } catch (e: Exception) {
            try { socket.close() } catch (_: Exception) {}
            return
        }

        pendingIncoming[socket.hashCode()] = conn
        try {
            // Issue a fresh random challenge nonce on every connection so the peer proves private key possession.
            sendChallenge(conn)
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), "UTF-8"))
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                line?.let { handleIncomingLine(conn, it) }
            }
        } catch (e: Exception) {
            if (running) Log.d(TAG, "Peer socket ended (${conn.peerId ?: conn.hostIp}): ${e.message}")
        } finally {
            removeConnection(conn)
        }
    }

    private fun sendChallenge(conn: PeerConn) {
        val chalPayload = CryptoManager.buildChallengePayload()
        val line = Envelope.build(
            senderId = PeerRegistry.selfId,
            senderName = PeerRegistry.selfName,
            recipientId = conn.peerId ?: Constants.RECIPIENT_BROADCAST,
            payload = chalPayload
        ) + "\n"
        try {
            conn.writeLine(line)
        } catch (e: Exception) {
            Log.w(TAG, "CHALLENGE write failed: ${e.message}")
        }
    }

    private fun sendSignedHelloResponse(conn: PeerConn, targetId: String, challengeNonce: String) {
        val localIp = conn.socket.localAddress?.hostAddress
            ?: DeviceIdentity.resolveLocalIp(conn.hostIp)
        if (!localIp.isNullOrBlank()) {
            PeerRegistry.selfIpAddress = localIp
        }
        val selfPubB64 = CryptoManager.getSelfPublicKeyB64(applicationContext)
        val helloPayload = CryptoManager.buildSignedHelloPayload(applicationContext, localIp, selfPubB64, challengeNonce)
        val line = Envelope.build(
            senderId = PeerRegistry.selfId,
            senderName = PeerRegistry.selfName,
            recipientId = targetId.ifBlank { "GO" },
            payload = helloPayload
        ) + "\n"
        try {
            conn.writeLine(line)
        } catch (e: Exception) {
            Log.w(TAG, "HELLO response write failed: ${e.message}")
        }
    }

    // ======================== Incoming routing & dedup ========================

    private fun handleIncomingLine(conn: PeerConn, raw: String) {
        val env = Envelope.parse(raw)
        if (env == null) {
            Log.w(TAG, "Dropped unencrypted legacy bare line from ${conn.hostIp}")
            return
        }

        if (env.senderId == PeerRegistry.selfId) {
            // Self-loop guard
            return
        }

        if (env.payload == Constants.PAYLOAD_HELLO_REQ) {
            sendChallenge(conn)
            return
        }

        if (env.payload.startsWith(Constants.MSG_TYPE_CHALLENGE)) {
            val nonce = env.payload.removePrefix(Constants.MSG_TYPE_CHALLENGE).trim()
            if (nonce.isNotBlank()) {
                sendSignedHelloResponse(conn, env.senderId, nonce)
            }
            return
        }

        val isForSelf = env.recipientId == PeerRegistry.selfId ||
            env.recipientId == Constants.RECIPIENT_BROADCAST ||
            (env.recipientId == "GO" && PeerRegistry.isSelfGroupOwner)

        if (env.payload.startsWith(Constants.MSG_TYPE_KEYX_CHALLENGE)) {
            if (!isForSelf && env.recipientId.isNotBlank()) {
                forwardToRecipient(env, origin = conn)
                return
            }
            val nonce = env.payload.removePrefix(Constants.MSG_TYPE_KEYX_CHALLENGE).trim()
            if (nonce.isNotBlank()) {
                val keyxPayload = CryptoManager.buildSignedKeyxPayload(applicationContext, nonce)
                val line = Envelope.build(
                    senderId = PeerRegistry.selfId,
                    senderName = PeerRegistry.selfName,
                    recipientId = env.senderId,
                    payload = keyxPayload
                ) + "\n"
                try {
                    conn.writeLine(line)
                } catch (e: Exception) {
                    Log.w(TAG, "KEYX response write failed: ${e.message}")
                }
            }
            return
        }

        if (CryptoManager.isHelloPayload(env.payload)) {
            val verified = CryptoManager.verifyAndAcceptSignedHello(applicationContext, env.senderId, env.payload)
            if (verified != null) {
                registerPeer(conn, env.senderId, env.senderName, verified.ip ?: conn.hostIp, verified.pubKeyB64)
            } else {
                Log.w(TAG, "Challenge-response failed in HELLO from ${env.senderId} — rejecting")
            }
            return
        }

        if (env.payload.startsWith(Constants.MSG_TYPE_KEYX)) {
            if (!isForSelf && env.recipientId.isNotBlank()) {
                forwardToRecipient(env, origin = conn)
                return
            }
            val verified = CryptoManager.verifyAndAcceptSignedKeyx(applicationContext, env.senderId, env.payload)
            if (verified != null) {
                val activePub = CryptoManager.getPeerPublicKeyB64(applicationContext, env.senderId) ?: verified.pubKeyB64
                PeerRegistry.updatePublicKey(env.senderId, activePub)
                if (verified.registrationResult == CryptoManager.KeyRegistrationResult.ADDED ||
                    verified.registrationResult == CryptoManager.KeyRegistrationResult.CHANGED
                ) {
                    broadcastPeerList()
                }
            } else {
                Log.w(TAG, "Challenge-response failed in KEYX from ${env.senderId} — rejecting")
            }
            return
        }

        if (env.payload == Constants.PAYLOAD_BYE) {
            removeConnection(conn)
            return
        }

        if (env.payload.startsWith(Constants.PAYLOAD_PEERS)) {
            val list = PeerRegistry.parsePeersPayload(
                payload = env.payload,
                senderId = env.senderId,
                senderName = env.senderName,
                senderIp = conn.hostIp,
                senderIsGo = conn.isGroupOwnerUplink || PeerRegistry.isSelfGroupOwner.not()
            )
            list.forEach { p ->
                if (!p.publicKeyB64.isNullOrBlank()) {
                    CryptoManager.registerPeerPublicKey(applicationContext, p.id, p.publicKeyB64, isFromThirdPartyBroadcast = true)
                }
            }
            PeerRegistry.mergeFromPeersPayload(list)
            maybeFormDirectPeerMesh(list)
            return
        }

        // Deduplicate broadcast / multi-path mesh messages by msgId
        if (!env.msgId.isNullOrBlank() && !markMessageSeen(env.msgId)) {
            return
        }

        routeEnvelope(env, origin = conn)
    }

    private fun registerPeer(
        conn: PeerConn,
        peerId: String,
        peerName: String,
        peerIp: String?,
        pubKeyB64: String? = null
    ) {
        pendingIncoming.remove(conn.socket.hashCode())
        conn.peerId = peerId
        conn.peerName = peerName.ifBlank { peerId }
        if (!peerIp.isNullOrBlank()) conn.hostIp = peerIp

        val existing = peers[peerId]
        if (existing != null && existing !== conn && !existing.socket.isClosed) {
            // Deterministic tie-breaker on simultaneous dual connections:
            // Keep the socket initiated by the lexicographically smaller peerId, or replace stale.
            val keepExisting = existing.isGroupOwnerUplink && !conn.isGroupOwnerUplink
            if (keepExisting) {
                if (!conn.hostIp.isNullOrBlank()) existing.hostIp = conn.hostIp
                try { conn.socket.close() } catch (_: Exception) {}
                return
            } else {
                try { existing.socket.close() } catch (_: Exception) {}
            }
        }

        val isNicknameUpdate = existing === conn
        peers[peerId] = conn

        val resolvedPub = CryptoManager.getPeerPublicKeyB64(applicationContext, peerId) ?: pubKeyB64
        PeerRegistry.upsert(
            PeerRegistry.Peer(
                id = peerId,
                name = conn.peerName,
                isGroupOwner = conn.isGroupOwnerUplink,
                ipAddress = conn.hostIp,
                transport = conn.transport,
                publicKeyB64 = resolvedPub
            )
        )
        broadcastPeerList()
        notifyMeshPeersChanged()
        if (!isNicknameUpdate) {
            Handler(Looper.getMainLooper()).post {
                onPeerHandshakeReady?.invoke(peerId)
            }
        }
        Log.d(TAG, "Registered E2EE mesh peer: $peerId (${conn.peerName}, ip=${conn.hostIp}) — active links=${peers.size}")
    }

    private fun maybeFormDirectPeerMesh(discoveredPeers: List<PeerRegistry.Peer>) {
        val selfId = PeerRegistry.selfId
        discoveredPeers.forEach { p ->
            if (p.id != selfId && !p.ipAddress.isNullOrBlank() && peers[p.id] == null) {
                // Tie-breaker: smaller ID initiates direct TCP link so only one connection opens
                if (selfId < p.id) {
                    connectToPeerInternal(
                        hostIp = p.ipAddress,
                        peerIdHint = p.id,
                        peerNameHint = p.name,
                        isGroupOwnerUplink = p.isGroupOwner,
                        transport = p.transport
                    )
                }
            }
        }
    }

    private fun routeEnvelope(env: Envelope, origin: PeerConn) {
        if (env.recipientId == Constants.RECIPIENT_BROADCAST) {
            // Relay to any connected peers we have that didn't send it (dedup msgId prevents loops)
            forwardToOtherPeers(env, origin)
            deliverToSelf(env)
        } else if (env.recipientId == PeerRegistry.selfId ||
            (env.recipientId == "GO" && PeerRegistry.isSelfGroupOwner)
        ) {
            deliverToSelf(env)
        } else {
            forwardToRecipient(env, origin)
        }
    }

    private fun forwardToOtherPeers(env: Envelope, origin: PeerConn) {
        val line = Envelope.build(
            senderId = env.senderId,
            senderName = env.senderName,
            recipientId = env.recipientId,
            payload = env.payload,
            msgId = env.msgId
        ) + "\n"
        peers.values.forEach { c ->
            if (c.peerId != null && c.peerId != env.senderId && c !== origin && !c.socket.isClosed) {
                try {
                    c.writeLine(line)
                } catch (e: Exception) {
                    Log.w(TAG, "Mesh forward to ${c.peerId} failed: ${e.message}")
                }
            }
        }
    }

    private fun forwardToRecipient(env: Envelope, origin: PeerConn) {
        val line = Envelope.build(
            senderId = env.senderId,
            senderName = env.senderName,
            recipientId = env.recipientId,
            payload = env.payload,
            msgId = env.msgId
        ) + "\n"
        val direct = peers[env.recipientId]
        if (direct != null && direct !== origin && !direct.socket.isClosed) {
            try {
                direct.writeLine(line)
            } catch (e: Exception) {
                Log.w(TAG, "Forward to ${env.recipientId} failed: ${e.message}")
            }
        } else {
            Log.w(TAG, "Recipient ${env.recipientId} unreachable from this node — dropping")
        }
    }

    private fun deliverToSelf(env: Envelope) {
        val decryptedPayload: String
        when {
            env.payload.startsWith(Constants.MSG_TYPE_E2E_DIRECT) -> {
                val plain = CryptoManager.decryptFromPeer(
                    context = applicationContext,
                    peerId = env.senderId,
                    senderId = env.senderId,
                    recipientId = env.recipientId,
                    cipherPayload = env.payload
                )
                if (plain == null) {
                    Log.w(TAG, "Failed to decrypt E2E1 from ${env.senderId} — triggering KEYX")
                    requestKeyExchange(env.senderId)
                    return
                }
                decryptedPayload = plain
            }
            env.payload.startsWith(Constants.MSG_TYPE_E2E_MULTI) -> {
                val plain = CryptoManager.decryptBroadcast(
                    context = applicationContext,
                    senderId = env.senderId,
                    selfId = PeerRegistry.selfId,
                    cipherPayload = env.payload
                )
                if (plain == null) {
                    Log.w(TAG, "Failed to decrypt E2EM from ${env.senderId} — triggering KEYX")
                    requestKeyExchange(env.senderId)
                    return
                }
                decryptedPayload = plain
            }
            else -> {
                Log.w(TAG, "Dropped unencrypted payload from ${env.senderId}")
                return
            }
        }
        val cleanEnv = env.copy(payload = decryptedPayload, isEncrypted = true)
        Handler(Looper.getMainLooper()).post {
            onMessageReceived?.invoke(cleanEnv)
        }
    }

    private fun requestKeyExchange(peerId: String) {
        val now = System.currentTimeMillis()
        val last = keyxCooldown[peerId] ?: 0L
        if (now - last < 3_000L) return
        keyxCooldown[peerId] = now
        val chalPayload = CryptoManager.buildKeyxChallengePayload()
        sendToInternal(peerId, chalPayload)
    }

    private fun notifyMeshPeersChanged() {
        Handler(Looper.getMainLooper()).post {
            onMeshPeersChanged?.invoke()
        }
    }

    private fun markMessageSeen(msgId: String): Boolean = synchronized(seenLock) {
        if (seenMsgIds.contains(msgId)) return@synchronized false
        seenMsgIds.add(msgId)
        if (seenMsgIds.size > MAX_SEEN_IDS) {
            val it = seenMsgIds.iterator()
            if (it.hasNext()) {
                it.next()
                it.remove()
            }
        }
        true
    }

    // ======================== Outgoing ========================

    private fun isWireControlPlaintext(payload: String): Boolean =
        payload.startsWith(Constants.MSG_TYPE_KEYX) ||
            payload.startsWith(Constants.MSG_TYPE_CHALLENGE) ||
            payload.startsWith(Constants.MSG_TYPE_KEYX_CHALLENGE) ||
            payload == Constants.PAYLOAD_HELLO_REQ ||
            CryptoManager.isHelloPayload(payload) ||
            payload == Constants.PAYLOAD_BYE ||
            payload.startsWith(Constants.PAYLOAD_PEERS) ||
            payload.startsWith(Constants.MSG_TYPE_E2E_DIRECT) ||
            payload.startsWith(Constants.MSG_TYPE_E2E_MULTI)

    private fun sendToInternal(recipientId: String, payload: String, explicitMsgId: String? = null): Boolean {
        if (CryptoManager.isPeerKeyBlocked(applicationContext, recipientId)) {
            Log.w(TAG, "sendTo($recipientId) blocked: peer key changed and pending")
            return false
        }
        val wirePayload = if (isWireControlPlaintext(payload)) {
            payload
        } else {
            val encrypted = CryptoManager.encryptForPeer(
                context = applicationContext,
                peerId = recipientId,
                senderId = PeerRegistry.selfId,
                recipientId = recipientId,
                plaintext = payload
            )
            if (encrypted == null) {
                Log.w(TAG, "Cannot encrypt for $recipientId (missing peer key) — initiating KEYX")
                requestKeyExchange(recipientId)
                return false
            }
            encrypted
        }

        val msgId = explicitMsgId?.takeIf { it.isNotBlank() } ?: Envelope.newMessageId()
        markMessageSeen(msgId)
        val line = Envelope.build(
            senderId = PeerRegistry.selfId,
            senderName = PeerRegistry.selfName,
            recipientId = recipientId,
            payload = wirePayload,
            msgId = msgId
        ) + "\n"

        val direct = peers[recipientId]?.takeIf { !it.socket.isClosed }
        val relay = direct ?: peers.values.firstOrNull { it.isGroupOwnerUplink && !it.socket.isClosed }
            ?: peers.values.firstOrNull { !it.socket.isClosed }
            ?: return false

        scope.launch {
            try {
                relay.writeLine(line)
            } catch (e: Exception) {
                Log.e(TAG, "sendTo($recipientId) error: ${e.message}")
            }
        }
        return true
    }

    private fun sendBroadcastInternal(payload: String, explicitMsgId: String? = null): Boolean {
        val targets = peers.values.filter { it.peerId != null && !it.socket.isClosed }
        if (targets.isEmpty()) return false

        val wirePayload = if (isWireControlPlaintext(payload)) {
            payload
        } else {
            val keyTargets = (peers.keys + PeerRegistry.snapshot().map { it.id })
                .distinct()
                .filter { it != PeerRegistry.selfId && CryptoManager.hasPeerKey(applicationContext, it) && !CryptoManager.isPeerKeyBlocked(applicationContext, it) }
            if (keyTargets.isEmpty()) {
                Log.w(TAG, "Cannot broadcast — no peer public keys registered yet")
                return false
            }
            CryptoManager.encryptForBroadcast(
                context = applicationContext,
                senderId = PeerRegistry.selfId,
                targetPeerIds = keyTargets,
                plaintext = payload
            ) ?: return false
        }

        val msgId = explicitMsgId?.takeIf { it.isNotBlank() } ?: Envelope.newMessageId()
        markMessageSeen(msgId)
        val line = Envelope.build(
            senderId = PeerRegistry.selfId,
            senderName = PeerRegistry.selfName,
            recipientId = Constants.RECIPIENT_BROADCAST,
            payload = wirePayload,
            msgId = msgId
        ) + "\n"

        scope.launch {
            targets.forEach { c ->
                try {
                    c.writeLine(line)
                } catch (e: Exception) {
                    Log.w(TAG, "broadcast to ${c.peerId} failed: ${e.message}")
                }
            }
        }
        return true
    }

    private fun reannounceSelfInternal() {
        scope.launch {
            peers.values.forEach { c ->
                val pid = c.peerId
                if (pid != null && !c.socket.isClosed) {
                    val reqLine = Envelope.build(
                        senderId = PeerRegistry.selfId,
                        senderName = PeerRegistry.selfName,
                        recipientId = pid,
                        payload = Constants.PAYLOAD_HELLO_REQ
                    ) + "\n"
                    try {
                        c.writeLine(reqLine)
                    } catch (_: Exception) {}
                }
            }
            broadcastPeerList()
        }
    }

    /** Push the current known peer list (with direct IPs) to connected peers. */
    private fun broadcastPeerList() {
        val payload = PeerRegistry.buildPeersPayload()
        val line = Envelope.build(
            senderId = PeerRegistry.selfId,
            senderName = PeerRegistry.selfName,
            recipientId = Constants.RECIPIENT_BROADCAST,
            payload = payload
        ) + "\n"
        peers.values.forEach { c ->
            if (c.peerId != null && !c.socket.isClosed) {
                try {
                    c.writeLine(line)
                } catch (e: Exception) {
                    Log.w(TAG, "PEERS broadcast to ${c.peerId} failed: ${e.message}")
                }
            }
        }
    }

    // ======================== Cleanup ========================

    private fun removeConnection(conn: PeerConn) {
        pendingIncoming.remove(conn.socket.hashCode())
        val id = conn.peerId
        var removed = false
        if (id != null && peers[id] === conn) {
            peers.remove(id)
            PeerRegistry.remove(id)
            removed = true
            Log.d(TAG, "Peer link closed: $id — remaining=${peers.size}")
        }
        try { conn.writer.close() } catch (_: Exception) {}
        try { conn.socket.close() } catch (_: Exception) {}

        if (removed) {
            broadcastPeerList()
            notifyMeshPeersChanged()
        }
    }

    private fun disconnectTransportInternal(transport: String) {
        activeOutboundTargets.entries.forEach { (key, target) ->
            if (target.transport == transport) {
                target.job.cancel()
                activeOutboundTargets.remove(key, target)
            }
        }
        val matching = peers.values.filter { it.transport == transport }
        matching.forEach { conn ->
            conn.peerId?.let { peers.remove(it, conn) }
            try { conn.writer.close() } catch (_: Exception) {}
            try { conn.socket.close() } catch (_: Exception) {}
        }
        PeerRegistry.removeByTransport(transport)
        notifyMeshPeersChanged()
    }

    private fun closeAll() {
        activeOutboundTargets.values.forEach { it.job.cancel() }
        activeOutboundTargets.clear()
        peers.values.forEach { c ->
            try { c.writer.close() } catch (_: Exception) {}
            try { c.socket.close() } catch (_: Exception) {}
        }
        peers.clear()
        pendingIncoming.values.forEach { c ->
            try { c.writer.close() } catch (_: Exception) {}
            try { c.socket.close() } catch (_: Exception) {}
        }
        pendingIncoming.clear()
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        PeerRegistry.clear()
    }
}
