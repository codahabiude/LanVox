package com.example.wifip2pchat

/**
 * Constants used throughout the LANVOX app.
 */
object Constants {
    // Port for the text chat server running on the group owner
    const val CHAT_PORT = 8988

    // Alias used by ChatServerService / ChatClient (same text channel)
    const val SERVER_PORT = CHAT_PORT

    // Port for audio call streaming
    const val AUDIO_PORT = 8989

    // Port for video call streaming
    const val VIDEO_PORT = 8990

    // Port for peer-to-peer file transfers
    const val FILE_PORT = 8991
    const val MAX_FILE_TRANSFER_BYTES = 250L * 1024L * 1024L

    // Intent actions
    const val ACTION_MESSAGE_RECEIVED = "com.example.lanvox.MESSAGE_RECEIVED"
    const val ACTION_INCOMING_CALL = "com.example.lanvox.INCOMING_CALL"

    // Intent extra keys
    const val EXTRA_MESSAGE = "extra_message"
    const val EXTRA_HOST_ADDRESS = "extra_host_address"
    const val EXTRA_CALL_TYPE = "extra_call_type"

    // Call types
    const val CALL_TYPE_AUDIO = "audio"
    const val CALL_TYPE_VIDEO = "video"

    // Buffer sizes
    const val TEXT_BUFFER_SIZE = 1024
    // 1024 bytes = 512 frames = ~32ms of audio at 16kHz/16-bit/mono.
    // Small enough that mic→socket→speaker has no perceivable delay,
    // large enough that we don't burn CPU on per-byte socket writes.
    const val AUDIO_BUFFER_SIZE = 1024
    // 256 KB — JPEG frames at 640×480 can be 50–250 KB depending on the
    // camera hardware's default quality. 64 KB was too small and caused
    // silent frame drops on scenes with detail (foliage, text, faces).
    const val VIDEO_BUFFER_SIZE = 262144

    // Audio configuration
    const val AUDIO_SAMPLE_RATE = 16000
    const val AUDIO_CHANNEL_CONFIG = android.media.AudioFormat.CHANNEL_IN_MONO
    const val AUDIO_FORMAT = android.media.AudioFormat.ENCODING_PCM_16BIT

    // Video configuration
    const val VIDEO_WIDTH = 640
    const val VIDEO_HEIGHT = 480
    const val VIDEO_FPS = 15
    const val VIDEO_BITRATE = 500_000

    // Message type prefixes for multiplexing on the chat channel
    const val MSG_TYPE_TEXT = "TEXT:"
    const val MSG_TYPE_FILE = "FILE:"
    const val MSG_TYPE_FILE_DONE = "FILE_DONE:"
    const val MSG_TYPE_FILE_FAIL = "FILE_FAIL:"
    const val MSG_TYPE_ACK = "ACK:"
    const val MSG_TYPE_READ = "READ:"
    const val MSG_TYPE_TYPING = "TYPING:"
    const val MSG_TYPE_REACT = "REACT:"
    const val MSG_TYPE_REPLY = "REPLY:"
    const val MSG_TYPE_PIN = "PIN:"
    const val MSG_TYPE_EDIT = "EDIT:"
    const val MSG_TYPE_TTL = "TTL:"
    const val MSG_TYPE_PING = "PING:"
    const val MSG_TYPE_PONG = "PONG:"
    const val MSG_TYPE_CALL_LOG = "CALL_LOG:"
    const val MSG_TYPE_CALL_REQUEST = "CALL_REQ:"
    const val MSG_TYPE_CALL_ACCEPT = "CALL_ACCEPT:"
    const val MSG_TYPE_CALL_REJECT = "CALL_REJECT:"
    const val MSG_TYPE_CALL_END = "CALL_END:"

    // ====================================================================
    // Multi-peer envelope protocol (v5.0+)
    // ====================================================================
    // Every chat-channel line is wrapped in an envelope so the Group Owner
    // can route messages between clients and every phone knows who sent what:
    //
    //   ENV|<senderId>|<senderName>|<recipientIdOrStar>|<payload>\n
    //
    // Special payloads used by the envelope layer itself:
    //   HELLO  — sent by a client right after connecting
    //   BYE    — sent by a client before disconnecting
    //   PEERS:id1:name1,id2:name2,... — GO broadcasts the current member list
    //
    // Legacy (pre-v5) clients send bare lines like "TEXT:hello" with no
    // envelope; the GO falls back to treating those as broadcasts from an
    // "Unknown" sender so old clients still interoperate.
    const val ENVELOPE_PREFIX = "ENV"
    const val ENVELOPE_DELIM = "|"
    const val RECIPIENT_BROADCAST = "*"
    const val PAYLOAD_HELLO = "HELLO"
    const val PAYLOAD_BYE = "BYE"
    const val PAYLOAD_PEERS = "PEERS:"
    const val MSG_ID_PREFIX = "MID:"
    const val NSD_SERVICE_TYPE = "_lanvox._tcp."

    // Special "peer ID" used for the group-wide broadcast chat thread.
    const val GROUP_PEER_ID = "__group__"
    const val GROUP_PEER_NAME = "Group Chat"
}
