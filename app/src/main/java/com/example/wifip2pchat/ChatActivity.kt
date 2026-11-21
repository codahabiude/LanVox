package com.example.wifip2pchat

import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One conversation thread with one contact device — or the Group Chat
 * broadcast thread (peerId == GROUP_PEER_ID).
 *
 *  - Shows only the messages stored for this peer (per-contact history)
 *  - Live-updates when new messages arrive for this peer (via ChatBus)
 *  - Audio/video call buttons in the top bar (hidden in Group Chat)
 */
class ChatActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PEER_ID = "extra_peer_id"
        const val EXTRA_PEER_NAME = "extra_peer_name"
    }

    private lateinit var chatRoot: LinearLayout
    private lateinit var rvMessages: RecyclerView
    private lateinit var etMessage: EditText
    private lateinit var btnSend: MaterialButton
    private lateinit var btnAudioCall: ImageView
    private lateinit var btnVideoCall: ImageView
    private lateinit var tvPeerName: TextView
    private lateinit var tvPeerStatus: TextView
    private lateinit var tvAvatar: TextView

    private val messageAdapter = MessageAdapter()
    private val chatDb by lazy { ChatDatabase.get(this) }

    private val peerId: String? by lazy { intent.getStringExtra(EXTRA_PEER_ID) }
    private val peerName: String by lazy {
        if (peerId == Constants.GROUP_PEER_ID) {
            return@lazy Constants.GROUP_PEER_NAME
        }
        intent.getStringExtra(EXTRA_PEER_NAME)
            ?: chatDb.peerNameFor(peerId)
            ?: PeerRegistry.nameFor(peerId ?: "")
            ?: peerId
            ?: "Device"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)

        chatRoot = findViewById(R.id.chatRoot)
        rvMessages = findViewById(R.id.rvMessages)
        etMessage = findViewById(R.id.etMessage)
        btnSend = findViewById(R.id.btnSend)
        btnAudioCall = findViewById(R.id.btnChatAudioCall)
        btnVideoCall = findViewById(R.id.btnChatVideoCall)
        tvPeerName = findViewById(R.id.tvChatPeerName)
        tvPeerStatus = findViewById(R.id.tvChatPeerStatus)
        tvAvatar = findViewById(R.id.tvChatAvatar)

        tvPeerName.text = peerName
        tvAvatar.text = peerName.trim().take(1).uppercase()

        // Group Chat: different avatar, hide the call buttons (calls are 1:1)
        val isGroupChat = peerId == Constants.GROUP_PEER_ID
        if (isGroupChat) {
            tvAvatar.text = "#"
            tvPeerStatus.text = "${PeerRegistry.snapshotIncludingSelf().size} members"
            btnAudioCall.visibility = View.GONE
            btnVideoCall.visibility = View.GONE
        }

        val layoutManager = LinearLayoutManager(this)
        layoutManager.stackFromEnd = true
        rvMessages.layoutManager = layoutManager
        rvMessages.adapter = messageAdapter

        findViewById<ImageView>(R.id.btnChatBack).setOnClickListener { finish() }
        btnAudioCall.setOnClickListener { startCall(video = false) }
        btnVideoCall.setOnClickListener { startCall(video = true) }
        btnSend.setOnClickListener { sendCurrentMessage() }

        // Mark this thread as the active one and listen for live events
        ChatBus.activeChatPeerId = peerId
        ChatBus.onMessageSaved = { message, savedPeerId ->
            if (savedPeerId == peerId) {
                runOnUiThread {
                    messageAdapter.addMessage(message)
                    scrollToBottom()
                }
            }
        }
        ChatBus.onConnectionStateChanged = {
            runOnUiThread { updateConnectionUi() }
        }

        loadHistory()
        updateConnectionUi()
    }

    override fun onResume() {
        super.onResume()
        ChatBus.activeChatPeerId = peerId
        updateConnectionUi()
    }

    override fun onDestroy() {
        if (ChatBus.activeChatPeerId == peerId) ChatBus.activeChatPeerId = null
        ChatBus.onMessageSaved = null
        ChatBus.onConnectionStateChanged = null
        super.onDestroy()
    }

    // ======================== History ========================

    private fun loadHistory() {
        lifecycleScope.launch {
            val history = withContext(Dispatchers.IO) { chatDb.loadHistory(peerId) }
            messageAdapter.setMessages(history)
            rvMessages.post { scrollToBottom() }
        }
    }

    // ======================== Sending ========================

    private fun sendCurrentMessage() {
        val text = etMessage.text.toString().trim()
        if (text.isEmpty()) return

        if (!MainActivity.isConnectedStatic()) {
            Snackbar.make(chatRoot,
                "Not connected — connect to a group on the main screen first",
                Snackbar.LENGTH_LONG).show()
            return
        }

        // Route the message to the right recipient:
        //  - Group chat thread → broadcast to everyone
        //  - 1:1 thread → addressed to that peer's id, relayed by the GO
        val isGroupChat = peerId == Constants.GROUP_PEER_ID
        val pid = peerId
        val recipient: String = when {
            isGroupChat -> Constants.RECIPIENT_BROADCAST
            pid != null -> pid
            else -> {
                Snackbar.make(chatRoot, "Invalid conversation", Snackbar.LENGTH_SHORT).show()
                return
            }
        }

        if (!isGroupChat && pid != null && !MainActivity.currentPeerMatches(pid)) {
            // Connected to a different peer — that's OK in a multi-peer group
            // (the GO relays). Just make sure the recipient is a known peer.
            if (PeerRegistry.nameFor(pid) == null) {
                Snackbar.make(chatRoot,
                    "That peer is not in the group right now",
                    Snackbar.LENGTH_LONG).show()
                return
            }
        }
        if (!MainActivity.transmitMessageTo(recipient, text)) {
            Snackbar.make(chatRoot, "Failed to send — no live channel", Snackbar.LENGTH_SHORT).show()
            return
        }

        // Show, persist to this contact's thread, clear input
        val message = ChatMessage(text, isSentByMe = true)
        messageAdapter.addMessage(message)
        scrollToBottom()
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                chatDb.insertMessage(message, peerId, peerName)
            }
        }
        etMessage.text.clear()
    }

    // ======================== Calling ========================

    private fun startCall(video: Boolean) {
        val started = if (video) MainActivity.startVideoCallStatic()
                      else MainActivity.startAudioCallStatic()
        if (!started) {
            Snackbar.make(chatRoot, "Connect to this device first", Snackbar.LENGTH_SHORT).show()
        }
    }

    // ======================== UI state ========================

    private fun scrollToBottom() {
        rvMessages.smoothScrollToPosition((messageAdapter.itemCount - 1).coerceAtLeast(0))
    }

    private fun updateConnectionUi() {
        val liveWithThisPeer = MainActivity.isConnectedStatic() &&
            (peerId == Constants.GROUP_PEER_ID || MainActivity.currentPeerMatches(peerId))
        tvPeerStatus.text = if (peerId == Constants.GROUP_PEER_ID) {
            "${PeerRegistry.snapshotIncludingSelf().size} members"
        } else if (liveWithThisPeer) {
            "online · encrypted"
        } else {
            "offline — history only"
        }
    }
}
