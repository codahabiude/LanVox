# LANVOX — Android App (Kotlin)

A peer-to-peer communication app that uses **Wi-Fi Direct (P2P)** to connect two Android phones directly — **no internet, router, or server required**. Supports **text messaging**, **audio calling**, and **video calling**.

## Features

| Feature | Description |
|---------|-------------|
| 💬 **Text Chat** | Real-time text messaging between two phones |
| 📞 **Audio Call** | Voice call using mic → socket → speaker |
| 📹 **Video Call** | Live video call with Camera2 + PiP preview |
| 🔍 **Peer Discovery** | Find nearby devices via Wi-Fi Direct |
| 📡 **No Internet Needed** | Direct WiFi P2P — no router or data plan |
| 🔀 **Camera Switch** | Toggle front/back camera during video calls |
| 🔇 **Mute / Speaker** | Audio controls during calls |
| 🌙 **Dark Mode** | Full dark theme support |
| 🔔 **Call Ringtone** | Custom ringtone for incoming calls |
| 📖 **Onboarding** | First-launch tutorial with permission explanations |
| 💾 **Chat History Saved** | Every message auto-saved to phone storage (SQLite) — restored on next launch |
| 👥 **Per-Contact Chats** | WhatsApp-style chats list — each device gets its own conversation thread |
| 📲 **Calls With App Closed** | Incoming calls ring over the lock screen even when LANVOX isn't opened — answers from the lock screen |
| 📨 **Messages While Closed** | Messages received while the app is closed are saved and shown as notifications |
| 🔄 **Auto-Reconnect** | Chat channel and audio/video sockets retry silently after glitches or handovers |
| 📤 **Export Chats** | Save all conversations as a .txt file in the Downloads folder |
| 🗑️ **Clear History** | Wipe one conversation (long-press) or everything, with confirmation |

## How It Works

### Wi-Fi Direct Connection
1. **Wi-Fi Direct** creates a direct wireless link between two devices
2. One device becomes the **Group Owner (GO)** — it acts as the access point
3. The other device connects as a **client**

### Text Chat
- Port `8988`: GO hosts a `ServerSocket`, client connects to it
- Newline-delimited text protocol over TCP

### Persistent Chat Storage (per-contact threads)
- Every sent/received message is written to a private SQLite database on the phone:
  `/data/data/com.example.lanvox/databases/lanvox_chat.db`
- Each message is tagged with the remote device's **WiFi Direct MAC** (`peer_id`) + display name (`peer_name`), so **every contact gets its own conversation thread**
- **Chats screen** (✉ icon on the main screen): lists all conversations with the last message preview, time and message count — tap to open one, long-press to delete it
- When a connection forms, the chat thread for that device **opens automatically**; tapping a connected device on the main screen re-opens its thread
- Inside a chat: audio/video call buttons in the top bar, live status line (`Connected • WiFi Direct` / `Offline • saved history`), and only that peer's history
- Offline mode: open any old conversation to read it — sending requires being connected to that same device
- New messages that arrive while you're not in the chat raise a "New message from …" snackbar on the main screen
- **Export** (💾 icon) writes all conversations to `Downloads/LANVOX_chats_<timestamp>.txt` (Android 10+) or the app Documents folder (Android 7–9)
- **Clear-all** (🗑 icon) wipes every stored message after confirmation
- The database is app-private — no storage permissions required, and it is included in Android backups
- Database v1 → v2 migration is automatic (old messages are kept and backfilled)

### Audio Call
- Port `8989`: Separate socket for raw PCM audio
- Mic → `AudioRecord` → `OutputStream` → [WiFi Direct] → `InputStream` → `AudioTrack` → Speaker
- 16kHz mono 16-bit PCM, optimized for voice communication
- Runs as a **foreground service** so the call survives app minimization
- The dialing side **retries the audio connection for up to ~30 s**, so the call still
  connects even if the other phone needs a while to answer (e.g. it wakes from the
  lock screen)

