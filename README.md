# LANVOX — Android App (Kotlin)

A peer-to-peer communication app that connects **two or more Android phones directly** over **Wi-Fi Direct (P2P)** and **local Wi-Fi / Mobile Hotspot mDNS mesh (`NSD`)** — **no internet, router account, or central server required**. Supports **1:1 and multi-phone group messaging**, **per-message delivery & read receipts**, **file & media sharing with multi-peer progress**, **WebRTC audio calling**, and **Camera2 video calling**.

## Features

| Feature | Description |
|---------|-------------|
| 💬 **1:1 & Multi-Phone Mesh Chat** | Symmetric `ChatServerService` (`8988`) runs on every phone with direct peer links + Group Chat (`__group__`) broadcast routing, `MID:` packet deduplication, and an IG Direct / Messenger-style chat icon (`ic_chats`) — no single central host bottleneck |
| 🌐 **Hostless Wi-Fi / Hotspot NSD** | `LocalMeshDiscovery` (`_lanvox._tcp.`) automatically discovers and connects nearby LANVOX devices sharing the same Wi-Fi network or mobile hotspot alongside Wi-Fi Direct |
| ❤️ **Reactions, Double-Tap & Action Sheet** | Instagram-style **double-tap bubble** to heart (`❤️`), interactive clickable reaction chips (`REACT:`), and a frosted long-press **Message Action Sheet** (`❤️ 😂 😮 😢 🙏 👍`, Reply, Copy, Star, Forward, Delete) |
| ↩️ **Swipe-to-Reply & Quote Jump** | WhatsApp/Messenger-style **swipe-right** on any bubble (`MessageSwipeHelper`) opens the composer reply bar (`REPLY:` JSON wire payload) and renders a quote card inside the bubble — tap the quote to smoothly scroll & pulse the original message; **swipe-left** deletes with **Undo** |
| ⌨️ **Live Typing Indicators** | Throttled `TYPING:` wire packets (`ChatBus.typingPeers` with 3.8s auto-expiry) surface live typing pills above the composer, in chat headers, and as mint italic previews inside `ChatsActivity` rows |
| ⭐ **Starred Filter, Copy & Forward** | Bookmark important messages with a bubble `★` badge, toggle the `★ Starred` filter pill inside the search bar, copy message text, or forward any message to another 1:1 contact or Group Chat |
| ✓✓ **Per-Message Delivery & Read Receipts** | Outgoing messages attach a `messageId` (`MID:<id>:`), transition **Sent** (`✓`) → **Delivered** (`✓✓` after recipient Room persistence via `ACK:`), and **Read** (`✓✓` bold mint label via `READ:` only when the recipient actively views the thread in foreground) |
| 📎 **P2P File Sharing & Multi-Peer Progress** | Zero-permission SAF picker (`OpenDocument`) + binary TCP port `8991` (`FileTransferManager`) up to 250 MB, inline thumbnails, `FileProvider` tap-to-open, plus live `LinearProgressIndicator`, byte counters, and per-peer upload/download chips (`FileTransferTracker`) |
| 🔎 **In-Chat Search & Star Filter** | Frosted glass search bar in both `MainActivity` and `ChatActivity` filters messages live by body text, sender name, file name, MIME type, timestamp (`14:30`, `3:54 PM`, `Nov 21`, `today`), or `★ Starred` status with match highlights and hit counter |
| 📡 **Peer Picker Bottom Sheet** | Interactive `PeerPickerBottomSheet` with live sweeping `RadarView`, outward sonar ring ripple during scans, an illustrated empty state (`img_no_peers_empty`) with embedded radar & **Start Scan** CTA, discovered peer list, and one-tap connect/disconnect |
| 🔐 **Wi-Fi P2P Runtime Permissions** | Android 13+ `NEARBY_WIFI_DEVICES` and Android 7–12 `ACCESS_FINE_LOCATION` permission flow with rationale dialog and Settings fallback |
| 💾 **Room Database Persistence (v4)** | Every message, quote-reply link, emoji reaction JSON, starred flag, `message_id` receipt state, and file attachment auto-saved locally via **Room** (`LanvoxDatabase` v4 with `MIGRATION_1_2`, `MIGRATION_2_3`, and `MIGRATION_3_4`) + reactive Kotlin `Flow` |
| 📞 **WebRTC P2P Audio Call** | Background-resilient **WebRTC** (`io.github.webrtc-sdk:android`) Opus/SRTP voice calling inside a `microphone` foreground service with hardware AEC/NS, wake/Wi-Fi locks, floating live call timer overlay (`MM:SS` / `HH:MM:SS`), and notification Mute/Hangup controls |
| 📹 **Direct 1:1 Video Call** | Live Camera2 JPEG-over-TCP video call (`8990`) + PiP preview + WebRTC audio + floating glassmorphic active call timer overlay (`● LIVE  00:00`); in multi-phone sessions (>2 phones), prompts you to pick the target peer and streams directly between caller and callee |
| 🔀 **Camera Switch, Mute & Speaker** | Switch front/back camera during video calls and toggle mute/speakerphone |
| 🌙 **Dark Mode & Cryo-Cyan HUD** | Obsidian Glassmorphism UI with ambient blurred orbs and bundled Space Grotesk / Plus Jakarta Sans / JetBrains Mono fonts |
| 📖 **Onboarding & Standby Alerts** | First-launch tutorial plus `CallListenerService` background standby so incoming calls, messages, replies, reactions, files, and `ACK:` receipts work even when the app is closed |
| 👥 **Per-Contact & Group Chats** | Conversations list (`ChatsActivity`) with true unread count badges, bold unread previews, live typing status, delivery/read ticks (`✓` / `✓✓`), and per-contact or Group Chat threads |
| 🔄 **Auto-Reconnect** | Chat mesh links and audio/video call sockets automatically retry after transient drops or handovers |
| 📤 **Export & Clear History** | Export all conversations to a `.txt` file in Downloads, delete one thread (long-press), or clear all history |

