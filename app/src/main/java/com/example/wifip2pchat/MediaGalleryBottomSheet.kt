package com.example.wifip2pchat

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Thread Media & Files Gallery sheet: browses all images, voice notes, and documents
 * exchanged in a conversation with category filter chips and one-tap open/play.
 */
object MediaGalleryBottomSheet {

    private enum class Filter { ALL, IMAGES, VOICE, DOCS }

    fun show(
        context: Context,
        scope: CoroutineScope,
        chatRepository: ChatRepository,
        peerId: String,
        threadTitle: String,
        onItemFeedback: (String) -> Unit
    ) {
        val dialog = BottomSheetDialog(context)
        val view = LayoutInflater.from(context).inflate(R.layout.bottom_sheet_media_gallery, null)
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

        val tvTitle = view.findViewById<TextView>(R.id.tvGalleryTitle)
        val tvSubtitle = view.findViewById<TextView>(R.id.tvGallerySubtitle)
        val rv = view.findViewById<RecyclerView>(R.id.rvMediaGallery)
        val tvEmpty = view.findViewById<TextView>(R.id.tvGalleryEmpty)
        val chipAll = view.findViewById<TextView>(R.id.chipFilterAll)
        val chipImages = view.findViewById<TextView>(R.id.chipFilterImages)
        val chipVoice = view.findViewById<TextView>(R.id.chipFilterVoice)
        val chipDocs = view.findViewById<TextView>(R.id.chipFilterDocs)

        tvTitle.text = context.getString(R.string.gallery_title)
        tvSubtitle.text = threadTitle
        view.findViewById<View>(R.id.btnGalleryClose).setOnClickListener { dialog.dismiss() }

        var allItems: List<ChatMessage> = emptyList()
        var activeFilter = Filter.ALL

        val adapter = GalleryAdapter(
            onClick = { msg ->
                if (msg.isVoiceNote && !msg.attachmentPath.isNullOrBlank()) {
                    VoiceNoteManager.togglePlayback(msg.attachmentPath)
                } else {
                    val err = FileTransferManager.openAttachment(context, msg)
                    if (err != null) {
                        onItemFeedback(err)
                    } else {
                        dialog.dismiss()
                    }
                }
            }
        )
        rv.layoutManager = LinearLayoutManager(context)
        rv.adapter = adapter

        fun applyFilter() {
            val filtered = when (activeFilter) {
                Filter.ALL -> allItems
                Filter.IMAGES -> allItems.filter { it.attachmentMime?.startsWith("image/") == true }
                Filter.VOICE -> allItems.filter { it.isVoiceNote }
                Filter.DOCS -> allItems.filter {
                    val mime = it.attachmentMime.orEmpty()
                    !mime.startsWith("image/") && !mime.startsWith("audio/")
                }
            }
            adapter.submit(filtered)
            tvSubtitle.text = "$threadTitle · ${filtered.size} item(s)"
            tvEmpty.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
            rv.visibility = if (filtered.isEmpty()) View.GONE else View.VISIBLE

            styleFilterChip(context, chipAll, activeFilter == Filter.ALL)
            styleFilterChip(context, chipImages, activeFilter == Filter.IMAGES)
            styleFilterChip(context, chipVoice, activeFilter == Filter.VOICE)
            styleFilterChip(context, chipDocs, activeFilter == Filter.DOCS)
        }

        chipAll.setOnClickListener { activeFilter = Filter.ALL; applyFilter() }
        chipImages.setOnClickListener { activeFilter = Filter.IMAGES; applyFilter() }
        chipVoice.setOnClickListener { activeFilter = Filter.VOICE; applyFilter() }
        chipDocs.setOnClickListener { activeFilter = Filter.DOCS; applyFilter() }

        applyFilter()
        scope.launch {
            allItems = withContext(Dispatchers.IO) {
                chatRepository.getAttachmentsForPeer(peerId)
            }
            applyFilter()
        }

        dialog.show()
    }

    private fun styleFilterChip(context: Context, view: TextView, selected: Boolean) {
        val density = context.resources.displayMetrics.density
        val cyan = ContextCompat.getColor(context, R.color.brand_secondary)
        val border = ContextCompat.getColor(context, R.color.glass_border)
        val bg = ColorUtils.setAlphaComponent(ContextCompat.getColor(context, R.color.surface_card), 210)
        view.setTextColor(
            if (selected) cyan else ContextCompat.getColor(context, R.color.text_secondary)
        )
        view.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 999f * density
            setColor(if (selected) ColorUtils.setAlphaComponent(cyan, 34) else bg)
            setStroke((1 * density).toInt().coerceAtLeast(1), if (selected) cyan else border)
        }
    }

    private class GalleryAdapter(
        private val onClick: (ChatMessage) -> Unit
    ) : RecyclerView.Adapter<GalleryAdapter.VH>() {

        private val items = mutableListOf<ChatMessage>()

        fun submit(list: List<ChatMessage>) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_media_gallery, parent, false)
            return VH(view)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount(): Int = items.size

        inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val ivThumb: ImageView = itemView.findViewById(R.id.ivGalleryThumb)
            private val tvName: TextView = itemView.findViewById(R.id.tvGalleryItemName)
            private val tvMeta: TextView = itemView.findViewById(R.id.tvGalleryItemMeta)
            private val tvAction: TextView = itemView.findViewById(R.id.tvGalleryItemAction)

            fun bind(msg: ChatMessage) {
                val ctx = itemView.context
                val mime = msg.attachmentMime.orEmpty()
                tvName.text = msg.attachmentName ?: "Attachment"
                val sender = if (msg.isSentByMe) "You" else (msg.senderName ?: "Peer")
                tvMeta.text = "${msg.formattedFileSize()} · $sender · ${msg.formattedTimestamp(ctx)}"

                if (msg.isVoiceNote) {
                    tvAction.text = ctx.getString(R.string.gallery_action_play)
                } else {
                    tvAction.text = ctx.getString(R.string.gallery_action_open)
                }

                val path = msg.attachmentPath
                val isImg = mime.startsWith("image/") && !path.isNullOrBlank() && File(path).exists()
                if (isImg && path != null) {
                    val bmp = runCatching {
                        val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
                        BitmapFactory.decodeFile(path, opts)
                    }.getOrNull()
                    if (bmp != null) {
                        ivThumb.setPadding(0, 0, 0, 0)
                        ivThumb.clearColorFilter()
                        ivThumb.setImageBitmap(bmp)
                    } else {
                        bindIcon(mime)
                    }
                } else {
                    bindIcon(mime)
                }

                itemView.setOnClickListener { onClick(msg) }
            }

            private fun bindIcon(mime: String) {
                val pad = (10 * itemView.resources.displayMetrics.density).toInt()
                ivThumb.setPadding(pad, pad, pad, pad)
                val iconRes = when {
                    mime.startsWith("image/") -> R.drawable.ic_file_image
                    mime.startsWith("video/") -> R.drawable.ic_videocam
                    mime.startsWith("audio/") -> R.drawable.ic_mic
                    else -> R.drawable.ic_file_doc
                }
                ivThumb.setImageResource(iconRes)
                ivThumb.setColorFilter(ContextCompat.getColor(itemView.context, R.color.brand_secondary))
            }
        }
    }
}
