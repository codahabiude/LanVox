package com.example.wifip2pchat

import android.util.Log

/**
 * In-memory registry of all peers currently in the WiFi Direct group.
 *
 * - On the GO: peers are added when a client sends HELLO, removed when its
 *   socket closes. The GO then broadcasts the updated list to every client.
 * - On a Client: peers come exclusively from PEERS: payloads received from
 *   the GO. The client never directly knows about other clients.
 *
 * UI listeners (MainActivity, ChatsActivity) subscribe via [addListener] to
 * repaint their lists when peers come and go.
 */
object PeerRegistry {

    private const val TAG = "PeerRegistry"

    /** One known peer in the group. */
    data class Peer(
        val id: String,        // WiFi Direct MAC (or "GO" for the group owner)
        val name: String,      // human-readable device name
        val isGroupOwner: Boolean
    )

    /** Current known peers, keyed by id. Thread-safe via synchronized accessors. */
    private val peers = LinkedHashMap<String, Peer>()

    /** This device's own id (WiFi Direct MAC) and name — set by MainActivity. */
    @Volatile var selfId: String = "UNKNOWN"
    @Volatile var selfName: String = "This device"
    @Volatile var isSelfGroupOwner: Boolean = false

    /** Listeners fired on the main thread whenever the peer list changes. */
    private val listeners = mutableSetOf<() -> Unit>()

    /**
     * Subscribe to peer-list changes. Returns a token the caller can pass to
     * [removeListener] to unsubscribe. Multiple listeners may coexist.
     */
    fun addListener(listener: () -> Unit): () -> Unit {
        synchronized(listeners) { listeners.add(listener) }
        return listener
    }

    /** Unsubscribe a previously-registered listener. */
    fun removeListener(token: () -> Unit) {
        synchronized(listeners) { listeners.remove(token) }
    }

    /** Snapshot of the current peer list, EXCLUDING self. */
    fun snapshot(): List<Peer> = synchronized(peers) {
        peers.values.filter { it.id != selfId }.toList()
    }

    /** Snapshot INCLUDING self — used for the Group Chat members preview. */
    fun snapshotIncludingSelf(): List<Peer> = synchronized(peers) {
        val out = peers.values.toMutableList()
        if (out.none { it.id == selfId }) {
            out.add(0, Peer(selfId, selfName, isSelfGroupOwner))
        }
        out
    }

    /** Look up a peer's name by id. */
    fun nameFor(id: String): String? = synchronized(peers) {
        if (id == selfId) selfName
        else peers[id]?.name
    }

    /** Replace the entire peer list (used by clients receiving a PEERS: payload). */
    fun replaceAll(list: List<Peer>) {
        synchronized(peers) {
            peers.clear()
            list.forEach { p ->
                if (p.id != selfId) peers[p.id] = p
            }
        }
        Log.d(TAG, "Peer list replaced: ${list.size} peers")
        notifyChanged()
    }

    /** Add or update a single peer (used by the GO when a client HELLOs). */
    fun upsert(peer: Peer) {
        if (peer.id == selfId) return
        synchronized(peers) {
            peers[peer.id] = peer
        }
        Log.d(TAG, "Peer upserted: ${peer.id} (${peer.name})")
        notifyChanged()
    }

    /** Remove a single peer (used by the GO when a client disconnects). */
    fun remove(peerId: String) {
        synchronized(peers) {
            peers.remove(peerId)
        }
        Log.d(TAG, "Peer removed: $peerId")
        notifyChanged()
    }

    /** Clear everything (used on full disconnect). */
    fun clear() {
        synchronized(peers) { peers.clear() }
        notifyChanged()
    }

    /**
     * Parse a PEERS: payload (`id1:name1,id2:name2,...`) into a list of peers.
     * The GO is included explicitly via [goId]/[goName].
     */
    fun parsePeersPayload(payload: String, goId: String, goName: String): List<Peer> {
        val out = mutableListOf<Peer>()
        out += Peer(goId, goName, isGroupOwner = true)
        val body = payload.removePrefix(Constants.PAYLOAD_PEERS)
        if (body.isBlank()) return out
        body.split(',').forEach { entry ->
            val parts = entry.split(':', limit = 2)
            if (parts.size == 2 && parts[0].isNotBlank()) {
                out += Peer(
                    id = parts[0],
                    name = parts[1].ifBlank { parts[0] },
                    isGroupOwner = false
                )
            }
        }
        return out
    }

    /** Build a PEERS: payload from the current peer list, for the GO to broadcast. */
    fun buildPeersPayload(): String {
        val list = synchronized(peers) {
            peers.values.map { "${it.id}:${it.name}" }
        }
        return Constants.PAYLOAD_PEERS + list.joinToString(",")
    }

    private fun notifyChanged() {
        // Fire on the main thread — UI listeners assume main-thread callback.
        val snapshot = synchronized(listeners) { listeners.toList() }
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            snapshot.forEach { it.invoke() }
        }
    }
}
