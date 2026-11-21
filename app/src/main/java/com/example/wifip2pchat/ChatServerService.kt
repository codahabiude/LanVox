package com.example.wifip2pchat

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

/**
 * Multi-peer chat server for the WiFi Direct Group Owner (v5.0+).
 *
 *   ┌────────┐       ┌─────────────────┐       ┌────────┐
 *   │ Phone B├──────►│  GO (this phone)│◄──────┤Phone C │
 *   └────────┘       └─────────────────┘       └────────┘
 *                          ▲
 *              (GO relays messages between clients)
 *
 * Responsibilities:
 *  1. Accept multiple client sockets (one reader per client).
 *  2. Each client announces itself on connect via `ENV|id|name|GO|HELLO`.
 *  3. On HELLO: register in [PeerRegistry], broadcast updated peer list.
 *  4. On addressed message: forward to that peer's socket only.
 *  5. On broadcast ("*"): forward to every OTHER client + deliver to local UI.
 *  6. On legacy bare line: treat as broadcast from "Unknown" (back-compat).
 *  7. On socket close: remove from registry, rebroadcast list.
 */
class ChatServerService : Service() {

    companion object {
        private const val TAG = "ChatServerService"

        var running = false
            private set

        /** Fired with the parsed envelope whenever a message arrives addressed
         *  to the GO itself or broadcast to everyone. */
        var onMessageReceived: ((Envelope) -> Unit)? = null

        private var serviceInstance: ChatServerService? = null

        /** Send a message addressed to one specific peer (by id). */
        fun sendTo(recipientId: String, payload: String) {
            serviceInstance?.sendToInternal(recipientId, payload)
        }

        /** Send a broadcast message to every connected client. */
        fun sendBroadcast(payload: String) {
            serviceInstance?.sendBroadcastInternal(payload)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null

    private val clients = ConcurrentHashMap<String, ClientConn>()
    private val pendingClients = ConcurrentHashMap<Int, ClientConn>()

    private inner class ClientConn(
        val socket: Socket,
        val writer: OutputStreamWriter,
        @Volatile var peerId: String? = null,
        @Volatile var peerName: String = "Unknown"
    ) {
        private val writeLock = Any()

        fun writeLine(line: String) {
            synchronized(writeLock) {
                writer.write(line)
                writer.flush()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        serviceInstance = this
        PeerRegistry.isSelfGroupOwner = true
        Log.d(TAG, "Chat server service created (multi-peer mode)")
        startServer()
    }

    override fun onDestroy() {
        running = false
        serviceInstance = null
        PeerRegistry.isSelfGroupOwner = false
        closeAll()
        scope.cancel()
        super.onDestroy()
        Log.d(TAG, "Chat server service destroyed")
    }

    // ======================== Server accept loop ========================

    private fun startServer() {
        serverJob = scope.launch {
            var bound = false
            repeat(10) {
                try {
                    serverSocket = ServerSocket(Constants.SERVER_PORT)
                    bound = true
                    return@repeat
                } catch (e: Exception) {
                    Log.w(TAG, "Bind busy (${e.message}) — retrying in 1s")
                    delay(1_000)
                }
            }
            if (!bound) {
                Log.e(TAG, "Could not bind port ${Constants.SERVER_PORT}")
                return@launch
            }
            Log.d(TAG, "Server listening on port ${Constants.SERVER_PORT}")

            while (isActive) {
                val socket = try {
                    serverSocket?.accept() ?: break
                } catch (e: Exception) {
                    if (isActive) Log.e(TAG, "Accept error: ${e.message}")
                    break
                }
                Log.d(TAG, "Client connected: ${socket.inetAddress}")
                handleNewClient(socket)
            }
        }
    }

    private fun handleNewClient(socket: Socket) {
        scope.launch {
            try {
                socket.tcpNoDelay = true
                val writer = OutputStreamWriter(socket.getOutputStream(), "UTF-8")
                val conn = ClientConn(socket, writer)
                pendingClients[socket.hashCode()] = conn

                val reader = BufferedReader(
                    InputStreamReader(socket.getInputStream(), "UTF-8")
                )
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    line?.let { handleIncomingLine(conn, it) }
                }
            } catch (e: Exception) {
                if (isActive) Log.d(TAG, "Client reader ended: ${e.message}")
            } finally {
                removeClient(socket)
            }
        }
    }

    // ======================== Incoming message routing ========================

    private fun handleIncomingLine(conn: ClientConn, raw: String) {
        val env = Envelope.parse(raw)
        if (env == null) {
            // Legacy bare line — treat as broadcast from "Unknown".
            Log.d(TAG, "Legacy bare line from ${conn.socket.inetAddress}: $raw")
            val legacyEnv = Envelope(
                senderId = conn.peerId ?: "legacy",
                senderName = conn.peerName,
                recipientId = Constants.RECIPIENT_BROADCAST,
                payload = raw
            )
            routeEnvelope(legacyEnv, origin = conn)
            return
        }

        if (env.payload == Constants.PAYLOAD_HELLO && conn.peerId == null) {
            registerClient(conn, env.senderId, env.senderName)
            return
        }

        if (env.payload == Constants.PAYLOAD_BYE) {
            removeClient(conn.socket)
            return
        }

        routeEnvelope(env, origin = conn)
    }

    private fun registerClient(conn: ClientConn, peerId: String, peerName: String) {
        pendingClients.remove(conn.socket.hashCode())
        conn.peerId = peerId
        conn.peerName = peerName
        clients[peerId] = conn
        Log.d(TAG, "Registered client: $peerId ($peerName) — total ${clients.size}")

        PeerRegistry.upsert(PeerRegistry.Peer(peerId, peerName, isGroupOwner = false))
        broadcastPeerList()
    }

    private fun routeEnvelope(env: Envelope, origin: ClientConn) {
        if (env.recipientId == Constants.RECIPIENT_BROADCAST) {
            forwardToOtherClients(env, origin)
            deliverToSelf(env)
        } else if (env.recipientId == PeerRegistry.selfId ||
                   env.recipientId == "GO") {
            deliverToSelf(env)
        } else {
            forwardToRecipient(env)
        }
    }

    private fun forwardToOtherClients(env: Envelope, origin: ClientConn) {
        val line = Envelope.build(env.senderId, env.senderName, env.recipientId, env.payload) + "\n"
        clients.values.forEach { c ->
            if (c.peerId != null && c.peerId != env.senderId && c !== origin) {
                try {
                    c.writeLine(line)
                } catch (e: Exception) {
                    Log.w(TAG, "Forward to ${c.peerId} failed: ${e.message}")
                }
            }
        }
    }

    private fun forwardToRecipient(env: Envelope) {
        val line = Envelope.build(env.senderId, env.senderName, env.recipientId, env.payload) + "\n"
        val target = clients[env.recipientId] ?: run {
            Log.w(TAG, "Recipient ${env.recipientId} not connected — dropping")
            return
        }
        try {
            target.writeLine(line)
        } catch (e: Exception) {
            Log.w(TAG, "Forward to ${env.recipientId} failed: ${e.message}")
        }
    }

    private fun deliverToSelf(env: Envelope) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            onMessageReceived?.invoke(env)
        }
    }

    // ======================== Outgoing (from GO) ========================

    private fun sendToInternal(recipientId: String, payload: String) {
        val line = Envelope.build(
            senderId = PeerRegistry.selfId,
            senderName = PeerRegistry.selfName,
            recipientId = recipientId,
            payload = payload
        ) + "\n"
        scope.launch {
            val target = clients[recipientId] ?: run {
                Log.w(TAG, "sendTo: $recipientId not connected")
                return@launch
            }
            try {
                target.writeLine(line)
            } catch (e: Exception) {
                Log.e(TAG, "sendTo error: ${e.message}")
            }
        }
    }

    private fun sendBroadcastInternal(payload: String) {
        val line = Envelope.build(
            senderId = PeerRegistry.selfId,
            senderName = PeerRegistry.selfName,
            recipientId = Constants.RECIPIENT_BROADCAST,
            payload = payload
        ) + "\n"
        scope.launch {
            clients.values.forEach { c ->
                if (c.peerId != null) {
                    try {
                        c.writeLine(line)
                    } catch (e: Exception) {
                        Log.w(TAG, "broadcast to ${c.peerId} failed: ${e.message}")
                    }
                }
            }
            deliverToSelf(Envelope(
                senderId = PeerRegistry.selfId,
                senderName = PeerRegistry.selfName,
                recipientId = Constants.RECIPIENT_BROADCAST,
                payload = payload
            ))
        }
    }

    /** Push the current peer list to every connected client. */
    private fun broadcastPeerList() {
        val payload = PeerRegistry.buildPeersPayload()
        val line = Envelope.build(
            senderId = "GO",
            senderName = PeerRegistry.selfName,
            recipientId = Constants.RECIPIENT_BROADCAST,
            payload = payload
        ) + "\n"
        clients.values.forEach { c ->
            if (c.peerId != null) {
                try {
                    c.writeLine(line)
                } catch (e: Exception) {
                    Log.w(TAG, "PEERS broadcast to ${c.peerId} failed: ${e.message}")
                }
            }
        }
    }

    // ======================== Cleanup ========================

    private fun removeClient(socket: Socket) {
        val key = socket.hashCode()
        val pending = pendingClients.remove(key)
        val registered = clients.entries.firstOrNull { it.value.socket === socket }
        registered?.let { (id, conn) ->
            clients.remove(id)
            PeerRegistry.remove(id)
            Log.d(TAG, "Client removed: $id — remaining ${clients.size}")
            try {
                conn.writer.close()
            } catch (_: Exception) {}
            try { socket.close() } catch (_: Exception) {}
            broadcastPeerList()
        }
        if (pending != null) {
            try { pending.writer.close() } catch (_: Exception) {}
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun closeAll() {
        clients.values.forEach { c ->
            try { c.writer.close() } catch (_: Exception) {}
            try { c.socket.close() } catch (_: Exception) {}
        }
        clients.clear()
        pendingClients.clear()
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        PeerRegistry.clear()
    }
}
