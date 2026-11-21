# LANVOX — Android App (Kotlin)

A peer-to-peer communication app that uses **Wi-Fi Direct (P2P)** to connect two Android phones directly — **no internet, router, or server required**. Supports **real-time text messaging**, **audio calling**, and **video calling**.

## Features

| Feature | Description |
|---------|-------------|
| 💬 **Inline & Thread Text Chat** | `RecyclerView`-based chat interface directly in `MainActivity` plus dedicated per-contact `ChatActivity` threads using `ChatMessage` & `DiffUtil`-backed `MessageAdapter` |
| 🔎 **In-Chat Message Search** | Frosted glass search bar in both `MainActivity` and `ChatActivity` filters messages live by body text, sender name, or timestamp (`14:30`, `3:54 PM`, `Nov 21`, `today`) with match highlights and hit counter |
| 📡 **Peer Picker Bottom Sheet** | Interactive `PeerPickerBottomSheet` with live radar sweep, scanning status, discovered peer list, and one-tap connect/disconnect |
| 🔐 **Wi-Fi P2P Runtime Permissions** | Android 13+ `NEARBY_WIFI_DEVICES` and Android 7–12 `ACCESS_FINE_LOCATION` permission flow with rationale dialog and Settings fallback |
| 💾 **Room Database Persistence** | Every message auto-saved locally via **Room** (`LanvoxDatabase`, `ChatMessageEntity`, `ChatMessageDao`, `ChatRepository`) + reactive Kotlin `Flow` — history survives app close and restart |
| 📞 **WebRTC P2P Audio Call** | Background-resilient **WebRTC** (`io.github.webrtc-sdk:android`) Opus/SRTP voice calling inside a `microphone` foreground service with hardware AEC/NS, wake/Wi-Fi locks, and notification Mute/Hangup controls |
| 📹 **Video Call** | Live video call with Camera2 + PiP preview + WebRTC audio |
| 🔍 **Peer Discovery** | Find nearby devices via Wi-Fi Direct |
| 🔀 **Camera Switch** | Toggle front/back camera during video calls |
| 🔇 **Mute / Speaker** | Audio controls during calls |
| 🌙 **Dark Mode** | Full dark theme support |
| 🔔 **Call Ringtone** | Custom ringtone for incoming calls |
| 📖 **Onboarding** | First-launch tutorial with permission explanations |
| 👥 **Per-Contact Chats** | WhatsApp-style chats list — each device gets its own conversation thread |
| 📲 **Calls With App Closed** | Incoming calls ring over the lock screen even when LANVOX isn't opened — answers from the lock screen |
| 📨 **Messages While Closed** | Messages received while the app is closed are persisted to Room and shown as notifications |
| 🔄 **Auto-Reconnect** | Chat channel and audio/video sockets retry silently after glitches or handovers |
| 📤 **Export Chats** | Save all conversations as a `.txt` file in the Downloads folder |
| 🗑️ **Clear History** | Wipe one conversation (long-press) or everything, with confirmation |

## How It Works

### Wi-Fi Direct Connection & Runtime Permissions
1. **Runtime Permissions**: On launch/discovery, `MainActivity` requests `NEARBY_WIFI_DEVICES` (Android 13+) or `ACCESS_FINE_LOCATION` (Android 7–12) using `ActivityResultContracts.RequestMultiplePermissions()`, falling back to rationale prompts and a Settings action if permanently denied.
2. **Peer Discovery Bottom Sheet**: Tap the **Devices** toggle or radar controls to open `PeerPickerBottomSheet`, which displays the live animated `RadarView`, discovered Wi-Fi Direct peers via `PeerAdapter`, and connect/disconnect actions.
3. **P2P Link**: One device becomes the **Group Owner (GO)** acting as the access point, while the other device connects as a **client**.

