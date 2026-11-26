package com.example.wifip2pchat

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * Tracks live TCP round-trip latency (RTT in milliseconds) to each connected peer
 * via lightweight `PING:<pingId>|<sentElapsedMs>` / `PONG:<pingId>|<sentElapsedMs>` envelopes.
 */
object MeshDiagnosticsBus {

    private val _peerRttMs = MutableStateFlow<Map<String, Long>>(emptyMap())
    val peerRttMs: StateFlow<Map<String, Long>> = _peerRttMs.asStateFlow()

    fun pingConnectedPeers(): Int {
        if (!ChatServerService.running) return 0
        val peers = PeerRegistry.snapshot()
        if (peers.isEmpty()) return 0
        val sentMs = SystemClock.elapsedRealtime()
        var sentCount = 0
        peers.forEach { peer ->
            val pingId = UUID.randomUUID().toString().take(8)
            val payload = "${Constants.MSG_TYPE_PING}$pingId|$sentMs"
            if (ChatServerService.sendTo(peer.id, payload)) {
                sentCount++
            }
        }
        return sentCount
    }

    fun recordPong(peerId: String, pingId: String, originalSentElapsedMs: Long) {
        if (peerId.isBlank() || pingId.isBlank() || originalSentElapsedMs <= 0L) return
        val rtt = (SystemClock.elapsedRealtime() - originalSentElapsedMs).coerceAtLeast(1L)
        val next = _peerRttMs.value.toMutableMap()
        next[peerId] = rtt
        _peerRttMs.value = next
    }

    fun clearPeer(peerId: String) {
        if (!_peerRttMs.value.containsKey(peerId)) return
        val next = _peerRttMs.value.toMutableMap()
        next.remove(peerId)
        _peerRttMs.value = next
    }
}
