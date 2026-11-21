package com.example.wifip2pchat

import android.content.Context
import android.text.format.DateFormat
import android.text.format.DateUtils
import java.util.Date
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
 *
 * @property timestamp Epoch time in milliseconds when the message was sent or received.
 */
data class ChatMessage(
    val text: String,
    val isSentByMe: Boolean,
    val timestamp: Long = System.currentTimeMillis(),
    val status: MessageStatus = if (isSentByMe) MessageStatus.SENT else MessageStatus.NONE,
    val senderName: String? = null,
    val id: String = UUID.randomUUID().toString()
) {
    /**
     * Formats [timestamp] for display inside the chat bubble, respecting the
     * device's 12/24-hour clock preference and showing the date for older messages.
     */
    fun formattedTimestamp(context: Context): String {
        val safeTs = if (timestamp > 0L) timestamp else System.currentTimeMillis()
        return if (DateUtils.isToday(safeTs)) {
            DateFormat.getTimeFormat(context).format(Date(safeTs))
        } else {
            DateUtils.formatDateTime(
                context,
                safeTs,
                DateUtils.FORMAT_SHOW_DATE or
                    DateUtils.FORMAT_ABBREV_MONTH or
                    DateUtils.FORMAT_SHOW_TIME
            )
        }
    }
}