### Text Chat (`MainActivity` & `ChatActivity`)
- Port `8988`: GO hosts a `ServerSocket`, client connects to it using a newline-delimited protocol over TCP.
- **`MainActivity` Inline Chat**: Features an embedded `RecyclerView` (`rvMessages`), empty-state view, live peer header (`tvActiveChatTitle` / `tvActiveChatSubtitle`), frosted glass search bar (`btnSearchMessages`), and message composer (`etMessage`, `btnSend`).
- **`ChatMessage` & `MessageAdapter`**: Dedicated `ChatMessage` data class (`text`, `isSentByMe`, `timestamp`, `status: MessageStatus`, `senderName`, `id`) with locale-aware bubble timestamp formatting (`formattedTimestamp`, adapting to 12/24-hour clock and showing dates for older threads), `ChatSearchHelper` live filtering & keyword/time highlighting, `VIEW_TYPE_SENT` / `VIEW_TYPE_RECEIVED` view types, `DiffUtil` updates, and single/double checkmark delivery & read receipt icons (`SENT`, `DELIVERED`, `READ`) persisted in Room (`ACK:` / `READ:` control envelopes).

### Persistent Chat Storage (Room Database)
- Every sent and received message is persisted to an app-private **Room Database** (`/data/data/com.example.lanvox/databases/lanvox_room_chat.db`) via KSP code generation:
  - `ChatMessageEntity` (`@Entity(tableName = "messages")`): stores `text`, `is_sent`, `timestamp`, `peer_id` (Wi-Fi Direct MAC), `peer_name`, `sender_name`, and `status`, indexed on `timestamp` and `peer_id`.
  - `ChatMessageDao` (`@Dao`): provides reactive `Flow<List<ChatMessageEntity>>`, `Flow<Int>` total count, `Flow<List<ConversationTuple>>`, and `suspend` insert/delete/query operations.
  - `LanvoxDatabase` (`@Database`): singleton Room database instance.
  - `ChatRepository`: maps entities/tuples to domain `ChatMessage` and `Conversation` models and exposes `Flow` streams collected with `repeatOnLifecycle(Lifecycle.State.STARTED)` in the UI.
- **Offline History**: Saved conversations remain available immediately after closing and reopening the app; sending new messages requires connecting to a peer.
- **Channel & Data Sheet** (`⋯` More Menu → `MainActionsBottomSheet`): Houses secondary and destructive actions cleanly off the main header — **Export Chat History** writes all conversations to `Downloads/LANVOX_chats_<timestamp>.txt` (Android 10+) or app Documents (Android 7–9), **Show Inline Radar**, **Disconnect Peer**, and a separated danger-zone **Delete All Chat History** (also available from `ChatsActivity`, or long-press a conversation to delete one thread).

### Audio Call (WebRTC Foreground Service)
- **`WebRtcAudioCallManager` (`io.github.webrtc-sdk:android`)**: Full-duplex **WebRTC** Unified Plan `PeerConnection` using `JavaAudioDeviceModule` (hardware Acoustic Echo Canceler, Noise Suppressor, Auto Gain Control), Opus codec over DTLS-SRTP/UDP with adaptive NetEQ jitter buffering, and buffered remote ICE candidate queuing.
- **P2P Signaling (`Constants.AUDIO_PORT` `8989`)**: Group Owner hosts a `ServerSocket` (`reuseAddress = true`) and acts as the SDP offerer; the client connects (up to 30 retries) as the SDP answerer, exchanging newline-delimited JSON `offer` / `answer` / `ice` / `bye` signaling packets while media flows peer-to-peer over ICE host candidates on the Wi-Fi Direct LAN.
- **Background Stability (`AudioCallService`)**: Runs as a `FOREGROUND_SERVICE_TYPE_MICROPHONE` foreground service holding a `PowerManager.PARTIAL_WAKE_LOCK`, `WifiManager.WifiLock` (`WIFI_MODE_FULL_LOW_LATENCY`), `AudioFocusRequest` (API 26+), `AudioManager.MODE_IN_COMMUNICATION`, and an interactive call notification (tap to reopen `AudioCallActivity`, **Mute/Unmute**, and **End Call** actions).

