package com.example.wifip2pchat

import android.content.Context

/**
 * Remembers the details of the last WiFi Direct session so that the
 * standby listener ([CallListenerService]) can keep receiving calls and
 * messages even after the app process has been killed or the phone
 * has been rebooted.
 *
 * Saved by [MainActivity] every time a group is formed, cleared when
 * the user disconnects.
 *
 * Stored values:
 *  - role          "go" (this phone is group owner) or "client"
 *  - go_address    IP of the group owner (our own IP when we are the GO)
 *  - peer_mac      WiFi Direct MAC of the remote phone (chat thread key)
 *  - peer_name     human-readable name of the remote phone
 */
object LastSession {

    private const val PREFS = "lanvox_session"

    private const val KEY_ROLE = "role"
    private const val KEY_GO_ADDRESS = "go_address"
    private const val KEY_PEER_MAC = "peer_mac"
    private const val KEY_PEER_NAME = "peer_name"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Persist the current connection snapshot. Call whenever the group forms. */
    fun save(
        context: Context,
        isGroupOwner: Boolean,
        groupOwnerAddress: String?,
        peerMac: String?,
        peerName: String?
    ) {
        prefs(context).edit()
            .putString(KEY_ROLE, if (isGroupOwner) "go" else "client")
            .putString(KEY_GO_ADDRESS, groupOwnerAddress)
            .putString(KEY_PEER_MAC, peerMac)
            .putString(KEY_PEER_NAME, peerName)
            .apply()
    }

    /** Store only the peer identity once it has been resolved (async). */
    fun savePeer(context: Context, peerMac: String?, peerName: String?) {
        prefs(context).edit()
            .putString(KEY_PEER_MAC, peerMac)
            .putString(KEY_PEER_NAME, peerName)
            .apply()
    }

    /** Called when the user disconnects — nothing reliable to listen on. */
    fun clearRole(context: Context) {
        prefs(context).edit()
            .remove(KEY_ROLE)
            .remove(KEY_GO_ADDRESS)
            .apply()
    }

    /** True when the last session ended with a live connection. */
    fun hasRole(context: Context): Boolean =
        prefs(context).getString(KEY_ROLE, null) != null

    fun isGroupOwner(context: Context): Boolean =
        prefs(context).getString(KEY_ROLE, null) == "go"

    fun groupOwnerAddress(context: Context): String? =
        prefs(context).getString(KEY_GO_ADDRESS, null)

    fun peerMac(context: Context): String? =
        prefs(context).getString(KEY_PEER_MAC, null)

    fun peerName(context: Context): String? =
        prefs(context).getString(KEY_PEER_NAME, null)
}
