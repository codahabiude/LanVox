package com.example.wifip2pchat

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Push-to-Talk AAC voice note recorder + single-active-track [MediaPlayer] playback manager.
 */
object VoiceNoteManager {

    private const val TAG = "VoiceNoteManager"
    private const val MAX_DURATION_MS = 180_000

    data class PlaybackState(
        val path: String? = null,
        val isPlaying: Boolean = false,
        val positionMs: Int = 0,
        val durationMs: Int = 0
    )

    private val _playbackState = MutableStateFlow(PlaybackState())
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    private val handler = Handler(Looper.getMainLooper())
    private var mediaPlayer: MediaPlayer? = null
    private var activePath: String? = null
    private val durationCache = ConcurrentHashMap<String, Int>()

    private val progressRunnable = object : Runnable {
        override fun run() {
            val mp = mediaPlayer ?: return
            val path = activePath ?: return
            val playing = runCatching { mp.isPlaying }.getOrDefault(false)
            if (playing) {
                val pos = runCatching { mp.currentPosition }.getOrDefault(0)
                val dur = runCatching { mp.duration }.getOrDefault(_playbackState.value.durationMs)
                _playbackState.value = PlaybackState(
                    path = path,
                    isPlaying = true,
                    positionMs = pos,
                    durationMs = dur.coerceAtLeast(1)
                )
                handler.postDelayed(this, 80L)
            }
        }
    }