### Video Call
- Port `8990`: Dedicated TCP socket for length-prefixed JPEG frames fed through a single conflated `Channel` and single-writer coroutine (no concurrent socket writes or frame corruption)
- Camera2 (`CONTROL_AF_MODE_CONTINUOUS_VIDEO` + dynamic `JPEG_ORIENTATION`) → 15 FPS paced `ImageReader` → `OutputStream` → [WiFi Direct] → `InputStream` → off-UI `Dispatchers.Default` JPEG decode → aspect-preserving center-crop `Canvas` draw on `SurfaceView`
- Local PiP overlay uses `setZOrderMediaOverlay(true)` and dynamically attaches/reconfigures when its `SurfaceView` becomes ready; camera switch serialized on `cameraHandler` and `HandlerThread` cleanly stopped on exit
- Simultaneously manages `AudioCallService` (speakerphone enabled by default) and `CallSessionBus` lifecycle

### Receive Calls Even When the App Is Closed
LANVOX keeps a **standby listener** (`CallListenerService`) running as a foreground service, so incoming calls and messages reach the phone even when the app was swiped away or rebooted:
1. **Standby listener** — keeps a socket alive on port `8988` in both GO and Client roles and decodes full v5 `Envelope` packets (`HELLO`, `PEERS`, `TEXT`, `ACK`, `READ`, `CALL_REQ`, `CALL_END`).
2. **Boot receiver** — `BootReceiver` restarts the listener after reboot or app update.
3. **Incoming call while closed** — wakes the screen, plays ringtone, forwards caller peer ID/name, and shows `IncomingCallActivity` over the lock screen.
4. **Messages while closed** — incoming texts are saved via `ChatRepository` into Room and posted as notifications.
5. **Handover** — reopening the app seamlessly hands the chat socket back from the standby listener.

Required setup (prompted by the app):
- **Notification permission** (Android 13+) for incoming call/message alerts
- **Battery optimization exemption** for reliable background receiving

## App Flow

```
Splash → Onboarding (first launch) → MainActivity (Permissions + PeerPickerBottomSheet + Inline Chat)
                                          ├── ✉ ChatsActivity → ChatActivity (Per-contact Room history)
                                          ├── 📞 AudioCallActivity
                                          └── 📹 VideoCallActivity
```

## Prerequisites

- **Android Studio** (AGP 9.1.1 / Gradle 9.3.1 compatible)
- **Android device** running **API 24+** (Android 7.0+)
- **Wi-Fi Direct capable** hardware for live P2P discovery (UI & Room chat history work on emulators; live P2P requires two physical devices)
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

### Connect Two Phones

| Phone A | Phone B |
|---------|---------|
| 1. Open app, grant Wi-Fi P2P permissions | 1. Open app, grant Wi-Fi P2P permissions |
| 2. Open **Devices** sheet (`PeerPickerBottomSheet`) and tap **Scan** | 2. Open **Devices** sheet and tap **Scan** |
| 3. Phone B appears in peer list | 3. Phone A appears in peer list |
| 4. Tap Phone B to connect | (Connection established) |

### Text Chat
- Type messages in the bottom composer of `MainActivity` (or inside a thread in `ChatActivity`) and tap **Send**
- Messages appear in real time and are immediately saved in Room so they remain visible after restarting the app

### Audio & Video Calls
- Tap **📞 Audio Call** or **📹 Video Call** once connected
- Controls: Mute, Speaker, Switch Camera (video), End Call

## Project Structure

