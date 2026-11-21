package com.example.wifip2pchat

import android.content.Context
import android.util.Log
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.audio.AudioDeviceModule
import org.webrtc.audio.JavaAudioDeviceModule
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Encapsulates the WebRTC audio stack for peer-to-peer Wi-Fi Direct calls:
 *  - Singleton [PeerConnectionFactory] initialization
 *  - Hardware-accelerated [JavaAudioDeviceModule] (AEC, NS, AGC + error telemetry)
 *  - Local [AudioSource] / [AudioTrack] with software mute gating
 *  - Unified Plan [PeerConnection] tuned for direct local/LAN ICE gathering
 *  - Pending remote ICE candidate queue so candidates arriving before
 *    `setRemoteDescription()` completes are never dropped
 *  - Serialized single-executor execution & ordered native resource disposal
 */
class WebRtcAudioCallManager(
    context: Context,
    private val listener: Listener
) {

    interface Listener {
        fun onLocalOffer(sdp: SessionDescription)
        fun onLocalAnswer(sdp: SessionDescription)
        fun onLocalIceCandidate(candidate: IceCandidate)
        fun onConnected()
        fun onReconnecting()
        fun onDisconnected(reason: String)
        fun onError(message: String)
    }

    companion object {
        private const val TAG = "WebRtcAudioCallManager"
        private const val AUDIO_TRACK_ID = "LANVOX_AUDIO_TRACK"
        private const val STREAM_ID = "LANVOX_AUDIO_STREAM"
        private val webrtcInitialized = AtomicBoolean(false)

        private fun ensureWebRtcInitialized(appContext: Context) {
            if (webrtcInitialized.compareAndSet(false, true)) {
                val options = PeerConnectionFactory.InitializationOptions.builder(appContext)
                    .setEnableInternalTracer(false)
                    .createInitializationOptions()
                PeerConnectionFactory.initialize(options)
                Log.d(TAG, "WebRTC native PeerConnectionFactory initialized")
            }
        }
    }

    private val appContext = context.applicationContext
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "LanvoxWebRtcAudio").apply { priority = Thread.NORM_PRIORITY + 1 }
    }
    private val disposed = AtomicBoolean(false)

    private var audioDeviceModule: AudioDeviceModule? = null
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var audioSource: AudioSource? = null
    private var localAudioTrack: AudioTrack? = null
    private var peerConnection: PeerConnection? = null

    @Volatile
    private var remoteDescriptionSet = false
    private val pendingRemoteIceCandidates = mutableListOf<IceCandidate>()

    @Volatile
    private var isMuted = false

    fun initialize(isOfferer: Boolean) {
        if (disposed.get()) return
        executor.execute {
            if (disposed.get()) return@execute
            try {
                ensureWebRtcInitialized(appContext)

                val adm = buildAudioDeviceModule()
                audioDeviceModule = adm

                val factory = PeerConnectionFactory.builder()
                    .setAudioDeviceModule(adm)
                    .createPeerConnectionFactory()
                peerConnectionFactory = factory

                val audioConstraints = MediaConstraints().apply {
                    mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
                    mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
                    mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
                    mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "true"))
                    mandatory.add(MediaConstraints.KeyValuePair("googTypingNoiseDetection", "true"))
                }

                val source = factory.createAudioSource(audioConstraints)
                audioSource = source

                val track = factory.createAudioTrack(AUDIO_TRACK_ID, source).apply {
                    setEnabled(!isMuted)
                }
                localAudioTrack = track

                val rtcConfig = PeerConnection.RTCConfiguration(emptyList()).apply {
                    // Direct Wi-Fi Direct / LAN peer-to-peer: host UDP & TCP candidates connect locally without STUN
                    sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                    iceTransportsType = PeerConnection.IceTransportsType.ALL
                    tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.ENABLED
                    bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
                    rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
                    continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE
                }

                val pc = factory.createPeerConnection(rtcConfig, peerConnectionObserver)
                if (pc == null) {
                    listener.onError("Failed to create WebRTC PeerConnection")
                    return@execute
                }
                peerConnection = pc
                pc.addTrack(track, listOf(STREAM_ID))

                Log.d(TAG, "WebRTC audio stack ready (isOfferer=$isOfferer)")
                if (isOfferer) {
                    createAndSendOffer(pc)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "WebRTC initialization failed: ${t.message}", t)
                listener.onError("WebRTC audio init failed: ${t.message ?: "unknown error"}")
            }
        }
    }

    fun setMuted(muted: Boolean) {
        isMuted = muted
        if (disposed.get()) return
        executor.execute {
            if (disposed.get()) return@execute
            try {
                localAudioTrack?.setEnabled(!muted)
                audioDeviceModule?.setMicrophoneMute(muted)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to update WebRTC mute state: ${e.message}")
            }
        }
    }

    fun onRemoteOfferReceived(sdpDescription: String) {
        if (disposed.get()) return
        executor.execute {
            val pc = peerConnection ?: return@execute
            val offer = SessionDescription(SessionDescription.Type.OFFER, sdpDescription)
            pc.setRemoteDescription(object : SimpleSdpObserver("setRemoteOffer") {
                override fun onSetSuccess() {
                    remoteDescriptionSet = true
                    flushPendingIceCandidates(pc)
                    createAndSendAnswer(pc)
                }

                override fun onSetFailure(error: String?) {
                    listener.onError("Remote offer rejected: $error")
                }
            }, offer)
        }
    }

    fun onRemoteAnswerReceived(sdpDescription: String) {
        if (disposed.get()) return
        executor.execute {
            val pc = peerConnection ?: return@execute
            val answer = SessionDescription(SessionDescription.Type.ANSWER, sdpDescription)
            pc.setRemoteDescription(object : SimpleSdpObserver("setRemoteAnswer") {
                override fun onSetSuccess() {
                    remoteDescriptionSet = true
                    flushPendingIceCandidates(pc)
                }

                override fun onSetFailure(error: String?) {
                    listener.onError("Remote answer rejected: $error")
                }
            }, answer)
        }
    }

    fun onRemoteIceCandidateReceived(sdpMid: String, sdpMLineIndex: Int, sdpCandidate: String) {
        if (disposed.get()) return
        val candidate = IceCandidate(sdpMid, sdpMLineIndex, sdpCandidate)
        executor.execute {
            if (disposed.get()) return@execute
            val pc = peerConnection
            if (pc == null || !remoteDescriptionSet) {
                pendingRemoteIceCandidates.add(candidate)
            } else {
                pc.addIceCandidate(candidate)
            }
        }
    }

    fun dispose() {
        if (!disposed.compareAndSet(false, true)) return
        executor.execute {
            try {
                pendingRemoteIceCandidates.clear()
                localAudioTrack?.setEnabled(false)
                try { peerConnection?.close() } catch (_: Exception) {}
                try { peerConnection?.dispose() } catch (_: Exception) {}
                peerConnection = null

                try { localAudioTrack?.dispose() } catch (_: Exception) {}
                localAudioTrack = null

                try { audioSource?.dispose() } catch (_: Exception) {}
                audioSource = null

                try { audioDeviceModule?.release() } catch (_: Exception) {}
                audioDeviceModule = null

                try { peerConnectionFactory?.dispose() } catch (_: Exception) {}
                peerConnectionFactory = null

                Log.d(TAG, "WebRTC audio resources disposed cleanly")
            } catch (e: Exception) {
                Log.w(TAG, "Error disposing WebRTC audio stack: ${e.message}")
            } finally {
                executor.shutdown()
            }
        }
    }

    // ======================== Internal WebRTC helpers ========================

    private fun buildAudioDeviceModule(): JavaAudioDeviceModule {
        val useHwAec = JavaAudioDeviceModule.isBuiltInAcousticEchoCancelerSupported()
        val useHwNs = JavaAudioDeviceModule.isBuiltInNoiseSuppressorSupported()
        Log.d(TAG, "JavaAudioDeviceModule hardware AEC=$useHwAec, hardware NS=$useHwNs")

        return JavaAudioDeviceModule.builder(appContext)
            .setUseHardwareAcousticEchoCanceler(useHwAec)
            .setUseHardwareNoiseSuppressor(useHwNs)
            .setAudioRecordErrorCallback(object : JavaAudioDeviceModule.AudioRecordErrorCallback {
                override fun onWebRtcAudioRecordInitError(errorMessage: String?) {
                    Log.e(TAG, "WebRTC AudioRecord init error: $errorMessage")
                    listener.onError("Microphone init failed")
                }

                override fun onWebRtcAudioRecordStartError(
                    errorCode: JavaAudioDeviceModule.AudioRecordStartErrorCode?,
                    errorMessage: String?
                ) {
                    Log.e(TAG, "WebRTC AudioRecord start error ($errorCode): $errorMessage")
                    listener.onError("Microphone capture error")
                }

                override fun onWebRtcAudioRecordError(errorMessage: String?) {
                    Log.e(TAG, "WebRTC AudioRecord runtime error: $errorMessage")
                }
            })
            .setAudioTrackErrorCallback(object : JavaAudioDeviceModule.AudioTrackErrorCallback {
                override fun onWebRtcAudioTrackInitError(errorMessage: String?) {
                    Log.e(TAG, "WebRTC AudioTrack init error: $errorMessage")
                }

                override fun onWebRtcAudioTrackStartError(
                    errorCode: JavaAudioDeviceModule.AudioTrackStartErrorCode?,
                    errorMessage: String?
                ) {
                    Log.e(TAG, "WebRTC AudioTrack start error ($errorCode): $errorMessage")
                }

                override fun onWebRtcAudioTrackError(errorMessage: String?) {
                    Log.e(TAG, "WebRTC AudioTrack runtime error: $errorMessage")
                }
            })
            .createAudioDeviceModule()
    }

    private fun createAndSendOffer(pc: PeerConnection) {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
        }
        pc.createOffer(object : SimpleSdpObserver("createOffer") {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                if (sdp == null || disposed.get()) return
                pc.setLocalDescription(object : SimpleSdpObserver("setLocalOffer") {
                    override fun onSetSuccess() {
                        listener.onLocalOffer(sdp)
                    }

                    override fun onSetFailure(error: String?) {
                        listener.onError("Failed to set local offer: $error")
                    }
                }, sdp)
            }

            override fun onCreateFailure(error: String?) {
                listener.onError("Failed to create offer: $error")
            }
        }, constraints)
    }

    private fun createAndSendAnswer(pc: PeerConnection) {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
        }
        pc.createAnswer(object : SimpleSdpObserver("createAnswer") {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                if (sdp == null || disposed.get()) return
                pc.setLocalDescription(object : SimpleSdpObserver("setLocalAnswer") {
                    override fun onSetSuccess() {
                        listener.onLocalAnswer(sdp)
                    }

                    override fun onSetFailure(error: String?) {
                        listener.onError("Failed to set local answer: $error")
                    }
                }, sdp)
            }

            override fun onCreateFailure(error: String?) {
                listener.onError("Failed to create answer: $error")
            }
        }, constraints)
    }

    private fun flushPendingIceCandidates(pc: PeerConnection) {
        if (pendingRemoteIceCandidates.isEmpty()) return
        Log.d(TAG, "Flushing ${pendingRemoteIceCandidates.size} buffered ICE candidates")
        pendingRemoteIceCandidates.forEach { pc.addIceCandidate(it) }
        pendingRemoteIceCandidates.clear()
    }

    private val peerConnectionObserver = object : PeerConnection.Observer {
        override fun onSignalingChange(newState: PeerConnection.SignalingState?) {
            Log.d(TAG, "Signaling state -> $newState")
        }

        override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) {
            Log.d(TAG, "ICE connection state -> $newState")
            if (disposed.get()) return
            when (newState) {
                PeerConnection.IceConnectionState.CONNECTED,
                PeerConnection.IceConnectionState.COMPLETED -> listener.onConnected()
                PeerConnection.IceConnectionState.DISCONNECTED -> listener.onReconnecting()
                PeerConnection.IceConnectionState.FAILED ->
                    listener.onDisconnected("WebRTC ICE connection failed")
                PeerConnection.IceConnectionState.CLOSED ->
                    listener.onDisconnected("Call ended")
                else -> Unit
            }
        }

        override fun onIceConnectionReceivingChange(receiving: Boolean) {
            Log.d(TAG, "ICE receiving audio -> $receiving")
        }

        override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState?) {
            Log.d(TAG, "ICE gathering state -> $newState")
        }

        override fun onIceCandidate(candidate: IceCandidate?) {
            if (candidate != null && !disposed.get()) {
                listener.onLocalIceCandidate(candidate)
            }
        }

        override fun onIceCandidatesRemoved(candidates: Array<IceCandidate>?) = Unit

        override fun onAddStream(stream: MediaStream?) {
            Log.d(TAG, "Remote WebRTC audio stream attached (${stream?.audioTracks?.size ?: 0} tracks)")
        }

        override fun onRemoveStream(stream: MediaStream?) = Unit

        override fun onDataChannel(dataChannel: DataChannel?) = Unit

        override fun onRenegotiationNeeded() = Unit

        override fun onAddTrack(receiver: RtpReceiver?, mediaStreams: Array<out MediaStream>?) {
            Log.d(TAG, "Remote WebRTC RTP audio track ready")
        }
    }

    private open class SimpleSdpObserver(private val label: String) : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription?) = Unit
        override fun onSetSuccess() = Unit
        override fun onCreateFailure(error: String?) {
            Log.e(TAG, "$label createFailure: $error")
        }

        override fun onSetFailure(error: String?) {
            Log.e(TAG, "$label setFailure: $error")
        }
    }
}
