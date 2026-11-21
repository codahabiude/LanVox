package com.example.wifip2pchat

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
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

        groupChatRow.setOnClickListener {
            startActivity(
                Intent(this, ChatActivity::class.java)
                    .putExtra(ChatActivity.EXTRA_PEER_ID, Constants.GROUP_PEER_ID)
                    .putExtra(ChatActivity.EXTRA_PEER_NAME, Constants.GROUP_PEER_NAME)
            )
            overridePendingTransition(R.anim.slide_up, android.R.anim.fade_out)
        }

        registryListenerToken = PeerRegistry.addListener { updateGroupChatRow() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                chatRepository.observeConversations().collect { conversations ->
                    val oneToOne = conversations.filter { it.peerId != Constants.GROUP_PEER_ID }
                    chatsAdapter.submit(oneToOne)
                    val hasGroupHistory = conversations.any { it.peerId == Constants.GROUP_PEER_ID }
                    tvEmpty.visibility =
                        if (oneToOne.isEmpty() && !hasGroupHistory) View.VISIBLE else View.GONE
                    rvChats.visibility = if (oneToOne.isEmpty()) View.GONE else View.VISIBLE
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateGroupChatRow()
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
}
