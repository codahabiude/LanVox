package com.example.wifip2pchat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton

/**
 * Displays LanVox's active end-to-end and at-rest encryption architecture alongside
 * verifiable 60-digit Signal-style Safety Numbers for peer key authentication.
 */
object EncryptionInfoBottomSheet {

    fun show(
        context: Context,
        peerId: String?,
        peerName: String? = null
    ) {
        CryptoManager.ensureInitialized(context)

        val dialog = BottomSheetDialog(context)
        val view = LayoutInflater.from(context).inflate(R.layout.bottom_sheet_encryption, null)
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

        view.findViewById<ImageView>(R.id.btnEncClose).setOnClickListener {
            dialog.dismiss()
        }

        val tvSelfFp = view.findViewById<TextView>(R.id.tvEncSelfFingerprint)
        tvSelfFp.text = context.getString(
            R.string.enc_self_fingerprint_fmt,
            CryptoManager.getSelfFingerprintShort(context)
        )

        val tvPeerTitle = view.findViewById<TextView>(R.id.tvEncPeerTitle)
        val tvStatusPill = view.findViewById<TextView>(R.id.tvEncPeerStatusPill)
        val tvKeyChanged = view.findViewById<TextView>(R.id.tvEncKeyChangedBanner)
        val tvInstructions = view.findViewById<TextView>(R.id.tvEncSafetyInstructions)
        val tvSafetyNumber = view.findViewById<TextView>(R.id.tvEncSafetyNumber)
        val llPeerList = view.findViewById<LinearLayout>(R.id.llEncPeerList)
        val llVerifyActions = view.findViewById<View>(R.id.llEncVerifyActions)
        val btnVerifyToggle = view.findViewById<MaterialButton>(R.id.btnEncVerifyToggle)
        val btnCopy = view.findViewById<MaterialButton>(R.id.btnEncCopy)

        var selectedPeerId = peerId?.takeIf { it.isNotBlank() && it != Constants.GROUP_PEER_ID }
        var selectedPeerName = peerName

        fun renderPeerState() {
            val targetId = selectedPeerId
            if (targetId == null) {
                tvPeerTitle.text = context.getString(R.string.enc_safety_header)
                tvStatusPill.visibility = View.GONE
                tvKeyChanged.visibility = View.GONE
                tvSafetyNumber.visibility = View.GONE
                llVerifyActions.visibility = View.GONE

                val knownIds = CryptoManager.getAllKnownPeerIds(context).sorted()
                if (knownIds.isEmpty()) {
                    tvInstructions.text = context.getString(R.string.enc_group_no_peers)
                    llPeerList.visibility = View.GONE
                } else {
                    tvInstructions.text = context.getString(R.string.enc_group_hint)
                    llPeerList.removeAllViews()
                    llPeerList.visibility = View.VISIBLE
                    for (pid in knownIds) {
                        val resolvedName = PeerRegistry.nameFor(pid) ?: pid
                        val row = TextView(context).apply {
                            val verified = CryptoManager.isPeerVerified(context, pid)
                            val changed = CryptoManager.hasPeerKeyChanged(context, pid)
                            val blocked = CryptoManager.isPeerKeyBlocked(context, pid)
                            val badge = when {
                                blocked -> "⚠ Key changed (blocked)"
                                changed -> "⚠ Key changed"
                                verified -> "✓ Verified"
                                else -> "Inspect key"
                            }
                            text = "$resolvedName  ·  $badge"
                            setTextColor(
                                ContextCompat.getColor(
                                    context,
                                    when {
                                        blocked || changed -> R.color.status_discovering
                                        verified -> R.color.status_online
                                        else -> R.color.brand_secondary
                                    }
                                )
                            )
                            textSize = 13f
                            setPadding(24, 24, 24, 24)
                            setBackgroundResource(R.drawable.bg_reaction_chip)
                            layoutParams = LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT
                            ).apply {
                                topMargin = 12
                            }
                            minHeight = (48 * context.resources.displayMetrics.density).toInt()
                            gravity = android.view.Gravity.CENTER_VERTICAL
                            setOnClickListener {
                                selectedPeerId = pid
                                selectedPeerName = resolvedName
                                renderPeerState()
                            }
                        }
                        llPeerList.addView(row)
                    }
                }
                return
            }

            llPeerList.visibility = View.GONE
            val resolvedName = selectedPeerName?.takeIf { it.isNotBlank() }
                ?: PeerRegistry.nameFor(targetId)
                ?: targetId

            tvPeerTitle.text = "${context.getString(R.string.enc_safety_header)} · $resolvedName"

            val safetyNumber = CryptoManager.getSafetyNumber(context, targetId)
            if (safetyNumber == null) {
                tvStatusPill.visibility = View.GONE
                tvKeyChanged.visibility = View.GONE
                tvSafetyNumber.visibility = View.GONE
                llVerifyActions.visibility = View.GONE
                tvInstructions.text = context.getString(R.string.enc_no_peer_key, resolvedName)
                return
            }

            val isVerified = CryptoManager.isPeerVerified(context, targetId)
            val keyChanged = CryptoManager.hasPeerKeyChanged(context, targetId)
            val isBlocked = CryptoManager.isPeerKeyBlocked(context, targetId)

            tvStatusPill.visibility = View.VISIBLE
            when {
                keyChanged -> {
                    tvStatusPill.setText(R.string.enc_status_key_changed)
                    tvStatusPill.setTextColor(ContextCompat.getColor(context, R.color.status_discovering))
                    tvKeyChanged.visibility = View.VISIBLE
                    tvKeyChanged.text = context.getString(R.string.enc_key_changed_warning, resolvedName)
                }
                isVerified -> {
                    tvStatusPill.setText(R.string.enc_status_verified)
                    tvStatusPill.setTextColor(ContextCompat.getColor(context, R.color.status_online))
                    tvKeyChanged.visibility = View.GONE
                }
                else -> {
                    tvStatusPill.setText(R.string.enc_status_unverified)
                    tvStatusPill.setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                    tvKeyChanged.visibility = View.GONE
                }
            }

            tvInstructions.text = context.getString(R.string.enc_safety_instructions, resolvedName)
            tvSafetyNumber.visibility = View.VISIBLE
            tvSafetyNumber.text = safetyNumber
            llVerifyActions.visibility = View.VISIBLE

            btnVerifyToggle.setText(
                when {
                    isBlocked -> R.string.enc_btn_accept_key
                    isVerified -> R.string.enc_btn_unverify
                    else -> R.string.enc_btn_mark_verified
                }
            )
            btnVerifyToggle.setOnClickListener {
                if (isBlocked) {
                    CryptoManager.acceptPendingKey(context, targetId)
                    Toast.makeText(context, "New key accepted. Please verify Safety Number.", Toast.LENGTH_SHORT).show()
                } else {
                    val next = !CryptoManager.isPeerVerified(context, targetId)
                    CryptoManager.setPeerVerified(context, targetId, next)
                    if (!next) {
                        CryptoManager.acknowledgeKeyChange(context, targetId)
                    }
                }
                renderPeerState()
            }
            btnCopy.setOnClickListener {
                val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                cb?.setPrimaryClip(ClipData.newPlainText("LanVox Safety Number", safetyNumber.replace('\n', ' ')))
                Toast.makeText(context, R.string.enc_copied_msg, Toast.LENGTH_SHORT).show()
            }
        }

        renderPeerState()
        dialog.show()
    }
}
