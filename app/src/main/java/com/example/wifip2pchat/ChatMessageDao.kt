package com.example.wifip2pchat

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Projection row returned by conversation summary queries.
 */
data class ConversationTuple(
    val peerId: String,
    val peerName: String?,
    val lastText: String?,
    val lastIsSent: Int,
    val lastTimestamp: Long,
    val messageCount: Int
)

/**
 * Room Data Access Object for chat message history and conversation threads.
 */
@Dao
interface ChatMessageDao {

    @Query(
        """
        SELECT * FROM (
            SELECT * FROM messages
            WHERE peer_id = :peerId
            ORDER BY timestamp DESC, _id DESC
            LIMIT :limit
        )
        ORDER BY timestamp ASC, _id ASC
        """
    )
    fun observeHistory(peerId: String, limit: Int): Flow<List<ChatMessageEntity>>

    @Query("SELECT * FROM messages WHERE peer_id = :peerId ORDER BY timestamp DESC, _id DESC LIMIT :limit")
    suspend fun getHistoryDesc(peerId: String, limit: Int): List<ChatMessageEntity>

    @Query("SELECT COUNT(*) FROM messages")
    fun observeTotalCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM messages")
    suspend fun countAll(): Int

    @Query("SELECT COUNT(*) FROM messages WHERE peer_id = :peerId")
    suspend fun countForPeer(peerId: String): Int

    @Query(
        """
        SELECT m.peer_id AS peerId,
            (SELECT m2.peer_name FROM messages m2
              WHERE m2.peer_id = m.peer_id AND m2.peer_name IS NOT NULL
              ORDER BY m2.timestamp DESC, m2._id DESC LIMIT 1) AS peerName,
            (SELECT m2.text FROM messages m2
              WHERE m2.peer_id = m.peer_id
              ORDER BY m2.timestamp DESC, m2._id DESC LIMIT 1) AS lastText,
            (SELECT m2.is_sent FROM messages m2
              WHERE m2.peer_id = m.peer_id
              ORDER BY m2.timestamp DESC, m2._id DESC LIMIT 1) AS lastIsSent,
            MAX(m.timestamp) AS lastTimestamp,
            COUNT(*) AS messageCount
        FROM messages m
        WHERE m.peer_id IS NOT NULL AND m.peer_id != ''
        GROUP BY m.peer_id
        ORDER BY MAX(m.timestamp) DESC
        """
    )
    fun observeConversations(): Flow<List<ConversationTuple>>

    @Query(
        """
        SELECT m.peer_id AS peerId,
            (SELECT m2.peer_name FROM messages m2
              WHERE m2.peer_id = m.peer_id AND m2.peer_name IS NOT NULL
              ORDER BY m2.timestamp DESC, m2._id DESC LIMIT 1) AS peerName,
            (SELECT m2.text FROM messages m2
              WHERE m2.peer_id = m.peer_id
              ORDER BY m2.timestamp DESC, m2._id DESC LIMIT 1) AS lastText,
            (SELECT m2.is_sent FROM messages m2
              WHERE m2.peer_id = m.peer_id
              ORDER BY m2.timestamp DESC, m2._id DESC LIMIT 1) AS lastIsSent,
            MAX(m.timestamp) AS lastTimestamp,
            COUNT(*) AS messageCount
        FROM messages m
        WHERE m.peer_id IS NOT NULL AND m.peer_id != ''
        GROUP BY m.peer_id
        ORDER BY MAX(m.timestamp) DESC
        """
    )
    suspend fun getConversations(): List<ConversationTuple>

    @Query(
        "SELECT peer_name FROM messages WHERE peer_id = :peerId AND peer_name IS NOT NULL " +
            "ORDER BY timestamp DESC, _id DESC LIMIT 1"
    )
    suspend fun getPeerName(peerId: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(entity: ChatMessageEntity): Long

    @Query("UPDATE messages SET status = :newStatus WHERE peer_id = :peerId AND is_sent = 1 AND status IN (:fromStatuses)")
    suspend fun updateOutgoingStatus(peerId: String, newStatus: String, fromStatuses: List<String>): Int

    @Query("UPDATE messages SET attachment_path = :attachmentPath, attachment_state = :attachmentState WHERE transfer_id = :transferId")
    suspend fun updateAttachmentByTransferId(transferId: String, attachmentPath: String?, attachmentState: String): Int

    @Query("UPDATE messages SET attachment_state = :attachmentState WHERE transfer_id = :transferId")
    suspend fun updateAttachmentStateOnlyByTransferId(transferId: String, attachmentState: String): Int

    @Query("DELETE FROM messages WHERE _id = :id")
    suspend fun deleteById(id: Long): Int

    @Query("DELETE FROM messages WHERE timestamp = :timestamp AND text = :text AND is_sent = :isSent")
    suspend fun deleteBySignature(timestamp: Long, text: String, isSent: Int): Int

    @Query("DELETE FROM messages WHERE peer_id = :peerId")
    suspend fun deleteConversation(peerId: String)

    @Query("DELETE FROM messages")
    suspend fun deleteAll()
}
