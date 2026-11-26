package com.example.wifip2pchat

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
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
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
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
    private lateinit var composerDock: View
    private lateinit var etMessage: EditText
    private lateinit var btnAttach: ImageView
    private lateinit var btnVoiceNote: ImageView
    private lateinit var btnSend: MaterialButton
    private lateinit var btnChatEphemeral: ImageView
    private lateinit var btnChatSearch: ImageView
    private lateinit var btnAudioCall: ImageView
    private lateinit var btnVideoCall: ImageView
    private lateinit var tvPeerName: TextView
    private lateinit var tvPeerStatus: TextView
    private lateinit var tvAvatar: TextView
    private lateinit var layoutPinnedBanner: View
    private lateinit var tvPinnedSender: TextView
    private lateinit var tvPinnedText: TextView
    private lateinit var btnUnpinBanner: View
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
    private lateinit var layoutVoiceRecording: View
    private lateinit var tvVoiceRecordTimer: TextView
    private lateinit var progressVoiceAmp: LinearProgressIndicator
    private lateinit var btnVoiceCancel: View
    private lateinit var btnVoiceSend: View

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
        },
        onCallBackClick = { msg ->
            startCall(video = msg.attachmentMime == Constants.CALL_TYPE_VIDEO)
        }
    )

    private val chatRepository by lazy { ChatRepository.get(this) }
    private val searchQueryFlow = MutableStateFlow("")
    private val starredOnlyFlow = MutableStateFlow(false)
    private var replyingToMessage: ChatMessage? = null
    private var editingMessage: ChatMessage? = null
    private var currentPinnedMessage: ChatMessage? = null

    private val voiceRecorder by lazy { VoiceNoteManager.Recorder(this) }
    private val voiceTimerHandler = Handler(Looper.getMainLooper())
    private val voiceTimerRunnable = object : Runnable {
        override fun run() {
            if (!voiceRecorder.isRecording) return
            val elapsed = voiceRecorder.elapsedMs().toInt()
            tvVoiceRecordTimer.text = "REC ${VoiceNoteManager.formatDurationMs(elapsed)}"
            progressVoiceAmp.setProgressCompat(voiceRecorder.currentAmplitudePercent(), true)
            voiceTimerHandler.postDelayed(this, 90L)
        }
    }

    private val voiceAudioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startVoiceRecording()
        } else {
            Snackbar.make(chatRoot, R.string.voice_permission_rationale, Snackbar.LENGTH_LONG)
                .setAction("Settings") {
                    runCatching {
                        startActivity(
                            Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.fromParts("package", packageName, null)
                            )
                        )
                    }
                }
                .show()
        }
    }

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
        composerDock = findViewById(R.id.composerDock)
        etMessage = findViewById(R.id.etMessage)
        btnAttach = findViewById(R.id.btnAttach)
        btnVoiceNote = findViewById(R.id.btnVoiceNote)
        btnSend = findViewById(R.id.btnSend)
        btnChatEphemeral = findViewById(R.id.btnChatEphemeral)
        btnChatSearch = findViewById(R.id.btnChatSearch)
        btnAudioCall = findViewById(R.id.btnChatAudioCall)
        btnVideoCall = findViewById(R.id.btnChatVideoCall)
        tvPeerName = findViewById(R.id.tvChatPeerName)
        tvPeerStatus = findViewById(R.id.tvChatPeerStatus)
        tvAvatar = findViewById(R.id.tvChatAvatar)
        layoutPinnedBanner = findViewById(R.id.layoutPinnedBanner)
        tvPinnedSender = findViewById(R.id.tvPinnedSender)
        tvPinnedText = findViewById(R.id.tvPinnedText)
        btnUnpinBanner = findViewById(R.id.btnUnpinBanner)
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
        layoutVoiceRecording = findViewById(R.id.layoutVoiceRecording)
        tvVoiceRecordTimer = findViewById(R.id.tvVoiceRecordTimer)
        progressVoiceAmp = findViewById(R.id.progressVoiceAmp)
        btnVoiceCancel = findViewById(R.id.btnVoiceCancel)
        btnVoiceSend = findViewById(R.id.btnVoiceSend)
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
        btnCancelReply.setOnClickListener { cancelReplyOrEdit() }
        layoutPinnedBanner.setOnClickListener {
            val targetId = currentPinnedMessage?.let { it.messageId ?: it.id } ?: return@setOnClickListener
            ChatFeatureActions.scrollToQuotedMessage(rvMessages, messageAdapter, targetId) {
                Snackbar.make(chatRoot, "Pinned message not in view", Snackbar.LENGTH_SHORT).show()
            }
        }
        btnUnpinBanner.setOnClickListener {
            currentPinnedMessage?.let { togglePin(it) }
        }
        btnChatEphemeral.setOnClickListener { openEphemeralTimerPicker() }
        btnAudioCall.setOnClickListener { startCall(video = false) }
        btnVideoCall.setOnClickListener { startCall(video = true) }
        btnAttach.setOnClickListener { pickAndSendFile() }
        btnVoiceNote.setOnClickListener { onVoiceNoteButtonClicked() }
        btnVoiceCancel.setOnClickListener { cancelVoiceRecording() }
        btnVoiceSend.setOnClickListener { finishAndSendVoiceRecording() }
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
        updateEphemeralIconUi()
        updateConnectionUi()
    }

    override fun onResume() {
        super.onResume()
        ChatBus.activeChatPeerId = peerId
        ChatBus.isChatScreenForeground = true
        updateEphemeralIconUi()
        updateConnectionUi()
        MainActivity.sendReadReceiptStatic(peerId)
    }

    override fun onPause() {
        typingHandler.removeCallbacks(stopTypingRunnable)
        if (isTypingSent) emitTypingState(false)
        if (voiceRecorder.isRecording) cancelVoiceRecording()
        ChatBus.isChatScreenForeground = false
        super.onPause()
    }

    override fun onDestroy() {
        voiceTimerHandler.removeCallbacks(voiceTimerRunnable)
        voiceRecorder.cancel()
        VoiceNoteManager.stopPlayback()
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
                    chatRepository.observePinnedMessage(peerId).collect { pinned ->
                        currentPinnedMessage = pinned
                        updatePinnedBannerUi(pinned)
                    }
                }
                launch {
                    FileTransferTracker.snapshots.collect { snapshots ->
                        messageAdapter.updateTransferSnapshots(snapshots)
                    }
                }
                launch {
                    VoiceNoteManager.playbackState.collect { playbackState ->
                        messageAdapter.updateVoicePlayback(playbackState)
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
                launch {
                    while (isActive) {
                        chatRepository.purgeExpiredMessages()
                        delay(4_000L)
                    }
                }
            }
        }
    }

    private fun updatePinnedBannerUi(pinned: ChatMessage?) {
        if (pinned == null) {
            layoutPinnedBanner.visibility = View.GONE
            return
        }
        val senderLabel = if (pinned.isSentByMe) {
            "Pinned · You"
        } else {
            val sender = pinned.senderName?.takeIf { it.isNotBlank() } ?: peerName
            "Pinned · $sender"
        }
        tvPinnedSender.text = senderLabel
        tvPinnedText.text = pinned.quoteSnippet()
        layoutPinnedBanner.visibility = View.VISIBLE
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
        editingMessage = null
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

    private fun startEditMessage(message: ChatMessage) {
        replyingToMessage = null
        editingMessage = message
        tvReplyPreviewSender.text = getString(R.string.editing_banner_title)
        tvReplyPreviewText.text = message.quoteSnippet()
        layoutReplyPreview.visibility = View.VISIBLE
        etMessage.setText(message.text)
        etMessage.setSelection(message.text.length)
        etMessage.requestFocus()
        val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.showSoftInput(etMessage, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun cancelReplyOrEdit() {
        val wasEditing = editingMessage != null
        replyingToMessage = null
        editingMessage = null
        layoutReplyPreview.visibility = View.GONE
        if (wasEditing) {
            etMessage.text.clear()
        }
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
            onTogglePin = { togglePin(message) },
            onEdit = { startEditMessage(message) },
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

    private fun togglePin(message: ChatMessage) {
        val pid = peerId ?: return
        val isGroup = pid == Constants.GROUP_PEER_ID
        val recipient = if (isGroup) Constants.RECIPIENT_BROADCAST else pid
        val next = !message.isPinned
        lifecycleScope.launch {
            chatRepository.setPinned(message, next)
            val targetMsgId = message.messageId
            if (!targetMsgId.isNullOrBlank()) {
                MainActivity.sendPinTo(recipient, targetMsgId, next, isGroup)
            }
            Snackbar.make(
                chatRoot,
                if (next) R.string.msg_pinned else R.string.msg_unpinned,
                Snackbar.LENGTH_SHORT
            ).show()
        }
    }

    private fun openEphemeralTimerPicker() {
        val pid = peerId ?: return
        EphemeralPrefs.showPickerDialog(this, pid) { chosen ->
            updateEphemeralIconUi()
            updateConnectionUi()
            val label = EphemeralPrefs.formatShortLabel(chosen)
            val msg = if (label != null) {
                getString(R.string.ephemeral_enabled_fmt, label)
            } else {
                getString(R.string.ephemeral_disabled_msg)
            }
            Snackbar.make(chatRoot, msg, Snackbar.LENGTH_SHORT).show()
        }
    }

    private fun updateEphemeralIconUi() {
        val ttl = EphemeralPrefs.getTtlSeconds(this, peerId)
        val tintColor = ContextCompat.getColor(
            this,
            if (ttl > 0L) R.color.status_discovering else R.color.text_secondary
        )
        btnChatEphemeral.setColorFilter(tintColor)
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

        val editing = editingMessage
        if (editing != null) {
            val cleanEdited = text.replace('\r', ' ').replace('\n', ' ')
            val editedAt = System.currentTimeMillis()
            lifecycleScope.launch {
                chatRepository.editLocalMessage(editing, cleanEdited, editedAt)
            }
            if (!editing.messageId.isNullOrBlank()) {
                MainActivity.sendEditTo(recipient, editing.messageId, editedAt, cleanEdited, isGroupChat)
            }
            cancelReplyOrEdit()
            return
        }

        val quote = replyingToMessage
        val replySender = quote?.let {
            if (it.isSentByMe) "You" else (it.senderName?.takeIf { s -> s.isNotBlank() } ?: peerName)
        }
        val replySnippet = quote?.quoteSnippet()
        val replyTargetId = quote?.let { it.messageId ?: it.id }

        val ttlSeconds = EphemeralPrefs.getTtlSeconds(this, pid)
        val expiresAt = if (ttlSeconds > 0L) System.currentTimeMillis() + ttlSeconds * 1000L else null

        val message = ChatMessage(
            text = text,
            isSentByMe = true,
            replyToMessageId = replyTargetId,
            replyToSender = replySender,
            replyToText = replySnippet,
            expiresAt = expiresAt
        )
        val transmitted = if (quote != null) {
            MainActivity.transmitReplyTo(recipient, replyTargetId, replySender, replySnippet, text, message.messageId, ttlSeconds)
        } else {
            MainActivity.transmitMessageTo(recipient, text, message.messageId, ttlSeconds)
        }
        if (!transmitted) {
            Snackbar.make(chatRoot, "Failed to send — no live channel", Snackbar.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            chatRepository.insertMessage(message, peerId, peerName)
        }
        cancelReplyOrEdit()
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

    private fun onVoiceNoteButtonClicked() {
        if (!MainActivity.isConnectedStatic()) {
            Snackbar.make(
                chatRoot,
                "Not connected — connect to a group on the main screen first",
                Snackbar.LENGTH_LONG
            ).show()
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startVoiceRecording()
        } else {
            voiceAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startVoiceRecording() {
        VoiceNoteManager.stopPlayback()
        val file = voiceRecorder.start()
        if (file == null) {
            Snackbar.make(chatRoot, "Could not access microphone", Snackbar.LENGTH_SHORT).show()
            return
        }
        composerDock.visibility = View.GONE
        layoutVoiceRecording.visibility = View.VISIBLE
        tvVoiceRecordTimer.text = "REC 00:00"
        progressVoiceAmp.setProgressCompat(12, false)
        voiceTimerHandler.removeCallbacks(voiceTimerRunnable)
        voiceTimerHandler.post(voiceTimerRunnable)
    }

    private fun cancelVoiceRecording() {
        voiceTimerHandler.removeCallbacks(voiceTimerRunnable)
        voiceRecorder.cancel()
        layoutVoiceRecording.visibility = View.GONE
        composerDock.visibility = View.VISIBLE
    }

    private fun finishAndSendVoiceRecording() {
        voiceTimerHandler.removeCallbacks(voiceTimerRunnable)
        val recorded = voiceRecorder.stopAndReturnFile()
        layoutVoiceRecording.visibility = View.GONE
        composerDock.visibility = View.VISIBLE
        if (recorded == null) {
            Snackbar.make(chatRoot, "Hold longer to record a voice note", Snackbar.LENGTH_SHORT).show()
            return
        }
        val (file, durationMs) = recorded
        val pid = peerId ?: run {
            file.delete()
            return
        }
        val recipient = if (pid == Constants.GROUP_PEER_ID) Constants.RECIPIENT_BROADCAST else pid
        if (!MainActivity.sendVoiceNoteToStatic(recipient, pid, peerName, file, durationMs)) {
            Snackbar.make(chatRoot, "Failed to send voice note", Snackbar.LENGTH_SHORT).show()
        }
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
        val ttlLabel = EphemeralPrefs.formatShortLabel(EphemeralPrefs.getTtlSeconds(this, pid))
        val ttlSuffix = if (ttlLabel != null) " · ⏱ $ttlLabel" else ""
        tvPeerStatus.text = when {
            typingLabel != null -> typingLabel
            pid == Constants.GROUP_PEER_ID -> "${PeerRegistry.snapshotIncludingSelf().size} members$ttlSuffix"
            liveWithThisPeer -> "online · encrypted$ttlSuffix"
            else -> "offline — history only$ttlSuffix"
        }
        if (liveWithThisPeer && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            MainActivity.sendReadReceiptStatic(pid)
        }
    }
}
