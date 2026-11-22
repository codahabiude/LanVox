package com.example.wifip2pchat

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
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
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.progressindicator.CircularProgressIndicator
import java.io.File
import java.util.concurrent.Executors

/**
 * RecyclerView adapter for binding and displaying sent and received [ChatMessage] items,
 * supporting both text bubbles and interactive file/image attachment bubbles.
 */
class MessageAdapter(
    private val onAttachmentClick: ((ChatMessage) -> Unit)? = null
) : RecyclerView.Adapter<MessageAdapter.MessageViewHolder>() {

    companion object {
        const val VIEW_TYPE_SENT = 1
        const val VIEW_TYPE_RECEIVED = 2

        private val thumbCache = object : LruCache<String, Bitmap>(24) {}
        private val thumbExecutor = Executors.newFixedThreadPool(2)
        private val mainHandler = Handler(Looper.getMainLooper())
    }

    private val messages = mutableListOf<ChatMessage>()
    private var activeQuery: String = ""

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
        private val progressFileTransfer: CircularProgressIndicator =
            itemView.findViewById(R.id.progressFileTransfer)
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

            val sizeLabel = message.formattedFileSize()
            val stateLabel = when (message.attachmentState) {
                AttachmentState.TRANSFERRING -> if (message.isSentByMe) "Sending…" else "Receiving…"
                AttachmentState.FAILED -> "Transfer failed"
                AttachmentState.COMPLETED -> "Tap to open"
                AttachmentState.NONE -> ""
            }
            tvFileMeta.text = if (stateLabel.isNotEmpty()) "$sizeLabel · $stateLabel" else sizeLabel
            progressFileTransfer.visibility =
                if (message.attachmentState == AttachmentState.TRANSFERRING) View.VISIBLE else View.GONE

            val localPath = message.attachmentPath
            val isImage = mime.startsWith("image/")
            if (isImage && !localPath.isNullOrBlank() && message.attachmentState == AttachmentState.COMPLETED) {
                loadThumbnailAsync(localPath)
            } else {
                cardFilePreview.visibility = View.GONE
                ivFilePreview.tag = null
                ivFilePreview.setImageDrawable(null)
            }

            layoutAttachment.setOnClickListener {
                onAttachmentClick?.invoke(message)
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