## How It Works

### Multi-Phone Discovery & Hostless Mesh (`WiFiDirectManager` + `LocalMeshDiscovery`)
1. **Runtime Permissions**: On launch/discovery, `MainActivity` requests `NEARBY_WIFI_DEVICES` (Android 13+) or `ACCESS_FINE_LOCATION` (Android 7–12) using `ActivityResultContracts.RequestMultiplePermissions()`, with rationale prompts and a Settings fallback.
2. **Peer Discovery Bottom Sheet**: Tap **Scan** or **Devices** to open `PeerPickerBottomSheet`, which displays the animated `RadarView`, sonar scan ring, illustrated empty state (`img_no_peers_empty`) with a **Start Scan** CTA, and discovered peers.
3. **Hostless Mesh & Failover**:
   - Every phone receives a stable `DeviceIdentity` (`lvx_...` or valid MAC) and runs `ChatServerService` listening on port `8988`.
   - Over **Wi-Fi Direct**, devices exchange `PEERS:id:name@ip` membership tables and open direct client-to-client TCP links (`selfId < peer.id` tie-breaker) so communication keeps working directly without routing everything through a single Group Owner.
   - Over **Wi-Fi / Mobile Hotspot**, `LocalMeshDiscovery` (`NSD` for `_lanvox._tcp.`) automatically discovers other LANVOX nodes on the same subnet so 2+ phones can chat, transfer files, and call with zero Group Owner setup.

### Text Chat & Per-Message Acknowledgements (`ACK:` & `READ:`)
- **Wire Envelope (`Envelope.kt`)**: Messages travel over port `8988` as `ENV|<senderId>|<senderName>|<recipientIdOrStar>|MID:<msgId>:<payload>`, allowing targeted 1:1 routing, Group Chat (`*`) broadcasts, and duplicate suppression across mesh paths.
- **Acknowledgement Lifecycle**:
  1. **Sent (`✓`)**: Outgoing messages are saved to Room with a unique `messageId` and `MessageStatus.SENT`.
  2. **Delivered (`✓✓`)**: Once the recipient's device parses and persists the text/file in Room (either in foreground `MainActivity` or background `CallListenerService`), it replies with `ACK:<scope>|<messageId>`, upgrading the sender's bubble and conversation preview to **Delivered**.
  3. **Read (`✓✓` Mint Bold)**: `ChatBus.isChatScreenForeground` ensures backgrounded/locked devices do not send false read receipts. When the recipient actively views the thread (or resumes/reconnects to a thread with unread incoming messages), it emits `READ:<scope>|<id1,id2,...>`, updating the sender's bubble to **Read**.
