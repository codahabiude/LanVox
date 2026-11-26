package com.example.wifip2pchat

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.ColorUtils
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Shared binder for the collapsible Quick-Reply Templates + Quick Poll strip
 * used in both [MainActivity] and [ChatActivity].
 */
object QuickReplyStripHelper {

    fun bind(
        context: Context,
        toggleButton: ImageView,
        scrollContainer: HorizontalScrollView,
        chipRow: LinearLayout,
        targetEditText: EditText,
        onCreatePoll: () -> Unit,
        onFeedback: (String) -> Unit
    ) {
        fun updateToggleTint(visible: Boolean) {
            toggleButton.setColorFilter(
                ContextCompat.getColor(
                    context,
                    if (visible) R.color.brand_secondary else R.color.text_secondary
                )
            )
        }

        fun renderChips() {
            chipRow.removeAllViews()
            val density = context.resources.displayMetrics.density
            val cyan = ContextCompat.getColor(context, R.color.brand_secondary)
            val borderNeutral = ContextCompat.getColor(context, R.color.glass_border)
            val bgNeutral = ColorUtils.setAlphaComponent(
                ContextCompat.getColor(context, R.color.surface_card),
                220
            )
            val textPrimary = ContextCompat.getColor(context, R.color.text_primary)
            val font = ResourcesCompat.getFont(context, R.font.plus_jakarta_sans)

            fun addChip(
                label: String,
                accentColor: Int? = null,
                onClick: () -> Unit,
                onLongClick: (() -> Unit)? = null
            ) {
                val chip = TextView(context).apply {
                    text = label
                    textSize = 12f
                    typeface = font
                    isSingleLine = true
                    minHeight = (38 * density).toInt()
                    gravity = Gravity.CENTER
                    setTextColor(accentColor ?: textPrimary)
                    setPadding(
                        (12 * density).toInt(),
                        (6 * density).toInt(),
                        (12 * density).toInt(),
                        (6 * density).toInt()
                    )
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = 999f * density
                        setColor(
                            if (accentColor != null) ColorUtils.setAlphaComponent(accentColor, 34)
                            else bgNeutral
                        )
                        setStroke(
                            (1 * density).toInt().coerceAtLeast(1),
                            accentColor ?: borderNeutral
                        )
                    }
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        marginEnd = (6 * density).toInt()
                    }
                    setOnClickListener { onClick() }
                    if (onLongClick != null) {
                        setOnLongClickListener {
                            onLongClick()
                            true
                        }
                    }
                }
                chipRow.addView(chip)
            }

            // First quick-action chip: Create Live Poll
            addChip(
                label = "📊 ${context.getString(R.string.action_create_poll)}",
                accentColor = cyan,
                onClick = onCreatePoll
            )

            // User-customizable quick-reply templates
            QuickReplyPrefs.getTemplates(context).forEach { tpl ->
                addChip(
                    label = tpl,
                    accentColor = null,
                    onClick = {
                        val existing = targetEditText.text?.toString().orEmpty()
                        val updated = if (existing.isBlank()) tpl else "$existing $tpl"
                        targetEditText.setText(updated)
                        targetEditText.setSelection(updated.length)
                        targetEditText.requestFocus()
                    },
                    onLongClick = {
                        MaterialAlertDialogBuilder(context)
                            .setTitle("Remove quick reply?")
                            .setMessage("\"$tpl\"")
                            .setNegativeButton(android.R.string.cancel, null)
                            .setPositiveButton(R.string.action_delete_message) { _, _ ->
                                QuickReplyPrefs.removeTemplate(context, tpl)
                                renderChips()
                                onFeedback("Template removed")
                            }
                            .show()
                    }
                )
            }

            // Trailing "+ Add" chip
            addChip(
                label = context.getString(R.string.quick_reply_add),
                accentColor = ContextCompat.getColor(context, R.color.status_online),
                onClick = {
                    val densityPx = (18 * density).toInt()
                    val input = EditText(context).apply {
                        hint = context.getString(R.string.quick_reply_add_hint)
                        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                        maxLines = 2
                    }
                    val container = FrameLayout(context).apply {
                        setPadding(densityPx, (8 * density).toInt(), densityPx, 0)
                        addView(input)
                    }
                    MaterialAlertDialogBuilder(context)
                        .setTitle(R.string.quick_reply_add_title)
                        .setView(container)
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(android.R.string.ok) { _, _ ->
                            val entered = input.text?.toString()?.trim().orEmpty()
                            if (entered.isNotEmpty()) {
                                QuickReplyPrefs.addTemplate(context, entered)
                                renderChips()
                                onFeedback("Quick reply added")
                            }
                        }
                        .show()
                }
            )
        }

        renderChips()
        updateToggleTint(scrollContainer.visibility == View.VISIBLE)

        toggleButton.setOnClickListener {
            val opening = scrollContainer.visibility != View.VISIBLE
            if (opening) renderChips()
            scrollContainer.visibility = if (opening) View.VISIBLE else View.GONE
            updateToggleTint(opening)
        }
    }
}
