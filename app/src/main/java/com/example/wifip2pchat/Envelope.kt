package com.example.wifip2pchat

import android.util.Log

/**
 * Parsed view of one line on the chat channel.
 *
 * Envelope format (v5.0+):
 *   ENV|<senderId>|<senderName>|<recipientIdOrStar>|<payload>
 *
 * Legacy bare line (pre-v5):
 *   TEXT:hello world
 *
 * Legacy lines are treated as a broadcast from an unknown sender so old
 * clients still interoperate with a v5 Group Owner.
 */
data class Envelope(
    val senderId: String,
    val senderName: String,
    val recipientId: String,   // "*" for broadcast
    val payload: String        // e.g. "TEXT:hello" or "HELLO" or "PEERS:..."
) {
    /** True if this envelope is addressed to [peerId] OR is a broadcast. */
    fun isFor(peerId: String): Boolean =
        recipientId == Constants.RECIPIENT_BROADCAST || recipientId == peerId

    companion object {
        private const val TAG = "Envelope"

        /**
         * Try to parse a raw chat-channel line as an envelope.
         * @return the parsed Envelope, or null if the line is a legacy bare
         *         message (in which case the caller should treat it as
         *         broadcast-from-unknown).
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
            return Envelope(
                senderId = parts[1],
                senderName = parts[2],
                recipientId = parts[3],
                payload = parts[4]
            )
        }

        /** Build an envelope line ready to write to the socket. */
        fun build(
            senderId: String,
            senderName: String,
            recipientId: String,
            payload: String
        ): String {
            // senderName may not contain '|' — sanitize defensively.
            val safeName = senderName.replace(Constants.ENVELOPE_DELIM, " ")
            return listOf(
                Constants.ENVELOPE_PREFIX,
                senderId,
                safeName,
                recipientId,
                payload
            ).joinToString(Constants.ENVELOPE_DELIM)
        }
    }
}
