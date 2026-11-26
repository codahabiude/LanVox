package com.example.wifip2pchat

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.ColorUtils
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Bottom sheet for customizing the user's device display nickname, inspecting
 * node/IP telemetry, and measuring live TCP round-trip latency (`PING`/`PONG`) to peers.
 */
object MeshDiagnosticsBottomSheet {

    fun show(
        context: Context,
        scope: CoroutineScope,
        onNicknameSaved: (String) -> Unit
    ) {
        val dialog = BottomSheetDialog(context)
        val view = LayoutInflater.from(context).inflate(R.layout.bottom_sheet_diagnostics, null)
        dialog.setContentView(view)

        var rttJob: Job? = null
        var peerToken: (() -> Unit)? = null

        dialog.setOnShowListener {
            val sheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            sheet?.setBackgroundColor(Color.TRANSPARENT)
            sheet?.let {
                val behavior = BottomSheetBehavior.from(it)
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
                behavior.skipCollapsed = true
            }
        }

        val etNick = view.findViewById<EditText>(R.id.etDiagNickname)
        val btnSave = view.findViewById<MaterialButton>(R.id.btnDiagSaveNickname)
        val tvNodeMeta = view.findViewById<TextView>(R.id.tvDiagNodeMeta)
        val btnPing = view.findViewById<MaterialButton>(R.id.btnDiagPingMesh)
        val tvPeersHeader = view.findViewById<TextView>(R.id.tvDiagPeersHeader)
        val llPeersList = view.findViewById<LinearLayout>(R.id.llDiagPeersList)
        val tvNoPeers = view.findViewById<TextView>(R.id.tvDiagNoPeers)

        view.findViewById<View>(R.id.btnDiagClose).setOnClickListener { dialog.dismiss() }

        etNick.setText(DeviceIdentity.defaultDeviceName(context))

        fun refreshMeta() {
            val ip = PeerRegistry.selfIpAddress ?: DeviceIdentity.resolveLocalIp() ?: "Unavailable"
            val role = if (PeerRegistry.isSelfGroupOwner) "Group Owner" else "Mesh Node"
            tvNodeMeta.text = "Node: ${PeerRegistry.selfId}\nIP: $ip · Role: $role"
        }

        fun renderPeers(rttMap: Map<String, Long>) {
            val peers = PeerRegistry.snapshot()
            tvPeersHeader.text = "LIVE MESH LINKS (${peers.size})"
            llPeersList.removeAllViews()
            if (peers.isEmpty()) {
                tvNoPeers.visibility = View.VISIBLE
                btnPing.isEnabled = false
                btnPing.alpha = 0.45f
                return
            }
            tvNoPeers.visibility = View.GONE
            btnPing.isEnabled = true
            btnPing.alpha = 1f

            val density = context.resources.displayMetrics.density
            val mono = ResourcesCompat.getFont(context, R.font.jetbrains_mono)
            val space = ResourcesCompat.getFont(context, R.font.space_grotesk)
            val mint = ContextCompat.getColor(context, R.color.status_online)
            val cyan = ContextCompat.getColor(context, R.color.brand_secondary)
            val amber = ContextCompat.getColor(context, R.color.status_discovering)

            peers.forEachIndexed { idx, peer ->
                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, (8 * density).toInt(), 0, (8 * density).toInt())
                    if (idx > 0) {
                        setPadding(0, (10 * density).toInt(), 0, (8 * density).toInt())
                    }
                }

                val infoCol = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }

                val tvName = TextView(context).apply {
                    text = peer.name.ifBlank { peer.id }
                    typeface = space
                    textSize = 14f
                    setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                }
                val tvSub = TextView(context).apply {
                    val ipLabel = peer.ipAddress ?: "Relayed"
                    val trLabel = peer.transport.uppercase()
                    text = "$trLabel · $ipLabel"
                    typeface = mono
                    textSize = 11f
                    setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                }
                infoCol.addView(tvName)
                infoCol.addView(tvSub)

                val rtt = rttMap[peer.id]
                val rttColor = when {
                    rtt == null -> cyan
                    rtt < 40L -> mint
                    rtt < 120L -> cyan
                    else -> amber
                }
                val rttPill = TextView(context).apply {
                    text = if (rtt != null) "${rtt} ms" else "—"
                    typeface = mono
                    textSize = 11.5f
                    setTextColor(rttColor)
                    setPadding(
                        (10 * density).toInt(),
                        (4 * density).toInt(),
                        (10 * density).toInt(),
                        (4 * density).toInt()
                    )
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = 999f * density
                        setColor(ColorUtils.setAlphaComponent(rttColor, 28))
                        setStroke((1 * density).toInt().coerceAtLeast(1), ColorUtils.setAlphaComponent(rttColor, 120))
                    }
                }

                row.addView(infoCol)
                row.addView(rttPill)
                llPeersList.addView(row)
            }
        }

        refreshMeta()
        renderPeers(MeshDiagnosticsBus.peerRttMs.value)

        // Immediately trigger a fresh ping when opening if peers exist
        MeshDiagnosticsBus.pingConnectedPeers()

        btnPing.setOnClickListener {
            MeshDiagnosticsBus.pingConnectedPeers()
        }

        btnSave.setOnClickListener {
            val entered = etNick.text.toString().trim()
            DeviceIdentity.setCustomName(context, entered)
            val resolved = DeviceIdentity.defaultDeviceName(context)
            onNicknameSaved(resolved)
            dialog.dismiss()
        }

        rttJob = scope.launch {
            MeshDiagnosticsBus.peerRttMs.collect { map ->
                renderPeers(map)
            }
        }
        peerToken = PeerRegistry.addListener {
            refreshMeta()
            renderPeers(MeshDiagnosticsBus.peerRttMs.value)
        }

        dialog.setOnDismissListener {
            rttJob?.cancel()
            peerToken?.let { PeerRegistry.removeListener(it) }
        }

        dialog.show()
    }
}
