package com.example.wifip2pchat

import android.app.*
import android.content.Intent
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import android.view.Surface
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.io.*
import java.net.*
import java.nio.ByteBuffer

/**
 * Foreground service that handles video calling over WiFi Direct.
 *
 * Architecture:
 *   Group Owner: listens on [Constants.VIDEO_PORT], accepts one connection
 *   Client: connects to GO's address on [Constants.VIDEO_PORT]
 *
 * Video flow:
 *   Camera2 → ImageReader → JPEG bytes → OutputStream → [WiFi Direct]
 *   → InputStream → decode JPEG → display on remote SurfaceView
 *
 * Also starts [AudioCallService] for simultaneous audio.
 */
class VideoCallService : Service() {

    companion object {
        private const val TAG = "VideoCallService"
        private const val NOTIFICATION_ID = 3001
        private const val CHANNEL_ID = "video_call_channel"

        var isRunning = false
            private set

        /** Callback to deliver received video frames for display */
        var onRemoteVideoFrame: ((ByteArray) -> Unit)? = null

        /** Surface for local camera preview — set by VideoCallActivity */
        var localPreviewSurface: Surface? = null

        private var instance: VideoCallService? = null
        fun endCall() {
            instance?.endCallInternal()
        }
        fun startAsGroupOwner() {
            instance?.startAsGroupOwnerInternal()
        }
        fun connectToGroupOwner(hostAddress: String) {
            instance?.connectToGroupOwnerInternal(hostAddress)
        }
        fun switchCamera() {
            instance?.switchCameraInternal()
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Network
    private var serverSocket: ServerSocket? = null
    private var socket: Socket? = null
    private var outputStream: OutputStream? = null
    private var inputStream: InputStream? = null

    // Camera
    private var cameraManager: CameraManager? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var cameraHandler: Handler? = null
    private var cameraThread: HandlerThread? = null
    private var isUsingFrontCamera = true
    private var isStreaming = false

    // Playback
    private var playJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        instance = this
        cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager
        setupCameraThread()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Video call active"))
        Log.d(TAG, "Video call service created")
    }

    override fun onDestroy() {
        isRunning = false
        instance = null
        stopStreaming()
        closeCamera()
        closeSockets()
        scope.cancel()
        super.onDestroy()
        Log.d(TAG, "Video call service destroyed")
    }

    // ======================== Server/Client Setup ========================

    fun startAsGroupOwnerInternal() {
        scope.launch {
            try {
                serverSocket = ServerSocket(Constants.VIDEO_PORT)
                Log.d(TAG, "Video server listening on port ${Constants.VIDEO_PORT}")
                val conn = serverSocket?.accept() ?: return@launch
                socket = conn
                outputStream = conn.getOutputStream()
                inputStream = conn.getInputStream()
                Log.d(TAG, "Video client connected")
                openCameraAndStream()
                startVideoPlayback()
            } catch (e: Exception) {
                if (scope.isActive) Log.e(TAG, "GO video error: ${e.message}")
            }
        }
    }

    /**
     * Connect as client to the Group Owner's video server.
     * The peer may still be answering the call (its video server socket is
     * only bound after the user taps Accept), so we retry patiently
     * instead of failing after a single 5-second attempt.
     */
    fun connectToGroupOwnerInternal(hostAddress: String) {
        scope.launch {
            repeat(30) { attempt ->
                if (!isRunning) return@launch
                try {
                    val conn = Socket()
                    conn.connect(InetSocketAddress(hostAddress, Constants.VIDEO_PORT), 3_000)
                    socket = conn
                    outputStream = conn.getOutputStream()
                    inputStream = conn.getInputStream()
                    Log.d(TAG, "Connected to GO video at $hostAddress:${Constants.VIDEO_PORT}")
                    openCameraAndStream()
                    startVideoPlayback()
                    return@launch
                } catch (e: Exception) {
                    Log.d(TAG, "Video connect attempt ${attempt + 1} failed: ${e.message}")
                    delay(1_000)
                }
            }
            Log.e(TAG, "Could not reach peer video port after 30 attempts")
        }
    }

    // ======================== Camera Setup ========================

    private fun setupCameraThread() {
        cameraThread = HandlerThread("CameraThread").apply { start() }
        cameraHandler = Handler(cameraThread!!.looper)
    }

    private fun getCameraId(front: Boolean): String? {
        try {
            val ids = cameraManager?.cameraIdList ?: return null
            for (id in ids) {
                val chars = cameraManager?.getCameraCharacteristics(id) ?: continue
                val facing = chars.get(CameraCharacteristics.LENS_FACING)
                if (front && facing == CameraCharacteristics.LENS_FACING_FRONT) return id
                if (!front && facing == CameraCharacteristics.LENS_FACING_BACK) return id
            }
            // Fallback to first camera
            return ids.firstOrNull()
        } catch (e: Exception) {
            Log.e(TAG, "Error finding camera: ${e.message}")
            return null
        }
    }

    private fun openCameraAndStream() {
        val cameraId = getCameraId(isUsingFrontCamera) ?: run {
            Log.e(TAG, "No camera available")
            return
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                cameraManager?.openCamera(cameraId, cameraStateCallback, cameraHandler)
            } else {
                @Suppress("DEPRECATION")
                cameraManager?.openCamera(cameraId, cameraStateCallback, cameraHandler)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error opening camera: ${e.message}")
        } catch (se: SecurityException) {
            Log.e(TAG, "Camera permission not granted: ${se.message}")
        }
    }

    private val cameraStateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            cameraDevice = camera
            Log.d(TAG, "Camera opened")
            startCaptureSession()
        }

