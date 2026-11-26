package com.example.wifip2pchat

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch

/**
 * Chats list — one row per contact device PLUS a "Group Chat" row at the top
 * that broadcasts to every group member.
 */
class ChatsActivity : AppCompatActivity() {

    private lateinit var rootLayout: View
    private lateinit var rvChats: RecyclerView
    private lateinit var tvEmpty: View
    private lateinit var groupChatRow: View
    private lateinit var tvGroupChatSubtitle: TextView

    private val chatRepository by lazy { ChatRepository.get(this) }

    private var registryListenerToken: (() -> Unit)? = null
    private var activeProfileSheet: ProfileBottomSheet? = null

    private val profilePhotoPickerLauncher = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        if (AvatarManager.saveSelfAvatarFromUri(this, uri)) {
            activeProfileSheet?.refreshAvatarPreview()
            MainActivity.broadcastSelfProfileAndAvatarStatic()
            Snackbar.make(rootLayout, R.string.profile_photo_updated_msg, Snackbar.LENGTH_SHORT).show()
        }
    }

    private val chatsAdapter = ChatsAdapter(
        onClick = { conversation -> openChat(conversation) },
        onLongClick = { conversation -> confirmDelete(conversation) }
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chats)

        rootLayout = findViewById(R.id.chatsRoot)
        rvChats = findViewById(R.id.rvChats)
        tvEmpty = findViewById(R.id.tvChatsEmpty)
        groupChatRow = findViewById(R.id.groupChatRow)
        tvGroupChatSubtitle = findViewById(R.id.tvGroupChatSubtitle)

        rvChats.layoutManager = LinearLayoutManager(this)
        rvChats.adapter = chatsAdapter

        findViewById<ImageView>(R.id.btnChatsBack).setOnClickListener { finish() }
        findViewById<ImageView>(R.id.btnChatsMore).setOnClickListener {
            lifecycleScope.launch {
                val total = chatRepository.count()
                MainActionsBottomSheet.show(
                    context = this@ChatsActivity,
                    storedMessageCount = total,
                    isConnected = MainActivity.isConnectedStatic(),
                    onExportChats = null,
                    onOpenProfile = { openProfileSheet() },
                    onOpenEncryption = { EncryptionInfoBottomSheet.show(this@ChatsActivity, null, null) },
                    onClearAllHistory = { confirmClearAll(total) }
                )
            }
        }

        groupChatRow.setOnClickListener {
            startActivity(
                Intent(this, ChatActivity::class.java)
                    .putExtra(ChatActivity.EXTRA_PEER_ID, Constants.GROUP_PEER_ID)
                    .putExtra(ChatActivity.EXTRA_PEER_NAME, Constants.GROUP_PEER_NAME)
            )
            overridePendingTransition(R.anim.slide_up, android.R.anim.fade_out)
        }

        registryListenerToken = PeerRegistry.addListener {
            updateGroupChatRow()
            submitMergedConversations(latestStoredConversations)
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    chatRepository.observeConversations().collect { conversations ->
                        latestStoredConversations = conversations
                        submitMergedConversations(conversations)
                    }
                }
                launch {
                    ChatBus.typingPeers.collect { typingMap ->
                        chatsAdapter.updateTyping(typingMap)
                    }
                }
                launch {
                    AvatarManager.avatarVersion.collect {
                        chatsAdapter.notifyDataSetChanged()
                    }
                }
            }
        }
    }

    private var latestStoredConversations: List<Conversation> = emptyList()

    private fun submitMergedConversations(conversations: List<Conversation>) {
        val oneToOne = conversations.filter { it.peerId != Constants.GROUP_PEER_ID }.toMutableList()
        val existingIds = oneToOne.mapTo(HashSet()) { it.peerId }
        val now = System.currentTimeMillis()
        PeerRegistry.snapshot().forEach { peer ->
            if (!existingIds.contains(peer.id)) {
                val transportLabel = if (peer.transport == "lan") "Wi-Fi mesh" else "Wi-Fi Direct"
                oneToOne += Conversation(
                    peerId = peer.id,
                    peerName = peer.name,
                    lastText = "Online ($transportLabel) · tap to chat or call",
                    lastIsSentByMe = false,
                    lastTimestamp = now,
                    messageCount = 0
                )
            }
        }
        chatsAdapter.submit(oneToOne)
        val hasGroupHistory = conversations.any { it.peerId == Constants.GROUP_PEER_ID }
        tvEmpty.visibility =
            if (oneToOne.isEmpty() && !hasGroupHistory) View.VISIBLE else View.GONE
        rvChats.visibility = if (oneToOne.isEmpty()) View.GONE else View.VISIBLE
    }

    override fun onResume() {
        super.onResume()
        updateGroupChatRow()
        submitMergedConversations(latestStoredConversations)
    }

    override fun onDestroy() {
        registryListenerToken?.let { PeerRegistry.removeListener(it) }
        registryListenerToken = null
        super.onDestroy()
    }

    private fun updateGroupChatRow() {
        val memberCount = PeerRegistry.snapshotIncludingSelf().size
        tvGroupChatSubtitle.text = if (memberCount > 1) {
            "$memberCount members · broadcast"
        } else {
            "Broadcast to all group members"
        }
    }

    private fun openChat(conversation: Conversation) {
        val name = conversation.peerName?.takeIf { it.isNotBlank() } ?: conversation.peerId
        startActivity(
            Intent(this, ChatActivity::class.java)
                .putExtra(ChatActivity.EXTRA_PEER_ID, conversation.peerId)
                .putExtra(ChatActivity.EXTRA_PEER_NAME, name)
        )
        overridePendingTransition(R.anim.slide_up, android.R.anim.fade_out)
    }

    private fun confirmDelete(conversation: Conversation) {
        if (conversation.messageCount == 0) {
            openChat(conversation)
            return
        }
        val name = conversation.peerName?.takeIf { it.isNotBlank() } ?: conversation.peerId
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete conversation?")
            .setMessage("All ${conversation.messageCount} messages with $name will be permanently deleted from this phone.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch {
                    chatRepository.clearConversation(conversation.peerId)
                    Snackbar.make(rootLayout, "Conversation deleted", Snackbar.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    private fun openProfileSheet() {
        val sheet = ProfileBottomSheet(
            context = this,
            onRequestPickPhoto = {
                profilePhotoPickerLauncher.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
            },
            onPhotoRemoved = {
                MainActivity.broadcastSelfProfileAndAvatarStatic(includeAvatar = true)
                Snackbar.make(rootLayout, R.string.profile_photo_removed_msg, Snackbar.LENGTH_SHORT).show()
            },
            onProfileSaved = { displayName, _, _ ->
                PeerRegistry.selfName = displayName
                MainActivity.broadcastSelfProfileAndAvatarStatic()
                Snackbar.make(rootLayout, R.string.profile_updated_msg, Snackbar.LENGTH_SHORT).show()
            }
        )
        activeProfileSheet = sheet
        sheet.show()
    }

    private fun confirmClearAll(totalCount: Int) {
        if (totalCount == 0) {
            Snackbar.make(rootLayout, "No saved messages to delete", Snackbar.LENGTH_SHORT).show()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete all chat history?")
            .setMessage("All $totalCount stored messages will be permanently deleted from this phone.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch {
                    chatRepository.clearAll()
                    Snackbar.make(rootLayout, "All chat history deleted", Snackbar.LENGTH_SHORT).show()
                }
            }
            .show()
    }
}