- **Chat UI & Social Messaging (`MessageAdapter`, `MessageActionBottomSheet`, `ChatsAdapter`, `ChatFeatureActions`)**: Both `MainActivity` inline chat and `ChatActivity` thread views support IG-style double-tap `❤️` reactions, long-press quick-reaction + action sheets (Reply, Copy, Star, Forward, Delete), bidirectional `MessageSwipeHelper` (swipe-right quote-reply with tap-to-jump highlight, swipe-left delete with Undo), throttled `TYPING:` live pills, `ChatSearchHelper` + `★ Starred` filtering, and live status ticks alongside timestamps. `ChatsAdapter` shows true unread count badges, bold unread previews, and live typing status.

### File & Media Sharing with Progress Tracking (`FileTransferManager` + `FileTransferTracker`)
- **Direct & Relay TCP Transfer (`8991`)**: Files up to 250 MB are staged from Android's zero-permission Storage Access Framework (`OpenDocument`). Senders serve files directly from their local `FileTransferManager` server (`host` field in `FILE:<json>`) or upload via `PUT <transferId> <size>` to a relay peer when client isolation applies.
- **Multi-Peer Progress UI**: `FileTransferTracker` publishes ~10 Hz `StateFlow<Map<String, TransferSnapshot>>` updates directly to `MessageAdapter` (via partial `PAYLOAD_PROGRESS` binds) showing:
  - Determinate `LinearProgressIndicator` (`0–100%`) and percentage pill badge
  - Byte telemetry readout (e.g., `4.2 MB / 12.0 MB · Uploading · 1/2 done`)
  - Per-peer status chips (`⏳` waiting, `↑ <peer> <pct>%`, `✓ <peer>`, `✕ <peer>`) in multi-phone Group Chat transfers
  - End-to-end `FILE_DONE:` / `FILE_FAIL:` confirmation from each recipient before marking completion.

### Persistent Chat Storage (Room Database v4)
- Saved locally in `/data/data/com.example.lanvox/databases/lanvox_room_chat.db`:
  - `ChatMessageEntity` (`@Entity(tableName = "messages")`): stores `text`, `is_sent`, `timestamp`, `peer_id`, `peer_name`, `sender_name`, `status`, `message_id` (indexed as `idx_messages_msg_id`), quote-reply columns (`reply_to_id`, `reply_to_sender`, `reply_to_text`), `reactions` JSON map, `is_starred`, and attachment columns (`attachment_name`, `attachment_mime`, `attachment_size`, `attachment_path`, `attachment_state`, `transfer_id`).
  - `ChatMessageDao` (`@Dao`): reactive `Flow` message/conversation queries (`ConversationTuple` includes `lastStatus` and `unreadCount`), `updateReactionsById(...)`, `updateStarredById(...)`, `updateOutgoingStatusByMessageIds(...)`, `getUnreadIncomingMessageIds(...)`, and `markIncomingThreadRead(...)`.
  - `LanvoxDatabase` (`@Database(version = 4)`): non-destructive `MIGRATION_1_2`, `MIGRATION_2_3`, and `MIGRATION_3_4` preserve prior messages.

### Audio & Video Calling (1:1 Direct Calls Across 2+ Phones)
- **Audio (`WebRtcAudioCallManager` & `AudioCallService`, port `8989`)**: Full-duplex WebRTC `PeerConnection` (`JavaAudioDeviceModule` with hardware AEC/NS/AGC, Opus DTLS-SRTP, NetEQ jitter buffer) over LAN ICE host candidates inside a `microphone` foreground service with wake/Wi-Fi locks and notification Mute/End controls.
- **Video (`VideoCallService` & `VideoCallActivity`, port `8990`)**: Camera2 (`CONTROL_AF_MODE_CONTINUOUS_VIDEO` + dynamic orientation) streams 15 FPS length-prefixed JPEG frames via a conflated `Channel` with a local PiP overlay (`setZOrderMediaOverlay(true)`), off-UI JPEG decoding, and simultaneous WebRTC audio.
- **Active Call Timer Overlay (`CallSessionBus` + `AudioCallActivity` / `VideoCallActivity`)**: Both call screens render a floating glassmorphic HUD timer pill (`bg_timer_overlay_pill`) with a pulsing status indicator (`RINGING`, `CONNECTING`, `LIVE`, `RECONNECTING`, `ENDED`) and tabular `JetBrains Mono` elapsed timer (`MM:SS` / `HH:MM:SS`). Duration is anchored to monotonic `SystemClock.elapsedRealtime()` inside `CallSessionBus` so the active call timer persists cleanly across brief reconnections and notification/back-stack returns, with edge-to-edge display cutout & system bar safe insets.
- **Behavior With >2 Phones Connected**: Audio and video calls are **1-to-1 peer-to-peer sessions** (not multi-way group conferences). When multiple peers are connected in Group Chat or the mesh, tapping **Audio Call** or **Video Call** prompts you to select which connected peer to ring; the caller hosts the direct call sockets (`CALL_REQ:<type>|<localIp>`) and connects directly to that peer without needing a central Group Owner relay.

