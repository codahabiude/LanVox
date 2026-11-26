# LanVox — Android App (Kotlin)

A peer-to-peer communication app that connects **two or more Android phones directly** over **Wi-Fi Direct (P2P)** and **local Wi-Fi / Mobile Hotspot mDNS mesh (`NSD`)** — **no internet, router account, or central server required**. Supports **1:1 and multi-phone group messaging**, **per-message delivery & read receipts**, **file & media sharing with multi-peer progress**, **WebRTC audio calling**, and **Camera2 video calling**.

## Download

Get the latest APK from the **[Releases page](https://github.com/codahabiude/LanVox/releases/latest)**.

1. Open the link on your Android phone and download the `.apk` file under **Assets**.
2. Open the file and allow installing from unknown sources if Android asks.
3. Install LanVox on every phone that should chat with each other.

Requires Android 7.0 (API 24) or newer. This is a debug build, not from the Play Store.

## Features

| Feature | Description |
|---------|-------------|
| 🔒 **100% End-to-End & At-Rest Encryption** | Hardware-backed `AndroidKeyStore` AES-256-GCM vault (`CryptoManager`), ECDH P-256 + HKDF-SHA256 pairwise & broadcast chat (`E2E1:`/`E2EM:`), streaming 64 KB `LVXE1` attachment encryption, ephemeral ECDH + DTLS-SRTP audio calls (`E2ES:`), AES-GCM video frames (`LVV1`), encrypted SQLite/avatars/prefs (`ENC1:`/`LVXB1`), `allowBackup="false"`, and 60-digit Safety Number verification (`EncryptionInfoBottomSheet`) |
| 💬 **1:1 & Multi-Phone Mesh Chat** | Symmetric `ChatServerService` (`8988`) runs on every phone with direct peer links + Group Chat (`__group__`) broadcast routing, `MID:` packet deduplication, and an IG Direct / Messenger-style chat icon (`ic_chats`) with a live total unread badge — no single central host bottleneck |
| 🌐 **Hostless Wi-Fi / Hotspot NSD** | `LocalMeshDiscovery` (`_lanvox._tcp.`) automatically discovers and connects nearby LanVox devices sharing the same Wi-Fi network or mobile hotspot alongside Wi-Fi Direct |
| 🎤 **Push-to-Talk Voice Notes** | Record AAC `.m4a` voice clips (`VoiceNoteManager`) directly from the composer mic button with live `REC MM:SS` timer and amplitude meter, stream over TCP `8991`, and play inline with an interactive `SeekBar` inside bubbles |
| 📌 **Pinned Messages & Banner** | Pin or unpin any message from the action sheet (`PIN:` wire sync); a frosted pinned banner (`layoutPinnedBanner`) stays docked at the top of the thread with tap-to-scroll and quick unpin |
| ✏️ **Message Editing** | Edit your sent text messages inline (`EDIT:` wire sync) with an `(edited)` badge and synced timestamp across devices |
| ⏳ **Disappearing Messages** | Configure per-thread self-destruct timers (`Off`, `30s`, `5m`, `1h`, `24h` via `EphemeralPrefs` + `TTL:` wire wrapper) with live bubble countdown badges (`⏱`) and automatic Room expiration purging |
| 👤 **Custom Profile Name, Avatar & Bio** | Edit your display name, presence preset (`Available` / `Busy` / `Away`), custom status bio, and circular profile picture via Android's zero-permission Photo Picker (`ProfileBottomSheet` + `AvatarManager`), automatically synced live to peers over `PROFILE:` and `AVATAR:` wire packets |
| 🟢 **Presence Indicators & Nudge / Buzz** | Live green/amber/red/gray presence dots and peer bios across peer cards, conversation rows, chat headers, and call screens, plus a 1:1 **Nudge / Buzz** (`BUZZ:`) alert with double-pulse haptics (`NudgeManager`), tone, and standby notifications |
| 📊 **Interactive Live Polls** | Create 2–6 option polls inside any thread (`PollCreatorDialog`), render real-time proportional vote progress bars and percentages inside chat bubbles (`POLL:` / `POLL_VOTE:`), and tap options to vote, switch, or unvote across all peers |
| ⚡ **Customizable Quick-Reply Templates** | Collapsible composer quick-reply bar (`QuickReplyStripHelper` + `QuickReplyPrefs`) toggled by the bolt button with editable presets, `+ Add` custom templates, long-press delete, and a one-tap `Create Poll` shortcut |
| 🖼️ **Thread Media & Files Gallery** | Browse every photo, voice note, and shared document in the active conversation (`MediaGalleryBottomSheet`) with `All` / `Images` / `Voice` / `Docs` filter chips and tap-to-open/play |
| 🩺 **Mesh Telemetry & RTT Ping** | Inspect your node ID, local IP, transport links, and measure live TCP round-trip latency (`PING:`/`PONG:` in ms via `MeshDiagnosticsBottomSheet`) |
| 📞 **Call History Cards & Call Back** | Incoming, outgoing, and missed audio/video calls log automatically as centered frosted timeline cards (`CALL_LOG`) inside the thread with one-tap **Call back** |
| ❤️ **Reactions, Double-Tap & Action Sheet** | Instagram-style **double-tap bubble** to heart (`❤️`), interactive clickable reaction chips (`REACT:`), and a frosted long-press **Message Action Sheet** (`❤️ 😂 😮 😢 🙏 👍`, Reply, Copy, Star, Pin, Edit, Forward, Delete) |
| ↩️ **Swipe-to-Reply & Quote Jump** | WhatsApp/Messenger-style **swipe-right** on any bubble (`MessageSwipeHelper`) opens the composer reply bar (`REPLY:` JSON wire payload) and renders a quote card inside the bubble — tap the quote to smoothly scroll & pulse the original message; **swipe-left** deletes with **Undo** |
| ⌨️ **Live Typing Indicators** | Throttled `TYPING:` wire packets (`ChatBus.typingPeers` with 3.8s auto-expiry) surface live typing pills above the composer, in chat headers, and as mint italic previews inside `ChatsActivity` rows |
| ⭐ **Starred Filter, Copy & Forward** | Bookmark important messages with a bubble `★` badge, toggle the `★ Starred` filter pill inside the search bar, copy message text, or forward any message to another 1:1 contact or Group Chat |
| ✓✓ **Per-Message Delivery & Read Receipts** | Outgoing messages attach a `messageId` (`MID:<id>:`), transition **Sent** (`✓`) → **Delivered** (`✓✓` after recipient Room persistence via `ACK:`), and **Read** (`✓✓` bold mint label via `READ:` only when the recipient actively views the thread in foreground) |
| 📎 **P2P File Sharing & Multi-Peer Progress** | Zero-permission SAF picker (`OpenDocument`) + binary TCP port `8991` (`FileTransferManager`) up to 250 MB, inline thumbnails, `FileProvider` tap-to-open, plus live `LinearProgressIndicator`, byte counters, and per-peer upload/download chips (`FileTransferTracker`) |
| 🔎 **In-Chat Search & Star Filter** | Frosted glass search bar in both `MainActivity` and `ChatActivity` filters messages live by body text, sender name, file name, MIME type, timestamp (`14:30`, `3:54 PM`, `Nov 21`, `today`), or `★ Starred` status with match highlights and hit counter |
| 📡 **Peer Picker Bottom Sheet** | Interactive `PeerPickerBottomSheet` with live sweeping `RadarView`, outward sonar ring ripple during scans, an illustrated empty state (`img_no_peers_empty`) with embedded radar & **Start Scan** CTA, discovered peer list, and one-tap connect/disconnect |
| 🔐 **Wi-Fi P2P Runtime Permissions** | Android 13+ `NEARBY_WIFI_DEVICES` and Android 7–12 `ACCESS_FINE_LOCATION` permission flow with rationale dialog and Settings fallback |
| 💾 **Room Database Persistence (v5)** | Every message, quote-reply link, emoji reaction JSON, starred/pinned flag, edit/expiry timestamp, call log type, `message_id` receipt state, and file attachment auto-saved locally via **Room** (`LanvoxDatabase` v5 with `MIGRATION_1_2` → `MIGRATION_4_5`) + reactive Kotlin `Flow` |
| 📞 **WebRTC P2P Audio Call** | Background-resilient **WebRTC** (`io.github.webrtc-sdk:android`) Opus/SRTP voice calling inside a `microphone` foreground service with hardware AEC/NS, wake/Wi-Fi locks, floating live call timer overlay (`MM:SS` / `HH:MM:SS`), and notification Mute/Hangup controls |
| 📹 **Direct 1:1 Video Call** | Live Camera2 JPEG-over-TCP video call (`8990`) + PiP preview + WebRTC audio + floating glassmorphic active call timer overlay (`● LIVE  00:00`); in multi-phone sessions (>2 phones), prompts you to pick the target peer and streams directly between caller and callee |
| 🔀 **Camera Switch, Mute & Speaker** | Switch front/back camera during video calls and toggle mute/speakerphone |
| 🌙 **Dark Mode & Cryo-Cyan HUD** | Obsidian Glassmorphism UI with ambient blurred orbs and bundled Space Grotesk / Plus Jakarta Sans / JetBrains Mono fonts |
| 📖 **Onboarding & Standby Alerts** | First-launch tutorial plus `CallListenerService` background standby so incoming calls, messages, replies, reactions, pins, edits, files, and `ACK:` receipts work even when the app is closed |
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
   - Over **Wi-Fi / Mobile Hotspot**, `LocalMeshDiscovery` (`NSD` for `_lanvox._tcp.`) automatically discovers other LanVox nodes on the same subnet so 2+ phones can chat, transfer files, and call with zero Group Owner setup.

### Text Chat & Per-Message Acknowledgements (`ACK:` & `READ:`)
- **Wire Envelope (`Envelope.kt`)**: Messages travel over port `8988` as `ENV|<senderId>|<senderName>|<recipientIdOrStar>|MID:<msgId>:<payload>`, allowing targeted 1:1 routing, Group Chat (`*`) broadcasts, and duplicate suppression across mesh paths.
- **Acknowledgement Lifecycle**:
  1. **Sent (`✓`)**: Outgoing messages are saved to Room with a unique `messageId` and `MessageStatus.SENT`.
  2. **Delivered (`✓✓`)**: Once the recipient's device parses and persists the text/file in Room (either in foreground `MainActivity` or background `CallListenerService`), it replies with `ACK:<scope>|<messageId>`, upgrading the sender's bubble and conversation preview to **Delivered**.
  3. **Read (`✓✓` Mint Bold)**: `ChatBus.isChatScreenForeground` ensures backgrounded/locked devices do not send false read receipts. When the recipient actively views the thread (or resumes/reconnects to a thread with unread incoming messages), it emits `READ:<scope>|<id1,id2,...>`, updating the sender's bubble to **Read**.
- **Chat UI & Social Messaging (`MessageAdapter`, `MessageActionBottomSheet`, `ChatsAdapter`, `ChatFeatureActions`)**: Both `MainActivity` inline chat and `ChatActivity` thread views support IG-style double-tap `❤️` reactions, long-press quick-reaction + action sheets (Reply, Copy, Star, Forward, Delete), bidirectional `MessageSwipeHelper` (swipe-right quote-reply with tap-to-jump highlight, swipe-left delete with Undo), throttled `TYPING:` live pills, `ChatSearchHelper` + `★ Starred` filtering, and live status ticks alongside timestamps. `ChatsAdapter` shows peer avatars, presence dots, true unread count badges, bold unread previews, poll summaries, and live typing status.
- **Profiles, Presence, Polls, Quick Replies, Nudge & Gallery**:
  - **Profiles & Avatars (`ProfileBottomSheet`, `AvatarManager`)**: Selecting an avatar uses `ActivityResultContracts.PickVisualMedia` (no storage permission needed), downscales to a compact 128×128 circular JPEG, persists under `files/avatars/`, and exchanges `PROFILE:` JSON + `AVATAR:` Base64 packets automatically on peer handshake or profile edit.
  - **Presence & Nudge (`PeerRegistry`, `NudgeManager`)**: Tracks peer `Available` / `Busy` / `Away` presets and bios across UI screens; `BUZZ:` sends a gentle peer nudge triggering double-pulse vibration and sound/notification alerts.
  - **Live Polls (`PollModels`, `PollCreatorDialog`)**: Polls serialize as `ChatMessage.TYPE_POLL` in Room; each `POLL_VOTE:` packet mutates the deterministic vote map so multi-phone counts stay synchronized.
  - **Quick Replies & Media Gallery (`QuickReplyPrefs`, `QuickReplyStripHelper`, `MediaGalleryBottomSheet`)**: Bolt button toggles saved one-tap reply chips above the composer; Gallery filters attachments in the active conversation by Images, Voice, or Docs.

### File & Media Sharing with Progress Tracking (`FileTransferManager` + `FileTransferTracker`)
- **Direct & Relay TCP Transfer (`8991`)**: Files up to 250 MB are staged from Android's zero-permission Storage Access Framework (`OpenDocument`). Senders serve files directly from their local `FileTransferManager` server (`host` field in `FILE:<json>`) or upload via `PUT <transferId> <size>` to a relay peer when client isolation applies.
- **Multi-Peer Progress UI**: `FileTransferTracker` publishes ~10 Hz `StateFlow<Map<String, TransferSnapshot>>` updates directly to `MessageAdapter` (via partial `PAYLOAD_PROGRESS` binds) showing:
  - Determinate `LinearProgressIndicator` (`0–100%`) and percentage pill badge
  - Byte telemetry readout (e.g., `4.2 MB / 12.0 MB · Uploading · 1/2 done`)
  - Per-peer status chips (`⏳` waiting, `↑ <peer> <pct>%`, `✓ <peer>`, `✕ <peer>`) in multi-phone Group Chat transfers
  - End-to-end `FILE_DONE:` / `FILE_FAIL:` confirmation from each recipient before marking completion.

### Persistent Chat Storage (Room Database v5)
- Saved locally in `/data/data/com.example.lanvox/databases/lanvox_room_chat.db`:
  - `ChatMessageEntity` (`@Entity(tableName = "messages")`): stores `text`, `is_sent`, `timestamp`, `peer_id`, `peer_name`, `sender_name`, `status`, `message_id` (indexed as `idx_messages_msg_id`), quote-reply columns (`reply_to_id`, `reply_to_sender`, `reply_to_text`), `reactions` JSON map, `is_starred`, `is_pinned`, `edited_at`, `expires_at`, `message_type` (`STANDARD` / `CALL_LOG`), and attachment columns (`attachment_name`, `attachment_mime`, `attachment_size`, `attachment_path`, `attachment_state`, `transfer_id`).
  - `ChatMessageDao` (`@Dao`): reactive `Flow` message/conversation/pinned queries (`ConversationTuple` includes `lastStatus` and `unreadCount`), `updateReactionsById(...)`, `updateStarredById(...)`, `updatePinnedById(...)`, `updateEditedTextById(...)`, `purgeExpiredMessages(...)`, `updateOutgoingStatusByMessageIds(...)`, `getUnreadIncomingMessageIds(...)`, and `markIncomingThreadRead(...)`.
  - `LanvoxDatabase` (`@Database(version = 5)`): non-destructive migrations (`MIGRATION_1_2` through `MIGRATION_4_5`) preserve prior messages.

### Audio & Video Calling (1:1 Direct Calls Across 2+ Phones)
- **Audio (`WebRtcAudioCallManager` & `AudioCallService`, port `8989`)**: Full-duplex WebRTC `PeerConnection` (`JavaAudioDeviceModule` with hardware AEC/NS/AGC, Opus DTLS-SRTP, NetEQ jitter buffer) over LAN ICE host candidates inside a `microphone` foreground service with wake/Wi-Fi locks and notification Mute/End controls.
- **Video (`VideoCallService` & `VideoCallActivity`, port `8990`)**: Camera2 (`CONTROL_AF_MODE_CONTINUOUS_VIDEO` + dynamic orientation) streams 15 FPS length-prefixed JPEG frames via a conflated `Channel` with a local PiP overlay (`setZOrderMediaOverlay(true)`), off-UI JPEG decoding, and simultaneous WebRTC audio.
- **Active Call Timer Overlay (`CallSessionBus` + `AudioCallActivity` / `VideoCallActivity`)**: Both call screens render a floating glassmorphic HUD timer pill (`bg_timer_overlay_pill`) with a pulsing status indicator (`RINGING`, `CONNECTING`, `LIVE`, `RECONNECTING`, `ENDED`) and tabular `JetBrains Mono` elapsed timer (`MM:SS` / `HH:MM:SS`). Duration is anchored to monotonic `SystemClock.elapsedRealtime()` inside `CallSessionBus` so the active call timer persists cleanly across brief reconnections and notification/back-stack returns, with edge-to-edge display cutout & system bar safe insets.
- **Behavior With >2 Phones Connected**: Audio and video calls are **1-to-1 peer-to-peer sessions** (not multi-way group conferences). When multiple peers are connected in Group Chat or the mesh, tapping **Audio Call** or **Video Call** prompts you to select which connected peer to ring; the caller hosts the direct call sockets (`CALL_REQ:<type>|<localIp>`) and connects directly to that peer without needing a central Group Owner relay.

### 100% End-to-End & At-Rest Encryption (`CryptoManager` + `EncryptionInfoBottomSheet`)
- **Hardware KeyStore Root & Identity**: Every device generates a hardware-backed `AndroidKeyStore` AES-256-GCM master key wrapping a local storage vault key and a persistent ECDH P-256 (`secp256r1`) identity keypair. `android:allowBackup="false"` prevents unencrypted ADB/cloud extraction.
- **Mesh Chat & Control (`8988`)**: Peers exchange P-256 public keys via `HELLO|KEY:`, `KEYX:`, and `PEERS:` membership tables and derive domain-separated AES-256-GCM session keys using HKDF-SHA256. 1:1 envelopes travel as `E2E1:<iv>:<ct>` and group broadcasts encrypt once with a fresh 256-bit message key wrapped per recipient (`E2EM:<msgIv>:<msgCt>:<wrappedKeys>`) with AAD binding.
- **Files & Voice Notes (`8991`)**: Every attachment and voice note is encrypted on disk and on the wire in 64 KB AES-256-GCM chunks (`LVXE1`), verified per-chunk during transfer (`copyAndVerifyEncryptedStream`), played in-memory via `MediaDataSource`, and decrypted temporarily to `cacheDir/secure_open/` (purged on close) only when explicitly opened via `FileProvider`.
- **Audio & Video Calls (`8989`, `8990`)**: Audio call signaling executes an ephemeral ECDH P-256 handshake (`E2ES:<seq>:<iv>:<ct>` with replay guard) over WebRTC Opus/DTLS-SRTP media; video calls execute an ephemeral ECDH `LVV1` handshake on port `8990` and encrypt every frame with AES-256-GCM and monotonic sequence AAD.
- **At-Rest Vault & Safety Numbers**: `ChatRepository` encrypts Room DB text, names, replies, reactions, and attachment metadata with `ENC1:` (auto-migrating legacy plaintext rows in the background), `AvatarManager` stores avatars in `LVXB1`, and `DeviceIdentity`/`QuickReplyPrefs` encrypt local PII preferences. Tap **Encryption & Safety Keys** or a chat header to verify 60-digit Safety Numbers with TOFU key-change detection.

### Standby Listener When App Is Closed (`CallListenerService`)
- Keeps listening on `8988` (and configures `FileTransferManager` on `8991`), restarted after reboot by `BootReceiver`.
- Receives incoming calls over the lock screen (`IncomingCallActivity`), verifies and decrypts `E2E1:`/`E2EM:` envelopes, persists incoming texts, `REPLY:` quote messages, and encrypted files to Room, applies remote `REACT:` updates, replies with encrypted `ACK:` delivery confirmations, processes incoming `ACK:` / `READ:` updates, and hands sockets back smoothly when `MainActivity` opens.

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

1. Open **LanVox** on each phone and grant Wi-Fi / location permissions.
2. Either connect devices on the same **Wi-Fi / Mobile Hotspot** (automatic `LocalMeshDiscovery` via mDNS) **or** open **Devices** (`PeerPickerBottomSheet`), tap **Scan**, and connect via **Wi-Fi Direct**.
3. Use **Group Chat** to message or send files to all connected phones at once, or open a specific contact thread from **Chats** for 1:1 messaging and direct calls.

### Text Chat, Reactions, Replies & File Sharing
- Type in the bottom composer of `MainActivity` or `ChatActivity` (peers see live typing indicators automatically) and tap **Send**; watch message ticks advance from **Sent (`✓`)** → **Delivered (`✓✓`)** → **Read (`✓✓`)**.
- **Double-tap** any bubble to toggle a quick `❤️` reaction, **swipe right** on a bubble to quote-reply (tap a quote block inside a bubble to scroll to the original message), **swipe left** to delete (with **Undo**), or **long-press** to open the frosted reaction & action sheet (Reply, Copy, Star, Forward, Delete).
- Tap the search icon to filter by text, sender, file name, or timestamp, or tap `★ Starred` to view only bookmarked messages.
- Tap the paperclip button (`btnAttach`) to pick any document, image, audio, or video file (up to 250 MB) and track real-time upload/download progress bars and per-peer chips inside the bubble.

### Profiles, Polls, Quick Replies, Nudge & Media Gallery
- Tap your avatar button in `MainActivity` (or **My Profile & Avatar** in the overflow/actions sheet) to edit your display name, pick a profile photo from your gallery, choose `Available` / `Busy` / `Away`, and set a status bio.
- Tap the **⚡** bolt button beside the composer to expand customizable **Quick Replies**, tap **+** to save new presets, long-press any chip to delete it, or tap **Poll** inside the strip (or **Create Poll** in chat/actions) to launch a live vote.
- In a 1:1 thread, tap **Nudge** in the top action row to send a double-pulse buzz alert to your peer, or tap **Gallery** to view all photos, voice notes, and shared files in that conversation.

### Audio & Video Calls
- Tap **Audio Call** or **Video Call** in a peer thread (or pick the target peer from the dialog when multiple devices are connected in Group Chat).
- Controls: Mute, Speaker, Switch Camera (video), End Call.

## Project Structure

```
app/src/main/java/com/example/wifip2pchat/
├── Constants.kt                    — Ports (8988–8991), audio/video config, file limits & E2EE envelope prefixes
├── CryptoManager.kt                — Hardware KeyStore vault, ECDH P-256 + HKDF-SHA256, AES-256-GCM wire/file/DB crypto & Safety Numbers
├── DeviceIdentity.kt               — Persistent node ID (`lvx_...`/MAC), encrypted custom name/bio, presence preset & IP resolver
├── AvatarManager.kt                — Downscaled circular JPEG avatar storage, Base64 wire sync & reactive StateFlow updates
├── Envelope.kt                     — Wire envelope (`ENV|...|MID:<id>:<payload>`) builder & parser
├── PeerRegistry.kt                 — Multi-peer membership, presence, bio & IP registry across Wi-Fi Direct and LAN mesh
├── LocalMeshDiscovery.kt           — Hostless LAN / Hotspot mDNS (`NSD`) service advertisement & discovery
├── WiFiDirectManager.kt            — Wi-Fi Direct lifecycle, permission guards & session restore
├── WiFiDirectBroadcastReceiver.kt  — P2P system event listener (Android 13+ compatible)
├── ChatServerService.kt            — Symmetric multi-peer P2P mesh server/client router (8988) + MID dedup
├── ChatClient.kt                   — Dedicated Group Owner uplink socket client (with auto-reconnect)
├── FileTransferManager.kt          — Binary TCP file server/relay (8991), direct host fetch & FileProvider helper
├── FileTransferTracker.kt          — Live ~10 Hz StateFlow progress & multi-peer status chip state
├── CallListenerService.kt          — Standby foreground service: closed-app calls, messages, profiles, polls, nudges & ACKs
├── BootReceiver.kt                 — Restarts the standby listener after device reboot/update
├── LastSession.kt                  — Remembers last session role, host IP & peer info
├── ChatMessage.kt                  — Domain ChatMessage model (`ReactionChip`, `DecodedReply`, `PollData`, `MessageStatus`)
├── PollModels.kt                   — Interactive poll JSON serialization, deterministic vote updates & summaries
├── ChatMessageEntity.kt            — Room @Entity table definition ("messages")
├── ChatMessageDao.kt               — Room @Dao queries (Flow & suspend), reactions, stars & unread count queries
├── LanvoxDatabase.kt               — Room @Database v5 singleton ("lanvox_room_chat.db") with v1→v5 migrations
├── ChatRepository.kt               — Repository layer, receipt transitions, pin/edit/expiry/poll/call-log mutations & Conversation model
├── ChatBus.kt                      — Foreground thread, message & auto-expiring typing indicator StateFlow bus
├── VoiceNoteManager.kt             — Push-to-Talk `.m4a` AAC recorder & single-track MediaPlayer playback StateFlow
├── EphemeralPrefs.kt               — Per-thread disappearing-messages TTL preferences & duration picker
├── QuickReplyPrefs.kt              — Persistent customizable quick-reply template presets
├── QuickReplyStripHelper.kt        — Shared collapsible quick-reply chip strip & preset add/delete controller
├── NudgeManager.kt                 — Double-pulse haptic buzz & tone alerts with standby notifications
├── MeshDiagnosticsBus.kt           — Live TCP `PING:`/`PONG:` round-trip latency (RTT) StateFlow tracker
├── CallSessionBus.kt               — Shared call signaling state, monotonic active call timer, mute sync & AudioFocus manager
├── WebRtcAudioCallManager.kt       — WebRTC PeerConnection, JavaAudioDeviceModule (AEC/NS/AGC) & ICE queue
├── AudioCallService.kt             — Microphone foreground service hosting WebRTC P2P audio & notification actions
├── VideoCallService.kt             — Video streaming foreground service (Camera2 + conflated TCP frame writer)
├── AudioCallActivity.kt            — Audio call UI with peer avatar, floating live timer HUD, call logging & edge-to-edge safe insets
├── VideoCallActivity.kt            — Video call UI with floating live timer overlay pill, PiP, call logging & edge-to-edge safe insets
├── IncomingCallActivity.kt         — Incoming call lock-screen UI with peer avatar & decline call logging
├── MainActivity.kt                 — Main HUD: self-profile header, PeerPickerBottomSheet, inline social mesh chat, polls, quick replies & 1:1 call routing
├── MainActionsBottomSheet.kt       — Material BottomSheetDialog for profile preview, encryption keys, polls, media gallery, disappearing timer, diagnostics & clear history
├── EncryptionInfoBottomSheet.kt    — 6-layer encryption inspector, own key fingerprint & 60-digit Safety Number verifier
├── ProfileBottomSheet.kt           — Profile editor bottom sheet (Photo Picker avatar, display name, presence preset & status bio)
├── PollCreatorDialog.kt            — Frosted dialog for composing 2–6 option live polls
├── MediaGalleryBottomSheet.kt      — Thread media/voice/docs gallery bottom sheet with category filter chips
├── MeshDiagnosticsBottomSheet.kt   — Nickname editor, node/IP telemetry & live per-peer RTT latency sheet
├── MessageActionBottomSheet.kt     — Floating frosted bottom sheet for quick emoji reactions & message actions (Reply, Copy, Star, Pin, Edit, Forward, Delete)
├── PeerPickerBottomSheet.kt        — Material BottomSheetDialog for peer discovery, RadarView & empty-state CTA
├── ChatFeatureActions.kt           — Shared helpers for clipboard copy, starred filter styling, quote scroll & forwarding
├── ChatsActivity.kt                — Conversations list (observed via Room Flow with peer avatars, presence, typing & unread badges)
├── ChatsAdapter.kt                 — RecyclerView adapter for conversations, avatars, presence dots, unread badges & typing indicators
├── ChatActivity.kt                 — Per-contact / Group thread with presence subtitle, nudge, polls, quick replies, media gallery, voice notes & file sharing
├── PeerAdapter.kt                  — RecyclerView adapter for discovered peers with avatars, presence dots & bios
├── MessageAdapter.kt               — RecyclerView adapter for bubbles, interactive polls, voice players, call-log cards, quotes, reactions & file progress
├── ChatSearchHelper.kt             — Live message filtering by text, poll content, sender, filename, MIME, or timestamp
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
- **Persistence**: AndroidX Room (`2.7.1`, schema v5 with `MIGRATION_1_2`, `MIGRATION_2_3`, `MIGRATION_3_4` & `MIGRATION_4_5`) with KSP (`2.3.5`), AES-256-GCM at-rest column/file/pref encryption (`ENC1:` / `LVXE1` / `LVXB1`), `android:allowBackup="false"`, Repository pattern, and Kotlin `Flow` / `StateFlow`
- **Cryptography**: `AndroidKeyStore` AES-256-GCM master key, ECDH P-256 (`secp256r1`), RFC 5869 HKDF-SHA256, AES-256-GCM (`E2E1:` / `E2EM:` / `E2ES:` / `LVV1` / `LVXE1`), WebRTC DTLS-SRTP, and 60-digit TOFU Safety Numbers
- **Networking**: Symmetric encrypted TCP mesh (`8988`), Wi-Fi Direct + Android NSD (`_lanvox._tcp.`), encrypted streaming TCP binary file server/relay (`8991`), ephemeral-ECDH-signaled WebRTC audio (`8989`), and encrypted Camera2 JPEG video (`8990`)
- **UI / UX**: Glassmorphism & Cryo-Cyan Orbital HUD aesthetic (`#050811` void, `#00F0FF` cryo-cyan, `#00F5A0` read-receipt mint), fitted adaptive launcher icon (`drawable/ic_launcher_foreground` safe-zone inset over `#00162B` + density `ic_launcher` / `ic_launcher_round` fallbacks) and centered `ic_brand_logo`, IG Direct / Messenger chat badge, `RadarView` conical sweep & sonar pulse, custom no-peers illustration, bundled `Space Grotesk`, `Plus Jakarta Sans`, & `JetBrains Mono` typography (`res/font/`), `MaterialProgressIndicator`, `BottomSheetDialog`, `RecyclerView` (`DiffUtil` + `ItemTouchHelper`), and ViewBinding

## License

MIT