### Video Call
- Port `8990`: Separate socket for video frames
- Camera2 → `ImageReader` (JPEG) → length-prefixed protocol → `OutputStream` → [WiFi Direct]
- Receiver reads frame length → frame bytes → decode JPEG → draw on `SurfaceView`
- Simultaneously starts `AudioCallService` for audio
- 640×480 @ 15fps JPEG, ~500kbps bitrate
- Local camera shown as picture-in-picture overlay

### Receive Calls Even When the App Is Closed (v4.0)

LANVOX keeps a tiny **standby listener** (`CallListenerService`) running as a foreground
service, so a call from your friend rings your phone even when the app was swiped away,
the phone was rebooted, or the app was never opened after installing:

1. **Standby listener** — a quiet persistent notification ("Ready to receive calls")
   keeps a socket alive on the chat port. It automatically re-checks the WiFi Direct
   group state and works in both roles:
   - Group Owner → listens on port `8988` for the peer to connect
   - Client → actively connects out to the group owner (a client can only receive
     data over its own outgoing socket)
2. **Boot receiver** — restarts the listener after the phone reboots (and after app
   updates), so no manual step is ever needed
3. **Incoming call while closed** — the screen wakes and the system ringtone plays;
   a full-screen call card appears **over the lock screen** with Answer / Decline
   (plus buttons on the notification itself as a fallback)
4. **Answer** → LANVOX's call screen opens and the call connects exactly like an
   in-app call. **Decline** → the caller hears the normal "declined".
   **No answer** → after 45 s the caller sees "declined" and you get a
   **Missed call** notification
5. **Messages while closed** — incoming texts are stored to the same per-contact
   SQLite threads and raise a notification; tapping it opens that conversation
