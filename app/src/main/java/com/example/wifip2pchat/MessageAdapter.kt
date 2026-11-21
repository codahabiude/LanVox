package com.example.wifip2pchat

import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Data class representing a single chat message with timestamp.
 *
 * `status` only applies to messages sent by me:
 *   - SENT       — saved locally, not yet acknowledged by the peer
 *   - DELIVERED  — peer acknowledged receipt
 *   - READ       — peer opened the chat (best-effort, optional)
 */
data class ChatMessage(
    val text: String,
    val isSentByMe: Boolean,
    val timestamp: Long = System.currentTimeMillis(),
    val status: MessageStatus = if (isSentByMe) MessageStatus.SENT else MessageStatus.NONE
)

enum class MessageStatus { NONE, SENT, DELIVERED, READ }

/**
 * Adapter for displaying chat messages with WhatsApp-style bubbles.
 *
 *  - Sent:     right-aligned green bubble, tail bottom-right, ticks visible
 *  - Received: left-aligned dark bubble, tail bottom-left, no ticks
 *  - Timestamps live INSIDE the bubble (bottom-right)
 */
class MessageAdapter : RecyclerView.Adapter<MessageAdapter.MessageViewHolder>() {

    private val messages = mutableListOf<ChatMessage>()
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val dateTimeFormat = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())

    fun addMessage(message: ChatMessage) {
        messages.add(message)
        notifyItemInserted(messages.size - 1)
    }

    fun setMessages(history: List<ChatMessage>) {
        messages.clear()
        messages.addAll(history)
        notifyDataSetChanged()
    }

    fun clear() {
        messages.clear()
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_message, parent, false)
        return MessageViewHolder(view)
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        holder.bind(messages[position])
    }

    override fun getItemCount() = messages.size

    inner class MessageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val bubbleContainer: LinearLayout =
            itemView.findViewById(R.id.bubbleContainer)
        private val tvMessage: TextView = itemView.findViewById(R.id.tvMessage)
        private val tvTimestamp: TextView = itemView.findViewById(R.id.tvTimestamp)
        private val ivTick: ImageView = itemView.findViewById(R.id.ivTick)

        fun bind(message: ChatMessage) {
            tvMessage.text = message.text

            tvTimestamp.text = if (android.text.format.DateUtils.isToday(message.timestamp)) {
                timeFormat.format(Date(message.timestamp))
            } else {
                dateTimeFormat.format(Date(message.timestamp))
            }

            val lp = bubbleContainer.layoutParams as FrameLayout.LayoutParams
            lp.gravity = if (message.isSentByMe) Gravity.END else Gravity.START
            bubbleContainer.layoutParams = lp

            if (message.isSentByMe) {
                bubbleContainer.setBackgroundResource(R.drawable.bubble_sent)
                tvMessage.setTextColor(0xFF0B1120.toInt())
                tvTimestamp.setTextColor(0xFF5A0B1120.toInt())
                ivTick.visibility = View.VISIBLE
                when (message.status) {
                    MessageStatus.READ -> {
                        ivTick.setImageResource(R.drawable.ic_check_double)
                        ivTick.setColorFilter(0xFF4FC3F7.toInt())
                    }
                    MessageStatus.DELIVERED -> {
                        ivTick.setImageResource(R.drawable.ic_check_double)
                        ivTick.setColorFilter(0xFF0B1120.toInt())
                    }
                    else -> {
                        ivTick.setImageResource(R.drawable.ic_check)
                        ivTick.setColorFilter(0xFF5A0B1120.toInt())
                    }
                }
            } else {
                bubbleContainer.setBackgroundResource(R.drawable.bubble_received)
                tvMessage.setTextColor(0xFFE8EDF2.toInt())
                tvTimestamp.setTextColor(0xFF7B94A3B8.toInt())
                ivTick.visibility = View.GONE
            }
        }
    }
}
