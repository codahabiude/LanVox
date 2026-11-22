package com.example.wifip2pchat

import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
 *  - Live-updates when new messages, reactions, replies, receipts, or typing arrive
 *  - Supports IG double-tap ❤️, long-press reactions/actions, swipe-right quote-reply,
 *    starred message filtering, clipboard copy, and message forwarding
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
    private lateinit var tvFilterStarred: TextView
    private lateinit var tvSearchCount: TextView
    private lateinit var btnCloseSearch: ImageView
    private lateinit var llSearchEmpty: LinearLayout
    private lateinit var tvTypingPill: TextView
    private lateinit var layoutReplyPreview: View
    private lateinit var tvReplyPreviewSender: TextView
    private lateinit var tvReplyPreviewText: TextView
    private lateinit var btnCancelReply: View

    private val messageAdapter: MessageAdapter = MessageAdapter(
        onAttachmentClick = { msg ->
            val err = FileTransferManager.openAttachment(this, msg)
            if (err != null) {
                Snackbar.make(chatRoot, err, Snackbar.LENGTH_SHORT).show()
            }
        },
        onDoubleTapReact = { msg -> toggleReaction(msg, "❤️", forceAdd = null) },
        onLongPressMessage = { msg -> showMessageActions(msg) },
        onReactionChipClick = { msg, emoji -> toggleReaction(msg, emoji, forceAdd = null) },
        onQuoteClick = { targetId ->
            ChatFeatureActions.scrollToQuotedMessage(rvMessages, messageAdapter, targetId) {
                Snackbar.make(chatRoot, "Original message not in view", Snackbar.LENGTH_SHORT).show()
            }
        }
    )

    private val chatRepository by lazy { ChatRepository.get(this) }
    private val searchQueryFlow = MutableStateFlow("")
    private val starredOnlyFlow = MutableStateFlow(false)
    private var replyingToMessage: ChatMessage? = null

    private val typingHandler = Handler(Looper.getMainLooper())
    private var lastTypingSentMs = 0L
    private var isTypingSent = false
    private val stopTypingRunnable = Runnable { emitTypingState(false) }

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
        tvFilterStarred = findViewById(R.id.tvFilterStarred)
        tvSearchCount = findViewById(R.id.tvSearchCount)
        btnCloseSearch = findViewById(R.id.btnCloseSearch)
        llSearchEmpty = findViewById(R.id.llSearchEmpty)
        tvTypingPill = findViewById(R.id.tvTypingPill)
        layoutReplyPreview = findViewById(R.id.layoutReplyPreview)
        tvReplyPreviewSender = findViewById(R.id.tvReplyPreviewSender)
        tvReplyPreviewText = findViewById(R.id.tvReplyPreviewText)
        btnCancelReply = findViewById(R.id.btnCancelReply)
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
        MessageSwipeHelper.attach(
            recyclerView = rvMessages,
            adapter = messageAdapter,
            onReplyMessage = { msg -> startReplyTo(msg) }
        ) { deletedMessage ->
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
            if (etSearchMessages.text?.isNotEmpty() == true || starredOnlyFlow.value) {
                etSearchMessages.text?.clear()
                starredOnlyFlow.value = false
                ChatFeatureActions.styleStarredFilterChip(this, tvFilterStarred, false)
            } else {
                setSearchOpen(false)
            }
        }
        tvFilterStarred.setOnClickListener {
            val next = !starredOnlyFlow.value
            starredOnlyFlow.value = next
            ChatFeatureActions.styleStarredFilterChip(this, tvFilterStarred, next)
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
        btnCancelReply.setOnClickListener { cancelReply() }
        btnAudioCall.setOnClickListener { startCall(video = false) }
        btnVideoCall.setOnClickListener { startCall(video = true) }
        btnAttach.setOnClickListener { pickAndSendFile() }
        btnSend.setOnClickListener { sendCurrentMessage() }
        etMessage.doAfterTextChanged { editable ->
            val hasText = !editable?.toString().isNullOrBlank()
            if (hasText) {
                val now = System.currentTimeMillis()
                if (!isTypingSent || now - lastTypingSentMs >= 1_800L) {
                    emitTypingState(true)
                }
                typingHandler.removeCallbacks(stopTypingRunnable)
                typingHandler.postDelayed(stopTypingRunnable, 2_500L)
            } else if (isTypingSent) {
                typingHandler.removeCallbacks(stopTypingRunnable)
                emitTypingState(false)
            }
        }

        ChatFeatureActions.styleStarredFilterChip(this, tvFilterStarred, false)

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
        typingHandler.removeCallbacks(stopTypingRunnable)
        if (isTypingSent) emitTypingState(false)
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
                        searchQueryFlow,
                        starredOnlyFlow
                    ) { history, query, starredOnly ->
                        Triple(history, query, starredOnly)
                    }.collect { (history, query, starredOnly) ->
                        val trimmed = query.trim()
                        val starFiltered = if (starredOnly) history.filter { it.isStarred } else history
                        val filtered = if (trimmed.isEmpty()) {
                            starFiltered
                        } else {
                            ChatSearchHelper.filter(this@ChatActivity, starFiltered, trimmed)
                        }
                        val prevCount = messageAdapter.itemCount
                        messageAdapter.submitMessages(filtered, trimmed)
                        updateSearchUi(filtered.size, trimmed, starredOnly)
                        if (trimmed.isEmpty() && !starredOnly && filtered.size > prevCount) {
                            rvMessages.post { scrollToBottom() }
                        }
                    }
                }
                launch {
                    FileTransferTracker.snapshots.collect { snapshots ->
                        messageAdapter.updateTransferSnapshots(snapshots)
                    }
                }
                launch {
                    ChatBus.typingPeers.collect { typingMap ->
                        val tid = peerId ?: return@collect
                        val label = ChatBus.typingLabelForThread(tid, typingMap)
                        if (label != null) {
                            tvTypingPill.text = label
                            tvTypingPill.visibility = View.VISIBLE
                        } else {
                            tvTypingPill.visibility = View.GONE
                        }
                        updateConnectionUi()
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
            starredOnlyFlow.value = false
            ChatFeatureActions.styleStarredFilterChip(this, tvFilterStarred, false)
            hideKeyboard(etSearchMessages)
        }
    }

    private fun hideKeyboard(view: View) {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(view.windowToken, 0)
    }

    private fun updateSearchUi(visibleCount: Int, query: String, starredOnly: Boolean) {
        if (query.isNotEmpty() || starredOnly) {
            tvSearchCount.visibility = View.VISIBLE
            tvSearchCount.text = if (visibleCount == 1) "1 hit" else "$visibleCount hits"
            llSearchEmpty.visibility = if (visibleCount == 0) View.VISIBLE else View.GONE
        } else {
            tvSearchCount.visibility = View.GONE
            llSearchEmpty.visibility = View.GONE
        }
    }

    // ======================== Actions, Reactions, Replies ========================

    private fun startReplyTo(message: ChatMessage) {
        replyingToMessage = message
        val senderLabel = if (message.isSentByMe) {
            "You"
        } else {
            message.senderName?.takeIf { it.isNotBlank() } ?: peerName
        }
        tvReplyPreviewSender.text = getString(R.string.replying_to_fmt, senderLabel)
        tvReplyPreviewText.text = message.quoteSnippet()
        layoutReplyPreview.visibility = View.VISIBLE
        etMessage.requestFocus()
        val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.showSoftInput(etMessage, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun cancelReply() {
        replyingToMessage = null
        layoutReplyPreview.visibility = View.GONE
    }

    private fun showMessageActions(message: ChatMessage) {
        MessageActionBottomSheet.show(
            context = this,
            message = message,
            onReact = { emoji -> toggleReaction(message, emoji, forceAdd = null) },
            onReply = { startReplyTo(message) },
            onCopy = { ChatFeatureActions.copyMessageText(this, chatRoot, message) },
            onToggleStar = { toggleStar(message) },
            onForward = {
                ChatFeatureActions.showForwardDialog(
                    context = this,
                    scope = lifecycleScope,
                    chatRepository = chatRepository,
                    message = message
                ) { feedback ->
                    Snackbar.make(chatRoot, feedback, Snackbar.LENGTH_SHORT).show()
                }
            },
            onDelete = {
                lifecycleScope.launch { chatRepository.deleteMessage(message) }
                Snackbar.make(chatRoot, "Message deleted", Snackbar.LENGTH_LONG)
                    .setAction("Undo") {
                        lifecycleScope.launch {
                            chatRepository.insertMessage(message, peerId, peerName)
                        }
                    }
                    .show()
            }
        )
    }

    private fun toggleReaction(message: ChatMessage, emoji: String, forceAdd: Boolean?) {
        val pid = peerId ?: return
        val isGroup = pid == Constants.GROUP_PEER_ID
        val recipient = if (isGroup) Constants.RECIPIENT_BROADCAST else pid
        lifecycleScope.launch {
            val added = chatRepository.setReactionForLocalMessage(
                message = message,
                emoji = emoji,
                forceAdd = forceAdd
            ) ?: return@launch
            val targetMsgId = message.messageId ?: return@launch
            MainActivity.sendReactionTo(recipient, targetMsgId, emoji, added, isGroup)
        }
    }

    private fun toggleStar(message: ChatMessage) {
        val next = !message.isStarred
        lifecycleScope.launch {
            chatRepository.setStarred(message, next)
            Snackbar.make(
                chatRoot,
                if (next) R.string.msg_starred else R.string.msg_unstarred,
                Snackbar.LENGTH_SHORT
            ).show()
        }
    }

    private fun emitTypingState(isTyping: Boolean) {
        val pid = peerId ?: return
        val isGroup = pid == Constants.GROUP_PEER_ID
        val recipient = if (isGroup) Constants.RECIPIENT_BROADCAST else pid
        isTypingSent = isTyping
        if (isTyping) lastTypingSentMs = System.currentTimeMillis()
        MainActivity.sendTypingTo(recipient, isTyping, isGroup)
    }

    // ======================== Sending ========================

    private fun sendCurrentMessage() {
        val text = etMessage.text.toString().trim()
        if (text.isEmpty()) return

        typingHandler.removeCallbacks(stopTypingRunnable)
        if (isTypingSent) emitTypingState(false)

        if (!MainActivity.isConnectedStatic()) {
            Snackbar.make(
                chatRoot,
                "Not connected — connect to a group on the main screen first",
                Snackbar.LENGTH_LONG
            ).show()
            return
        }

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
            if (PeerRegistry.nameFor(pid) == null) {
                Snackbar.make(
                    chatRoot,
                    "That peer is not in the group right now",
                    Snackbar.LENGTH_LONG
                ).show()
                return
            }
        }

        val quote = replyingToMessage
        val replySender = quote?.let {
            if (it.isSentByMe) "You" else (it.senderName?.takeIf { s -> s.isNotBlank() } ?: peerName)
        }
        val replySnippet = quote?.quoteSnippet()
        val replyTargetId = quote?.let { it.messageId ?: it.id }

        val message = ChatMessage(
            text = text,
            isSentByMe = true,
            replyToMessageId = replyTargetId,
            replyToSender = replySender,
            replyToText = replySnippet
        )
        val transmitted = if (quote != null) {
            MainActivity.transmitReplyTo(recipient, replyTargetId, replySender, replySnippet, text, message.messageId)
        } else {
            MainActivity.transmitMessageTo(recipient, text, message.messageId)
        }
        if (!transmitted) {
            Snackbar.make(chatRoot, "Failed to send — no live channel", Snackbar.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            chatRepository.insertMessage(message, peerId, peerName)
        }
        cancelReply()
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
        val typingLabel = pid?.let { ChatBus.typingLabelForThread(it) }
        val liveWithThisPeer = MainActivity.isConnectedStatic() && (
            pid == Constants.GROUP_PEER_ID ||
                MainActivity.currentPeerMatches(pid) ||
                (pid != null && PeerRegistry.nameFor(pid) != null)
            )
        tvPeerStatus.text = when {
            typingLabel != null -> typingLabel
            pid == Constants.GROUP_PEER_ID -> "${PeerRegistry.snapshotIncludingSelf().size} members"
            liveWithThisPeer -> "online · encrypted"
            else -> "offline — history only"
        }
        if (liveWithThisPeer && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            MainActivity.sendReadReceiptStatic(pid)
        }
    }
}
