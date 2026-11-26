package com.example.wifip2pchat

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One row in the chats list — a conversation with a single contact device.
 */
data class Conversation(
    val peerId: String,
    val peerName: String?,
    val lastText: String,
    val lastIsSentByMe: Boolean,
    val lastTimestamp: Long,
    val messageCount: Int,
    val lastStatus: MessageStatus = MessageStatus.NONE,
    val unreadCount: Int = 0
)

/**
 * Repository abstracting local Room persistence for chat threads and messages.
 * All user-authored message text, peer/sender names, replies, reactions, and
 * attachment metadata are encrypted at rest with AES-256-GCM (`ENC1:`) backed
 * by AndroidKeyStore.
 */
class ChatRepository(
    context: Context,
    private val dao: ChatMessageDao
) {

    private val appContext: Context = context.applicationContext

    companion object {
        const val HISTORY_LIMIT = 200

        @Volatile
        private var instance: ChatRepository? = null

        private val migrationStarted = AtomicBoolean(false)
        private val migrationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        fun get(context: Context): ChatRepository =
            instance ?: synchronized(this) {
                instance ?: ChatRepository(
                    context.applicationContext,
                    LanvoxDatabase.get(context).chatMessageDao()
                ).also { repo ->
                    instance = repo
                    repo.scheduleLegacyMigrationIfNeeded()
                }
            }
    }

    private fun scheduleLegacyMigrationIfNeeded() {
        if (!migrationStarted.compareAndSet(false, true)) return
        migrationScope.launch {
            runCatching { migrateLegacyPlaintextIfNeeded() }
        }
    }

    /**
     * Encrypts any legacy plaintext rows left in SQLite from pre-E2EE versions
     * without changing schema or dropping history.
     */
    suspend fun migrateLegacyPlaintextIfNeeded() {
        CryptoManager.ensureInitialized(appContext)
        val rows = dao.getAllMessages()
        for (row in rows) {
            val needsText = !row.text.startsWith(CryptoManager.PREFIX_LOCAL_ENC)
            val needsPeerName = row.peerName != null && !row.peerName.startsWith(CryptoManager.PREFIX_LOCAL_ENC)
            val needsSenderName = row.senderName != null && !row.senderName.startsWith(CryptoManager.PREFIX_LOCAL_ENC)
            val needsReplySender = row.replyToSender != null && !row.replyToSender.startsWith(CryptoManager.PREFIX_LOCAL_ENC)
            val needsReplyText = row.replyToText != null && !row.replyToText.startsWith(CryptoManager.PREFIX_LOCAL_ENC)
            val needsReactions = row.reactions != null && !row.reactions.startsWith(CryptoManager.PREFIX_LOCAL_ENC)
            val needsAttName = row.attachmentName != null && !row.attachmentName.startsWith(CryptoManager.PREFIX_LOCAL_ENC)
            val needsAttMime = row.attachmentMime != null && !row.attachmentMime.startsWith(CryptoManager.PREFIX_LOCAL_ENC)

            if (needsText || needsPeerName || needsSenderName || needsReplySender ||
                needsReplyText || needsReactions || needsAttName || needsAttMime
            ) {
                val encrypted = row.copy(
                    text = CryptoManager.encryptLocalString(appContext, row.text) ?: row.text,
                    peerName = CryptoManager.encryptLocalString(appContext, row.peerName),
                    senderName = CryptoManager.encryptLocalString(appContext, row.senderName),
                    replyToSender = CryptoManager.encryptLocalString(appContext, row.replyToSender),
                    replyToText = CryptoManager.encryptLocalString(appContext, row.replyToText),
                    reactions = CryptoManager.encryptLocalString(appContext, row.reactions),
                    attachmentName = CryptoManager.encryptLocalString(appContext, row.attachmentName),
                    attachmentMime = CryptoManager.encryptLocalString(appContext, row.attachmentMime)
                )
                dao.updateMessage(encrypted)
            }
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
            text = CryptoManager.encryptLocalString(appContext, message.text) ?: "",
            isSent = if (message.isSentByMe) 1 else 0,
            timestamp = message.timestamp,
            peerId = peerId,
            peerName = CryptoManager.encryptLocalString(appContext, peerName),
            senderName = CryptoManager.encryptLocalString(appContext, message.senderName),
            status = message.status.name,
            attachmentName = CryptoManager.encryptLocalString(appContext, message.attachmentName),
            attachmentMime = CryptoManager.encryptLocalString(appContext, message.attachmentMime),
            attachmentSize = message.attachmentSize,
            attachmentPath = message.attachmentPath,
            attachmentState = message.attachmentState.name,
            transferId = message.transferId,
            messageId = message.messageId,
            replyToId = message.replyToMessageId,
            replyToSender = CryptoManager.encryptLocalString(appContext, message.replyToSender),
            replyToText = CryptoManager.encryptLocalString(appContext, message.replyToText),
            reactions = CryptoManager.encryptLocalString(appContext, message.reactionsJson),
            isStarred = if (message.isStarred) 1 else 0,
            isPinned = if (message.isPinned) 1 else 0,
            editedAt = message.editedAt,
            expiresAt = message.expiresAt,
            messageType = message.messageType
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
        return CryptoManager.decryptLocalString(appContext, dao.getPeerName(peerId))
    }

    suspend fun count(peerId: String? = null): Int =
        if (peerId == null) dao.countAll() else dao.countForPeer(peerId)

    /**
     * Toggles or sets a reaction from [actorId] on [message] in Room.
     * Returns `true` if added, `false` if removed, or `null` if the row wasn't found.
     */
    suspend fun setReactionForLocalMessage(
        message: ChatMessage,
        emoji: String,
        actorId: String = ChatMessage.SELF_REACTOR_KEY,
        forceAdd: Boolean? = null
    ): Boolean? {
        val rowId = message.id.removePrefix("room_").toLongOrNull() ?: return null
        val entity = dao.getById(rowId) ?: return null
        val currentReactions = CryptoManager.decryptLocalString(appContext, entity.reactions)
        val (updatedJson, added) = ChatMessage.mutateReactionJson(currentReactions, emoji, actorId, forceAdd)
        val encryptedReactions = CryptoManager.encryptLocalString(appContext, updatedJson)
        dao.updateReactionsById(entity.id, encryptedReactions)
        return added
    }

    suspend fun applyRemoteReaction(
        threadPeerId: String,
        targetMessageId: String,
        emoji: String,
        actorId: String,
        add: Boolean
    ) {
        if (threadPeerId.isBlank() || targetMessageId.isBlank() || emoji.isBlank()) return
        val entity = dao.getByMessageId(threadPeerId, targetMessageId) ?: return
        val currentReactions = CryptoManager.decryptLocalString(appContext, entity.reactions)
        val (updatedJson, _) = ChatMessage.mutateReactionJson(currentReactions, emoji, actorId, forceAdd = add)
        val encryptedReactions = CryptoManager.encryptLocalString(appContext, updatedJson)
        dao.updateReactionsById(entity.id, encryptedReactions)
    }

    suspend fun setStarred(message: ChatMessage, starred: Boolean) {
        val rowId = message.id.removePrefix("room_").toLongOrNull() ?: return
        dao.updateStarredById(rowId, if (starred) 1 else 0)
    }

    fun observePinnedMessage(peerId: String?): Flow<ChatMessage?> {
        if (peerId.isNullOrBlank()) return flowOf(null)
        return dao.observePinnedMessage(peerId).map { it?.toDomain() }
    }

    suspend fun setPinned(message: ChatMessage, pinned: Boolean) {
        val rowId = message.id.removePrefix("room_").toLongOrNull() ?: return
        dao.updatePinnedById(rowId, if (pinned) 1 else 0)
    }

    suspend fun applyRemotePin(threadPeerId: String, targetMessageId: String, pinned: Boolean) {
        if (threadPeerId.isBlank() || targetMessageId.isBlank()) return
        dao.updatePinnedByMessageId(threadPeerId, targetMessageId, if (pinned) 1 else 0)
    }

    suspend fun editLocalMessage(message: ChatMessage, newText: String, editedAt: Long): Boolean {
        val clean = newText.trim()
        if (clean.isEmpty()) return false
        val rowId = message.id.removePrefix("room_").toLongOrNull() ?: return false
        val encrypted = CryptoManager.encryptLocalString(appContext, clean) ?: clean
        return dao.updateEditedTextById(rowId, encrypted, editedAt) > 0
    }

    suspend fun applyRemoteEdit(
        threadPeerId: String,
        targetMessageId: String,
        newText: String,
        editedAt: Long
    ) {
        if (threadPeerId.isBlank() || targetMessageId.isBlank() || newText.isBlank()) return
        val encrypted = CryptoManager.encryptLocalString(appContext, newText.trim()) ?: newText.trim()
        dao.updateEditedTextByMessageId(threadPeerId, targetMessageId, encrypted, editedAt)
    }

    suspend fun voteOnPollLocal(message: ChatMessage, optionIndex: Int): Boolean {
        val rowId = message.id.removePrefix("room_").toLongOrNull() ?: return false
        val entity = dao.getById(rowId) ?: return false
        val rawJson = CryptoManager.decryptLocalString(appContext, entity.text) ?: ""
        val updated = PollData.applyVote(
            rawJson = rawJson,
            optionIndex = optionIndex,
            voterId = PeerRegistry.selfId,
            alsoClearId = ChatMessage.SELF_REACTOR_KEY
        ) ?: return false
        val encrypted = CryptoManager.encryptLocalString(appContext, updated) ?: updated
        return dao.updatePollJsonById(entity.id, encrypted) > 0
    }

    suspend fun applyRemotePollVote(
        threadPeerId: String,
        targetMessageId: String,
        optionIndex: Int,
        voterId: String
    ) {
        if (threadPeerId.isBlank() || targetMessageId.isBlank() || voterId.isBlank()) return
        val entity = dao.getByMessageId(threadPeerId, targetMessageId) ?: return
        val rawJson = CryptoManager.decryptLocalString(appContext, entity.text) ?: ""
        val updated = PollData.applyVote(
            rawJson = rawJson,
            optionIndex = optionIndex,
            voterId = voterId
        ) ?: return
        val encrypted = CryptoManager.encryptLocalString(appContext, updated) ?: updated
        dao.updatePollJsonById(entity.id, encrypted)
    }

    suspend fun updatePeerName(peerId: String, newName: String) {
        if (peerId.isBlank() || newName.isBlank()) return
        val encrypted = CryptoManager.encryptLocalString(appContext, newName.trim()) ?: newName.trim()
        dao.updatePeerNameForPeer(peerId, encrypted)
    }

    suspend fun getAttachmentsForPeer(peerId: String?): List<ChatMessage> {
        if (peerId.isNullOrBlank()) return emptyList()
        return dao.getAttachmentsForPeer(peerId).map { it.toDomain() }
    }

    suspend fun purgeExpiredMessages(nowMs: Long = System.currentTimeMillis()): Int =
        dao.purgeExpiredMessages(nowMs)

    suspend fun insertCallLog(
        peerId: String?,
        peerName: String?,
        callType: String,
        summary: String,
        isSentByMe: Boolean
    ) {
        val pid = peerId?.takeIf { it.isNotBlank() } ?: return
        val resolvedName = peerName?.takeIf { it.isNotBlank() } ?: peerNameFor(pid) ?: pid
        val msg = ChatMessage(
            text = summary,
            isSentByMe = isSentByMe,
            status = MessageStatus.NONE,
            attachmentMime = callType,
            messageId = null,
            messageType = ChatMessage.TYPE_CALL_LOG
        )
        insertMessage(msg, pid, resolvedName)
        ChatBus.notifyMessageSaved(msg, pid)
    }

    suspend fun markThreadDelivered(peerId: String) {
        if (peerId.isBlank()) return
        dao.updateOutgoingStatus(
            peerId = peerId,
            newStatus = MessageStatus.DELIVERED.name,
            fromStatuses = listOf(MessageStatus.SENT.name)
        )
    }

    suspend fun markMessagesDelivered(peerId: String, messageIds: List<String>) {
        if (peerId.isBlank()) return
        val clean = messageIds.filter { it.isNotBlank() }.distinct()
        if (clean.isEmpty()) {
            markThreadDelivered(peerId)
            return
        }
        clean.chunked(500).forEach { chunk ->
            dao.updateOutgoingStatusByMessageIds(
                peerId = peerId,
                messageIds = chunk,
                newStatus = MessageStatus.DELIVERED.name,
                fromStatuses = listOf(MessageStatus.SENT.name)
            )
        }
    }

    suspend fun markThreadRead(peerId: String) {
        if (peerId.isBlank()) return
        dao.updateOutgoingStatus(
            peerId = peerId,
            newStatus = MessageStatus.READ.name,
            fromStatuses = listOf(MessageStatus.SENT.name, MessageStatus.DELIVERED.name)
        )
    }

    suspend fun markMessagesRead(peerId: String, messageIds: List<String>) {
        if (peerId.isBlank()) return
        val clean = messageIds.filter { it.isNotBlank() }.distinct()
        if (clean.isEmpty()) {
            markThreadRead(peerId)
            return
        }
        clean.chunked(500).forEach { chunk ->
            dao.updateOutgoingStatusByMessageIds(
                peerId = peerId,
                messageIds = chunk,
                newStatus = MessageStatus.READ.name,
                fromStatuses = listOf(MessageStatus.SENT.name, MessageStatus.DELIVERED.name)
            )
        }
    }

    suspend fun getUnreadIncomingMessageIds(peerId: String): List<String> {
        if (peerId.isBlank()) return emptyList()
        return dao.getUnreadIncomingMessageIds(peerId)
    }

    suspend fun countUnreadIncoming(peerId: String): Int {
        if (peerId.isBlank()) return 0
        return dao.countUnreadIncoming(peerId)
    }

    suspend fun markIncomingThreadRead(peerId: String) {
        if (peerId.isBlank()) return
        dao.markIncomingThreadRead(peerId)
    }

    suspend fun updateAttachmentState(
        transferId: String,
        attachmentPath: String?,
        state: AttachmentState
    ) {
        if (transferId.isBlank()) return
        dao.updateAttachmentByTransferId(transferId, attachmentPath, state.name)
    }

    suspend fun updateAttachmentStateOnly(
        transferId: String,
        state: AttachmentState
    ) {
        if (transferId.isBlank()) return
        dao.updateAttachmentStateOnlyByTransferId(transferId, state.name)
    }

    suspend fun deleteMessage(message: ChatMessage) {
        val rowId = message.id.removePrefix("room_").toLongOrNull()
        if (rowId != null) {
            dao.deleteById(rowId)
        } else {
            val removed = dao.deleteBySignature(
                timestamp = message.timestamp,
                text = message.text,
                isSent = if (message.isSentByMe) 1 else 0
            )
            if (removed == 0) {
                dao.deleteByTimestampAndSender(
                    timestamp = message.timestamp,
                    isSent = if (message.isSentByMe) 1 else 0
                )
            }
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
        val parsedAttachmentState = runCatching { AttachmentState.valueOf(attachmentState) }
            .getOrElse { AttachmentState.NONE }
        return ChatMessage(
            text = CryptoManager.decryptLocalString(appContext, text) ?: "",
            isSentByMe = isSent == 1,
            timestamp = timestamp,
            status = parsedStatus,
            senderName = CryptoManager.decryptLocalString(appContext, senderName),
            id = "room_$id",
            attachmentName = CryptoManager.decryptLocalString(appContext, attachmentName),
            attachmentMime = CryptoManager.decryptLocalString(appContext, attachmentMime),
            attachmentSize = attachmentSize,
            attachmentPath = attachmentPath,
            attachmentState = parsedAttachmentState,
            transferId = transferId,
            messageId = messageId,
            replyToMessageId = replyToId,
            replyToSender = CryptoManager.decryptLocalString(appContext, replyToSender),
            replyToText = CryptoManager.decryptLocalString(appContext, replyToText),
            reactionsJson = CryptoManager.decryptLocalString(appContext, reactions),
            isStarred = isStarred == 1,
            isPinned = isPinned == 1,
            editedAt = editedAt,
            expiresAt = expiresAt,
            messageType = messageType
        )
    }

    private fun ConversationTuple.toDomain(): Conversation {
        val parsedLastStatus = lastStatus?.let {
            runCatching { MessageStatus.valueOf(it) }.getOrNull()
        } ?: if (lastIsSent == 1) MessageStatus.SENT else MessageStatus.NONE
        return Conversation(
            peerId = peerId,
            peerName = CryptoManager.decryptLocalString(appContext, peerName),
            lastText = CryptoManager.decryptLocalString(appContext, lastText) ?: "",
            lastIsSentByMe = lastIsSent == 1,
            lastTimestamp = lastTimestamp,
            messageCount = messageCount,
            lastStatus = parsedLastStatus,
            unreadCount = unreadCount
        )
    }
}
