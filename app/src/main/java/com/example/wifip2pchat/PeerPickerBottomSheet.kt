package com.example.wifip2pchat

import android.content.Context
import android.graphics.Color
import android.net.wifi.p2p.WifiP2pDevice
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator

/**
 * Material BottomSheetDialog displaying discovered Wi-Fi Direct peers
 * and letting the user select one to connect, start/stop scanning, or disconnect.
 */
class PeerPickerBottomSheet(
    context: Context,
    private val onPeerSelected: (WifiP2pDevice) -> Unit,
    private val onPeerAudioCall: (WifiP2pDevice) -> Unit,
    private val onPeerVideoCall: (WifiP2pDevice) -> Unit,
    private val onToggleScan: () -> Unit,
    private val onDisconnect: () -> Unit,
    private val onDismissed: () -> Unit = {}
) {
    private val dialog = BottomSheetDialog(context)
    private val contentView: View = LayoutInflater.from(context)
        .inflate(R.layout.bottom_sheet_peers, null)

    private val sheetRadarView: RadarView = contentView.findViewById(R.id.sheetRadarView)
    private val sheetRadarPulse: View = contentView.findViewById(R.id.sheetRadarPulse)
    private val sheetEmptyRadarView: RadarView = contentView.findViewById(R.id.sheetEmptyRadarView)
    private val sheetEmptyPulseRing: View = contentView.findViewById(R.id.sheetEmptyPulseRing)
    private val tvSubtitle: TextView = contentView.findViewById(R.id.tvSheetSubtitle)
    private val btnClose: ImageView = contentView.findViewById(R.id.btnSheetClose)
    private val progressScanning: LinearProgressIndicator =
        contentView.findViewById(R.id.progressSheetScanning)
    private val rvPeers: RecyclerView = contentView.findViewById(R.id.rvSheetPeers)
    private val llEmpty: LinearLayout = contentView.findViewById(R.id.llSheetPeersEmpty)
    private val tvEmptyTitle: TextView = contentView.findViewById(R.id.tvSheetEmptyTitle)
    private val tvEmptySubtitle: TextView = contentView.findViewById(R.id.tvSheetEmptySubtitle)
    private val btnEmptyScan: MaterialButton = contentView.findViewById(R.id.btnSheetEmptyScan)
    private val btnScan: MaterialButton = contentView.findViewById(R.id.btnSheetScan)
    private val btnDisconnect: MaterialButton = contentView.findViewById(R.id.btnSheetDisconnect)

    private val adapter = PeerAdapter(
        onPeerClick = { device ->
            dismiss()
            onPeerSelected(device)
        },
        onCallClick = { device ->
            dismiss()
            onPeerAudioCall(device)
        },
        onVideoClick = { device ->
            dismiss()
            onPeerVideoCall(device)
        }
    )

    init {
        dialog.setContentView(contentView)
        dialog.setOnShowListener {
            val sheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            sheet?.setBackgroundColor(Color.TRANSPARENT)
            sheet?.let {
                val behavior = BottomSheetBehavior.from(it)
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
                behavior.skipCollapsed = true
            }
        }
        dialog.setOnDismissListener {
            sheetRadarView.stopSweeping()
            sheetEmptyRadarView.stopSweeping()
            sheetRadarPulse.clearAnimation()
            sheetEmptyPulseRing.clearAnimation()
            onDismissed()
        }

        rvPeers.layoutManager = LinearLayoutManager(context)
        rvPeers.adapter = adapter

        btnClose.setOnClickListener { dismiss() }
        btnScan.setOnClickListener { onToggleScan() }
        btnEmptyScan.setOnClickListener { onToggleScan() }
        btnDisconnect.setOnClickListener {
            dismiss()
            onDisconnect()
        }
    }

    fun show(peers: List<WifiP2pDevice>, isDiscovering: Boolean, isConnected: Boolean) {
        updateState(peers, isDiscovering, isConnected)
        if (!dialog.isShowing) {
            dialog.show()
        }
    }

    fun updateState(peers: List<WifiP2pDevice>, isDiscovering: Boolean, isConnected: Boolean) {
        adapter.updatePeers(peers)
        sheetRadarView.setBlipCount(peers.size)
        sheetEmptyRadarView.setBlipCount(peers.size)
        val showEmpty = peers.isEmpty()
        llEmpty.visibility = if (showEmpty) View.VISIBLE else View.GONE
        rvPeers.visibility = if (showEmpty) View.GONE else View.VISIBLE

        progressScanning.visibility = if (isDiscovering) View.VISIBLE else View.GONE
        val context = contentView.context
        if (isDiscovering) {
            sheetRadarView.startSweeping()
            btnScan.visibility = View.VISIBLE
            btnScan.setText(R.string.btn_stop_scan)
            btnScan.setIconResource(R.drawable.ic_close)
            tvSubtitle.setText(R.string.sheet_peers_hint_scanning)
            tvEmptyTitle.setText(R.string.sheet_peers_scanning_empty_title)
            tvEmptySubtitle.setText(R.string.sheet_peers_scanning_empty_subtitle)
            btnEmptyScan.visibility = View.GONE
            sheetRadarPulse.visibility = View.VISIBLE
            if (sheetRadarPulse.animation == null) {
                sheetRadarPulse.startAnimation(
                    android.view.animation.AnimationUtils.loadAnimation(context, R.anim.pulse_scan)
                )
            }
            if (showEmpty) {
                sheetEmptyRadarView.startSweeping()
                sheetEmptyPulseRing.visibility = View.VISIBLE
                if (sheetEmptyPulseRing.animation == null) {
                    sheetEmptyPulseRing.startAnimation(
                        android.view.animation.AnimationUtils.loadAnimation(context, R.anim.pulse_scan)
                    )
                }
            } else {
                sheetEmptyRadarView.stopSweeping()
                sheetEmptyPulseRing.clearAnimation()
                sheetEmptyPulseRing.visibility = View.INVISIBLE
            }
        } else {
            sheetRadarView.stopSweeping()
            sheetEmptyRadarView.stopSweeping()
            sheetRadarPulse.clearAnimation()
            sheetRadarPulse.visibility = View.INVISIBLE
            sheetEmptyPulseRing.clearAnimation()
            sheetEmptyPulseRing.visibility = View.INVISIBLE
            tvEmptyTitle.setText(R.string.sheet_peers_empty_title)
            tvEmptySubtitle.setText(R.string.sheet_peers_empty_subtitle)
            btnEmptyScan.visibility = if (showEmpty) View.VISIBLE else View.GONE
            btnScan.visibility = if (showEmpty) View.GONE else View.VISIBLE
            btnScan.setText(R.string.btn_scan)
            btnScan.setIconResource(R.drawable.ic_search)
            tvSubtitle.text = if (showEmpty) {
                context.getString(R.string.sheet_peers_hint_idle)
            } else {
                "${peers.size} device(s) nearby — tap to connect"
            }
        }

        btnDisconnect.visibility = if (isConnected) View.VISIBLE else View.GONE
        (btnDisconnect.layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
            lp.marginStart = if (btnScan.visibility == View.VISIBLE) {
                (10 * context.resources.displayMetrics.density).toInt()
            } else {
                0
            }
            btnDisconnect.layoutParams = lp
        }
    }

    fun isShowing(): Boolean = dialog.isShowing

    fun dismiss() {
        if (dialog.isShowing) {
            dialog.dismiss()
        }
    }
}