6. **Handover** — when you open the app again it takes the chat channel back over
   from the standby listener seamlessly (the peer's client auto-reconnects), and
   on open the app restores any still-formed WiFi Direct session automatically

Required setup (asked once by the app):
- **Notification permission** (Android 13+) so the call card can appear
- **Battery optimization exemption** — aggressive OEM battery managers kill
  background listeners; LANVOX prompts for this automatically

> ⚠️ Platform limit: if you **Force stop** the app from system Settings, Android will
> not restart it until you open it once. Swiping the app away from recents is fine —
> the listener survives that.

## App Flow

```
Splash → Main (discover + connect) → Chat thread for that device (auto-opens)
              │
              └── ✉ Chats list → tap a conversation → its chat thread
```

## Prerequisites

- **Android Studio** (Hedgehog or newer)
- **Android device** running **API 24+** (Android 7.0+)
- **Wi-Fi Direct capable** device (most modern phones)
- **Two phones** for testing (emulators don't support Wi-Fi Direct)
- **Camera and microphone** on both devices

## Setup & Build

### 1. Open in Android Studio

```bash
cd WifiP2PChat
# File → Open → select the WifiP2PChat directory
```

### 2. Build

```bash
./gradlew assembleDebug
```

### 3. Install on both phones

Connect each phone via USB and run:

```bash
./gradlew installDebug
```

## Usage

### Connect Two Phones

| Phone A | Phone B |
|---------|---------|
| 1. Open app, grant permissions | 1. Open app, grant permissions |
| 2. Tap **Discover** | 2. Tap **Discover** |
| 3. Phone B appears in list | 3. Phone A appears in list |
| 4. Tap Phone B to connect | (Connection established) |

### Text Chat
- Type messages and tap **Send**
- Messages appear in real-time on both phones

### Audio Call
- Tap **📞 Audio Call**
- Full-screen call UI with duration timer
- Controls: Mute, Speaker, End Call
- The other phone automatically receives and opens the call screen

### Video Call
- Tap **📹 Video Call**
- Full-screen remote video with local PiP camera preview
- Controls: Mute, Switch Camera, Speaker, End Call
- Audio is included automatically
- The other phone automatically receives and opens the call screen

### Disconnect
- Tap **Disconnect** to end the P2P connection

## Project Structure

```
app/src/main/java/com/example/wifip2pchat/
├── Constants.kt                    — Ports, audio/video config, message types
├── WiFiDirectManager.kt            — Core Wi-Fi Direct logic + session restore
├── WiFiDirectBroadcastReceiver.kt  — P2P system event listener
├── ChatServerService.kt            — Text chat socket server (GO)
├── ChatClient.kt                   — Text chat socket client (with auto-reconnect)
├── CallListenerService.kt          — Standby listener: receives calls/messages while app closed
├── BootReceiver.kt                 — Restarts the standby listener after boot
├── LastSession.kt                  — Remembers last connection (role, GO IP, peer)
├── ChatDatabase.kt                 — SQLite persistence, per-contact threads
├── ChatBus.kt                      — Event bus between main screen and chat threads
├── AudioCallService.kt             — Audio streaming foreground service
├── VideoCallService.kt             — Video streaming foreground service
├── AudioCallActivity.kt            — Audio call UI
├── VideoCallActivity.kt            — Video call UI
├── IncomingCallActivity.kt         — Incoming call screen (app open or closed)
├── MainActivity.kt                 — Main screen: discovery + connect + call buttons
├── ChatsActivity.kt                — Chats list (all conversations)
├── ChatsAdapter.kt                 — RecyclerView for the chats list
├── ChatActivity.kt                 — Per-contact chat thread
├── PeerAdapter.kt                  — RecyclerView for peer list
├── MessageAdapter.kt               — RecyclerView for chat messages
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
| `ACCESS_FINE_LOCATION` | Required for peer discovery |
| `ACCESS_COARSE_LOCATION` | Fallback location |
| `NEARBY_WIFI_DEVICES` | Android 13+ Wi-Fi discovery |
| `INTERNET` | Socket communication |
| `ACCESS_NETWORK_STATE` | Check connectivity |
| `CHANGE_NETWORK_STATE` | P2P network setup |
| `RECORD_AUDIO` | Microphone for audio/video calls |
| `MODIFY_AUDIO_SETTINGS` | Speaker/mute controls |
| `CAMERA` | Camera for video calls |
| `FOREGROUND_SERVICE` | Keep call services alive |
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

- **No peers found**: Both phones need Wi-Fi ON and Location enabled. Move closer together.
- **Can't start call**: Ensure both phones are connected (green indicator) before tapping call.
- **No video**: Check that camera permission is granted. Some devices may need `CAMERA` permission in Settings.
- **No audio during call**: Check microphone permission. Try toggling speaker on/off.
- **Call drops**: The foreground service should keep it alive, but aggressive battery savers (Samsung, Xiaomi) may kill it. Add the app to battery optimization exceptions.
- **Calls not received while app is closed**: Make sure LANVOX was granted the notification permission and the battery-optimization exemption (the app asks for both). If you ever used **Force stop** in system settings, open the app once — Android blocks background restart for force-stopped apps.
- **Standby notification always visible**: This is expected — that persistent (silent) notification is what keeps the listener alive so calls can reach you. You can hide its category ("Background listener") in system notification settings without stopping it.
- **Poor video quality**: WiFi Direct range is typically 10-50m. Video quality drops with distance.

## Technical Details

- **Language**: Kotlin
- **Min SDK**: 24 (Android 7.0)
- **Target SDK**: 34 (Android 14)
- **Architecture**: P2P with Group Owner / Client model
- **Audio**: PCM 16kHz mono via `AudioRecord` / `AudioTrack`
- **Video**: Camera2 API → JPEG frames → length-prefixed TCP protocol
- **Concurrency**: Kotlin coroutines (`kotlinx.coroutines`) for all I/O
- **UI**: Material Design Components with dark mode

## License

MIT
