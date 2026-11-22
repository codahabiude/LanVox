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

            sendHello()
            startReader(s)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Connect failed: ${e.message}")
            isConnected = false
            false
        }
    }

    /** Send the HELLO envelope so the GO adds us to the peer registry. */
    private fun sendHello() {
        val line = Envelope.build(
            senderId = PeerRegistry.selfId,
            senderName = PeerRegistry.selfName,
            recipientId = "GO",
            payload = Constants.PAYLOAD_HELLO
        ) + "\n"
        try {
            if (writeSynchronized(line)) {
                Log.d(TAG, "Sent HELLO as ${PeerRegistry.selfId} (${PeerRegistry.selfName})")
            }
        } catch (e: Exception) {
            Log.e(TAG, "HELLO send failed: ${e.message}")
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

    /** Send a payload (e.g. "TEXT:hello") addressed to one specific peer. */
    fun sendTo(recipientId: String, payload: String) {
        scope.launch {
            val line = Envelope.build(
                senderId = PeerRegistry.selfId,
                senderName = PeerRegistry.selfName,
                recipientId = recipientId,
                payload = payload
            ) + "\n"
            try {
                if (writeSynchronized(line)) {
                    Log.d(TAG, "Sent to $recipientId: $payload")
                } else {
                    Log.w(TAG, "sendTo: not connected")
                }
            } catch (e: Exception) {
                Log.e(TAG, "sendTo failed: ${e.message}")
            }
        }
    }

    /** Send a broadcast payload (e.g. group chat text) to every member. */
    fun sendBroadcast(payload: String) {
        scope.launch {
            val line = Envelope.build(
                senderId = PeerRegistry.selfId,
                senderName = PeerRegistry.selfName,
                recipientId = Constants.RECIPIENT_BROADCAST,
                payload = payload
            ) + "\n"
            try {
                if (writeSynchronized(line)) {
                    Log.d(TAG, "Broadcast: $payload")
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
        val env = Envelope.parse(raw)
        if (env == null) {
            // Legacy bare line — wrap as broadcast from "Unknown" for the UI.
            Log.d(TAG, "Legacy bare line: $raw")
            val legacyEnv = Envelope(
                senderId = "legacy",
                senderName = "Unknown",
                recipientId = Constants.RECIPIENT_BROADCAST,
                payload = raw
            )
            deliver(legacyEnv)
            return
        }

        if (env.payload.startsWith(Constants.PAYLOAD_PEERS)) {
            val list = PeerRegistry.parsePeersPayload(
                payload = env.payload,
                senderId = env.senderId,
                senderName = env.senderName
            )
            PeerRegistry.mergeFromPeersPayload(list)
            return
        }

        deliver(env)
    }

    private fun deliver(env: Envelope) {
        if (!env.isFor(PeerRegistry.selfId)) return
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            onEnvelopeReceived?.invoke(env)
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
