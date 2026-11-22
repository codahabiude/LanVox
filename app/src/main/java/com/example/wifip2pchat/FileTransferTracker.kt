package com.example.wifip2pchat

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * In-memory real-time tracker for active and completed file transfers across one or more peers.
 *
 * High-frequency byte chunk updates flow here at ~10 Hz via [snapshots] without writing every
 * buffer chunk to SQLite, while terminal state transitions are persisted via [ChatRepository].
 */
object FileTransferTracker {

    enum class TransferDirection {
        SENDING,
        RECEIVING
    }

    enum class PeerTransferStatus {
        PENDING,
        TRANSFERRING,
        COMPLETED,
        FAILED
    }

    data class PeerProgress(
        val peerId: String,
        val peerName: String,
        val state: PeerTransferStatus,
        val bytesTransferred: Long = 0L,
        val totalBytes: Long = 0L
    ) {
        val percent: Int
            get() = when {
                state == PeerTransferStatus.COMPLETED -> 100
                totalBytes > 0L -> ((bytesTransferred * 100L) / totalBytes).toInt().coerceIn(0, 99)
                else -> 0
            }
    }

    data class TransferSnapshot(
        val transferId: String,
        val direction: TransferDirection,
        val stageLabel: String,
        val bytesTransferred: Long,
        val totalBytes: Long,
        val overallPercent: Int,
        val peerStatuses: Map<String, PeerProgress>,
        val isFinished: Boolean,
        val hasFailures: Boolean
    ) {
        val completedPeerCount: Int
            get() = peerStatuses.values.count { it.state == PeerTransferStatus.COMPLETED }

        val failedPeerCount: Int
            get() = peerStatuses.values.count { it.state == PeerTransferStatus.FAILED }

        val totalPeerCount: Int
            get() = peerStatuses.size
    }

    private val lock = Any()
    private val mutableMap = LinkedHashMap<String, TransferSnapshot>()
    private val _snapshots = MutableStateFlow<Map<String, TransferSnapshot>>(emptyMap())
    val snapshots: StateFlow<Map<String, TransferSnapshot>> = _snapshots.asStateFlow()

    fun snapshotFor(transferId: String?): TransferSnapshot? {
        if (transferId.isNullOrBlank()) return null
        return synchronized(lock) { mutableMap[transferId] }
    }

    fun registerOutgoing(
        transferId: String,
        totalBytes: Long,
        targets: List<PeerRegistry.Peer>
    ) {
        synchronized(lock) {
            val peerMap = LinkedHashMap<String, PeerProgress>()
            targets.forEach { peer ->
                peerMap[peer.id] = PeerProgress(
                    peerId = peer.id,
                    peerName = peer.name.ifBlank { peer.id },
                    state = PeerTransferStatus.PENDING,
                    bytesTransferred = 0L,
                    totalBytes = totalBytes
                )
            }
            val stage = if (peerMap.isEmpty()) {
                "Staged locally"
            } else {
                "Waiting · 0/${peerMap.size}"
            }
            mutableMap[transferId] = TransferSnapshot(
                transferId = transferId,
                direction = TransferDirection.SENDING,
                stageLabel = stage,
                bytesTransferred = 0L,
                totalBytes = totalBytes,
                overallPercent = 0,
                peerStatuses = peerMap,
                isFinished = false,
                hasFailures = false
            )
            publishLocked()
        }
    }

    fun markOutgoingNoTargets(transferId: String) {
        synchronized(lock) {
            val current = mutableMap[transferId] ?: return
            mutableMap[transferId] = current.copy(
                stageLabel = "Saved locally · no peers online",
                bytesTransferred = current.totalBytes,
                overallPercent = 100,
                isFinished = true,
                hasFailures = false
            )
            publishLocked()
        }
    }

    fun markSignalFailed(transferId: String) {
        synchronized(lock) {
            val current = mutableMap[transferId] ?: return
            val failedPeers = LinkedHashMap<String, PeerProgress>()
            current.peerStatuses.forEach { (id, progress) ->
                failedPeers[id] = if (progress.state == PeerTransferStatus.COMPLETED) {
                    progress
                } else {
                    progress.copy(state = PeerTransferStatus.FAILED)
                }
            }
            mutableMap[transferId] = current.copy(
                stageLabel = "Send failed",
                peerStatuses = failedPeers,
                isFinished = true,
                hasFailures = true
            )
            publishLocked()
        }
    }