        override fun onDisconnected(camera: CameraDevice) {
            camera.close()
            cameraDevice = null
        }

        override fun onError(camera: CameraDevice, error: Int) {
            Log.e(TAG, "Camera error: $error")
            camera.close()
            cameraDevice = null
        }
    }

    private fun startCaptureSession() {
        val device = cameraDevice ?: return

        // ImageReader for capturing frames to send over network
        imageReader = ImageReader.newInstance(
            Constants.VIDEO_WIDTH, Constants.VIDEO_HEIGHT,
            ImageFormat.JPEG, 2
        )

        imageReader?.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val buffer = image.planes[0].buffer
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                sendVideoFrame(bytes)
            } catch (e: Exception) {
                Log.e(TAG, "Frame read error: ${e.message}")
            } finally {
                image.close()
            }
        }, cameraHandler)

        val surfaces = mutableListOf<Surface>()
        surfaces.add(imageReader!!.surface)
        localPreviewSurface?.let { surfaces.add(it) }

        try {
            val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            surfaces.forEach { builder.addTarget(it) }

            device.createCaptureSession(
                surfaces,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        isStreaming = true
                        try {
                            session.setRepeatingRequest(
                                builder.build(), null, cameraHandler
                            )
                            Log.d(TAG, "Video capture started")
                        } catch (e: Exception) {
                            Log.e(TAG, "Repeating request error: ${e.message}")
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(TAG, "Capture session config failed")
                    }
                },
                cameraHandler
            )
        } catch (e: Exception) {
            Log.e(TAG, "Capture session error: ${e.message}")
        }
    }

    /** Switch between front and back camera. */
    fun switchCameraInternal() {
        closeCamera()
        isUsingFrontCamera = !isUsingFrontCamera
        openCameraAndStream()
    }

    // ======================== Send Video ========================

    private fun sendVideoFrame(jpegBytes: ByteArray) {
        scope.launch {
            try {
                val dos = DataOutputStream(outputStream ?: return@launch)
                // Write frame length + frame data (length-prefixed protocol)
                dos.writeInt(jpegBytes.size)
                dos.write(jpegBytes)
                dos.flush()
            } catch (e: Exception) {
                if (scope.isActive) Log.e(TAG, "Video send error: ${e.message}")
            }
        }
    }

    // ======================== Receive Video ========================

    private fun startVideoPlayback() {
        playJob = scope.launch {
            try {
                val dis = DataInputStream(inputStream ?: return@launch)
                // IMPORTANT: do NOT gate this loop on `isStreaming`.
                // `isStreaming` only flips to true later, when the async camera
                // open finishes and the capture session is configured. If we
                // checked it here the loop would exit immediately and nobody
                // would ever read incoming video frames — which is exactly the
                // "I can only see my own video" bug.
                while (isActive) {
                    val frameSize = dis.readInt()
                    // JPEG frames at 640×480 can be 50–250 KB. Accept up to
                    // VIDEO_BUFFER_SIZE (256 KB); anything larger is a
                    // protocol desync, so log & bail rather than spin.
                    if (frameSize <= 0 || frameSize > Constants.VIDEO_BUFFER_SIZE) {
                        Log.w(TAG, "Invalid frame size: $frameSize (max=${Constants.VIDEO_BUFFER_SIZE})")
                        break
                    }
                    val frameBytes = ByteArray(frameSize)
                    dis.readFully(frameBytes)
                    withContext(Dispatchers.Main) {
                        onRemoteVideoFrame?.invoke(frameBytes)
                    }
                }
            } catch (e: java.io.EOFException) {
                // Peer hung up cleanly — not an error.
                Log.d(TAG, "Video stream ended by peer")
            } catch (e: Exception) {
                if (scope.isActive) Log.e(TAG, "Video playback error: ${e.message}")
            }
        }
    }

    // ======================== Cleanup ========================

    private fun stopStreaming() {
        isStreaming = false
        playJob?.cancel()
        try { captureSession?.stopRepeating() } catch (_: Exception) {}
        try { captureSession?.close() } catch (_: Exception) {}
        captureSession = null
    }

    private fun closeCamera() {
        try { imageReader?.close() } catch (_: Exception) {}
        try { cameraDevice?.close() } catch (_: Exception) {}
        imageReader = null
        cameraDevice = null
    }

    private fun closeSockets() {
        try { outputStream?.close() } catch (_: Exception) {}
        try { inputStream?.close() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        try { serverSocket?.close() } catch (_: Exception) {}
        outputStream = null
        inputStream = null
        socket = null
        serverSocket = null
    }

    private fun endCallInternal() {
        stopStreaming()
        closeCamera()
        closeSockets()
        stopSelf()
    }

    // ======================== Notification ========================

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Video Call",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Video call in progress"
                setSound(null, null)
            }
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("LANVOX")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()
    }
}