### Standby Listener When App Is Closed (`CallListenerService`)
- Keeps listening on `8988` (and configures `FileTransferManager` on `8991`), restarted after reboot by `BootReceiver`.
- Receives incoming calls over the lock screen (`IncomingCallActivity`), persists incoming texts, `REPLY:` quote messages, and files to Room, applies remote `REACT:` updates, replies with `ACK:` delivery confirmations, processes incoming `ACK:` / `READ:` updates, and hands sockets back smoothly when `MainActivity` opens.

## App Flow

```
Splash → Onboarding (first launch) → MainActivity (Permissions + PeerPickerBottomSheet + Inline Mesh Chat)
                                          ├── ✉ ChatsActivity → ChatActivity (Per-contact / Group Room history)
                                          ├── 📞 AudioCallActivity (1:1 WebRTC voice)
                                          └── 📹 VideoCallActivity (1:1 Camera2 + WebRTC audio)
```

## Prerequisites

- **Android Studio** (AGP 9.1.1 / Gradle 9.3.1 compatible)
- **Android device** running **API 24+** (Android 7.0+)
- **Wi-Fi Direct or shared Wi-Fi/Hotspot** hardware for live P2P discovery (UI & Room history work on emulators; live P2P requires physical devices)
- **Camera and microphone** for audio/video calls

## Setup & Build

### 1. Build Debug APK

```bash
gradle assembleDebug
```

*(or `./gradlew assembleDebug` in a standard local Android Studio checkout)*

### 2. Install on Devices

Connect each device via USB and run:

```bash
gradle installDebug
```

## Usage

### Connect Two or More Phones

1. Open **LANVOX** on each phone and grant Wi-Fi / location permissions.
2. Either connect devices on the same **Wi-Fi / Mobile Hotspot** (automatic `LocalMeshDiscovery` via mDNS) **or** open **Devices** (`PeerPickerBottomSheet`), tap **Scan**, and connect via **Wi-Fi Direct**.
3. Use **Group Chat** to message or send files to all connected phones at once, or open a specific contact thread from **Chats** for 1:1 messaging and direct calls.

### Text Chat, Reactions, Replies & File Sharing
- Type in the bottom composer of `MainActivity` or `ChatActivity` (peers see live typing indicators automatically) and tap **Send**; watch message ticks advance from **Sent (`✓`)** → **Delivered (`✓✓`)** → **Read (`✓✓`)**.
- **Double-tap** any bubble to toggle a quick `❤️` reaction, **swipe right** on a bubble to quote-reply (tap a quote block inside a bubble to scroll to the original message), **swipe left** to delete (with **Undo**), or **long-press** to open the frosted reaction & action sheet (Reply, Copy, Star, Forward, Delete).
- Tap the search icon to filter by text, sender, file name, or timestamp, or tap `★ Starred` to view only bookmarked messages.
- Tap the paperclip button (`btnAttach`) to pick any document, image, audio, or video file (up to 250 MB) and track real-time upload/download progress bars and per-peer chips inside the bubble.

### Audio & Video Calls
- Tap **Audio Call** or **Video Call** in a peer thread (or pick the target peer from the dialog when multiple devices are connected in Group Chat).
- Controls: Mute, Speaker, Switch Camera (video), End Call.

## Project Structure

