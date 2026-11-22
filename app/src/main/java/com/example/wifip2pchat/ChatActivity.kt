package com.example.wifip2pchat

import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

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
    private lateinit var btnAttach: ImageView
    private lateinit var btnSend: MaterialButton
    private lateinit var btnChatSearch: ImageView
    private lateinit var btnAudioCall: ImageView
    private lateinit var btnVideoCall: ImageView
    private lateinit var tvPeerName: TextView
    private lateinit var tvPeerStatus: TextView
    private lateinit var tvAvatar: TextView
    private lateinit var searchBarContainer: LinearLayout
    private lateinit var etSearchMessages: EditText
    private lateinit var tvSearchCount: TextView
    private lateinit var btnCloseSearch: ImageView
    private lateinit var llSearchEmpty: LinearLayout

    private val messageAdapter = MessageAdapter { msg ->
        val err = FileTransferManager.openAttachment(this, msg)
        if (err != null) {
            Snackbar.make(chatRoot, err, Snackbar.LENGTH_SHORT).show()
        }
    }
    private val chatRepository by lazy { ChatRepository.get(this) }
    private val searchQueryFlow = MutableStateFlow("")

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        if (!MainActivity.isConnectedStatic()) {
            Snackbar.make(
                chatRoot,
                "Not connected — connect to a group on the main screen first",
                Snackbar.LENGTH_LONG
            ).show()
            return@registerForActivityResult
        }
        val pid = peerId ?: return@registerForActivityResult
        val isGroupChat = pid == Constants.GROUP_PEER_ID
        val recipient = if (isGroupChat) Constants.RECIPIENT_BROADCAST else pid
        if (!MainActivity.sendFileToStatic(recipient, pid, peerName, uri)) {
            Snackbar.make(chatRoot, "Failed to start file transfer", Snackbar.LENGTH_SHORT).show()
        }
    }

    private val searchBackCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            setSearchOpen(false)
        }
    }

    private val peerId: String? by lazy { intent.getStringExtra(EXTRA_PEER_ID) }
    private var peerName: String = "Device"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)

        chatRoot = findViewById(R.id.chatRoot)
        rvMessages = findViewById(R.id.rvMessages)
        etMessage = findViewById(R.id.etMessage)
        btnAttach = findViewById(R.id.btnAttach)
        btnSend = findViewById(R.id.btnSend)
        btnChatSearch = findViewById(R.id.btnChatSearch)
        btnAudioCall = findViewById(R.id.btnChatAudioCall)
        btnVideoCall = findViewById(R.id.btnChatVideoCall)
        tvPeerName = findViewById(R.id.tvChatPeerName)
        tvPeerStatus = findViewById(R.id.tvChatPeerStatus)
        tvAvatar = findViewById(R.id.tvChatAvatar)
        searchBarContainer = findViewById(R.id.searchBarContainer)
        etSearchMessages = findViewById(R.id.etSearchMessages)
        tvSearchCount = findViewById(R.id.tvSearchCount)
        btnCloseSearch = findViewById(R.id.btnCloseSearch)
        llSearchEmpty = findViewById(R.id.llSearchEmpty)
        onBackPressedDispatcher.addCallback(this, searchBackCallback)

        peerName = if (peerId == Constants.GROUP_PEER_ID) {
            Constants.GROUP_PEER_NAME
        } else {
            intent.getStringExtra(EXTRA_PEER_NAME)
                ?: PeerRegistry.nameFor(peerId ?: "")
                ?: peerId
                ?: "Device"
        }

        tvPeerName.text = peerName
        tvAvatar.text = peerName.trim().take(1).uppercase()

        if (peerId != Constants.GROUP_PEER_ID && intent.getStringExtra(EXTRA_PEER_NAME) == null) {
            lifecycleScope.launch {
                val resolved = chatRepository.peerNameFor(peerId)
                if (!resolved.isNullOrBlank()) {
                    peerName = resolved
                    tvPeerName.text = resolved
                    tvAvatar.text = resolved.trim().take(1).uppercase()
                }
            }
        }

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
        MessageSwipeHelper.attach(rvMessages, messageAdapter) { deletedMessage ->
            lifecycleScope.launch {
                chatRepository.deleteMessage(deletedMessage)
            }
            Snackbar.make(chatRoot, "Message deleted", Snackbar.LENGTH_LONG)
                .setAction("Undo") {
                    lifecycleScope.launch {
                        chatRepository.insertMessage(deletedMessage, peerId, peerName)
                    }
                }
                .show()
        }

        // Glassmorphism backdrop blur & floating composer/search glow
        GlassEffects.applyAmbientBlur(findViewById(R.id.ambientOrb1), 64f)
        GlassEffects.applyAmbientBlur(findViewById(R.id.ambientOrb2), 72f)
        GlassEffects.applyAmbientBlur(findViewById(R.id.ambientOrb3), 64f)
        GlassEffects.applyGlowElevation(searchBarContainer, R.color.glow_bubble_sent, 10f)
        GlassEffects.applyGlowElevation(findViewById(R.id.composerDock), R.color.glow_bubble_sent, 12f)

        findViewById<ImageView>(R.id.btnChatBack).setOnClickListener { finish() }
        btnChatSearch.setOnClickListener {
            val opening = searchBarContainer.visibility != View.VISIBLE
            setSearchOpen(opening)
        }
        btnCloseSearch.setOnClickListener {
            if (etSearchMessages.text?.isNotEmpty() == true) {
                etSearchMessages.text?.clear()
            } else {
                setSearchOpen(false)
            }
        }
        etSearchMessages.doAfterTextChanged { editable ->
            searchQueryFlow.value = editable?.toString().orEmpty()
        }
        etSearchMessages.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                hideKeyboard(etSearchMessages)
                true
            } else {
                false
            }
        }
        btnAudioCall.setOnClickListener { startCall(video = false) }
        btnVideoCall.setOnClickListener { startCall(video = true) }
        btnAttach.setOnClickListener { pickAndSendFile() }
        btnSend.setOnClickListener { sendCurrentMessage() }

        // Mark this thread as the active one and observe Room Flow
        ChatBus.activeChatPeerId = peerId
        ChatBus.onConnectionStateChanged = {
            runOnUiThread { updateConnectionUi() }
        }

        observeHistory()
        updateConnectionUi()
    }

    override fun onResume() {
        super.onResume()
        ChatBus.activeChatPeerId = peerId
        ChatBus.isChatScreenForeground = true
        updateConnectionUi()
        MainActivity.sendReadReceiptStatic(peerId)
    }

    override fun onPause() {
        ChatBus.isChatScreenForeground = false
        super.onPause()
    }

    override fun onDestroy() {
        ChatBus.isChatScreenForeground = false
        if (ChatBus.activeChatPeerId == peerId) ChatBus.activeChatPeerId = null
        ChatBus.onMessageSaved = null
        ChatBus.onConnectionStateChanged = null
        super.onDestroy()
    }

    // ======================== History ========================

    private fun observeHistory() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    combine(
                        chatRepository.observeHistory(peerId),
                        searchQueryFlow
                    ) { history, query ->
                        Pair(history, query)
                    }.collect { (history, query) ->
                        val trimmed = query.trim()
                        val filtered = if (trimmed.isEmpty()) {
                            history
                        } else {
                            ChatSearchHelper.filter(this@ChatActivity, history, trimmed)
                        }
                        val prevCount = messageAdapter.itemCount
                        messageAdapter.submitMessages(filtered, trimmed)
                        updateSearchUi(filtered.size, trimmed)
                        if (trimmed.isEmpty() && filtered.size > prevCount) {
                            rvMessages.post { scrollToBottom() }
                        }
                    }
                }
                launch {
                    FileTransferTracker.snapshots.collect { snapshots ->
                        messageAdapter.updateTransferSnapshots(snapshots)
                    }
                }
            }
        }
    }

    private fun setSearchOpen(open: Boolean) {
        searchBarContainer.visibility = if (open) View.VISIBLE else View.GONE
        searchBackCallback.isEnabled = open
        val topDp = if (open) 74 else 14
        val topPx = (topDp * resources.displayMetrics.density).toInt()
        rvMessages.setPadding(
            rvMessages.paddingLeft,
            topPx,
            rvMessages.paddingRight,
            rvMessages.paddingBottom
        )
        if (open) {
            etSearchMessages.requestFocus()
            etSearchMessages.post {
                val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.showSoftInput(etSearchMessages, InputMethodManager.SHOW_IMPLICIT)
            }
        } else {
            etSearchMessages.text?.clear()
            searchQueryFlow.value = ""
            hideKeyboard(etSearchMessages)
        }
    }

    private fun hideKeyboard(view: View) {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(view.windowToken, 0)
    }

    private fun updateSearchUi(visibleCount: Int, query: String) {
        if (query.isNotEmpty()) {
            tvSearchCount.visibility = View.VISIBLE
            tvSearchCount.text = if (visibleCount == 1) "1 hit" else "$visibleCount hits"
            llSearchEmpty.visibility = if (visibleCount == 0) View.VISIBLE else View.GONE
        } else {
            tvSearchCount.visibility = View.GONE
            llSearchEmpty.visibility = View.GONE
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
        val message = ChatMessage(text, isSentByMe = true)
        if (!MainActivity.transmitMessageTo(recipient, text, message.messageId)) {
            Snackbar.make(chatRoot, "Failed to send — no live channel", Snackbar.LENGTH_SHORT).show()
            return
        }

        // Persist to this contact's thread (Room Flow updates RecyclerView), clear input
        lifecycleScope.launch {
            chatRepository.insertMessage(message, peerId, peerName)
        }
        etMessage.text.clear()
        if (searchQueryFlow.value.isNotBlank()) {
            setSearchOpen(false)
        }
    }

    private fun pickAndSendFile() {
        if (!MainActivity.isConnectedStatic()) {
            Snackbar.make(
                chatRoot,
                "Not connected — connect to a group on the main screen first",
                Snackbar.LENGTH_LONG
            ).show()
            return
        }
        val pid = peerId ?: run {
            Snackbar.make(chatRoot, "Invalid conversation", Snackbar.LENGTH_SHORT).show()
            return
        }
        val isGroupChat = pid == Constants.GROUP_PEER_ID
        if (!isGroupChat && !MainActivity.currentPeerMatches(pid) && PeerRegistry.nameFor(pid) == null) {
            Snackbar.make(chatRoot, "That peer is not in the group right now", Snackbar.LENGTH_LONG).show()
            return
        }
        filePickerLauncher.launch(arrayOf("*/*"))
    }

    // ======================== Calling ========================

    private fun startCall(video: Boolean) {
        val started = if (video) {
            MainActivity.startVideoCallStatic(peerId, peerName)
        } else {
            MainActivity.startAudioCallStatic(peerId, peerName)
        }
        if (!started) {
            Snackbar.make(chatRoot, "Connect to this device first", Snackbar.LENGTH_SHORT).show()
        }
    }

    // ======================== UI state ========================

    private fun scrollToBottom() {
        rvMessages.smoothScrollToPosition((messageAdapter.itemCount - 1).coerceAtLeast(0))
    }

    private fun updateConnectionUi() {
        val pid = peerId
        val liveWithThisPeer = MainActivity.isConnectedStatic() && (
            pid == Constants.GROUP_PEER_ID ||
                MainActivity.currentPeerMatches(pid) ||
                (pid != null && PeerRegistry.nameFor(pid) != null)
            )
        tvPeerStatus.text = if (pid == Constants.GROUP_PEER_ID) {
            "${PeerRegistry.snapshotIncludingSelf().size} members"
        } else if (liveWithThisPeer) {
            "online · encrypted"
        } else {
            "offline — history only"
        }
        if (liveWithThisPeer && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            MainActivity.sendReadReceiptStatic(pid)
        }
    }
}
