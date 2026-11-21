package com.example.wifip2pchat

import java.util.UUID

/**
 * Delivery/read state for outgoing chat messages.
 *
 *  - [NONE]      — incoming message (ticks hidden)
 *  - [SENT]      — saved locally / dispatched
 *  - [DELIVERED] — acknowledged by the peer
 *  - [READ]      — seen by the peer
 */
enum class MessageStatus {
    NONE,
    SENT,
    DELIVERED,
    READ
}

/**
 * Data class representing a single chat message in a conversation thread.
 */
data class ChatMessage(
    val text: String,
    val isSentByMe: Boolean,
    val timestamp: Long = System.currentTimeMillis(),
    val status: MessageStatus = if (isSentByMe) MessageStatus.SENT else MessageStatus.NONE,
    val senderName: String? = null,
    val id: String = UUID.randomUUID().toString()
)
