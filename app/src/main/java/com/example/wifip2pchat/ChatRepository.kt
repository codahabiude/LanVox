package com.example.wifip2pchat

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * One row in the chats list — a conversation with a single contact device.
 */
data class Conversation(
    val peerId: String,
    val peerName: String?,
    val lastText: String,
    val lastIsSentByMe: Boolean,
    val lastTimestamp: Long,
    val messageCount: Int
)

/**
 * Repository abstracting local Room persistence for chat threads and messages.
 */
class ChatRepository(private val dao: ChatMessageDao) {

    companion object {
        const val HISTORY_LIMIT = 200

        @Volatile
        private var instance: ChatRepository? = null

        fun get(context: Context): ChatRepository =
            instance ?: synchronized(this) {
                instance ?: ChatRepository(
                    LanvoxDatabase.get(context).chatMessageDao()
                ).also { instance = it }
            }
    }

    fun observeHistory(peerId: String?, limit: Int = HISTORY_LIMIT): Flow<List<ChatMessage>> {
        if (peerId.isNullOrBlank()) return flowOf(emptyList())
        return dao.observeHistory(peerId, limit).map { entities ->
            entities.map { it.toDomain() }
        }
    }

    fun observeTotalCount(): Flow<Int> = dao.observeTotalCount()

    fun observeConversations(): Flow<List<Conversation>> =
        dao.observeConversations().map { rows -> rows.map { it.toDomain() } }

    suspend fun insertMessage(message: ChatMessage, peerId: String?, peerName: String?): Long {
        val entity = ChatMessageEntity(
            text = message.text,
            isSent = if (message.isSentByMe) 1 else 0,
            timestamp = message.timestamp,
            peerId = peerId,
            peerName = peerName,
            senderName = message.senderName,
            status = message.status.name
        )
        return dao.insertMessage(entity)
    }

    suspend fun loadHistory(peerId: String?, limit: Int = HISTORY_LIMIT): List<ChatMessage> {
        if (peerId.isNullOrBlank()) return emptyList()
        return dao.getHistoryDesc(peerId, limit).reversed().map { it.toDomain() }
    }

    suspend fun getConversations(): List<Conversation> =
        dao.getConversations().map { it.toDomain() }

    suspend fun peerNameFor(peerId: String?): String? {
        if (peerId.isNullOrBlank()) return null
        return dao.getPeerName(peerId)
    }

    suspend fun count(peerId: String? = null): Int =
        if (peerId == null) dao.countAll() else dao.countForPeer(peerId)

    suspend fun markThreadDelivered(peerId: String) {
        if (peerId.isBlank()) return
        dao.updateOutgoingStatus(
            peerId = peerId,
            newStatus = MessageStatus.DELIVERED.name,
            fromStatuses = listOf(MessageStatus.SENT.name)
        )
    }

    suspend fun markThreadRead(peerId: String) {
        if (peerId.isBlank()) return
        dao.updateOutgoingStatus(
            peerId = peerId,
            newStatus = MessageStatus.READ.name,
            fromStatuses = listOf(MessageStatus.SENT.name, MessageStatus.DELIVERED.name)
        )
    }

    suspend fun deleteMessage(message: ChatMessage) {
        val rowId = message.id.removePrefix("room_").toLongOrNull()
        if (rowId != null) {
            dao.deleteById(rowId)
        } else {
            dao.deleteBySignature(
                timestamp = message.timestamp,
                text = message.text,
                isSent = if (message.isSentByMe) 1 else 0
            )
        }
    }

    suspend fun clearConversation(peerId: String) {
        dao.deleteConversation(peerId)
    }

    suspend fun clearAll() {
        dao.deleteAll()
    }

    private fun ChatMessageEntity.toDomain(): ChatMessage {
        val parsedStatus = runCatching { MessageStatus.valueOf(status) }
            .getOrElse { if (isSent == 1) MessageStatus.SENT else MessageStatus.NONE }
        return ChatMessage(
            text = text,
            isSentByMe = isSent == 1,
            timestamp = timestamp,
            status = parsedStatus,
            senderName = senderName,
            id = "room_$id"
        )
    }

    private fun ConversationTuple.toDomain(): Conversation = Conversation(
        peerId = peerId,
        peerName = peerName,
        lastText = lastText ?: "",
        lastIsSentByMe = lastIsSent == 1,
        lastTimestamp = lastTimestamp,
        messageCount = messageCount
    )
}
