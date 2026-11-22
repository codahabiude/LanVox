package com.example.wifip2pchat

import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Lightweight event bus for LANVOX chat screens, including live typing indicators.
 */
object ChatBus {

    data class TypingPeer(
        val threadPeerId: String,
        val senderId: String,
        val senderName: String,
        val expiresAtMs: Long
    )

    private const val TYPING_TTL_MS = 3_800L
    private val mainHandler = Handler(Looper.getMainLooper())
    private val typingLock = Any()
    private val activeTyping = LinkedHashMap<String, TypingPeer>() // key = "$threadPeerId::$senderId"
    private val _typingPeers = MutableStateFlow<Map<String, TypingPeer>>(emptyMap())
    val typingPeers: StateFlow<Map<String, TypingPeer>> = _typingPeers.asStateFlow()

    /** Peer id of the conversation currently shown in ChatActivity (null = none open). */
    @Volatile
    var activeChatPeerId: String? = null

    /** True only while ChatActivity is resumed and visible in the foreground. */
    @Volatile
    var isChatScreenForeground: Boolean = false

    /** Set by ChatActivity: (saved message, peer id it belongs to). */
    @Volatile
    var onMessageSaved: ((ChatMessage, String) -> Unit)? = null

    /** Set by ChatActivity: fired when the P2P connection goes up/down. */
    @Volatile
    var onConnectionStateChanged: ((Boolean) -> Unit)? = null

    /** Publish a persisted message to any open chat screen. */
    fun notifyMessageSaved(message: ChatMessage, peerId: String) {
        onMessageSaved?.invoke(message, peerId)
    }

    /** Publish a connection up/down event to any open chat screen. */
    fun notifyConnectionChanged(connected: Boolean) {
        onConnectionStateChanged?.invoke(connected)
    }

    fun updateTyping(
        threadPeerId: String,
        senderId: String,
        senderName: String,
        isTyping: Boolean
    ) {
        val key = "$threadPeerId::$senderId"
        val now = System.currentTimeMillis()
        synchronized(typingLock) {
            pruneExpiredLocked(now)
            if (isTyping) {
                activeTyping[key] = TypingPeer(
                    threadPeerId = threadPeerId,
                    senderId = senderId,
                    senderName = senderName,
                    expiresAtMs = now + TYPING_TTL_MS
                )
            } else {
                activeTyping.remove(key)
            }
            _typingPeers.value = LinkedHashMap(activeTyping)
        }
        if (isTyping) {
            mainHandler.postDelayed({ pruneExpiredTyping() }, TYPING_TTL_MS + 100L)
        }
    }

    fun clearTypingForThread(threadPeerId: String) {
        synchronized(typingLock) {
            val it = activeTyping.entries.iterator()
            var changed = false
            while (it.hasNext()) {
                if (it.next().value.threadPeerId == threadPeerId) {
                    it.remove()
                    changed = true
                }
            }
            if (changed) {
                _typingPeers.value = LinkedHashMap(activeTyping)
            }
        }
    }

    fun typingLabelForThread(
        threadPeerId: String,
        map: Map<String, TypingPeer> = _typingPeers.value
    ): String? {
        val now = System.currentTimeMillis()
        val matching = map.values.filter { it.threadPeerId == threadPeerId && it.expiresAtMs > now }
        if (matching.isEmpty()) return null
        return if (threadPeerId == Constants.GROUP_PEER_ID) {
            when (matching.size) {
                1 -> "${matching.first().senderName} is typing…"
                else -> "${matching.size} people are typing…"
            }
        } else {
            "typing…"
        }
    }

    private fun pruneExpiredTyping() {
        val now = System.currentTimeMillis()
        synchronized(typingLock) {
            if (pruneExpiredLocked(now)) {
                _typingPeers.value = LinkedHashMap(activeTyping)
            }
        }
    }

    private fun pruneExpiredLocked(now: Long): Boolean {
        val it = activeTyping.entries.iterator()
        var removed = false
        while (it.hasNext()) {
            if (it.next().value.expiresAtMs <= now) {
                it.remove()
                removed = true
            }
        }
        return removed
    }
}
