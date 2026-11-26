package com.example.wifip2pchat

import android.content.Context
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog

/**
 * Polished Material BottomSheetDialog housing secondary & destructive actions
 * (Export Chats, Inline Radar toggle, Disconnect, Clear All Chat History)
 * so the main app bar remains uncluttered and accidental deletions are avoided.
 */
object MainActionsBottomSheet {

    fun show(
        context: Context,
        storedMessageCount: Int,
        isConnected: Boolean,
        isScannerExpanded: Boolean? = null,
        ephemeralTtlSeconds: Long = 0L,
        onExportChats: (() -> Unit)? = null,
        onToggleRadar: (() -> Unit)? = null,
        onConfigureEphemeral: (() -> Unit)? = null,
        onOpenMeshDiagnostics: (() -> Unit)? = null,
        onDisconnect: (() -> Unit)? = null,
        onClearAllHistory: () -> Unit
    ) {
        val dialog = BottomSheetDialog(context)
        val view = LayoutInflater.from(context).inflate(R.layout.bottom_sheet_actions, null)
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

        val tvSummary = view.findViewById<TextView>(R.id.tvActionsStorageSummary)
        tvSummary.text = if (storedMessageCount == 1) {
            "1 message stored on this device"
        } else {
            "$storedMessageCount messages stored on this device"
        }

        view.findViewById<ImageView>(R.id.btnActionsClose).setOnClickListener {
            dialog.dismiss()
        }

        val rowExport = view.findViewById<View>(R.id.rowActionExport)
        if (onExportChats != null) {
            rowExport.visibility = View.VISIBLE
            rowExport.setOnClickListener {
                dialog.dismiss()
                onExportChats()
            }
        } else {
            rowExport.visibility = View.GONE
        }

        val rowRadar = view.findViewById<View>(R.id.rowActionRadar)
        val tvRadarTitle = view.findViewById<TextView>(R.id.tvActionRadarTitle)
        if (onToggleRadar != null && isScannerExpanded != null) {
            rowRadar.visibility = View.VISIBLE
            tvRadarTitle.text = if (isScannerExpanded) {
                context.getString(R.string.action_hide_radar)
            } else {
                context.getString(R.string.action_toggle_radar)
            }
            rowRadar.setOnClickListener {
                dialog.dismiss()
                onToggleRadar()
            }
        } else {
            rowRadar.visibility = View.GONE
        }

        val rowEphemeral = view.findViewById<View>(R.id.rowActionEphemeral)
        val tvEphemeralSub = view.findViewById<TextView>(R.id.tvActionEphemeralSub)
        if (onConfigureEphemeral != null) {
            rowEphemeral.visibility = View.VISIBLE
            tvEphemeralSub.text = EphemeralPrefs.describeForSheet(context, ephemeralTtlSeconds)
            rowEphemeral.setOnClickListener {
                dialog.dismiss()
                onConfigureEphemeral()
            }
        } else {
            rowEphemeral.visibility = View.GONE
        }

        val rowMeshDiag = view.findViewById<View>(R.id.rowActionMeshDiagnostics)
        val tvMeshDiagSub = view.findViewById<TextView>(R.id.tvActionMeshDiagnosticsSub)
        if (onOpenMeshDiagnostics != null) {
            rowMeshDiag.visibility = View.VISIBLE
            val nick = DeviceIdentity.defaultDeviceName(context)
            tvMeshDiagSub.text = "$nick · ${PeerRegistry.snapshot().size} active peer(s)"
            rowMeshDiag.setOnClickListener {
                dialog.dismiss()
                onOpenMeshDiagnostics()
            }
        } else {
            rowMeshDiag.visibility = View.GONE
        }

        val rowDisconnect = view.findViewById<View>(R.id.rowActionDisconnect)
        if (isConnected && onDisconnect != null) {
            rowDisconnect.visibility = View.VISIBLE
            rowDisconnect.setOnClickListener {
                dialog.dismiss()
                onDisconnect()
            }
        } else {
            rowDisconnect.visibility = View.GONE
        }

        view.findViewById<View>(R.id.rowActionClearAll).setOnClickListener {
            dialog.dismiss()
            onClearAllHistory()
        }

        dialog.show()
    }
}
