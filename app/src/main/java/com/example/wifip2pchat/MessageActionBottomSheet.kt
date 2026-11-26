package com.example.wifip2pchat

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog

/**
 * Instagram / WhatsApp / Messenger-inspired floating bottom sheet for message
 * reactions (`❤️ 😂 😮 😢 🙏 👍`) and quick actions (Reply, Copy, Star, Forward, Delete).
 */
object MessageActionBottomSheet {

    fun show(
        context: Context,
        message: ChatMessage,
        onReact: (String) -> Unit,
        onReply: () -> Unit,
        onCopy: () -> Unit,
        onToggleStar: () -> Unit,
        onForward: () -> Unit,
        onTogglePin: () -> Unit,
        onEdit: () -> Unit,
        onDelete: () -> Unit
    ) {
        val dialog = BottomSheetDialog(context)
        val view = LayoutInflater.from(context).inflate(R.layout.bottom_sheet_message_actions, null)
        dialog.setContentView(view)

        dialog.setOnShowListener {
            val sheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            sheet?.setBackgroundColor(Color.TRANSPARENT)
            sheet?.let {
                val behavior = BottomSheetBehavior.from(it)
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
                behavior.skipCollapsed = true
            }
        }

        view.findViewById<TextView>(R.id.tvActionMsgPreview).text = message.quoteSnippet()

        val bar = view.findViewById<LinearLayout>(R.id.llQuickReactionBar)
        val cyan = ContextCompat.getColor(context, R.color.brand_secondary)
        val density = context.resources.displayMetrics.density

        ChatMessage.QUICK_REACTIONS.forEach { emoji ->
            val selected = message.hasReacted(emoji)
            val btn = TextView(context).apply {
                text = emoji
                textSize = 22f
                gravity = Gravity.CENTER
                contentDescription = "React with $emoji"
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    (48 * density).toInt(),
                    1f
                ).apply {
                    marginStart = (2 * density).toInt()
                    marginEnd = (2 * density).toInt()
                }
                if (selected) {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = 999f * density
                        setColor(ColorUtils.setAlphaComponent(cyan, 44))
                        setStroke((1.5f * density).toInt().coerceAtLeast(1), cyan)
                    }
                }
                setOnClickListener {
                    dialog.dismiss()
                    onReact(emoji)
                }
            }
            bar.addView(btn)
        }

        view.findViewById<View>(R.id.rowMsgReply).setOnClickListener {
            dialog.dismiss()
            onReply()
        }

        val rowCopy = view.findViewById<View>(R.id.rowMsgCopy)
        if (message.text.isNotBlank() && !message.isCallLog) {
            rowCopy.visibility = View.VISIBLE
            rowCopy.setOnClickListener {
                dialog.dismiss()
                onCopy()
            }
        } else {
            rowCopy.visibility = View.GONE
        }

        val ivStar = view.findViewById<ImageView>(R.id.ivActionStar)
        val tvStar = view.findViewById<TextView>(R.id.tvActionStar)
        if (message.isStarred) {
            ivStar.setImageResource(R.drawable.ic_star)
            tvStar.text = context.getString(R.string.action_unstar_message)
        } else {
            ivStar.setImageResource(R.drawable.ic_star_outline)
            tvStar.text = context.getString(R.string.action_star_message)
        }
        view.findViewById<View>(R.id.rowMsgStar).setOnClickListener {
            dialog.dismiss()
            onToggleStar()
        }

        val rowForward = view.findViewById<View>(R.id.rowMsgForward)
        if (!message.isFileMessage && !message.isCallLog && message.text.isNotBlank()) {
            rowForward.visibility = View.VISIBLE
            rowForward.setOnClickListener {
                dialog.dismiss()
                onForward()
            }
        } else {
            rowForward.visibility = View.GONE
        }

        val rowPin = view.findViewById<View>(R.id.rowMsgPin)
        val tvPin = view.findViewById<TextView>(R.id.tvActionPin)
        if (!message.isCallLog) {
            rowPin.visibility = View.VISIBLE
            tvPin.text = context.getString(
                if (message.isPinned) R.string.action_unpin_message else R.string.action_pin_message
            )
            rowPin.setOnClickListener {
                dialog.dismiss()
                onTogglePin()
            }
        } else {
            rowPin.visibility = View.GONE
        }

        val rowEdit = view.findViewById<View>(R.id.rowMsgEdit)
        if (message.isSentByMe && !message.isFileMessage && !message.isCallLog && message.text.isNotBlank()) {
            rowEdit.visibility = View.VISIBLE
            rowEdit.setOnClickListener {
                dialog.dismiss()
                onEdit()
            }
        } else {
            rowEdit.visibility = View.GONE
        }

        view.findViewById<View>(R.id.rowMsgDelete).setOnClickListener {
            dialog.dismiss()
            onDelete()
        }

        dialog.show()
    }
}
