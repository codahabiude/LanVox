package com.example.wifip2pchat

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.abs
import kotlin.math.min

/**
 * Attaches horizontal swipe-to-delete behavior to a chat [RecyclerView] with
 * a glassmorphic crimson delete backdrop and trash glyph.
 */
object MessageSwipeHelper {

    fun attach(
        recyclerView: RecyclerView,
        adapter: MessageAdapter,
        onSwipedMessage: (ChatMessage) -> Unit
    ): ItemTouchHelper {
        val callback = object : ItemTouchHelper.SimpleCallback(
            0,
            ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
        ) {
            private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
            }
            private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
            }

            override fun onMove(
                rv: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean = false

            override fun getSwipeThreshold(viewHolder: RecyclerView.ViewHolder): Float = 0.35f

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val position = viewHolder.adapterPosition
                if (position == RecyclerView.NO_POSITION) return
                val removed = adapter.removeAt(position) ?: return
                onSwipedMessage(removed)
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
                if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE && abs(dX) > 1f) {
                    val ctx = rv.context
                    val density = ctx.resources.displayMetrics.density
                    val cornerRadius = 18f * density
                    val marginH = 12f * density
                    val marginV = 5f * density
                    val dangerColor = ContextCompat.getColor(ctx, R.color.call_decline)

                    val progress = min(1f, abs(dX) / (itemView.width * 0.45f))
                    itemView.alpha = 1f - 0.35f * progress

                    val top = itemView.top + marginV
                    val bottom = itemView.bottom - marginV
                    val rect = if (dX > 0f) {
                        RectF(
                            itemView.left + marginH,
                            top,
                            (itemView.left + dX - marginH * 0.5f).coerceAtLeast(itemView.left + marginH),
                            bottom
                        )
                    } else {
                        RectF(
                            (itemView.right + dX + marginH * 0.5f).coerceAtMost(itemView.right - marginH),
                            top,
                            itemView.right - marginH,
                            bottom
                        )
                    }

                    if (rect.width() > cornerRadius) {
                        fillPaint.color = dangerColor
                        fillPaint.alpha = (56 * progress).toInt()
                        strokePaint.color = dangerColor
                        strokePaint.alpha = (180 * progress).toInt()
                        strokePaint.strokeWidth = 1.2f * density

                        c.drawRoundRect(rect, cornerRadius, cornerRadius, fillPaint)
                        c.drawRoundRect(rect, cornerRadius, cornerRadius, strokePaint)

                        val icon = ContextCompat.getDrawable(ctx, R.drawable.ic_delete)
                        if (icon != null && rect.width() > 36f * density) {
                            val wrapped = DrawableCompat.wrap(icon.mutate())
                            DrawableCompat.setTint(wrapped, dangerColor)
                            wrapped.alpha = (255 * progress).toInt()
                            val iconSize = (20f * density).toInt()
                            val iconTop = (itemView.top + (itemView.height - iconSize) / 2)
                            val iconCenterX = if (dX > 0f) {
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

                super.onChildDraw(c, rv, viewHolder, dX, dY, actionState, isCurrentlyActive)
            }

            override fun clearView(rv: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(rv, viewHolder)
                viewHolder.itemView.alpha = 1f
            }
        }

        return ItemTouchHelper(callback).apply { attachToRecyclerView(recyclerView) }
    }
}
