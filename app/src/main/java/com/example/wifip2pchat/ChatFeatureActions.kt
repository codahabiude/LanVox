package com.example.wifip2pchat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Shared Instagram / WhatsApp / Messenger message interaction utilities used by
 * both [MainActivity] and [ChatActivity]: clipboard copy, starred filter styling,
 * quote-jump scrolling, and multi-thread message forwarding.
 */
object ChatFeatureActions {

    fun copyMessageText(context: Context, rootView: View, message: ChatMessage) {
        val text = message.text.trim()
        if (text.isEmpty()) return
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        cm?.setPrimaryClip(ClipData.newPlainText("LanVox Message", text))
        Snackbar.make(rootView, R.string.msg_copied, Snackbar.LENGTH_SHORT).show()
    }

    fun styleStarredFilterChip(context: Context, chip: TextView, active: Boolean) {
        val density = context.resources.displayMetrics.density
        val gold = ContextCompat.getColor(context, R.color.status_discovering)
        val neutralBorder = ContextCompat.getColor(context, R.color.glass_border)
        val secondaryText = ContextCompat.getColor(context, R.color.text_secondary)

        chip.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 999f * density
            if (active) {
                setColor(ColorUtils.setAlphaComponent(gold, 42))
                setStroke((1.2f * density).toInt().coerceAtLeast(1), gold)
            } else {
                setColor(ContextCompat.getColor(context, R.color.glass_surface))
                setStroke((1f * density).toInt().coerceAtLeast(1), neutralBorder)
            }
        }
        chip.setTextColor(if (active) gold else secondaryText)
    }

    fun scrollToQuotedMessage(
        recyclerView: RecyclerView,
        adapter: MessageAdapter,
        targetMessageId: String,
        onMissing: () -> Unit
    ) {
        val pos = adapter.findPositionByMessageId(targetMessageId)
        if (pos < 0) {
            onMissing()
            return
        }
        recyclerView.smoothScrollToPosition(pos)
        recyclerView.postDelayed({
            val holder = recyclerView.findViewHolderForAdapterPosition(pos) ?: return@postDelayed
            holder.itemView.animate()
                .scaleX(1.03f)
                .scaleY(1.03f)
                .setDuration(140L)
                .withEndAction {
                    holder.itemView.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(180L)
                        .start()
                }
                .start()
        }, 240L)
    }

    fun showForwardDialog(
        context: Context,
        scope: CoroutineScope,
        chatRepository: ChatRepository,
        message: ChatMessage,
        onFeedback: (String) -> Unit
    ) {
        val body = message.text.trim()
        if (body.isEmpty()) return

        scope.launch {
            val saved = chatRepository.getConversations()
            val destinations = LinkedHashMap<String, String>()
            destinations[Constants.GROUP_PEER_ID] = Constants.GROUP_PEER_NAME
            PeerRegistry.snapshot().forEach { peer ->
                destinations[peer.id] = peer.name.ifBlank { peer.id }
            }
            saved.forEach { conv ->
                if (!destinations.containsKey(conv.peerId)) {
                    destinations[conv.peerId] = conv.peerName?.takeIf { it.isNotBlank() } ?: conv.peerId
                }
            }

            val entries = destinations.entries.toList()
            val labels = entries.map { (id, name) ->
                if (id == Constants.GROUP_PEER_ID) "👥 $name (Broadcast)" else "💬 $name"
            }.toTypedArray()

            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.forward_title)
                .setItems(labels) { _, which ->
                    val chosen = entries[which]
                    val targetThreadId = chosen.key
                    val targetThreadName = chosen.value
                    val recipient = if (targetThreadId == Constants.GROUP_PEER_ID) {
                        Constants.RECIPIENT_BROADCAST
                    } else {
                        targetThreadId
                    }
                    val forwarded = ChatMessage(text = body, isSentByMe = true)
                    if (MainActivity.isConnectedStatic()) {
                        MainActivity.transmitMessageTo(recipient, body, forwarded.messageId)
                    }
                    scope.launch {
                        chatRepository.insertMessage(forwarded, targetThreadId, targetThreadName)
                        ChatBus.notifyMessageSaved(forwarded, targetThreadId)
                    }
                    onFeedback("Forwarded to $targetThreadName")
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }
}
