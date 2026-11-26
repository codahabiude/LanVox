package com.example.wifip2pchat

import android.content.Context
import android.os.Build
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.UUID

/**
 * Provides a stable, collision-free node ID for each installation and resolves the
 * device's reachable local IPv4 address across Wi-Fi Direct (`p2p*`) and Wi-Fi LAN (`wlan*`)
 * interfaces so peers can connect directly without relying on a single central host.
 */
object DeviceIdentity {

    private const val PREFS = "lanvox_identity"
    private const val KEY_NODE_ID = "node_id"
    private const val REDACTED_MAC_1 = "02:00:00:00:00:00"
    private const val REDACTED_MAC_2 = "00:00:00:00:00:00"

    @Volatile
    private var cachedId: String? = null

    fun isUsableHardwareId(raw: String?): Boolean {
        val trimmed = raw?.trim().orEmpty()
        return trimmed.isNotBlank() &&
            trimmed != REDACTED_MAC_1 &&
            trimmed != REDACTED_MAC_2 &&
            !trimmed.equals("unknown", ignoreCase = true)
    }

    /**
     * Returns a persistent unique node identifier for this device. Uses a valid hardware
     * Wi-Fi Direct MAC if Android exposes one, otherwise falls back to a stable per-install UUID
     * so multiple Android 10+ devices never collide on `02:00:00:00:00:00`.
     */
    fun getId(context: Context, reportedMac: String? = null): String {
        if (isUsableHardwareId(reportedMac)) {
            val clean = reportedMac!!.trim()
            cachedId = clean
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_NODE_ID, clean)
                .apply()
            return clean
        }

        cachedId?.let { return it }

        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val existing = prefs.getString(KEY_NODE_ID, null)?.takeIf { isUsableHardwareId(it) }
        if (existing != null) {
            cachedId = existing
            return existing
        }

        val generated = "lvx_" + UUID.randomUUID().toString().replace("-", "").take(12)
        prefs.edit().putString(KEY_NODE_ID, generated).apply()
        cachedId = generated
        return generated
    }

    private const val KEY_CUSTOM_NAME = "custom_display_name"
    private const val KEY_STATUS_PRESET = "status_preset"
    private const val KEY_CUSTOM_BIO = "custom_bio"

    const val STATUS_AVAILABLE = "AVAILABLE"
    const val STATUS_BUSY = "BUSY"
    const val STATUS_AWAY = "AWAY"

    fun getCustomName(context: Context): String? {
        val raw = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_CUSTOM_NAME, null)
            ?.trim()
        return raw?.takeIf { it.isNotBlank() }
    }

    fun setCustomName(context: Context, name: String?) {
        val clean = name?.trim()?.take(32)
        val editor = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
        if (clean.isNullOrBlank()) {
            editor.remove(KEY_CUSTOM_NAME)
        } else {
            editor.putString(KEY_CUSTOM_NAME, clean)
        }
        editor.apply()
    }

    fun getStatusPreset(context: Context): String {
        val raw = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_STATUS_PRESET, STATUS_AVAILABLE)
            ?.trim()
        return when (raw) {
            STATUS_BUSY, STATUS_AWAY -> raw
            else -> STATUS_AVAILABLE
        }
    }

    fun setStatusPreset(context: Context, status: String) {
        val normalized = when (status) {
            STATUS_BUSY, STATUS_AWAY -> status
            else -> STATUS_AVAILABLE
        }
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_STATUS_PRESET, normalized)
            .apply()
    }

    fun getCustomBio(context: Context): String? {
        val raw = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_CUSTOM_BIO, null)
            ?.replace('\r', ' ')
            ?.replace('\n', ' ')
            ?.trim()
        return raw?.takeIf { it.isNotBlank() }
    }

    fun setCustomBio(context: Context, bio: String?) {
        val clean = bio?.replace('\r', ' ')?.replace('\n', ' ')?.trim()?.take(80)
        val editor = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
        if (clean.isNullOrBlank()) {
            editor.remove(KEY_CUSTOM_BIO)
        } else {
            editor.putString(KEY_CUSTOM_BIO, clean)
        }
        editor.apply()
    }

    fun statusDisplayLabel(status: String): String = when (status) {
        STATUS_BUSY -> "Busy"
        STATUS_AWAY -> "Away"
        else -> "Available"
    }

    fun buildProfilePayload(context: Context): String {
        val obj = org.json.JSONObject().apply {
            put("name", defaultDeviceName(context))
            put("status", getStatusPreset(context))
            put("bio", getCustomBio(context).orEmpty())
        }
        return Constants.MSG_TYPE_PROFILE + obj.toString()
    }

    data class PeerProfile(
        val name: String?,
        val status: String,
        val bio: String?
    )

    fun parseProfilePayload(payload: String): PeerProfile? {
        if (!payload.startsWith(Constants.MSG_TYPE_PROFILE)) return null
        val raw = payload.removePrefix(Constants.MSG_TYPE_PROFILE).trim()
        return runCatching {
            val obj = org.json.JSONObject(raw)
            val name = obj.optString("name").trim().takeIf { it.isNotBlank() }
            val status = when (obj.optString("status").trim()) {
                STATUS_BUSY, STATUS_AWAY -> obj.optString("status").trim()
                else -> STATUS_AVAILABLE
            }
            val bio = obj.optString("bio").trim().takeIf { it.isNotBlank() }
            PeerProfile(name = name, status = status, bio = bio)
        }.getOrNull()
    }

    fun defaultDeviceName(context: Context? = null): String {
        if (context != null) {
            getCustomName(context)?.let { return it }
        }
        return Build.MODEL?.takeIf { it.isNotBlank() } ?: "LanVox Device"
    }

    /**
     * Resolves the best local non-loopback IPv4 address for direct peer connections.
     * If [remotePeerIp] is provided, prefers an interface on the same `/24` subnet;
     * otherwise prioritizes `p2p` (Wi-Fi Direct), then `wlan`/`ap` interfaces.
     */
    fun resolveLocalIp(remotePeerIp: String? = null): String? {
        val candidates = mutableListOf<Pair<String, String>>() // ifaceName to ip
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (!iface.isUp || iface.isLoopback) continue
                val addrs = iface.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress && addr.isSiteLocalAddress) {
                        val ip = addr.hostAddress ?: continue
                        candidates += iface.name.lowercase() to ip
                    }
                }
            }
        } catch (_: Exception) {
            return null
        }

        if (candidates.isEmpty()) return null

        if (!remotePeerIp.isNullOrBlank()) {
            val prefix = remotePeerIp.substringBeforeLast('.', "")
            if (prefix.isNotBlank()) {
                candidates.firstOrNull { it.second.startsWith("$prefix.") }?.let { return it.second }
            }
        }

        return candidates.firstOrNull { it.first.contains("p2p") }?.second
            ?: candidates.firstOrNull { it.first.startsWith("wlan") || it.first.startsWith("ap") }?.second
            ?: candidates.first().second
    }
}
