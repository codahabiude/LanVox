package com.example.wifip2pchat

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton

/**
 * Interactive profile editor sheet allowing the user to customize their display name,
 * pick/remove a profile picture (via Android Photo Picker), set their presence status
 * (`Available`, `Busy`, `Away`), and update their status bio.
 */
class ProfileBottomSheet(
    private val context: Context,
    private val onRequestPickPhoto: () -> Unit,
    private val onPhotoRemoved: () -> Unit,
    private val onProfileSaved: (displayName: String, status: String, bio: String?) -> Unit
) {
    private val dialog = BottomSheetDialog(context)
    private val view: View = LayoutInflater.from(context).inflate(R.layout.bottom_sheet_profile, null)

    private val ivPreview: ImageView = view.findViewById(R.id.ivProfileAvatarPreview)
    private val tvInitial: TextView = view.findViewById(R.id.tvProfileAvatarInitial)
    private val btnPick: MaterialButton = view.findViewById(R.id.btnPickAvatar)
    private val btnRemove: MaterialButton = view.findViewById(R.id.btnRemoveAvatar)
    private val etName: EditText = view.findViewById(R.id.etProfileName)
    private val etBio: EditText = view.findViewById(R.id.etProfileBio)
    private val chipAvailable: TextView = view.findViewById(R.id.chipStatusAvailable)
    private val chipBusy: TextView = view.findViewById(R.id.chipStatusBusy)
    private val chipAway: TextView = view.findViewById(R.id.chipStatusAway)
    private val btnSave: MaterialButton = view.findViewById(R.id.btnProfileSave)

    private var selectedStatus: String = DeviceIdentity.getStatusPreset(context)

    init {
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

        view.findViewById<View>(R.id.btnProfileClose).setOnClickListener { dialog.dismiss() }
        btnPick.setOnClickListener { onRequestPickPhoto() }
        btnRemove.setOnClickListener {
            AvatarManager.clearSelfAvatar(context)
            refreshAvatarPreview()
            onPhotoRemoved()
        }

        chipAvailable.setOnClickListener {
            selectedStatus = DeviceIdentity.STATUS_AVAILABLE
            styleStatusChips()
        }
        chipBusy.setOnClickListener {
            selectedStatus = DeviceIdentity.STATUS_BUSY
            styleStatusChips()
        }
        chipAway.setOnClickListener {
            selectedStatus = DeviceIdentity.STATUS_AWAY
            styleStatusChips()
        }

        btnSave.setOnClickListener {
            val enteredName = etName.text.toString().trim()
            val enteredBio = etBio.text.toString().trim().takeIf { it.isNotBlank() }
            DeviceIdentity.setCustomName(context, enteredName)
            DeviceIdentity.setStatusPreset(context, selectedStatus)
            DeviceIdentity.setCustomBio(context, enteredBio)
            val resolvedName = DeviceIdentity.defaultDeviceName(context)
            dialog.dismiss()
            onProfileSaved(resolvedName, selectedStatus, enteredBio)
        }
    }

    fun show() {
        etName.setText(DeviceIdentity.defaultDeviceName(context))
        etBio.setText(DeviceIdentity.getCustomBio(context).orEmpty())
        selectedStatus = DeviceIdentity.getStatusPreset(context)
        styleStatusChips()
        refreshAvatarPreview()
        if (!dialog.isShowing) {
            dialog.show()
        }
    }

    fun refreshAvatarPreview() {
        val name = etName.text?.toString()?.takeIf { it.isNotBlank() }
            ?: DeviceIdentity.defaultDeviceName(context)
        AvatarManager.bindAvatar(
            context = context,
            imageView = ivPreview,
            initialView = tvInitial,
            peerId = PeerRegistry.selfId,
            displayName = name
        )
        btnRemove.visibility = if (AvatarManager.getSelfAvatarBitmap(context) != null) {
            View.VISIBLE
        } else {
            View.GONE
        }
    }

    fun isShowing(): Boolean = dialog.isShowing

    private fun styleStatusChips() {
        styleChip(chipAvailable, selectedStatus == DeviceIdentity.STATUS_AVAILABLE, R.color.status_online)
        styleChip(chipBusy, selectedStatus == DeviceIdentity.STATUS_BUSY, R.color.status_offline)
        styleChip(chipAway, selectedStatus == DeviceIdentity.STATUS_AWAY, R.color.status_discovering)
    }

    private fun styleChip(view: TextView, selected: Boolean, colorRes: Int) {
        val density = context.resources.displayMetrics.density
        val activeColor = ContextCompat.getColor(context, colorRes)
        val neutralStroke = ContextCompat.getColor(context, R.color.glass_border)
        val neutralBg = ColorUtils.setAlphaComponent(ContextCompat.getColor(context, R.color.surface_card), 210)
        view.setTextColor(
            if (selected) activeColor else ContextCompat.getColor(context, R.color.text_secondary)
        )
        view.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 999f * density
            setColor(if (selected) ColorUtils.setAlphaComponent(activeColor, 34) else neutralBg)
            setStroke(
                (1.2f * density).toInt().coerceAtLeast(1),
                if (selected) activeColor else neutralStroke
            )
        }
    }
}
