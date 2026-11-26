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
        private val tvAvatarInitial: TextView? = itemView.findViewById(R.id.tvAvatarInitial)
        private val ivPeerAvatar: ImageView? = itemView.findViewById(R.id.ivPeerAvatar)
        private val viewPresenceDot: View? = itemView.findViewById(R.id.viewPeerPresenceDot)
        val btnCall: MaterialButton? = itemView.findViewById(R.id.btnPeerCall)
        val btnVideo: MaterialButton? = itemView.findViewById(R.id.btnPeerVideo)

        fun bind(device: WifiP2pDevice) {
            val matchedPeer = PeerRegistry.snapshot().firstOrNull {
                it.id == device.deviceAddress || it.name.equals(device.deviceName, ignoreCase = true)
            }
            val displayName = matchedPeer?.name?.takeIf { it.isNotBlank() }
                ?: device.deviceName.ifBlank { device.deviceAddress }
            tvName?.text = displayName

            val statusStr = deviceStatusToString(device.status)
            val presenceSuffix = matchedPeer?.let { peer ->
                val pLabel = DeviceIdentity.statusDisplayLabel(peer.presenceStatus)
                if (!peer.bio.isNullOrBlank()) "$pLabel · ${peer.bio}" else pLabel
            } ?: "Strong signal"
            tvStatus?.text = "$statusStr · $presenceSuffix"

            AvatarManager.bindAvatar(
                context = itemView.context,
                imageView = ivPeerAvatar,
                initialView = tvAvatarInitial,
                peerId = matchedPeer?.id ?: device.deviceAddress,
                displayName = displayName
            )
            val isOnline = device.status != WifiP2pDevice.UNAVAILABLE &&
                device.status != WifiP2pDevice.FAILED
            AvatarManager.applyPresenceDot(viewPresenceDot, matchedPeer?.presenceStatus, isOnline)
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
