package com.example.wifip2pchat

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.LruCache
import android.view.GestureDetector
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.progressindicator.LinearProgressIndicator
import java.io.File
import java.util.concurrent.Executors

/**
 * RecyclerView adapter for binding sent and received [ChatMessage] items, supporting:
 *  - Text bubbles, file/image attachment cards with live multi-peer progress
 *  - WhatsApp/IG/FB quote-reply blocks (tap to jump to quoted message)
 *  - IG-style double-tap ❤️ quick reactions, reaction chip bars, and long-press action sheets
 *  - Starred badges and live Sent/Delivered/Read receipt labels
 */
class MessageAdapter(
    private val onAttachmentClick: ((ChatMessage) -> Unit)? = null,
    private val onDoubleTapReact: ((ChatMessage) -> Unit)? = null,
    private val onLongPressMessage: ((ChatMessage) -> Unit)? = null,
    private val onReactionChipClick: ((ChatMessage, String) -> Unit)? = null,
    private val onQuoteClick: ((String) -> Unit)? = null,
    private val onCallBackClick: ((ChatMessage) -> Unit)? = null
) : RecyclerView.Adapter<MessageAdapter.MessageViewHolder>() {

    companion object {
        const val VIEW_TYPE_SENT = 1
        const val VIEW_TYPE_RECEIVED = 2
        private const val PAYLOAD_PROGRESS = "payload_progress"
        private const val PAYLOAD_VOICE = "payload_voice"
        private const val MAX_VISIBLE_PEER_CHIPS = 6

        private val thumbCache = object : LruCache<String, Bitmap>(24) {}
        private val thumbExecutor = Executors.newFixedThreadPool(2)
        private val mainHandler = Handler(Looper.getMainLooper())
    }

    private val messages = mutableListOf<ChatMessage>()
    private var activeQuery: String = ""
    private var transferSnapshots: Map<String, FileTransferTracker.TransferSnapshot> = emptyMap()
    private var voicePlaybackState: VoiceNoteManager.PlaybackState = VoiceNoteManager.PlaybackState()

    override fun getItemViewType(position: Int): Int {
        return if (messages[position].isSentByMe) VIEW_TYPE_SENT else VIEW_TYPE_RECEIVED
    }

    fun addMessage(message: ChatMessage) {
        messages.add(message)
        notifyItemInserted(messages.size - 1)
    }

    fun setMessages(history: List<ChatMessage>) {
        submitMessages(history, activeQuery)
    }

    fun findPositionByMessageId(targetMsgId: String): Int {
        if (targetMsgId.isBlank()) return -1
        return messages.indexOfFirst { it.messageId == targetMsgId || it.id == targetMsgId }
    }

    fun updateTransferSnapshots(newSnapshots: Map<String, FileTransferTracker.TransferSnapshot>) {
        val oldSnapshots = transferSnapshots
        if (oldSnapshots == newSnapshots) return
        transferSnapshots = newSnapshots
        for (i in messages.indices) {
            val tid = messages[i].transferId ?: continue
            if (oldSnapshots[tid] != newSnapshots[tid]) {
                notifyItemChanged(i, PAYLOAD_PROGRESS)
            }
        }
    }

    fun updateVoicePlayback(newState: VoiceNoteManager.PlaybackState) {
        val oldState = voicePlaybackState
        if (oldState == newState) return
        voicePlaybackState = newState
        for (i in messages.indices) {
            val path = messages[i].attachmentPath ?: continue
            if (messages[i].isVoiceNote && (path == oldState.path || path == newState.path)) {
                notifyItemChanged(i, PAYLOAD_VOICE)
            }
        }
    }

    fun submitMessages(history: List<ChatMessage>, query: String) {
        val normalizedQuery = query.trim()
        val queryChanged = normalizedQuery != activeQuery
        activeQuery = normalizedQuery

        if (queryChanged) {
            messages.clear()
            messages.addAll(history)
            notifyDataSetChanged()
            return
        }

        val oldList = messages.toList()
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize(): Int = oldList.size
            override fun getNewListSize(): Int = history.size

            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                val oldItem = oldList[oldItemPosition]
                val newItem = history[newItemPosition]
                return oldItem.id == newItem.id ||
                    (oldItem.messageId != null && oldItem.messageId == newItem.messageId) ||
                    (oldItem.transferId != null && oldItem.transferId == newItem.transferId) ||
                    (oldItem.timestamp == newItem.timestamp &&
                        oldItem.isSentByMe == newItem.isSentByMe &&
                        oldItem.text == newItem.text)
            }

            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                val oldItem = oldList[oldItemPosition]
                val newItem = history[newItemPosition]
                return oldItem.text == newItem.text &&
                    oldItem.isSentByMe == newItem.isSentByMe &&
                    oldItem.timestamp == newItem.timestamp &&
                    oldItem.status == newItem.status &&
                    oldItem.senderName == newItem.senderName &&
                    oldItem.attachmentName == newItem.attachmentName &&
                    oldItem.attachmentMime == newItem.attachmentMime &&
                    oldItem.attachmentSize == newItem.attachmentSize &&
                    oldItem.attachmentPath == newItem.attachmentPath &&
                    oldItem.attachmentState == newItem.attachmentState &&
                    oldItem.replyToMessageId == newItem.replyToMessageId &&
                    oldItem.replyToSender == newItem.replyToSender &&
                    oldItem.replyToText == newItem.replyToText &&
                    oldItem.reactionsJson == newItem.reactionsJson &&
                    oldItem.isStarred == newItem.isStarred &&
                    oldItem.isPinned == newItem.isPinned &&
                    oldItem.editedAt == newItem.editedAt &&
                    oldItem.expiresAt == newItem.expiresAt &&
                    oldItem.messageType == newItem.messageType
            }
        })
        messages.clear()
        messages.addAll(history)
        diff.dispatchUpdatesTo(this)
    }

    fun getMessageAt(position: Int): ChatMessage? = messages.getOrNull(position)

    fun removeAt(position: Int): ChatMessage? {
        if (position !in messages.indices) return null
        val removed = messages.removeAt(position)
        notifyItemRemoved(position)
        return removed
    }

    fun clear() {
        val count = messages.size
        if (count == 0) return
        messages.clear()
        notifyItemRangeRemoved(0, count)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_message, parent, false)
        return MessageViewHolder(view, viewType)
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        holder.bind(messages[position])
    }

    override fun onBindViewHolder(
        holder: MessageViewHolder,
        position: Int,
        payloads: MutableList<Any>
    ) {
        if (payloads.contains(PAYLOAD_PROGRESS) && payloads.size == 1) {
            holder.bindTransferProgress(messages[position])
            return
        }
        if (payloads.contains(PAYLOAD_VOICE) && payloads.size == 1) {
            holder.bindVoicePlaybackState(messages[position])
            return
        }
        super.onBindViewHolder(holder, position, payloads)
    }

    override fun getItemCount(): Int = messages.size

    inner class MessageViewHolder(
        itemView: View,
        private val viewType: Int
    ) : RecyclerView.ViewHolder(itemView) {

        private val layoutCallLog: LinearLayout = itemView.findViewById(R.id.layoutCallLog)
        private val ivCallLogIcon: ImageView = itemView.findViewById(R.id.ivCallLogIcon)
        private val tvCallLogTitle: TextView = itemView.findViewById(R.id.tvCallLogTitle)
        private val tvCallLogMeta: TextView = itemView.findViewById(R.id.tvCallLogMeta)
        private val tvCallLogCallback: TextView = itemView.findViewById(R.id.tvCallLogCallback)

        private val messageColumn: LinearLayout = itemView.findViewById(R.id.messageColumn)
        private val bubbleContainer: LinearLayout = itemView.findViewById(R.id.bubbleContainer)
        private val tvSenderName: TextView = itemView.findViewById(R.id.tvSenderName)
        private val layoutReplyQuote: LinearLayout = itemView.findViewById(R.id.layoutReplyQuote)
        private val tvQuoteSender: TextView = itemView.findViewById(R.id.tvQuoteSender)
        private val tvQuoteText: TextView = itemView.findViewById(R.id.tvQuoteText)
        private val layoutAttachment: LinearLayout = itemView.findViewById(R.id.layoutAttachment)
        private val cardFilePreview: MaterialCardView = itemView.findViewById(R.id.cardFilePreview)
        private val ivFilePreview: ImageView = itemView.findViewById(R.id.ivFilePreview)
        private val ivFileTypeIcon: ImageView = itemView.findViewById(R.id.ivFileTypeIcon)
        private val tvFileName: TextView = itemView.findViewById(R.id.tvFileName)
        private val tvFileMeta: TextView = itemView.findViewById(R.id.tvFileMeta)
        private val tvFileProgressBadge: TextView = itemView.findViewById(R.id.tvFileProgressBadge)
        private val progressFileTransfer: CircularProgressIndicator =
            itemView.findViewById(R.id.progressFileTransfer)
        private val layoutTransferProgress: LinearLayout =
            itemView.findViewById(R.id.layoutTransferProgress)
        private val linearFileProgress: LinearProgressIndicator =
            itemView.findViewById(R.id.linearFileProgress)
        private val tvTransferStage: TextView = itemView.findViewById(R.id.tvTransferStage)
        private val tvTransferPeerCount: TextView = itemView.findViewById(R.id.tvTransferPeerCount)
        private val llPeerTransferChips: LinearLayout = itemView.findViewById(R.id.llPeerTransferChips)
        private val layoutVoiceNote: LinearLayout = itemView.findViewById(R.id.layoutVoiceNote)
        private val btnVoicePlayPause: ImageView = itemView.findViewById(R.id.btnVoicePlayPause)
        private val seekVoiceNote: SeekBar = itemView.findViewById(R.id.seekVoiceNote)
        private val tvVoiceDuration: TextView = itemView.findViewById(R.id.tvVoiceDuration)
        private val tvVoiceStateBadge: TextView = itemView.findViewById(R.id.tvVoiceStateBadge)
        private val tvMessage: TextView = itemView.findViewById(R.id.tvMessage)
        private val ivPinnedBadge: ImageView = itemView.findViewById(R.id.ivPinnedBadge)
        private val ivStarred: ImageView = itemView.findViewById(R.id.ivStarred)
        private val tvExpiryTag: TextView = itemView.findViewById(R.id.tvExpiryTag)
        private val tvEditedTag: TextView = itemView.findViewById(R.id.tvEditedTag)
        private val tvTimestamp: TextView = itemView.findViewById(R.id.tvTimestamp)
        private val ivTick: ImageView = itemView.findViewById(R.id.ivTick)
        private val tvReadStatus: TextView = itemView.findViewById(R.id.tvReadStatus)
        private val llReactionsRow: LinearLayout = itemView.findViewById(R.id.llReactionsRow)

        private var boundMessage: ChatMessage? = null

        private val gestureDetector = GestureDetector(
            itemView.context,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent): Boolean = true

                override fun onDoubleTap(e: MotionEvent): Boolean {
                    val msg = boundMessage ?: return false
                    bubbleContainer.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                    bubbleContainer.animate()
                        .scaleX(1.04f)
                        .scaleY(1.04f)
                        .setDuration(90L)
                        .withEndAction {
                            bubbleContainer.animate().scaleX(1f).scaleY(1f).setDuration(110L).start()
                        }
                        .start()
                    onDoubleTapReact?.invoke(msg)
                    return true
                }

                override fun onLongPress(e: MotionEvent) {
                    val msg = boundMessage ?: return
                    bubbleContainer.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    onLongPressMessage?.invoke(msg)
                }
            }
        )

        init {
            @SuppressLint("ClickableViewAccessibility")
            bubbleContainer.setOnTouchListener { _, event ->
                gestureDetector.onTouchEvent(event)
                false
            }
        }

        fun bind(message: ChatMessage) {
            boundMessage = message
            val ctx = itemView.context
            val formattedTime = message.formattedTimestamp(ctx)
            val highlightColor = ContextCompat.getColor(ctx, R.color.search_highlight)

            if (message.isCallLog) {
                layoutCallLog.visibility = View.VISIBLE
                messageColumn.visibility = View.GONE
                val isVideo = message.attachmentMime == Constants.CALL_TYPE_VIDEO
                ivCallLogIcon.setImageResource(if (isVideo) R.drawable.ic_videocam else R.drawable.ic_phone)
                val isMissedOrDeclined = message.text.contains("Missed", ignoreCase = true) ||
                    message.text.contains("Declined", ignoreCase = true)
                ivCallLogIcon.setColorFilter(
                    ContextCompat.getColor(
                        ctx,
                        if (isMissedOrDeclined) R.color.call_decline else R.color.brand_secondary
                    )
                )
                tvCallLogTitle.text = ChatSearchHelper.highlight(message.text, activeQuery, highlightColor)
                tvCallLogMeta.text = formattedTime
                tvCallLogCallback.visibility = if (onCallBackClick != null) View.VISIBLE else View.GONE
                tvCallLogCallback.setOnClickListener { onCallBackClick?.invoke(message) }
                layoutCallLog.setOnLongClickListener {
                    layoutCallLog.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    onLongPressMessage?.invoke(message)
                    true
                }
                return
            }

            layoutCallLog.visibility = View.GONE
            messageColumn.visibility = View.VISIBLE

            val isSent = viewType == VIEW_TYPE_SENT || message.isSentByMe

            bindQuoteReply(message)

            val showVoiceInline = message.isVoiceNote &&
                !message.attachmentPath.isNullOrBlank() &&
                message.attachmentState != AttachmentState.FAILED

            if (showVoiceInline) {
                layoutAttachment.visibility = View.GONE
                layoutVoiceNote.visibility = View.VISIBLE
                bindVoiceNote(message)
            } else {
                layoutVoiceNote.visibility = View.GONE
                bindAttachment(message, highlightColor)
            }

            if (message.isFileMessage) {
                tvMessage.visibility = View.GONE
            } else {
                tvMessage.visibility = View.VISIBLE
                tvMessage.text = ChatSearchHelper.highlight(message.text, activeQuery, highlightColor)
            }

            ivPinnedBadge.visibility = if (message.isPinned) View.VISIBLE else View.GONE
            ivStarred.visibility = if (message.isStarred) View.VISIBLE else View.GONE
            tvEditedTag.visibility = if (message.editedAt != null) View.VISIBLE else View.GONE
            val ttlLabel = message.formattedRemainingTtl()
            if (ttlLabel != null) {
                tvExpiryTag.text = "⏱ $ttlLabel"
                tvExpiryTag.visibility = View.VISIBLE
            } else {
                tvExpiryTag.visibility = View.GONE
            }

            tvTimestamp.visibility = View.VISIBLE
            tvTimestamp.text = ChatSearchHelper.highlight(formattedTime, activeQuery, highlightColor)
            tvTimestamp.contentDescription = if (isSent) {
                "Sent at $formattedTime"
            } else {
                "Received at $formattedTime"
            }

            val sideMarginPx = (48 * ctx.resources.displayMetrics.density).toInt()
            val colLp = messageColumn.layoutParams as FrameLayout.LayoutParams
            val bubbleLp = bubbleContainer.layoutParams as LinearLayout.LayoutParams
            val reactLp = llReactionsRow.layoutParams as LinearLayout.LayoutParams
            if (isSent) {
                colLp.gravity = Gravity.END
                colLp.marginStart = sideMarginPx
                colLp.marginEnd = 0
                bubbleLp.gravity = Gravity.END
                reactLp.gravity = Gravity.END
            } else {
                colLp.gravity = Gravity.START
                colLp.marginStart = 0
                colLp.marginEnd = sideMarginPx
                bubbleLp.gravity = Gravity.START
                reactLp.gravity = Gravity.START
            }
            messageColumn.layoutParams = colLp
            bubbleContainer.layoutParams = bubbleLp
            llReactionsRow.layoutParams = reactLp

            if (isSent) {
                bubbleContainer.setBackgroundResource(R.drawable.bubble_sent)
                GlassEffects.applyGlowElevation(bubbleContainer, R.color.glow_bubble_sent, 10f)
                tvSenderName.visibility = View.GONE
                tvMessage.setTextColor(ContextCompat.getColor(ctx, R.color.bubble_sent_text))
                tvTimestamp.setTextColor(ContextCompat.getColor(ctx, R.color.bubble_sent_time))
                tvEditedTag.setTextColor(ContextCompat.getColor(ctx, R.color.bubble_sent_time))
                ivTick.visibility = View.VISIBLE
                tvReadStatus.visibility = View.VISIBLE
                when (message.status) {
                    MessageStatus.READ -> {
                        val readColor = ContextCompat.getColor(ctx, R.color.tick_read)
                        ivTick.setImageResource(R.drawable.ic_check_double)
                        ivTick.setColorFilter(readColor)
                        ivTick.contentDescription = ctx.getString(R.string.status_msg_read)
                        tvReadStatus.text = ctx.getString(R.string.status_msg_read)
                        tvReadStatus.setTextColor(readColor)
                        tvReadStatus.setTypeface(tvReadStatus.typeface, android.graphics.Typeface.BOLD)
                    }
                    MessageStatus.DELIVERED -> {
                        val deliveredColor = ContextCompat.getColor(ctx, R.color.tick_delivered)
                        ivTick.setImageResource(R.drawable.ic_check_double)
                        ivTick.setColorFilter(deliveredColor)
                        ivTick.contentDescription = ctx.getString(R.string.status_msg_delivered)
                        tvReadStatus.text = ctx.getString(R.string.status_msg_delivered)
                        tvReadStatus.setTextColor(deliveredColor)
                        tvReadStatus.setTypeface(null, android.graphics.Typeface.NORMAL)
                    }
                    else -> {
                        val sentColor = ContextCompat.getColor(ctx, R.color.tick_sent)
                        ivTick.setImageResource(R.drawable.ic_check)
                        ivTick.setColorFilter(sentColor)
                        ivTick.contentDescription = ctx.getString(R.string.status_msg_sent)
                        tvReadStatus.text = ctx.getString(R.string.status_msg_sent)
                        tvReadStatus.setTextColor(sentColor)
                        tvReadStatus.setTypeface(null, android.graphics.Typeface.NORMAL)
                    }
                }
            } else {
                bubbleContainer.setBackgroundResource(R.drawable.bubble_received)
                GlassEffects.applyGlowElevation(bubbleContainer, R.color.glow_bubble_received, 7f)
                val sender = message.senderName?.takeIf { it.isNotBlank() }
                if (sender != null) {
                    tvSenderName.text = sender
                    tvSenderName.visibility = View.VISIBLE
                } else {
                    tvSenderName.visibility = View.GONE
                }
                tvMessage.setTextColor(ContextCompat.getColor(ctx, R.color.bubble_received_text))
                tvTimestamp.setTextColor(ContextCompat.getColor(ctx, R.color.bubble_received_time))
                tvEditedTag.setTextColor(ContextCompat.getColor(ctx, R.color.bubble_received_time))
                ivTick.visibility = View.GONE
                tvReadStatus.visibility = View.GONE
            }

            bindReactions(message)
        }

        private fun bindVoiceNote(message: ChatMessage) {
            val path = message.attachmentPath ?: return
            bindVoicePlaybackState(message)
            btnVoicePlayPause.setOnClickListener {
                VoiceNoteManager.togglePlayback(path)
            }
            seekVoiceNote.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser && voicePlaybackState.path == path) {
                        VoiceNoteManager.seekTo(path, progress / 1000f)
                    }
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
            layoutVoiceNote.setOnLongClickListener {
                bubbleContainer.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                onLongPressMessage?.invoke(message)
                true
            }
        }

        fun bindVoicePlaybackState(message: ChatMessage) {
            val path = message.attachmentPath ?: return
            val active = voicePlaybackState.path == path
            val isPlaying = active && voicePlaybackState.isPlaying
            btnVoicePlayPause.setImageResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
            if (active && voicePlaybackState.durationMs > 0) {
                val progress = ((voicePlaybackState.positionMs.toFloat() / voicePlaybackState.durationMs.toFloat()) * 1000f)
                    .toInt()
                    .coerceIn(0, 1000)
                seekVoiceNote.progress = progress
                tvVoiceDuration.text = "${VoiceNoteManager.formatDurationMs(voicePlaybackState.positionMs)} / ${VoiceNoteManager.formatDurationMs(voicePlaybackState.durationMs)}"
                tvVoiceStateBadge.text = if (isPlaying) "PLAYING" else "PAUSED"
            } else {
                seekVoiceNote.progress = 0
                val totalDur = VoiceNoteManager.readDurationMs(path)
                tvVoiceDuration.text = if (totalDur > 0) VoiceNoteManager.formatDurationMs(totalDur) else message.formattedFileSize()
                tvVoiceStateBadge.text = "VOICE"
            }
        }

        private fun bindQuoteReply(message: ChatMessage) {
            if (!message.hasReplyQuote) {
                layoutReplyQuote.visibility = View.GONE
                layoutReplyQuote.setOnClickListener(null)
                return
            }
            layoutReplyQuote.visibility = View.VISIBLE
            tvQuoteSender.text = message.replyToSender?.takeIf { it.isNotBlank() } ?: "Reply"
            tvQuoteText.text = message.replyToText.orEmpty()
            val targetId = message.replyToMessageId
            if (!targetId.isNullOrBlank()) {
                layoutReplyQuote.setOnClickListener { onQuoteClick?.invoke(targetId) }
            } else {
                layoutReplyQuote.setOnClickListener(null)
            }
        }

        private fun bindReactions(message: ChatMessage) {
            val chips = message.reactionChips()
            if (chips.isEmpty()) {
                llReactionsRow.removeAllViews()
                llReactionsRow.visibility = View.GONE
                return
            }
            val ctx = itemView.context
            val density = ctx.resources.displayMetrics.density
            val cyan = ContextCompat.getColor(ctx, R.color.brand_secondary)
            val borderNeutral = ContextCompat.getColor(ctx, R.color.glass_border)
            val chipBgNeutral = ColorUtils.setAlphaComponent(
                ContextCompat.getColor(ctx, R.color.surface_card),
                230
            )
            val mono = ResourcesCompat.getFont(ctx, R.font.jetbrains_mono)

            llReactionsRow.removeAllViews()
            llReactionsRow.visibility = View.VISIBLE

            chips.forEachIndexed { idx, chip ->
                val pill = TextView(ctx).apply {
                    text = if (chip.count > 1) "${chip.emoji} ${chip.count}" else chip.emoji
                    textSize = 11.5f
                    typeface = mono
                    setTextColor(ContextCompat.getColor(ctx, R.color.text_primary))
                    setPadding(
                        (8 * density).toInt(),
                        (3 * density).toInt(),
                        (8 * density).toInt(),
                        (3 * density).toInt()
                    )
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = 999f * density
                        setColor(
                            if (chip.reactedByMe) ColorUtils.setAlphaComponent(cyan, 46)
                            else chipBgNeutral
                        )
                        setStroke(
                            (1 * density).toInt().coerceAtLeast(1),
                            if (chip.reactedByMe) cyan else borderNeutral
                        )
                    }
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        if (idx > 0) marginStart = (4 * density).toInt()
                    }
                    setOnClickListener {
                        onReactionChipClick?.invoke(message, chip.emoji)
                    }
                }
                llReactionsRow.addView(pill)
            }
        }

        private fun bindAttachment(message: ChatMessage, highlightColor: Int) {
            if (!message.isFileMessage) {
                layoutAttachment.visibility = View.GONE
                cardFilePreview.visibility = View.GONE
                layoutTransferProgress.visibility = View.GONE
                ivFilePreview.tag = null
                ivFilePreview.setImageDrawable(null)
                layoutAttachment.setOnClickListener(null)
                layoutAttachment.setOnLongClickListener(null)
                return
            }

            layoutAttachment.visibility = View.VISIBLE
            val fileName = message.attachmentName ?: "Attachment"
            val mime = message.attachmentMime.orEmpty()
            tvFileName.text = ChatSearchHelper.highlight(fileName, activeQuery, highlightColor)

            val iconRes = when {
                mime.startsWith("image/") -> R.drawable.ic_file_image
                mime.startsWith("video/") -> R.drawable.ic_videocam
                mime.startsWith("audio/") -> R.drawable.ic_mic
                else -> R.drawable.ic_file_doc
            }
            ivFileTypeIcon.setImageResource(iconRes)

            val localPath = message.attachmentPath
            val isImage = mime.startsWith("image/")
            val canShowPreview = isImage && !localPath.isNullOrBlank() &&
                (message.attachmentState == AttachmentState.COMPLETED || message.isSentByMe)
            if (canShowPreview && localPath != null) {
                loadThumbnailAsync(localPath)
            } else {
                cardFilePreview.visibility = View.GONE
                ivFilePreview.tag = null
                ivFilePreview.setImageDrawable(null)
            }

            bindTransferProgress(message)

            layoutAttachment.setOnClickListener {
                onAttachmentClick?.invoke(message)
            }
            layoutAttachment.setOnLongClickListener {
                bubbleContainer.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                onLongPressMessage?.invoke(message)
                true
            }
        }

        fun bindTransferProgress(message: ChatMessage) {
            if (!message.isFileMessage) return
            val ctx = itemView.context
            val sizeLabel = message.formattedFileSize()
            val snapshot = message.transferId?.let { transferSnapshots[it] }

            val cyan = ContextCompat.getColor(ctx, R.color.brand_secondary)
            val green = ContextCompat.getColor(ctx, R.color.status_online)
            val amber = ContextCompat.getColor(ctx, R.color.status_discovering)
            val red = ContextCompat.getColor(ctx, R.color.status_offline)

            if (snapshot != null) {
                progressFileTransfer.visibility = View.GONE
                layoutTransferProgress.visibility = View.VISIBLE

                val accentColor = when {
                    snapshot.hasFailures && snapshot.completedPeerCount == 0 -> red
                    snapshot.hasFailures -> amber
                    snapshot.isFinished -> green
                    else -> cyan
                }

                val headerAction = when {
                    snapshot.hasFailures && snapshot.completedPeerCount == 0 -> "Transfer failed"
                    snapshot.isFinished && message.attachmentPath != null -> "Tap to open"
                    snapshot.direction == FileTransferTracker.TransferDirection.SENDING -> snapshot.stageLabel
                    else -> snapshot.stageLabel
                }
                tvFileMeta.text = "$sizeLabel · $headerAction"

                tvFileProgressBadge.visibility = View.VISIBLE
                tvFileProgressBadge.text = when {
                    snapshot.isFinished && !snapshot.hasFailures && snapshot.totalPeerCount > 1 ->
                        "${snapshot.completedPeerCount}/${snapshot.totalPeerCount} ✓"
                    snapshot.isFinished && !snapshot.hasFailures -> "✓ 100%"
                    snapshot.hasFailures && snapshot.isFinished && snapshot.completedPeerCount == 0 -> "FAILED"
                    else -> "${snapshot.overallPercent}%"
                }
                styleBadgePill(tvFileProgressBadge, accentColor)

                linearFileProgress.isIndeterminate = false
                linearFileProgress.setProgressCompat(snapshot.overallPercent, true)
                linearFileProgress.setIndicatorColor(accentColor)
                linearFileProgress.trackColor = ColorUtils.setAlphaComponent(accentColor, 34)

                val transferredLabel = FileTransferTracker.formatBytesShort(snapshot.bytesTransferred)
                val totalLabel = FileTransferTracker.formatBytesShort(
                    snapshot.totalBytes.coerceAtLeast(message.attachmentSize)
                )
                tvTransferStage.text = if (snapshot.isFinished && !snapshot.hasFailures) {
                    "$totalLabel · ${snapshot.stageLabel}"
                } else {
                    "$transferredLabel / $totalLabel · ${snapshot.stageLabel}"
                }
                tvTransferStage.setTextColor(
                    if (snapshot.hasFailures && snapshot.completedPeerCount == 0) red
                    else ContextCompat.getColor(ctx, R.color.text_secondary)
                )

                if (snapshot.direction == FileTransferTracker.TransferDirection.SENDING && snapshot.totalPeerCount > 0) {
                    tvTransferPeerCount.visibility = View.VISIBLE
                    tvTransferPeerCount.text = "${snapshot.completedPeerCount}/${snapshot.totalPeerCount} peers"
                    tvTransferPeerCount.setTextColor(accentColor)
                } else {
                    tvTransferPeerCount.visibility = View.GONE
                }

                val showChips = snapshot.peerStatuses.isNotEmpty() && (
                    snapshot.direction == FileTransferTracker.TransferDirection.SENDING ||
                        !snapshot.isFinished ||
                        snapshot.hasFailures
                    )
                if (showChips) {
                    renderPeerChips(snapshot.peerStatuses.values.toList(), cyan, green, amber, red)
                    llPeerTransferChips.visibility = View.VISIBLE
                } else {
                    llPeerTransferChips.removeAllViews()
                    llPeerTransferChips.visibility = View.GONE
                }
            } else {
                val stateLabel = when (message.attachmentState) {
                    AttachmentState.TRANSFERRING -> if (message.isSentByMe) "Sending…" else "Receiving…"
                    AttachmentState.FAILED -> "Transfer failed"
                    AttachmentState.COMPLETED -> "Tap to open"
                    AttachmentState.NONE -> ""
                }
                tvFileMeta.text = if (stateLabel.isNotEmpty()) "$sizeLabel · $stateLabel" else sizeLabel

                llPeerTransferChips.removeAllViews()
                llPeerTransferChips.visibility = View.GONE

                when (message.attachmentState) {
                    AttachmentState.TRANSFERRING -> {
                        tvFileProgressBadge.visibility = View.GONE
                        progressFileTransfer.visibility = View.VISIBLE
                        layoutTransferProgress.visibility = View.VISIBLE
                        linearFileProgress.isIndeterminate = true
                        linearFileProgress.setIndicatorColor(cyan)
                        linearFileProgress.trackColor = ColorUtils.setAlphaComponent(cyan, 34)
                        tvTransferStage.text = if (message.isSentByMe) "Preparing upload…" else "Preparing download…"
                        tvTransferPeerCount.visibility = View.GONE
                    }
                    AttachmentState.FAILED -> {
                        progressFileTransfer.visibility = View.GONE
                        tvFileProgressBadge.visibility = View.VISIBLE
                        tvFileProgressBadge.text = "FAILED"
                        styleBadgePill(tvFileProgressBadge, red)
                        layoutTransferProgress.visibility = View.VISIBLE
                        linearFileProgress.isIndeterminate = false
                        linearFileProgress.progress = 100
                        linearFileProgress.setIndicatorColor(red)
                        linearFileProgress.trackColor = ColorUtils.setAlphaComponent(red, 34)
                        tvTransferStage.text = "Transfer interrupted"
                        tvTransferStage.setTextColor(red)
                        tvTransferPeerCount.visibility = View.GONE
                    }
                    else -> {
                        progressFileTransfer.visibility = View.GONE
                        tvFileProgressBadge.visibility = View.GONE
                        layoutTransferProgress.visibility = View.GONE
                    }
                }
            }
        }

        private fun renderPeerChips(
            peers: List<FileTransferTracker.PeerProgress>,
            cyan: Int,
            green: Int,
            amber: Int,
            red: Int
        ) {
            val ctx = itemView.context
            val density = ctx.resources.displayMetrics.density
            val monoFont = ResourcesCompat.getFont(ctx, R.font.jetbrains_mono)
            llPeerTransferChips.removeAllViews()

            val visible = peers.take(MAX_VISIBLE_PEER_CHIPS)
            val overflow = (peers.size - visible.size).coerceAtLeast(0)

            var currentRow: LinearLayout? = null
            val allChipSpecs = visible.map { peer ->
                val color = when (peer.state) {
                    FileTransferTracker.PeerTransferStatus.PENDING -> amber
                    FileTransferTracker.PeerTransferStatus.TRANSFERRING -> cyan
                    FileTransferTracker.PeerTransferStatus.COMPLETED -> green
                    FileTransferTracker.PeerTransferStatus.FAILED -> red
                }
                val label = when (peer.state) {
                    FileTransferTracker.PeerTransferStatus.PENDING -> "⏳ ${peer.peerName}"
                    FileTransferTracker.PeerTransferStatus.TRANSFERRING -> "↑ ${peer.peerName} ${peer.percent}%"
                    FileTransferTracker.PeerTransferStatus.COMPLETED -> "✓ ${peer.peerName}"
                    FileTransferTracker.PeerTransferStatus.FAILED -> "✕ ${peer.peerName}"
                }
                label to color
            }.toMutableList()

            if (overflow > 0) {
                allChipSpecs.add("+$overflow more" to ContextCompat.getColor(ctx, R.color.text_secondary))
            }

            allChipSpecs.forEachIndexed { index, (text, color) ->
                if (index % 2 == 0) {
                    currentRow = LinearLayout(ctx).apply {
                        orientation = LinearLayout.HORIZONTAL
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply {
                            if (index > 0) topMargin = (4 * density).toInt()
                        }
                    }
                    llPeerTransferChips.addView(currentRow)
                }

                val chip = TextView(ctx).apply {
                    this.text = text
                    textSize = 9.5f
                    typeface = monoFont
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    setTextColor(color)
                    setPadding(
                        (7 * density).toInt(),
                        (3 * density).toInt(),
                        (7 * density).toInt(),
                        (3 * density).toInt()
                    )
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = 8f * density
                        setColor(ColorUtils.setAlphaComponent(color, 26))
                        setStroke((1 * density).toInt().coerceAtLeast(1), ColorUtils.setAlphaComponent(color, 110))
                    }
                    layoutParams = LinearLayout.LayoutParams(
                        0,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        1f
                    ).apply {
                        if (index % 2 == 0 && index + 1 < allChipSpecs.size) {
                            marginEnd = (4 * density).toInt()
                        }
                    }
                }
                currentRow?.addView(chip)
            }
        }

        private fun styleBadgePill(view: TextView, color: Int) {
            val density = view.resources.displayMetrics.density
            view.setTextColor(color)
            view.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 999f * density
                setColor(ColorUtils.setAlphaComponent(color, 30))
                setStroke((1 * density).toInt().coerceAtLeast(1), ColorUtils.setAlphaComponent(color, 125))
            }
        }

        private fun loadThumbnailAsync(path: String) {
            ivFilePreview.tag = path
            val cached = thumbCache.get(path)
            if (cached != null) {
                cardFilePreview.visibility = View.VISIBLE
                ivFilePreview.setImageBitmap(cached)
                return
            }

            cardFilePreview.visibility = View.GONE
            ivFilePreview.setImageDrawable(null)
            thumbExecutor.execute {
                val bmp = decodeSampledBitmap(path, 520, 340) ?: return@execute
                thumbCache.put(path, bmp)
                mainHandler.post {
                    if (ivFilePreview.tag == path) {
                        cardFilePreview.visibility = View.VISIBLE
                        ivFilePreview.setImageBitmap(bmp)
                    }
                }
            }
        }

        private fun decodeSampledBitmap(path: String, reqWidth: Int, reqHeight: Int): Bitmap? {
            val file = File(path)
            if (!file.exists()) return null
            return runCatching {
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(path, opts)
                var inSampleSize = 1
                val (h, w) = opts.outHeight to opts.outWidth
                if (h > reqHeight || w > reqWidth) {
                    val halfH = h / 2
                    val halfW = w / 2
                    while ((halfH / inSampleSize) >= reqHeight && (halfW / inSampleSize) >= reqWidth) {
                        inSampleSize *= 2
                    }
                }
                BitmapFactory.Options().apply { this.inSampleSize = inSampleSize }
                    .let { BitmapFactory.decodeFile(path, it) }
            }.getOrNull()
        }
    }
}