    /**
     * Called by [FileTransferManager]'s TCP server while streaming raw bytes to an attached peer.
     */
    fun updateOutgoingPeerStream(
        transferId: String,
        peerIp: String?,
        bytesSent: Long,
        totalBytes: Long
    ) {
        synchronized(lock) {
            val current = mutableMap[transferId] ?: return
            val resolvedPeer = PeerRegistry.peerForIp(peerIp)
            val peerMap = LinkedHashMap(current.peerStatuses)

            val targetKey = when {
                resolvedPeer != null -> resolvedPeer.id
                peerMap.size == 1 -> peerMap.keys.first()
                !peerIp.isNullOrBlank() -> "ip:$peerIp"
                else -> "peer"
            }

            val existing = peerMap[targetKey]
            val peerName = resolvedPeer?.name?.takeIf { it.isNotBlank() }
                ?: existing?.peerName
                ?: peerIp?.takeIf { it.isNotBlank() }
                ?: "Peer"

            if (existing?.state != PeerTransferStatus.COMPLETED) {
                // Keep socket upload at max 99% until recipient confirms end-to-end FILE_DONE
                val cappedBytes = if (bytesSent >= totalBytes && totalBytes > 0L) {
                    (totalBytes - 1L).coerceAtLeast(0L)
                } else {
                    bytesSent.coerceAtLeast(0L)
                }
                peerMap[targetKey] = PeerProgress(
                    peerId = targetKey,
                    peerName = peerName,
                    state = PeerTransferStatus.TRANSFERRING,
                    bytesTransferred = cappedBytes,
                    totalBytes = totalBytes
                )
            }

            mutableMap[transferId] = recomputeOutgoingLocked(current, peerMap, totalBytes)
            publishLocked()
        }
    }

    fun markPeerCompleted(
        transferId: String,
        peerId: String,
        peerName: String?
    ) {
        synchronized(lock) {
            val current = mutableMap[transferId] ?: return
            val peerMap = LinkedHashMap(current.peerStatuses)
            // Remove any provisional IP-keyed entry if this peer had one matching IP or name
            val ip = PeerRegistry.ipFor(peerId)
            if (!ip.isNullOrBlank()) {
                peerMap.remove("ip:$ip")
            }

            val resolvedName = peerName?.takeIf { it.isNotBlank() }
                ?: PeerRegistry.nameFor(peerId)
                ?: peerMap[peerId]?.peerName
                ?: peerId
            val total = current.totalBytes.coerceAtLeast(0L)
            peerMap[peerId] = PeerProgress(
                peerId = peerId,
                peerName = resolvedName,
                state = PeerTransferStatus.COMPLETED,
                bytesTransferred = total,
                totalBytes = total
            )
            mutableMap[transferId] = recomputeOutgoingLocked(current, peerMap, total)
            publishLocked()
        }
    }

    fun markPeerFailed(
        transferId: String,
        peerId: String,
        peerName: String?
    ) {
        synchronized(lock) {
            val current = mutableMap[transferId] ?: return
            val peerMap = LinkedHashMap(current.peerStatuses)
            val ip = PeerRegistry.ipFor(peerId)
            if (!ip.isNullOrBlank()) {
                peerMap.remove("ip:$ip")
            }

            val resolvedName = peerName?.takeIf { it.isNotBlank() }
                ?: PeerRegistry.nameFor(peerId)
                ?: peerMap[peerId]?.peerName
                ?: peerId
            val existing = peerMap[peerId]
            if (existing?.state == PeerTransferStatus.COMPLETED) return

            peerMap[peerId] = PeerProgress(
                peerId = peerId,
                peerName = resolvedName,
                state = PeerTransferStatus.FAILED,
                bytesTransferred = existing?.bytesTransferred ?: 0L,
                totalBytes = current.totalBytes
            )
            mutableMap[transferId] = recomputeOutgoingLocked(current, peerMap, current.totalBytes)
            publishLocked()
        }
    }

    fun updateIncomingDownload(
        transferId: String,
        bytesDone: Long,
        totalBytes: Long,
        senderId: String?,
        senderName: String?
    ) {
        synchronized(lock) {
            val key = senderId?.takeIf { it.isNotBlank() } ?: "sender"
            val displaySender = senderName?.takeIf { it.isNotBlank() }
                ?: senderId?.let { PeerRegistry.nameFor(it) }
                ?: "Sender"
            val pct = if (totalBytes > 0L) {
                ((bytesDone * 100L) / totalBytes).toInt().coerceIn(0, 99)
            } else {
                0
            }
            val stage = if (bytesDone <= 0L) {
                "Connecting to $displaySender…"
            } else {
                "Downloading from $displaySender"
            }
            val peerProgress = PeerProgress(
                peerId = key,
                peerName = displaySender,
                state = PeerTransferStatus.TRANSFERRING,
                bytesTransferred = bytesDone.coerceAtLeast(0L),
                totalBytes = totalBytes.coerceAtLeast(0L)
            )
            mutableMap[transferId] = TransferSnapshot(
                transferId = transferId,
                direction = TransferDirection.RECEIVING,
                stageLabel = stage,
                bytesTransferred = bytesDone.coerceAtLeast(0L),
                totalBytes = totalBytes.coerceAtLeast(0L),
                overallPercent = pct,
                peerStatuses = mapOf(key to peerProgress),
                isFinished = false,
                hasFailures = false
            )
            publishLocked()
        }
    }

