package com.example.wifip2pchat

/**
 * Lightweight event bus for LANVOX chat screens.
 *
 * MainActivity persists every incoming message and publishes it here;
 * whichever ChatActivity is currently open (and matching the peer) picks
 * it up and appends it live to the conversation.
 *
 * Uses the same static-reference pattern as the rest of the app
 * (ChatServerService.onMessageReceived, MainActivity.staticInstance).
 */
object ChatBus {

    /** Peer id of the conversation currently shown in ChatActivity (null = none open). */
    @Volatile
    var activeChatPeerId: String? = null

    /** True only while ChatActivity is resumed and visible in the foreground. */
    @Volatile
    var isChatScreenForeground: Boolean = false

    /** Set by ChatActivity: (saved message, peer id it belongs to). */
    @Volatile
    var onMessageSaved: ((ChatMessage, String) -> Unit)? = null

    /** Set by ChatActivity: fired when the P2P connection goes up/down. */
    @Volatile
    var onConnectionStateChanged: ((Boolean) -> Unit)? = null

    /** Publish a persisted message to any open chat screen. */
    fun notifyMessageSaved(message: ChatMessage, peerId: String) {
        onMessageSaved?.invoke(message, peerId)
    }

    /** Publish a connection up/down event to any open chat screen. */
    fun notifyConnectionChanged(connected: Boolean) {
        onConnectionStateChanged?.invoke(connected)
    }
}