    fun togglePlayback(context: Context, path: String, transferId: String? = null) {
        val file = File(path)
        if (path.isBlank() || !file.exists()) return
        val current = _playbackState.value
        val mp = mediaPlayer
        if (activePath == path && mp != null) {
            runCatching {
                if (mp.isPlaying) {
                    mp.pause()
                    handler.removeCallbacks(progressRunnable)
                    _playbackState.value = current.copy(
                        isPlaying = false,
                        positionMs = mp.currentPosition
                    )
                } else {
                    mp.start()
                    _playbackState.value = current.copy(isPlaying = true)
                    handler.removeCallbacks(progressRunnable)
                    handler.post(progressRunnable)
                }
            }.onFailure {
                stopPlayback()
            }
            return
        }

        stopPlayback()
        val created = MediaPlayer()
        try {
            created.setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build()
            )
            if (CryptoManager.isEncryptedAttachmentFile(file)) {
                val plainBytes = CryptoManager.decryptAttachmentToBytes(context, transferId, file)
                    ?: error("Failed to decrypt voice note")
                created.setDataSource(byteArrayDataSource(plainBytes))
            } else {
                created.setDataSource(path)
            }
            created.prepare()
            val totalDur = created.duration.coerceAtLeast(1)
            durationCache[path] = totalDur
            created.setOnCompletionListener {
                handler.removeCallbacks(progressRunnable)
                _playbackState.value = PlaybackState(
                    path = path,
                    isPlaying = false,
                    positionMs = 0,
                    durationMs = totalDur
                )
            }
            created.start()
            mediaPlayer = created
            activePath = path
            _playbackState.value = PlaybackState(
                path = path,
                isPlaying = true,
                positionMs = 0,
                durationMs = totalDur
            )
            handler.post(progressRunnable)
        } catch (e: Exception) {
            Log.e(TAG, "Playback failed for $path: ${e.message}")
            runCatching { created.release() }
            stopPlayback()
        }
    }

    fun seekTo(path: String, progressFraction: Float) {
        val mp = mediaPlayer ?: return
        if (activePath != path) return
        val dur = runCatching { mp.duration }.getOrDefault(0)
        if (dur <= 0) return
        val target = (dur * progressFraction.coerceIn(0f, 1f)).toInt()
        runCatching {
            mp.seekTo(target)
            _playbackState.value = _playbackState.value.copy(positionMs = target)
        }
    }

    fun stopPlayback() {
        handler.removeCallbacks(progressRunnable)
        val mp = mediaPlayer
        mediaPlayer = null
        activePath = null
        if (mp != null) {
            runCatching {
                if (mp.isPlaying) mp.stop()
            }
            runCatching { mp.release() }
        }
        _playbackState.value = PlaybackState()
    }

    fun readDurationMs(context: Context, path: String?, transferId: String? = null): Int {
        if (path.isNullOrBlank()) return 0
        durationCache[path]?.let { return it }
        val file = File(path)
        if (!file.exists()) return 0
        val mmr = MediaMetadataRetriever()
        return try {
            if (CryptoManager.isEncryptedAttachmentFile(file)) {
                val plainBytes = CryptoManager.decryptAttachmentToBytes(context, transferId, file)
                    ?: return 0
                mmr.setDataSource(byteArrayDataSource(plainBytes))
            } else {
                mmr.setDataSource(path)
            }
            val dur = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toIntOrNull() ?: 0
            if (dur > 0) durationCache[path] = dur
            dur
        } catch (_: Exception) {
            0
        } finally {
            runCatching { mmr.release() }
        }
    }

    private fun byteArrayDataSource(bytes: ByteArray): MediaDataSource = object : MediaDataSource() {
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (position < 0 || position >= bytes.size) return -1
            val available = minOf(size.toLong(), bytes.size.toLong() - position).toInt()
            System.arraycopy(bytes, position.toInt(), buffer, offset, available)
            return available
        }

        override fun getSize(): Long = bytes.size.toLong()
        override fun close() {}
    }

    fun formatDurationMs(ms: Int): String {
        val totalSecs = (ms.coerceAtLeast(0) / 1000)
        val mins = totalSecs / 60
        val secs = totalSecs % 60
        return String.format(Locale.US, "%02d:%02d", mins, secs)
    }

    /**
     * Lightweight lifecycle-aware voice note recorder producing `.m4a` AAC clips.
     */
    class Recorder(private val context: Context) {
        private var recorder: MediaRecorder? = null
        private var outputFile: File? = null
        private var startedElapsedMs = 0L

        @Volatile
        var isRecording: Boolean = false
            private set

        fun start(): File? {
            cancel()
            val dir = File(context.cacheDir, "voice_notes").apply { mkdirs() }
            val file = File(dir, "voice_${System.currentTimeMillis()}.m4a")
            val mr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            return try {
                mr.apply {
                    setAudioSource(MediaRecorder.AudioSource.MIC)
                    setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    setAudioChannels(1)
                    setAudioSamplingRate(22_050)
                    setAudioEncodingBitRate(64_000)
                    setMaxDuration(MAX_DURATION_MS)
                    setOutputFile(file.absolutePath)
                    prepare()
                    start()
                }
                recorder = mr
                outputFile = file
                startedElapsedMs = SystemClock.elapsedRealtime()
                isRecording = true
                file
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start voice note recorder: ${e.message}")
                runCatching { mr.release() }
                file.delete()
                recorder = null
                outputFile = null
                isRecording = false
                null
            }
        }

        fun elapsedMs(): Long =
            if (isRecording) (SystemClock.elapsedRealtime() - startedElapsedMs).coerceAtLeast(0L) else 0L

        /** Normalized amplitude 0..100 for the live recording level bar. */
        fun currentAmplitudePercent(): Int {
            val mr = recorder ?: return 0
            if (!isRecording) return 0
            val raw = runCatching { mr.maxAmplitude }.getOrDefault(0)
            // MediaRecorder.maxAmplitude ranges 0..32767; boost quiet speech gently
            return ((raw / 18000f) * 100f).toInt().coerceIn(8, 100)
        }

        fun stopAndReturnFile(): Pair<File, Long>? {
            val mr = recorder ?: return null
            val file = outputFile ?: return null
            val durationMs = elapsedMs()
            isRecording = false
            recorder = null
            outputFile = null
            return try {
                mr.stop()
                mr.release()
                if (file.exists() && file.length() > 0L && durationMs >= 450L) {
                    file to durationMs
                } else {
                    file.delete()
                    null
                }
            } catch (e: Exception) {
                Log.w(TAG, "Stop voice note failed: ${e.message}")
                runCatching { mr.release() }
                file.delete()
                null
            }
        }

        fun cancel() {
            isRecording = false
            val mr = recorder
            val file = outputFile
            recorder = null
            outputFile = null
            if (mr != null) {
                runCatching { mr.stop() }
                runCatching { mr.release() }
            }
            file?.delete()
        }
    }
}
