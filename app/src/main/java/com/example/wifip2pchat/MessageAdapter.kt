package com.example.wifip2pchat

import android.text.format.DateUtils
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * RecyclerView adapter for binding and displaying sent and received [ChatMessage] items.
 *
 *  - Sent (`VIEW_TYPE_SENT`): right-aligned green bubble, tail bottom-right, delivery ticks visible
 *  - Received (`VIEW_TYPE_RECEIVED`): left-aligned dark bubble, tail bottom-left, optional sender label
 */
class MessageAdapter : RecyclerView.Adapter<MessageAdapter.MessageViewHolder>() {

    companion object {
        const val VIEW_TYPE_SENT = 1
        const val VIEW_TYPE_RECEIVED = 2
    }

    private val messages = mutableListOf<ChatMessage>()
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val dateTimeFormat = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())

    override fun getItemViewType(position: Int): Int {
        return if (messages[position].isSentByMe) VIEW_TYPE_SENT else VIEW_TYPE_RECEIVED
    }

    fun addMessage(message: ChatMessage) {
        messages.add(message)
        notifyItemInserted(messages.size - 1)
    }

    fun setMessages(history: List<ChatMessage>) {
        val oldList = messages.toList()
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize(): Int = oldList.size
            override fun getNewListSize(): Int = history.size

            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                val oldItem = oldList[oldItemPosition]
                val newItem = history[newItemPosition]
                return oldItem.id == newItem.id ||
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
                    oldItem.senderName == newItem.senderName
            }
        })
        messages.clear()
        messages.addAll(history)
        diff.dispatchUpdatesTo(this)
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
        private val tvMessage: TextView = itemView.findViewById(R.id.tvMessage)
        private val tvTimestamp: TextView = itemView.findViewById(R.id.tvTimestamp)
        private val ivTick: ImageView = itemView.findViewById(R.id.ivTick)

        fun bind(message: ChatMessage) {
            val ctx = itemView.context
            tvMessage.text = message.text
            tvTimestamp.text = if (DateUtils.isToday(message.timestamp)) {
                timeFormat.format(Date(message.timestamp))
            } else {
                dateTimeFormat.format(Date(message.timestamp))
            }

            val isSent = viewType == VIEW_TYPE_SENT || message.isSentByMe
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
    }
}
