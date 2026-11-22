package com.example.wifip2pchat

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.HapticFeedbackConstants
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.abs
import kotlin.math.min

/**
 * Attaches bidirectional swipe gestures to a chat [RecyclerView]:
 *  - **Swipe RIGHT** -> WhatsApp / IG style **Swipe-to-Reply** (snaps bubble back cleanly)
 *  - **Swipe LEFT**  -> Glassmorphic crimson **Swipe-to-Delete**
 */
object MessageSwipeHelper {

    fun attach(
        recyclerView: RecyclerView,
        adapter: MessageAdapter,
        onReplyMessage: ((ChatMessage) -> Unit)? = null,
        onSwipedDelete: (ChatMessage) -> Unit
    ): ItemTouchHelper {
        val dirs = if (onReplyMessage != null) {
            ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
        } else {
            ItemTouchHelper.LEFT
        }

        val callback = object : ItemTouchHelper.SimpleCallback(0, dirs) {
            private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
            }
            private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
            }
            private var hapticTriggered = false

            override fun onMove(
                rv: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean = false

            override fun getSwipeThreshold(viewHolder: RecyclerView.ViewHolder): Float = 0.28f

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val position = viewHolder.adapterPosition
                if (position == RecyclerView.NO_POSITION) return
                hapticTriggered = false
                if (direction == ItemTouchHelper.RIGHT && onReplyMessage != null) {
                    val target = adapter.getMessageAt(position)
                    adapter.notifyItemChanged(position)
                    if (target != null) {
                        onReplyMessage(target)
                    }
                } else {
                    val removed = adapter.removeAt(position) ?: return
                    onSwipedDelete(removed)
                }
            }

            override fun onChildDraw(
                c: Canvas,
                rv: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                dX: Float,
                dY: Float,
                actionState: Int,
                isCurrentlyActive: Boolean
            ) {
                val itemView = viewHolder.itemView
                val clampedDx = if (dX > 0f && onReplyMessage != null) {
                    min(dX, itemView.width * 0.36f)
                } else {
                    dX
                }

                if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE && abs(clampedDx) > 1f) {
                    val ctx = rv.context
                    val density = ctx.resources.displayMetrics.density
                    val cornerRadius = 18f * density
                    val marginH = 12f * density
                    val marginV = 5f * density
                    val isReplySwipe = clampedDx > 0f && onReplyMessage != null
                    val accentColor = ContextCompat.getColor(
                        ctx,
                        if (isReplySwipe) R.color.brand_secondary else R.color.call_decline
                    )

                    val triggerDist = itemView.width * 0.28f
                    val progress = min(1f, abs(clampedDx) / triggerDist)
                    if (isCurrentlyActive && progress >= 1f && !hapticTriggered) {
                        hapticTriggered = true
                        itemView.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    } else if (progress < 0.5f) {
                        hapticTriggered = false
                    }

                    if (!isReplySwipe) {
                        itemView.alpha = 1f - 0.35f * progress
                    }

                    val top = itemView.top + marginV
                    val bottom = itemView.bottom - marginV
                    val rect = if (isReplySwipe) {
                        RectF(
                            itemView.left + marginH,
                            top,
                            (itemView.left + clampedDx - marginH * 0.4f).coerceAtLeast(itemView.left + marginH),
                            bottom
                        )
                    } else {
                        RectF(
                            (itemView.right + clampedDx + marginH * 0.5f).coerceAtMost(itemView.right - marginH),
                            top,
                            itemView.right - marginH,
                            bottom
                        )
                    }

                    if (rect.width() > cornerRadius) {
                        fillPaint.color = accentColor
                        fillPaint.alpha = (52 * progress).toInt()
                        strokePaint.color = accentColor
                        strokePaint.alpha = (180 * progress).toInt()
                        strokePaint.strokeWidth = 1.2f * density

                        c.drawRoundRect(rect, cornerRadius, cornerRadius, fillPaint)
                        c.drawRoundRect(rect, cornerRadius, cornerRadius, strokePaint)

                        val iconRes = if (isReplySwipe) R.drawable.ic_reply else R.drawable.ic_delete
                        val icon = ContextCompat.getDrawable(ctx, iconRes)
                        if (icon != null && rect.width() > 32f * density) {
                            val wrapped = DrawableCompat.wrap(icon.mutate())
                            DrawableCompat.setTint(wrapped, accentColor)
                            wrapped.alpha = (255 * progress).toInt()
                            val iconSize = (20f * density).toInt()
                            val iconTop = itemView.top + (itemView.height - iconSize) / 2
                            val iconCenterX = if (isReplySwipe) {
                                (rect.left + 22f * density).toInt()
                            } else {
                                (rect.right - 22f * density).toInt()
                            }
                            wrapped.setBounds(
                                iconCenterX - iconSize / 2,
                                iconTop,
                                iconCenterX + iconSize / 2,
                                iconTop + iconSize
                            )
                            wrapped.draw(c)
                        }
                    }
                } else if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE) {
                    itemView.alpha = 1f
                }

                super.onChildDraw(c, rv, viewHolder, clampedDx, dY, actionState, isCurrentlyActive)
            }

            override fun clearView(rv: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(rv, viewHolder)
                hapticTriggered = false
                viewHolder.itemView.alpha = 1f
            }
        }

        return ItemTouchHelper(callback).apply { attachToRecyclerView(recyclerView) }
    }
}
