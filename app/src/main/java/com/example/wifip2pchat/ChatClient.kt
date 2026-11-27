package com.example.wifip2pchat

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Client-side chat channel for the non-Group-Owner device (v5.0+).
 *
 *  1. On connect, send `ENV|<myId>|<myName>|GO|HELLO` so the GO registers us
 *     and broadcasts the membership list to everyone.
 *  2. Incoming lines are parsed as envelopes. PEERS: payloads update the
 *     [PeerRegistry]. Other payloads fire [onEnvelopeReceived].
 *  3. Outgoing messages use [sendTo] / [sendBroadcast] which wrap the payload
 *     in an envelope addressed to the GO for routing.
 */
class ChatClient(private val context: Context) {

    companion object {
        private const val TAG = "ChatClient"
        private const val RECONNECT_ATTEMPTS = 60
        private const val RECONNECT_DELAY_MS = 2_000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var socket: Socket? = null
    private var writer: OutputStreamWriter? = null
    private val writeLock = Any()
    private var readJob: Job? = null
    private var reconnectJob: Job? = null
    private var hostAddress: String? = null

    @Volatile
    private var userDisconnected = false

    /** Fired on the main thread whenever an envelope arrives addressed to us
     *  or broadcast to everyone. */
    var onEnvelopeReceived: ((Envelope) -> Unit)? = null

    @Volatile
    var isConnected = false
        private set

    fun connect(hostAddress: String) {
        this.hostAddress = hostAddress
        userDisconnected = false
        reconnectJob?.cancel()
        scope.launch {
            if (!connectOnce() && !userDisconnected) {
                scheduleReconnect()
            }
        }
    }

    private suspend fun connectOnce(): Boolean {
        val target = hostAddress ?: return false
        return try {
            try { writer?.close() } catch (_: Exception) {}
            try { socket?.close() } catch (_: Exception) {}

            val s = Socket()
            s.tcpNoDelay = true
            s.connect(InetSocketAddress(target, Constants.SERVER_PORT), 5_000)
            socket = s
            writer = OutputStreamWriter(s.getOutputStream(), "UTF-8")
            isConnected = true
            Log.d(TAG, "Connected to GO at $target:${Constants.SERVER_PORT}")

            sendChallenge()
            startReader(s)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Connect failed: ${e.message}")
            isConnected = false
            false
        }
    }

    /** Send a fresh random challenge nonce to the peer on connect. */
    private fun sendChallenge() {
        CryptoManager.ensureInitialized(context)
        val line = Envelope.build(
            senderId = PeerRegistry.selfId,
            senderName = PeerRegistry.selfName,
            recipientId = "GO",
            payload = CryptoManager.buildChallengePayload()
        ) + "\n"
        try {
            if (writeSynchronized(line)) {
                Log.d(TAG, "Sent challenge nonce as ${PeerRegistry.selfId}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "CHALLENGE send failed: ${e.message}")
        }
    }

    private fun sendSignedHelloResponse(targetId: String, challengeNonce: String) {
        CryptoManager.ensureInitialized(context)
        val pubB64 = CryptoManager.getSelfPublicKeyB64(context)
        val line = Envelope.build(
            senderId = PeerRegistry.selfId,
            senderName = PeerRegistry.selfName,
            recipientId = targetId.ifBlank { "GO" },
            payload = CryptoManager.buildSignedHelloPayload(context, null, pubB64, challengeNonce)
        ) + "\n"
        try {
            if (writeSynchronized(line)) {
                Log.d(TAG, "Sent challenge-signed E2EE HELLO as ${PeerRegistry.selfId}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "HELLO response send failed: ${e.message}")
        }
    }

    private fun writeSynchronized(line: String): Boolean {
        synchronized(writeLock) {
            val w = writer ?: return false
            w.write(line)
            w.flush()
            return true
        }
    }

    // ======================== Send ========================

    /** Send an E2EE payload addressed to one specific peer. */
    fun sendTo(recipientId: String, payload: String) {
        if (CryptoManager.isPeerKeyBlocked(context, recipientId)) {
            Log.w(TAG, "sendTo($recipientId) blocked: peer key changed and pending")
            return
        }
        scope.launch {
            val wirePayload = if (payload.startsWith(Constants.MSG_TYPE_KEYX) ||
                payload.startsWith(Constants.MSG_TYPE_CHALLENGE) ||
                payload.startsWith(Constants.MSG_TYPE_KEYX_CHALLENGE) ||
                payload.startsWith(Constants.MSG_TYPE_E2E_DIRECT)
            ) {
                payload
            } else {
                CryptoManager.encryptForPeer(
                    context = context,
                    peerId = recipientId,
                    senderId = PeerRegistry.selfId,
                    recipientId = recipientId,
                    plaintext = payload
                ) ?: run {
                    Log.w(TAG, "sendTo($recipientId): missing peer public key")
                    return@launch
                }
            }
            val line = Envelope.build(
                senderId = PeerRegistry.selfId,
                senderName = PeerRegistry.selfName,
                recipientId = recipientId,
                payload = wirePayload
            ) + "\n"
            try {
                if (writeSynchronized(line)) {
                    Log.d(TAG, "Sent E2EE to $recipientId")
                } else {
                    Log.w(TAG, "sendTo: not connected")
                }
            } catch (e: Exception) {
                Log.e(TAG, "sendTo failed: ${e.message}")
            }
        }
    }

    /** Send an E2EE broadcast payload to every group member. */
    fun sendBroadcast(payload: String) {
        scope.launch {
            val keyTargets = PeerRegistry.snapshot()
                .map { it.id }
                .filter { it != PeerRegistry.selfId && CryptoManager.hasPeerKey(context, it) && !CryptoManager.isPeerKeyBlocked(context, it) }
            val wirePayload = CryptoManager.encryptForBroadcast(
                context = context,
                senderId = PeerRegistry.selfId,
                targetPeerIds = keyTargets,
                plaintext = payload
            ) ?: run {
                Log.w(TAG, "sendBroadcast: no peer public keys")
                return@launch
            }
            val line = Envelope.build(
                senderId = PeerRegistry.selfId,
                senderName = PeerRegistry.selfName,
                recipientId = Constants.RECIPIENT_BROADCAST,
                payload = wirePayload
            ) + "\n"
            try {
                if (writeSynchronized(line)) {
                    Log.d(TAG, "Broadcast E2EE sent")
                } else {
                    Log.w(TAG, "sendBroadcast: not connected")
                }
            } catch (e: Exception) {
                Log.e(TAG, "sendBroadcast failed: ${e.message}")
            }
        }
    }

    // ======================== Receive ========================

    private fun startReader(s: Socket) {
        readJob?.cancel()
        readJob = scope.launch {
            try {
                val reader = BufferedReader(
                    InputStreamReader(s.getInputStream(), "UTF-8")
                )
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    line?.let { handleIncomingLine(it) }
                }
            } catch (e: Exception) {
                if (!userDisconnected) Log.e(TAG, "Reader error: ${e.message}")
            } finally {
                isConnected = false
                if (!userDisconnected) scheduleReconnect()
            }
        }
    }

    private fun handleIncomingLine(raw: String) {
        val env = Envelope.parse(raw) ?: return

        if (env.payload == Constants.PAYLOAD_HELLO_REQ) {
            sendChallenge()
            return
        }

        if (env.payload.startsWith(Constants.MSG_TYPE_CHALLENGE)) {
            val nonce = env.payload.removePrefix(Constants.MSG_TYPE_CHALLENGE).trim()
            if (nonce.isNotBlank()) {
                sendSignedHelloResponse(env.senderId, nonce)
            }
            return
        }

        if (env.payload.startsWith(Constants.MSG_TYPE_KEYX_CHALLENGE)) {
            val nonce = env.payload.removePrefix(Constants.MSG_TYPE_KEYX_CHALLENGE).trim()
            if (nonce.isNotBlank()) {
                val line = Envelope.build(
                    senderId = PeerRegistry.selfId,
                    senderName = PeerRegistry.selfName,
                    recipientId = env.senderId,
                    payload = CryptoManager.buildSignedKeyxPayload(context, nonce)
                ) + "\n"
                try { writeSynchronized(line) } catch (_: Exception) {}
            }
            return
        }

        if (CryptoManager.isHelloPayload(env.payload)) {
            val verified = CryptoManager.verifyAndAcceptSignedHello(context, env.senderId, env.payload)
            if (verified != null) {
                val activePub = CryptoManager.getPeerPublicKeyB64(context, env.senderId) ?: verified.pubKeyB64
                PeerRegistry.upsert(
                    PeerRegistry.Peer(
                        id = env.senderId,
                        name = env.senderName,
                        isGroupOwner = false,
                        ipAddress = verified.ip,
                        publicKeyB64 = activePub
                    )
                )
            } else {
                Log.w(TAG, "Challenge-response failed in HELLO from ${env.senderId} — rejecting")
            }
            return
        }

        if (env.payload.startsWith(Constants.MSG_TYPE_KEYX)) {
            val verified = CryptoManager.verifyAndAcceptSignedKeyx(context, env.senderId, env.payload)
            if (verified != null) {
                val activePub = CryptoManager.getPeerPublicKeyB64(context, env.senderId) ?: verified.pubKeyB64
                PeerRegistry.updatePublicKey(env.senderId, activePub)
            } else {
                Log.w(TAG, "Challenge-response failed in KEYX from ${env.senderId} — rejecting")
            }
            return
        }

        if (env.payload.startsWith(Constants.PAYLOAD_PEERS)) {
            val list = PeerRegistry.parsePeersPayload(
                payload = env.payload,
                senderId = env.senderId,
                senderName = env.senderName
            )
            list.forEach { p ->
                if (!p.publicKeyB64.isNullOrBlank()) {
                    CryptoManager.registerPeerPublicKey(context, p.id, p.publicKeyB64, isFromThirdPartyBroadcast = true)
                }
            }
            PeerRegistry.mergeFromPeersPayload(list)
            return
        }

        deliver(env)
    }

    private fun deliver(env: Envelope) {
        if (!env.isFor(PeerRegistry.selfId)) return
        val decryptedPayload = when {
            env.payload.startsWith(Constants.MSG_TYPE_E2E_DIRECT) ->
                CryptoManager.decryptFromPeer(context, env.senderId, env.senderId, env.recipientId, env.payload)
            env.payload.startsWith(Constants.MSG_TYPE_E2E_MULTI) ->
                CryptoManager.decryptBroadcast(context, env.senderId, PeerRegistry.selfId, env.payload)
            else -> null
        } ?: return
        val clean = env.copy(payload = decryptedPayload, isEncrypted = true)
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            onEnvelopeReceived?.invoke(clean)
        }
    }

    // ======================== Reconnect ========================

    private fun scheduleReconnect() {
        if (userDisconnected || hostAddress == null) return
        if (reconnectJob?.isActive == true) return
        reconnectJob = scope.launch {
            repeat(RECONNECT_ATTEMPTS) {
                if (userDisconnected) return@launch
                delay(RECONNECT_DELAY_MS)
                if (userDisconnected) return@launch
                Log.d(TAG, "Reconnecting to GO at $hostAddress…")
                if (connectOnce()) return@launch
            }
            Log.w(TAG, "Reconnect gave up after $RECONNECT_ATTEMPTS attempts")
        }
    }

    fun disconnect() {
        userDisconnected = true
        try {
            val line = Envelope.build(
                senderId = PeerRegistry.selfId,
                senderName = PeerRegistry.selfName,
                recipientId = "GO",
                payload = Constants.PAYLOAD_BYE
            ) + "\n"
            writeSynchronized(line)
        } catch (_: Exception) {}
        reconnectJob?.cancel()
        reconnectJob = null
        readJob?.cancel()
        readJob = null
        try { writer?.close() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        writer = null
        socket = null
        isConnected = false
        PeerRegistry.clear()
    }
}