    fun markIncomingCompleted(
        transferId: String,
        totalBytes: Long,
        senderId: String?,
        senderName: String?
    ) {
        synchronized(lock) {
            val key = senderId?.takeIf { it.isNotBlank() } ?: "sender"
            val displaySender = senderName?.takeIf { it.isNotBlank() }
                ?: senderId?.let { PeerRegistry.nameFor(it) }
                ?: "Sender"
            val peerProgress = PeerProgress(
                peerId = key,
                peerName = displaySender,
                state = PeerTransferStatus.COMPLETED,
                bytesTransferred = totalBytes.coerceAtLeast(0L),
                totalBytes = totalBytes.coerceAtLeast(0L)
            )
            mutableMap[transferId] = TransferSnapshot(
                transferId = transferId,
                direction = TransferDirection.RECEIVING,
                stageLabel = "Downloaded from $displaySender",
                bytesTransferred = totalBytes.coerceAtLeast(0L),
                totalBytes = totalBytes.coerceAtLeast(0L),
                overallPercent = 100,
                peerStatuses = mapOf(key to peerProgress),
                isFinished = true,
                hasFailures = false
            )
            publishLocked()
        }
    }

    fun markIncomingFailed(
        transferId: String,
        senderId: String?,
        senderName: String?
    ) {
        synchronized(lock) {
            val existing = mutableMap[transferId]
            val key = senderId?.takeIf { it.isNotBlank() } ?: "sender"
            val displaySender = senderName?.takeIf { it.isNotBlank() }
                ?: senderId?.let { PeerRegistry.nameFor(it) }
                ?: "Sender"
            val total = existing?.totalBytes ?: 0L
            val done = existing?.bytesTransferred ?: 0L
            val peerProgress = PeerProgress(
                peerId = key,
                peerName = displaySender,
                state = PeerTransferStatus.FAILED,
                bytesTransferred = done,
                totalBytes = total
            )
            mutableMap[transferId] = TransferSnapshot(
                transferId = transferId,
                direction = TransferDirection.RECEIVING,
                stageLabel = "Download failed",
                bytesTransferred = done,
                totalBytes = total,
                overallPercent = existing?.overallPercent ?: 0,
                peerStatuses = mapOf(key to peerProgress),
                isFinished = true,
                hasFailures = true
            )
            publishLocked()
        }
    }

    private fun recomputeOutgoingLocked(
        current: TransferSnapshot,
        peerMap: LinkedHashMap<String, PeerProgress>,
        totalBytes: Long
    ): TransferSnapshot {
        val list = peerMap.values
        if (list.isEmpty()) {
            return current.copy(peerStatuses = peerMap)
        }
        val totalPeers = list.size
        val completed = list.count { it.state == PeerTransferStatus.COMPLETED }
        val failed = list.count { it.state == PeerTransferStatus.FAILED }
        val transferring = list.count { it.state == PeerTransferStatus.TRANSFERRING }

        val overallPercent = (list.sumOf { it.percent } / totalPeers).coerceIn(0, 100)
        val finished = (completed + failed) == totalPeers
        val hasFailures = failed > 0

        val representativeBytes = when {
            completed == totalPeers -> totalBytes
            list.isNotEmpty() -> list.maxOf { it.bytesTransferred }
            else -> current.bytesTransferred
        }

        val stage = when {
            completed == totalPeers && totalPeers == 1 -> "Delivered"
            completed == totalPeers -> "Delivered ($completed/$totalPeers)"
            finished && completed > 0 && hasFailures -> "$completed/$totalPeers delivered · $failed failed"
            finished && hasFailures -> "Transfer failed"
            transferring > 0 -> "Uploading · $completed/$totalPeers done"
            completed > 0 -> "Sending · $completed/$totalPeers done"
            else -> "Waiting · 0/$totalPeers peers"
        }

        return current.copy(
            stageLabel = stage,
            bytesTransferred = representativeBytes,
            totalBytes = totalBytes,
            overallPercent = overallPercent,
            peerStatuses = peerMap,
            isFinished = finished,
            hasFailures = hasFailures
        )
    }

    private fun publishLocked() {
        _snapshots.value = LinkedHashMap(mutableMap)
    }

    fun formatBytesShort(bytes: Long): String {
        val safe = bytes.coerceAtLeast(0L)
        return when {
            safe < 1024L -> "$safe B"
            safe < 1024L * 1024L -> "${safe / 1024L} KB"
            safe < 1024L * 1024L * 1024L ->
                String.format(Locale.US, "%.1f MB", safe / (1024.0 * 1024.0))
            else ->
                String.format(Locale.US, "%.2f GB", safe / (1024.0 * 1024.0 * 1024.0))
        }
    }
}
