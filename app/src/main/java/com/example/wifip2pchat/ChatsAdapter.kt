package com.example.wifip2pchat

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import android.text.format.DateUtils
import java.util.Date

/**
 * Adapter for the chats list screen — one row per contact device.
 * Shows avatar (with online dot), contact name, last message preview, time
 * and a message-count badge (only shown when count > 0).
 */
class ChatsAdapter(
    private val onClick: (Conversation) -> Unit,
    private val onLongClick: (Conversation) -> Unit
) : RecyclerView.Adapter<ChatsAdapter.ConversationViewHolder>() {

    private val items = mutableListOf<Conversation>()
    private val timeFormat = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
    private val dateFormat = java.text.SimpleDateFormat("MMM d", java.util.Locale.getDefault())

    fun submit(conversations: List<Conversation>) {
        items.clear()
        items.addAll(conversations)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ConversationViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_conversation, parent, false)
        return ConversationViewHolder(view)
    }

    override fun onBindViewHolder(holder: ConversationViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount() = items.size

    inner class ConversationViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvAvatar: TextView = itemView.findViewById(R.id.tvAvatar)
        private val tvName: TextView = itemView.findViewById(R.id.tvConvName)
        private val ivTick: android.widget.ImageView = itemView.findViewById(R.id.ivConvTick)
        private val tvPreview: TextView = itemView.findViewById(R.id.tvConvPreview)
        private val tvTime: TextView = itemView.findViewById(R.id.tvConvTime)
        private val tvCount: TextView = itemView.findViewById(R.id.tvConvCount)

        fun bind(conversation: Conversation) {
            val ctx = itemView.context
            val name = conversation.peerName?.takeIf { it.isNotBlank() }
                ?: conversation.peerId
            tvName.text = name
            tvAvatar.text = name.trim().take(1).uppercase().ifBlank { "?" }

            val prefix = if (conversation.lastIsSentByMe) "You: " else ""
            tvPreview.text = prefix + conversation.lastText.replace("\n", " ")

            if (conversation.lastIsSentByMe) {
                ivTick.visibility = View.VISIBLE
                when (conversation.lastStatus) {
                    MessageStatus.READ -> {
                        ivTick.setImageResource(R.drawable.ic_check_double)
                        ivTick.setColorFilter(androidx.core.content.ContextCompat.getColor(ctx, R.color.tick_read))
                        ivTick.contentDescription = ctx.getString(R.string.status_msg_read)
                    }
                    MessageStatus.DELIVERED -> {
                        ivTick.setImageResource(R.drawable.ic_check_double)
                        ivTick.setColorFilter(androidx.core.content.ContextCompat.getColor(ctx, R.color.tick_delivered))
                        ivTick.contentDescription = ctx.getString(R.string.status_msg_delivered)
                    }
                    else -> {
                        ivTick.setImageResource(R.drawable.ic_check)
                        ivTick.setColorFilter(androidx.core.content.ContextCompat.getColor(ctx, R.color.tick_sent))
                        ivTick.contentDescription = ctx.getString(R.string.status_msg_sent)
                    }
                }
            } else {
                ivTick.visibility = View.GONE
            }

            tvTime.text = if (DateUtils.isToday(conversation.lastTimestamp)) {
                timeFormat.format(Date(conversation.lastTimestamp))
            } else {
                dateFormat.format(Date(conversation.lastTimestamp))
            }

            if (conversation.messageCount > 0) {
                tvCount.visibility = View.VISIBLE
                tvCount.text = conversation.messageCount.toString()
            } else {
                tvCount.visibility = View.GONE
            }

            itemView.setOnClickListener { onClick(conversation) }
            itemView.setOnLongClickListener {
                onLongClick(conversation)
                true
            }
        }
    }
}
