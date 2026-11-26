package com.example.wifip2pchat

import android.content.Context
import android.text.format.DateFormat
import android.text.format.DateUtils
import org.json.JSONArray
import org.json.JSONObject
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
 * Transfer lifecycle state for file attachment messages.
 */
enum class AttachmentState {
    NONE,
    TRANSFERRING,
    COMPLETED,
    FAILED
}

/**
 * Aggregated emoji reaction pill model for display below a message bubble.
 */
data class ReactionChip(
    val emoji: String,
    val count: Int,
    val reactedByMe: Boolean
)

/**
 * Decoded quoted-reply envelope payload.
 */
data class DecodedReply(
    val replyToId: String?,
    val replySender: String?,
    val replySnippet: String?,
    val text: String
)

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
    val id: String = UUID.randomUUID().toString(),
    val attachmentName: String? = null,
    val attachmentMime: String? = null,
    val attachmentSize: Long = 0L,
    val attachmentPath: String? = null,
    val attachmentState: AttachmentState = AttachmentState.NONE,
    val transferId: String? = null,
    val messageId: String? = if (isSentByMe) Envelope.newMessageId() else null,
    val replyToMessageId: String? = null,
    val replyToSender: String? = null,
    val replyToText: String? = null,
    val reactionsJson: String? = null,
    val isStarred: Boolean = false,
    val isPinned: Boolean = false,
    val editedAt: Long? = null,
    val expiresAt: Long? = null,
    val messageType: String = TYPE_STANDARD
) {
    val isFileMessage: Boolean
        get() = !attachmentName.isNullOrBlank()

    val isVoiceNote: Boolean
        get() = attachmentMime?.startsWith("audio/") == true

    val isCallLog: Boolean
        get() = messageType == TYPE_CALL_LOG

    val isPoll: Boolean
        get() = messageType == TYPE_POLL

    fun parsedPoll(): PollData? = if (isPoll) PollData.fromJsonString(text) else null

    val hasReplyQuote: Boolean
        get() = !replyToText.isNullOrBlank()

    fun formattedRemainingTtl(nowMs: Long = System.currentTimeMillis()): String? {
        val exp = expiresAt ?: return null
        val remSec = ((exp - nowMs) / 1000L).coerceAtLeast(0L)
        return when {
            remSec <= 0L -> "0s"
            remSec < 60L -> "${remSec}s"
            remSec < 3600L -> "${(remSec + 59L) / 60L}m"
            else -> "${(remSec + 3599L) / 3600L}h"
        }
    }

    fun quoteSnippet(): String {
        val raw = when {
            isPoll -> parsedPoll()?.summaryText() ?: "📊 Poll"
            isVoiceNote -> "🎤 Voice note"
            isFileMessage -> "📎 ${attachmentName ?: "Attachment"}"
            else -> text
        }
        val clean = raw.replace('\n', ' ').trim()
        return if (clean.length > 90) clean.take(87) + "…" else clean
    }

    fun parsedReactions(): Map<String, List<String>> = parseReactionsJson(reactionsJson)

    fun reactionChips(selfKey: String = SELF_REACTOR_KEY): List<ReactionChip> {
        val map = parsedReactions()
        if (map.isEmpty()) return emptyList()
        return map.entries
            .filter { it.value.isNotEmpty() }
            .map { (emoji, actors) ->
                ReactionChip(
                    emoji = emoji,
                    count = actors.size,
                    reactedByMe = actors.contains(selfKey) || actors.contains(PeerRegistry.selfId)
                )
            }
    }

    fun hasReacted(emoji: String, selfKey: String = SELF_REACTOR_KEY): Boolean {
        val actors = parsedReactions()[emoji].orEmpty()
        return actors.contains(selfKey) || actors.contains(PeerRegistry.selfId)
    }

    fun formattedFileSize(): String {
        val bytes = attachmentSize.coerceAtLeast(0L)
        return when {
            bytes < 1024L -> "$bytes B"
            bytes < 1024L * 1024L -> String.format("%.1f KB", bytes / 1024.0)
            bytes < 1024L * 1024L * 1024L -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
            else -> String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
        }
    }

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

    companion object {
        const val SELF_REACTOR_KEY = "__self__"
        const val TYPE_STANDARD = "STANDARD"
        const val TYPE_CALL_LOG = "CALL_LOG"
        const val TYPE_POLL = "POLL"
        val QUICK_REACTIONS = listOf("❤️", "😂", "😮", "😢", "🙏", "👍")

        fun encodeTtlPayload(ttlSeconds: Long, innerPayload: String): String {
            if (ttlSeconds <= 0L) return innerPayload
            return "${Constants.MSG_TYPE_TTL}$ttlSeconds|$innerPayload"
        }

        fun decodeTtlPayload(payload: String): Pair<Long?, String> {
            if (!payload.startsWith(Constants.MSG_TYPE_TTL)) return null to payload
            val rest = payload.removePrefix(Constants.MSG_TYPE_TTL)
            val sep = rest.indexOf('|')
            if (sep <= 0) return null to payload
            val secs = rest.substring(0, sep).trim().toLongOrNull()?.takeIf { it > 0L } ?: return null to payload
            return secs to rest.substring(sep + 1)
        }

        fun encodeReplyPayload(
            replyToId: String?,
            replySender: String?,
            replySnippet: String?,
            text: String
        ): String {
            val json = JSONObject().apply {
                if (!replyToId.isNullOrBlank()) put("rid", replyToId)
                if (!replySender.isNullOrBlank()) put("rs", replySender.take(60))
                if (!replySnippet.isNullOrBlank()) put("rt", replySnippet.take(140))
                put("txt", text)
            }
            return Constants.MSG_TYPE_REPLY + json.toString()
        }

        fun decodeReplyPayload(payload: String): DecodedReply? {
            if (!payload.startsWith(Constants.MSG_TYPE_REPLY)) return null
            val raw = payload.removePrefix(Constants.MSG_TYPE_REPLY).trim()
            return runCatching {
                val obj = JSONObject(raw)
                val text = obj.optString("txt")
                if (text.isEmpty() && !obj.has("txt")) return null
                DecodedReply(
                    replyToId = obj.optString("rid").takeIf { it.isNotBlank() },
                    replySender = obj.optString("rs").takeIf { it.isNotBlank() },
                    replySnippet = obj.optString("rt").takeIf { it.isNotBlank() },
                    text = text
                )
            }.getOrNull()
        }

        fun parseReactionsJson(raw: String?): LinkedHashMap<String, MutableList<String>> {
            val out = LinkedHashMap<String, MutableList<String>>()
            if (raw.isNullOrBlank()) return out
            runCatching {
                val obj = JSONObject(raw)
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val emoji = keys.next()
                    val arr = obj.optJSONArray(emoji) ?: continue
                    val list = mutableListOf<String>()
                    for (i in 0 until arr.length()) {
                        val actor = arr.optString(i).trim()
                        if (actor.isNotEmpty() && !list.contains(actor)) {
                            list.add(actor)
                        }
                    }
                    if (list.isNotEmpty()) {
                        out[emoji] = list
                    }
                }
            }
            return out
        }

        /**
         * Updates [existingJson] by adding or removing [actorId] under [emoji].
         * If [forceAdd] is null, toggles membership cleanly.
         * Returns Pair(newJsonOrNull, addedBoolean).
         */
        fun mutateReactionJson(
            existingJson: String?,
            emoji: String,
            actorId: String,
            forceAdd: Boolean? = null
        ): Pair<String?, Boolean> {
            val map = parseReactionsJson(existingJson)
            val actors = map.getOrPut(emoji) { mutableListOf() }
            val alreadyPresent = actors.contains(actorId)
            val shouldAdd = forceAdd ?: !alreadyPresent
            if (shouldAdd) {
                if (!alreadyPresent) actors.add(actorId)
            } else {
                actors.remove(actorId)
                if (actors.isEmpty()) map.remove(emoji)
            }
            if (map.isEmpty()) return null to shouldAdd
            val obj = JSONObject()
            map.forEach { (e, list) ->
                if (list.isNotEmpty()) {
                    obj.put(e, JSONArray(list))
                }
            }
            return (if (obj.length() == 0) null else obj.toString()) to shouldAdd
        }
    }
}
