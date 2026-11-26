package com.example.wifip2pchat

import android.net.Uri
import android.util.Log

/**
 * In-memory registry of all peers currently reachable across the Wi-Fi Direct group
 * and/or local Wi-Fi/Hotspot NSD mesh.
 *
 * Every peer carries its optional direct [Peer.ipAddress] so devices can talk,
 * stream files, and place WebRTC/Camera2 calls directly to one another without
 * needing a single central host.
 */
object PeerRegistry {

    private const val TAG = "PeerRegistry"

    /** One known peer in the mesh / Wi-Fi Direct group. */
    data class Peer(
        val id: String,
        val name: String,
        val isGroupOwner: Boolean,
        val ipAddress: String? = null,
        val transport: String = "p2p",
        val presenceStatus: String = DeviceIdentity.STATUS_AVAILABLE,
        val bio: String? = null,
        val publicKeyB64: String? = null
    )

    private val peers = LinkedHashMap<String, Peer>()

    @Volatile var selfId: String = "UNKNOWN"
    @Volatile var selfName: String = "This device"
    @Volatile var isSelfGroupOwner: Boolean = false
    @Volatile var selfIpAddress: String? = null

    private val listeners = mutableSetOf<() -> Unit>()

    fun addListener(listener: () -> Unit): () -> Unit {
        synchronized(listeners) { listeners.add(listener) }
        return listener
    }

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
            out.add(0, Peer(selfId, selfName, isSelfGroupOwner, selfIpAddress))
        }
        out
    }

    /** Look up a peer's name by id. */
    fun nameFor(id: String): String? = synchronized(peers) {
        if (id == selfId) selfName
        else peers[id]?.name
    }

    /** Look up a peer entry by id. */
    fun peerForId(id: String?): Peer? {
        if (id.isNullOrBlank()) return null
        return synchronized(peers) { peers[id] }
    }

    /** Look up a peer's direct IP address by id. */
    fun ipFor(id: String?): String? {
        if (id.isNullOrBlank()) return null
        return synchronized(peers) {
            if (id == selfId) selfIpAddress else peers[id]?.ipAddress
        }
    }

    /** Look up a peer by its direct IP address. */
    fun peerForIp(ip: String?): Peer? {
        if (ip.isNullOrBlank()) return null
        return synchronized(peers) {
            peers.values.firstOrNull { it.ipAddress == ip }
        }
    }

    /** Merge/replace peers received from a PEERS: payload (preserving any known direct IPs & profiles). */
    fun mergeFromPeersPayload(list: List<Peer>) {
        synchronized(peers) {
            list.forEach { p ->
                if (p.id != selfId) {
                    val existing = peers[p.id]
                    val mergedIp = p.ipAddress?.takeIf { it.isNotBlank() } ?: existing?.ipAddress
                    val mergedTransport = existing?.transport ?: p.transport
                    val mergedStatus = existing?.presenceStatus ?: p.presenceStatus
                    val mergedBio = existing?.bio ?: p.bio
                    val mergedPub = p.publicKeyB64?.takeIf { it.isNotBlank() } ?: existing?.publicKeyB64
                    peers[p.id] = p.copy(
                        ipAddress = mergedIp,
                        transport = mergedTransport,
                        presenceStatus = mergedStatus,
                        bio = mergedBio,
                        publicKeyB64 = mergedPub
                    )
                }
            }
        }
        Log.d(TAG, "Peer list merged: ${peers.size} peers")
        notifyChanged()
    }

    /** Replace the entire peer list (used when syncing full membership). */
    fun replaceAll(list: List<Peer>) {
        synchronized(peers) {
            val prev = peers.toMap()
            peers.clear()
            list.forEach { p ->
                if (p.id != selfId) {
                    val existing = prev[p.id]
                    peers[p.id] = if (existing != null) {
                        p.copy(
                            presenceStatus = existing.presenceStatus,
                            bio = existing.bio,
                            publicKeyB64 = p.publicKeyB64?.takeIf { it.isNotBlank() } ?: existing.publicKeyB64
                        )
                    } else {
                        p
                    }
                }
            }
        }
        Log.d(TAG, "Peer list replaced: ${list.size} peers")
        notifyChanged()
    }

    /** Add or update a single peer while preserving non-null IP and profile info. */
    fun upsert(peer: Peer) {
        if (peer.id == selfId) return
        synchronized(peers) {
            val existing = peers[peer.id]
            val merged = peer.copy(
                name = peer.name.ifBlank { existing?.name ?: peer.id },
                ipAddress = peer.ipAddress?.takeIf { it.isNotBlank() } ?: existing?.ipAddress,
                isGroupOwner = peer.isGroupOwner || (existing?.isGroupOwner == true),
                presenceStatus = existing?.presenceStatus ?: peer.presenceStatus,
                bio = existing?.bio ?: peer.bio,
                publicKeyB64 = peer.publicKeyB64?.takeIf { it.isNotBlank() } ?: existing?.publicKeyB64
            )
            peers[peer.id] = merged
        }
        Log.d(TAG, "Peer upserted: ${peer.id} (${peer.name}, ip=${peer.ipAddress})")
        notifyChanged()
    }

    fun updateProfile(peerId: String, name: String?, presenceStatus: String, bio: String?) {
        if (peerId.isBlank() || peerId == selfId) return
        synchronized(peers) {
            val existing = peers[peerId]
            if (existing != null) {
                peers[peerId] = existing.copy(
                    name = name?.takeIf { it.isNotBlank() } ?: existing.name,
                    presenceStatus = presenceStatus,
                    bio = bio
                )
            } else {
                peers[peerId] = Peer(
                    id = peerId,
                    name = name?.takeIf { it.isNotBlank() } ?: peerId,
                    isGroupOwner = false,
                    presenceStatus = presenceStatus,
                    bio = bio
                )
            }
        }
        notifyChanged()
    }

    fun updateIp(peerId: String, ipAddress: String?) {
        if (peerId == selfId || ipAddress.isNullOrBlank()) return
        synchronized(peers) {
            val existing = peers[peerId]
            if (existing != null) {
                peers[peerId] = existing.copy(ipAddress = ipAddress)
            }
        }
    }

    fun updatePublicKey(peerId: String, publicKeyB64: String?) {
        if (peerId == selfId || publicKeyB64.isNullOrBlank()) return
        synchronized(peers) {
            val existing = peers[peerId]
            if (existing != null) {
                peers[peerId] = existing.copy(publicKeyB64 = publicKeyB64)
            }
        }
    }

    fun remove(peerId: String) {
        synchronized(peers) {
            peers.remove(peerId)
        }
        Log.d(TAG, "Peer removed: $peerId")
        notifyChanged()
    }

    fun removeByTransport(transport: String) {
        var removed = false
        synchronized(peers) {
            val it = peers.entries.iterator()
            while (it.hasNext()) {
                if (it.next().value.transport == transport) {
                    it.remove()
                    removed = true
                }
            }
        }
        if (removed) notifyChanged()
    }

    fun clear() {
        synchronized(peers) { peers.clear() }
        notifyChanged()
    }

    /**
     * Parse a PEERS: payload (`encodedId:encodedName@encodedIp,...`) into peers.
     * Backward compatible with v5 `encodedId:encodedName`.
     */
    fun parsePeersPayload(
        payload: String,
        senderId: String,
        senderName: String,
        senderIp: String? = null,
        senderIsGo: Boolean = true
    ): List<Peer> {
        val out = mutableListOf<Peer>()
        if (senderId.isNotBlank() && senderId != "GO" && senderId != selfId) {
            out += Peer(senderId, senderName, isGroupOwner = senderIsGo, ipAddress = senderIp)
        }
        val body = payload.removePrefix(Constants.PAYLOAD_PEERS)
        if (body.isBlank()) return out
        body.split(',').forEach { entry ->
            val parts = entry.split(':', limit = 2)
            if (parts.size == 2 && parts[0].isNotBlank()) {
                val decodedId = Uri.decode(parts[0])
                val rawRest = parts[1]
                val hashIdx = rawRest.lastIndexOf('#')
                val nameAndIp: String
                val decodedPub: String?
                if (hashIdx >= 0) {
                    nameAndIp = rawRest.substring(0, hashIdx)
                    decodedPub = Uri.decode(rawRest.substring(hashIdx + 1)).takeIf { it.isNotBlank() }
                } else {
                    nameAndIp = rawRest
                    decodedPub = null
                }
                val atIdx = nameAndIp.lastIndexOf('@')
                val decodedName: String
                val decodedIp: String?
                if (atIdx >= 0) {
                    decodedName = Uri.decode(nameAndIp.substring(0, atIdx)).ifBlank { decodedId }
                    decodedIp = Uri.decode(nameAndIp.substring(atIdx + 1)).takeIf { it.isNotBlank() }
                } else {
                    decodedName = Uri.decode(nameAndIp).ifBlank { decodedId }
                    decodedIp = null
                }
                if (decodedId != selfId) {
                    out += Peer(
                        id = decodedId,
                        name = decodedName,
                        isGroupOwner = false,
                        ipAddress = decodedIp,
                        publicKeyB64 = decodedPub
                    )
                }
            }
        }
        return out
    }

    /** Build a PEERS: payload including known direct IP addresses and E2EE public keys. */
    fun buildPeersPayload(): String {
        val list = synchronized(peers) {
            peers.values.map { p ->
                val ipPart = p.ipAddress?.takeIf { it.isNotBlank() }?.let { "@${Uri.encode(it)}" } ?: ""
                val pubPart = p.publicKeyB64?.takeIf { it.isNotBlank() }?.let { "#${Uri.encode(it)}" } ?: ""
                "${Uri.encode(p.id)}:${Uri.encode(p.name)}$ipPart$pubPart"
            }
        }
        return Constants.PAYLOAD_PEERS + list.joinToString(",")
    }

    private fun notifyChanged() {
        val snapshot = synchronized(listeners) { listeners.toList() }
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            snapshot.forEach { it.invoke() }
        }
    }
}