```
app/src/main/java/com/example/wifip2pchat/
├── Constants.kt                    — Ports (8988–8991), audio/video config, file limits & envelope prefixes
├── DeviceIdentity.kt               — Persistent node ID (`lvx_...`/MAC) & local IPv4 interface resolver
├── Envelope.kt                     — Wire envelope (`ENV|...|MID:<id>:<payload>`) builder & parser
├── PeerRegistry.kt                 — Multi-peer membership & IP registry across Wi-Fi Direct and LAN mesh
├── LocalMeshDiscovery.kt           — Hostless LAN / Hotspot mDNS (`NSD`) service advertisement & discovery
├── WiFiDirectManager.kt            — Wi-Fi Direct lifecycle, permission guards & session restore
├── WiFiDirectBroadcastReceiver.kt  — P2P system event listener (Android 13+ compatible)
├── ChatServerService.kt            — Symmetric multi-peer P2P mesh server/client router (8988) + MID dedup
├── ChatClient.kt                   — Dedicated Group Owner uplink socket client (with auto-reconnect)
├── FileTransferManager.kt          — Binary TCP file server/relay (8991), direct host fetch & FileProvider helper
├── FileTransferTracker.kt          — Live ~10 Hz StateFlow progress & multi-peer status chip state
├── CallListenerService.kt          — Standby foreground service: closed-app calls, messages, files & ACKs
├── BootReceiver.kt                 — Restarts the standby listener after device reboot/update
├── LastSession.kt                  — Remembers last session role, host IP & peer info
├── ChatMessage.kt                  — Domain ChatMessage model (`ReactionChip`, `DecodedReply`, `MessageStatus`, quotes & stars)
├── ChatMessageEntity.kt            — Room @Entity table definition ("messages")
├── ChatMessageDao.kt               — Room @Dao queries (Flow & suspend), reactions, stars & unread count queries
├── LanvoxDatabase.kt               — Room @Database v4 singleton ("lanvox_room_chat.db") with v1→v2→v3→v4 migrations
├── ChatRepository.kt               — Repository layer, receipt transitions, reaction/star mutations & Conversation model
├── ChatBus.kt                      — Foreground thread, message & auto-expiring typing indicator StateFlow bus
├── CallSessionBus.kt               — Shared call signaling state, monotonic active call timer, mute sync & AudioFocus manager
├── WebRtcAudioCallManager.kt       — WebRTC PeerConnection, JavaAudioDeviceModule (AEC/NS/AGC) & ICE queue
├── AudioCallService.kt             — Microphone foreground service hosting WebRTC P2P audio & notification actions
├── VideoCallService.kt             — Video streaming foreground service (Camera2 + conflated TCP frame writer)
├── AudioCallActivity.kt            — Audio call UI with floating live timer overlay HUD & edge-to-edge safe insets
├── VideoCallActivity.kt            — Video call UI with floating live timer overlay pill, PiP & edge-to-edge safe insets
├── IncomingCallActivity.kt         — Incoming call lock-screen UI
├── MainActivity.kt                 — Main HUD: PeerPickerBottomSheet, inline social mesh chat, reactions/replies & 1:1 call routing
├── MainActionsBottomSheet.kt       — Material BottomSheetDialog for secondary/destructive actions
├── MessageActionBottomSheet.kt     — Floating frosted bottom sheet for quick emoji reactions & message actions
├── PeerPickerBottomSheet.kt        — Material BottomSheetDialog for peer discovery, RadarView & empty-state CTA
├── ChatFeatureActions.kt           — Shared helpers for clipboard copy, starred filter styling, quote scroll & forwarding
├── ChatsActivity.kt                — Conversations list (observed via Room Flow with live typing & unread badges)
├── ChatsAdapter.kt                 — RecyclerView adapter for conversations, unread badges & typing indicators
├── ChatActivity.kt                 — Per-contact / Group thread with reactions, swipe-to-reply, typing, search & file sharing
├── PeerAdapter.kt                  — RecyclerView adapter for discovered peers
├── MessageAdapter.kt               — RecyclerView adapter for bubbles, quotes, reaction chips, stars, receipts & file progress
├── ChatSearchHelper.kt             — Live message filtering by text, sender, filename, MIME, or timestamp
├── MessageSwipeHelper.kt           — Bidirectional swipe-right (reply) & swipe-left (delete + Undo) ItemTouchHelper
├── GlassEffects.kt                 — RenderEffect backdrop blur & luminous glow shadows
├── OnboardingActivity.kt           — First-launch tutorial
├── OnboardingAdapter.kt            — Pages for the tutorial
├── CallRingtoneManager.kt          — System ringtone + vibration for incoming calls
├── RadarView.kt                    — Animated discovery radar
└── SplashActivity.kt               — Branded splash screen
```

