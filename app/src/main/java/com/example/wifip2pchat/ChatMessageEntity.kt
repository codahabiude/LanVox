package com.example.wifip2pchat

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity representing one persisted chat message in a conversation thread.
 */
@Entity(
    tableName = "messages",
    indices = [
        Index(value = ["timestamp"], name = "idx_messages_ts"),
        Index(value = ["peer_id"], name = "idx_messages_peer")
    ]
)
data class ChatMessageEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "_id")
    val id: Long = 0L,
    @ColumnInfo(name = "text")
    val text: String,
    @ColumnInfo(name = "is_sent")
    val isSent: Int,
    @ColumnInfo(name = "timestamp")
    val timestamp: Long,
    @ColumnInfo(name = "peer_id")
    val peerId: String?,
    @ColumnInfo(name = "peer_name")
    val peerName: String?,
    @ColumnInfo(name = "sender_name")
    val senderName: String? = null,
    @ColumnInfo(name = "status")
    val status: String = MessageStatus.NONE.name,
    @ColumnInfo(name = "attachment_name")
    val attachmentName: String? = null,
    @ColumnInfo(name = "attachment_mime")
    val attachmentMime: String? = null,
    @ColumnInfo(name = "attachment_size")
    val attachmentSize: Long = 0L,
    @ColumnInfo(name = "attachment_path")
    val attachmentPath: String? = null,
    @ColumnInfo(name = "attachment_state")
    val attachmentState: String = AttachmentState.NONE.name,
    @ColumnInfo(name = "transfer_id")
    val transferId: String? = null
)