```
app/src/main/java/com/example/wifip2pchat/
├── Constants.kt                    — Ports, audio/video config, message types
├── Envelope.kt                     — Wire message framing helpers
├── WiFiDirectManager.kt            — Core Wi-Fi Direct logic, permission guards & session restore
├── WiFiDirectBroadcastReceiver.kt  — P2P system event listener (Android 13+ compatible)
├── ChatServerService.kt            — Text chat socket server (GO)
├── ChatClient.kt                   — Text chat socket client (with auto-reconnect)
├── CallListenerService.kt          — Standby listener: receives calls/messages while app closed
├── BootReceiver.kt                 — Restarts the standby listener after boot
├── LastSession.kt                  — Remembers last connection (role, GO IP, peer)
├── ChatMessage.kt                  — Domain ChatMessage model & MessageStatus enum
├── ChatMessageEntity.kt            — Room @Entity table definition ("messages")
├── ChatMessageDao.kt               — Room @Dao queries (Flow & suspend) + ConversationTuple
├── LanvoxDatabase.kt               — Room @Database singleton ("lanvox_room_chat.db")
├── ChatRepository.kt               — Repository layer & Conversation domain model
├── ChatBus.kt                      — Event bus between main screen and chat threads
├── CallSessionBus.kt               — Shared call signaling state (`RINGING`/`CONNECTING`/`CONNECTED`/`RECONNECTING`/`ENDED`), mute sync & AudioFocus manager
├── WebRtcAudioCallManager.kt       — WebRTC PeerConnection, JavaAudioDeviceModule (AEC/NS/AGC), SDP Offer/Answer & ICE candidate queue
├── AudioCallService.kt             — Microphone foreground service hosting WebRTC P2P audio, wake/Wi-Fi locks & ongoing notification controls
├── VideoCallService.kt             — Video streaming foreground service (Camera2 + conflated TCP writer)
├── AudioCallActivity.kt            — Audio call UI
├── VideoCallActivity.kt            — Video call UI
├── IncomingCallActivity.kt         — Incoming call screen (app open or closed)
├── MainActivity.kt                 — Main screen: streamlined Obsidian Glass header, PeerPickerBottomSheet, inline RecyclerView chat & call controls
├── MainActionsBottomSheet.kt       — Material BottomSheetDialog for secondary/destructive actions (Export, Inline Radar, Disconnect, Clear History)
├── PeerPickerBottomSheet.kt        — Material BottomSheetDialog for peer discovery & selection
├── ChatsActivity.kt                — Chats list (all conversations observed via Room Flow)
├── ChatsAdapter.kt                 — RecyclerView adapter for the chats list
├── ChatActivity.kt                 — Per-contact chat thread
├── PeerAdapter.kt                  — RecyclerView adapter for discovered peers
├── MessageAdapter.kt               — RecyclerView adapter for ChatMessage items (sent/received + DiffUtil + search highlights)
├── ChatSearchHelper.kt             — Live message filtering by content, sender, or timestamp + span highlighting
├── MessageSwipeHelper.kt           — ItemTouchHelper swipe-to-delete on message bubbles (UI + Room + Undo)
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
| `CHANGE_WIFI_STATE` | Enable Wi-Fi Direct |
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

- **No peers found**: Both phones need Wi-Fi ON, Location enabled (on Android <13), and Wi-Fi P2P permissions granted. Move closer together.
- **Can't start call**: Ensure both phones are connected (green indicator) before tapping call.
- **No video**: Check that camera permission is granted.
- **No audio during call**: Check microphone permission and try toggling speaker on/off.
- **Calls not received while app is closed**: Make sure LANVOX was granted notification permission and battery-optimization exemption.
- **Poor video quality**: Wi-Fi Direct range is typically 10–50m; video quality drops with distance.

## Technical Details

- **Language**: Kotlin (AGP 9.1.1 built-in Kotlin, Gradle 9.3.1)
- **Min SDK**: 24 (Android 7.0)
- **Target SDK**: 34 (Android 14)
- **Persistence**: AndroidX Room (`2.7.1`) with KSP (`2.3.5`), Repository pattern, and Kotlin `Flow` / `StateFlow`
- **Architecture**: P2P Group Owner / Client sockets + Repository-backed Room persistence
- **Audio**: WebRTC (`io.github.webrtc-sdk:android`) `PeerConnection` + `JavaAudioDeviceModule` (Opus SRTP, hardware AEC/NS/AGC) over Wi-Fi Direct LAN ICE candidates with JSON TCP signaling (`8989`) inside a `microphone` foreground service
- **Video**: Camera2 API → JPEG frames → length-prefixed TCP protocol
- **Concurrency**: Kotlin Coroutines & Flow (`kotlinx.coroutines`)
- **UI / UX**: Glassmorphism & Cryo-Cyan Orbital HUD aesthetic (`#050811` void, `#00F0FF` cryo-cyan, semi-transparent frosted chat bubbles with soft glow borders, `RenderEffect` blurred ambient light orbs via `GlassEffects.kt`, floating glass composer dock), `RadarView` conical `SweepGradient`, bundled `Space Grotesk`, `Plus Jakarta Sans`, & `JetBrains Mono` typography (`res/font/`), `BottomSheetDialog`, `RecyclerView` (`DiffUtil`), and ViewBinding

## License

MIT
