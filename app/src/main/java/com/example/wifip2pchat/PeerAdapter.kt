package com.example.wifip2pchat

import android.net.wifi.p2p.WifiP2pDevice
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.ImageView
import com.google.android.material.button.MaterialButton
import androidx.recyclerview.widget.RecyclerView

/**
 * Adapter for displaying discovered WiFi Direct peers in a list.
 * Each item shows device info with Call and Video pill buttons.
 */
class PeerAdapter(
    private val onPeerClick: (WifiP2pDevice) -> Unit,
    private val onCallClick: (WifiP2pDevice) -> Unit,
    private val onVideoClick: (WifiP2pDevice) -> Unit
) : RecyclerView.Adapter<PeerAdapter.PeerViewHolder>() {

    private val peers = mutableListOf<WifiP2pDevice>()

    fun updatePeers(newPeers: List<WifiP2pDevice>) {
        peers.clear()
        peers.addAll(newPeers)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PeerViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_peer, parent, false)
        return PeerViewHolder(view)
    }

    override fun onBindViewHolder(holder: PeerViewHolder, position: Int) {
        val device = peers[position]
        holder.bind(device)
        holder.itemView.setOnClickListener { onPeerClick(device) }
        holder.btnCall?.setOnClickListener { onCallClick(device) }
        holder.btnVideo?.setOnClickListener { onVideoClick(device) }
    }

    override fun getItemCount() = peers.size

    class PeerViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvName: TextView? = itemView.findViewById(R.id.tvDeviceName)
        private val tvStatus: TextView? = itemView.findViewById(R.id.tvDeviceStatus)
        val btnCall: MaterialButton? = itemView.findViewById(R.id.btnPeerCall)
        val btnVideo: MaterialButton? = itemView.findViewById(R.id.btnPeerVideo)

        fun bind(device: WifiP2pDevice) {
            tvName?.text = device.deviceName.ifBlank { device.deviceAddress }
            // Friendlier status line — no "Hotspot" wording.
            val statusStr = deviceStatusToString(device.status)
            tvStatus?.text = "$statusStr · Strong signal"
            // First letter of the device name on the avatar
            val initial = device.deviceName.trim().take(1).ifBlank { "L" }.uppercase()
            (itemView.findViewById<TextView>(R.id.tvAvatarInitial))?.text = initial
        }

        private fun deviceStatusToString(status: Int): String = when (status) {
            WifiP2pDevice.CONNECTED -> "Connected"
            WifiP2pDevice.INVITED -> "Invited"
            WifiP2pDevice.FAILED -> "Failed"
            WifiP2pDevice.AVAILABLE -> "Available"
            WifiP2pDevice.UNAVAILABLE -> "Unavailable"
            else -> "Unknown"
        }
    }
}
