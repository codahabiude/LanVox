package com.example.wifip2pchat

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.LruCache
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
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
 * RecyclerView adapter for binding and displaying sent and received [ChatMessage] items,
 * supporting both text bubbles and interactive file/image attachment bubbles with live
 * determinate progress and multi-peer upload/download status indicators.
 */
class MessageAdapter(
    private val onAttachmentClick: ((ChatMessage) -> Unit)? = null
) : RecyclerView.Adapter<MessageAdapter.MessageViewHolder>() {

    companion object {
        const val VIEW_TYPE_SENT = 1
        const val VIEW_TYPE_RECEIVED = 2
        private const val PAYLOAD_PROGRESS = "payload_progress"
        private const val MAX_VISIBLE_PEER_CHIPS = 6

        private val thumbCache = object : LruCache<String, Bitmap>(24) {}
        private val thumbExecutor = Executors.newFixedThreadPool(2)
        private val mainHandler = Handler(Looper.getMainLooper())
    }

    private val messages = mutableListOf<ChatMessage>()
    private var activeQuery: String = ""
    private var transferSnapshots: Map<String, FileTransferTracker.TransferSnapshot> = emptyMap()

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
                    oldItem.attachmentState == newItem.attachmentState
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
        super.onBindViewHolder(holder, position, payloads)
    }

    override fun getItemCount(): Int = messages.size

    inner class MessageViewHolder(
        itemView: View,
        private val viewType: Int
    ) : RecyclerView.ViewHolder(itemView) {

        private val bubbleContainer: LinearLayout = itemView.findViewById(R.id.bubbleContainer)
        private val tvSenderName: TextView = itemView.findViewById(R.id.tvSenderName)
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
        private val tvMessage: TextView = itemView.findViewById(R.id.tvMessage)
        private val tvTimestamp: TextView = itemView.findViewById(R.id.tvTimestamp)
        private val ivTick: ImageView = itemView.findViewById(R.id.ivTick)

        fun bind(message: ChatMessage) {
            val ctx = itemView.context
            val isSent = viewType == VIEW_TYPE_SENT || message.isSentByMe
            val formattedTime = message.formattedTimestamp(ctx)
            val highlightColor = ContextCompat.getColor(ctx, R.color.search_highlight)

            bindAttachment(message, highlightColor)

            if (message.isFileMessage) {
                tvMessage.visibility = View.GONE
            } else {
                tvMessage.visibility = View.VISIBLE
                tvMessage.text = ChatSearchHelper.highlight(message.text, activeQuery, highlightColor)
            }

            tvTimestamp.visibility = View.VISIBLE
            tvTimestamp.text = ChatSearchHelper.highlight(formattedTime, activeQuery, highlightColor)
            tvTimestamp.contentDescription = if (isSent) {
                "Sent at $formattedTime"
            } else {
                "Received at $formattedTime"
            }

            val sideMarginPx = (48 * ctx.resources.displayMetrics.density).toInt()
            val lp = bubbleContainer.layoutParams as FrameLayout.LayoutParams
            if (isSent) {
                lp.gravity = Gravity.END
                lp.marginStart = sideMarginPx
                lp.marginEnd = 0
            } else {
                lp.gravity = Gravity.START
                lp.marginStart = 0
                lp.marginEnd = sideMarginPx
            }
            bubbleContainer.layoutParams = lp

            if (isSent) {
                bubbleContainer.setBackgroundResource(R.drawable.bubble_sent)
                GlassEffects.applyGlowElevation(bubbleContainer, R.color.glow_bubble_sent, 10f)
                tvSenderName.visibility = View.GONE
                tvMessage.setTextColor(ContextCompat.getColor(ctx, R.color.bubble_sent_text))
                tvTimestamp.setTextColor(ContextCompat.getColor(ctx, R.color.bubble_sent_time))
                ivTick.visibility = View.VISIBLE
                when (message.status) {
                    MessageStatus.READ -> {
                        ivTick.setImageResource(R.drawable.ic_check_double)
                        ivTick.setColorFilter(ContextCompat.getColor(ctx, R.color.tick_read))
                        ivTick.contentDescription = "Read"
                    }
                    MessageStatus.DELIVERED -> {
                        ivTick.setImageResource(R.drawable.ic_check_double)
                        ivTick.setColorFilter(ContextCompat.getColor(ctx, R.color.tick_delivered))
                        ivTick.contentDescription = "Delivered"
                    }
                    else -> {
                        ivTick.setImageResource(R.drawable.ic_check)
                        ivTick.setColorFilter(ContextCompat.getColor(ctx, R.color.tick_sent))
                        ivTick.contentDescription = "Sent"
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
                ivTick.visibility = View.GONE
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

                // Header subtitle & right pill badge
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

                // Determinate progress bar
                linearFileProgress.isIndeterminate = false
                linearFileProgress.setProgressCompat(snapshot.overallPercent, true)
                linearFileProgress.setIndicatorColor(accentColor)
                linearFileProgress.trackColor = ColorUtils.setAlphaComponent(accentColor, 34)

                // Byte telemetry line
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

                // Multi-peer status chip rows
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
                // Persisted state without active in-memory snapshot (e.g. after app restart)
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
