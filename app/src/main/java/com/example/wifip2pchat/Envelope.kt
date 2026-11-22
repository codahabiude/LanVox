package com.example.wifip2pchat

import android.util.Log
import java.util.UUID

/**
 * Parsed view of one line on the chat channel.
 *
 * Envelope format (v5.0+):
 *   ENV|<senderId>|<senderName>|<recipientIdOrStar>|<payload>
 *
 * Mesh deduplication tag (v6.0+, backward-compatible inside field 5):
 *   ENV|<senderId>|<senderName>|<recipientIdOrStar>|MID:<id>:<payload>
 */
data class Envelope(
    val senderId: String,
    val senderName: String,
    val recipientId: String,   // "*" for broadcast
    val payload: String,       // e.g. "TEXT:hello" or "HELLO" or "PEERS:..."
    val msgId: String? = null  // present on mesh packets to prevent duplicate delivery
) {
    /** True if this envelope is addressed to [peerId] OR is a broadcast. */
    fun isFor(peerId: String): Boolean =
        recipientId == Constants.RECIPIENT_BROADCAST || recipientId == peerId

    companion object {
        private const val TAG = "Envelope"

        fun newMessageId(): String = UUID.randomUUID().toString().replace("-", "").take(10)

        /**
         * Try to parse a raw chat-channel line as an envelope.
         * Transparently strips any `MID:<id>:` deduplication header so callers
         * always receive the clean [payload].
         */
        fun parse(line: String): Envelope? {
            val trimmed = line.trimEnd('\r', '\n')
            if (!trimmed.startsWith(Constants.ENVELOPE_PREFIX + Constants.ENVELOPE_DELIM)) {
                return null  // legacy bare line — caller decides what to do
            }
            // ENV|a|b|c|payload   → split with limit 5 so payload may contain '|'
            val parts = trimmed.split(Constants.ENVELOPE_DELIM, limit = 5)
            if (parts.size < 5) {
                Log.w(TAG, "Malformed envelope (got ${parts.size} parts): $trimmed")
                return null
            }
            val rawPayload = parts[4]
            var msgId: String? = null
            var cleanPayload = rawPayload
            if (rawPayload.startsWith(Constants.MSG_ID_PREFIX)) {
                val rest = rawPayload.removePrefix(Constants.MSG_ID_PREFIX)
                val sepIdx = rest.indexOf(':')
                if (sepIdx > 0) {
                    msgId = rest.substring(0, sepIdx)
                    cleanPayload = rest.substring(sepIdx + 1)
                }
            }
            return Envelope(
                senderId = parts[1],
                senderName = parts[2],
                recipientId = parts[3],
                payload = cleanPayload,
                msgId = msgId
            )
        }

        /** Build an envelope line ready to write to the socket. */
        fun build(
            senderId: String,
            senderName: String,
            recipientId: String,
            payload: String,
            msgId: String? = null
        ): String {
            // senderName may not contain '|' — sanitize defensively.
            val safeName = senderName.replace(Constants.ENVELOPE_DELIM, " ")
            val wirePayload = if (!msgId.isNullOrBlank() && !payload.startsWith(Constants.MSG_ID_PREFIX)) {
                "${Constants.MSG_ID_PREFIX}$msgId:$payload"
            } else {
                payload
            }
            return listOf(
                Constants.ENVELOPE_PREFIX,
                senderId,
                safeName,
                recipientId,
                wirePayload
            ).joinToString(Constants.ENVELOPE_DELIM)
        }
    }
}