## Permissions

| Permission | Purpose |
|---|---|
| `ACCESS_WIFI_STATE` | Check Wi-Fi status |
| `CHANGE_WIFI_STATE` | Enable Wi-Fi Direct & NSD multicast lock |
| `ACCESS_FINE_LOCATION` | Required for peer discovery on Android 7–12 |
| `ACCESS_COARSE_LOCATION` | Fallback location |
| `NEARBY_WIFI_DEVICES` | Runtime permission for Wi-Fi Direct discovery on Android 13+ |
| `INTERNET` | Socket communication |
| `ACCESS_NETWORK_STATE` | Check connectivity |
| `CHANGE_NETWORK_STATE` | P2P network setup |
| `RECORD_AUDIO` | Microphone for audio/video calls |
| `MODIFY_AUDIO_SETTINGS` | Speaker/mute controls |
| `CAMERA` | Camera for video calls |
| `FOREGROUND_SERVICE` | Keep call & listener services alive |
| `FOREGROUND_SERVICE_MICROPHONE` | Android 14+ mic FG service |
| `FOREGROUND_SERVICE_CAMERA` | Android 14+ camera FG service |
| `FOREGROUND_SERVICE_DATA_SYNC` | Android 14+ standby listener FG service |
| `RECEIVE_BOOT_COMPLETED` | Restart the standby listener after reboot |
| `POST_NOTIFICATIONS` | Android 13+ incoming-call & message notifications |
| `USE_FULL_SCREEN_INTENT` | Show the call card over the lock screen |
| `WAKE_LOCK` | Keep Wi-Fi responsive while the screen is off |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Prompt for reliable background receiving |
| `VIBRATE` | Ring vibration pattern |

## Troubleshooting

- **No peers found**: Ensure Wi-Fi is ON and Wi-Fi P2P / location permissions are granted, or connect phones to the same Wi-Fi network / mobile hotspot for automatic `LocalMeshDiscovery`.
- **Can't start call**: Ensure at least one peer is connected (green status) before tapping call; when 2+ peers are connected in Group Chat, select the specific peer to call.
- **Read receipt stuck on Delivered**: `Read` (`✓✓` mint) is sent only when the recipient actively opens and views the matching conversation in the foreground while connected.
- **File transfer failed / can't open file**: Keep devices connected during transfer and ensure a compatible viewer app is installed (otherwise use the share sheet fallback).
- **Calls not received while app is closed**: Grant notification permission and battery-optimization exemption when prompted.
- **Poor video quality**: Wi-Fi Direct range is typically 10–50m; video quality drops with distance or multi-peer congestion.

## Technical Details

- **Language**: Kotlin (AGP 9.1.1 built-in Kotlin, Gradle 9.3.1)
- **Min SDK**: 24 (Android 7.0)
- **Target SDK**: 34 (Android 14)
- **Persistence**: AndroidX Room (`2.7.1`, schema v4 with `MIGRATION_1_2`, `MIGRATION_2_3` & `MIGRATION_3_4`) with KSP (`2.3.5`), Repository pattern, and Kotlin `Flow` / `StateFlow`
- **Networking**: Symmetric TCP mesh (`8988`), Wi-Fi Direct + Android NSD (`_lanvox._tcp.`), TCP binary file server/relay (`8991`), WebRTC audio (`8989`), and Camera2 JPEG video (`8990`)
- **UI / UX**: Glassmorphism & Cryo-Cyan Orbital HUD aesthetic (`#050811` void, `#00F0FF` cryo-cyan, `#00F5A0` read-receipt mint), custom Cryo-Cyan adaptive launcher icon (`ic_launcher_foreground` 66dp safe zone + `ic_launcher_round`), IG Direct / Messenger chat badge, `RadarView` conical sweep & sonar pulse, custom no-peers illustration, bundled `Space Grotesk`, `Plus Jakarta Sans`, & `JetBrains Mono` typography (`res/font/`), `MaterialProgressIndicator`, `BottomSheetDialog`, `RecyclerView` (`DiffUtil` + `ItemTouchHelper`), and ViewBinding

## License

MIT
