package com.example.wifip2pchat

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Peer-to-peer Wi-Fi Direct binary file transfer engine on [Constants.FILE_PORT].
 *
 * Topology:
 *  - Group Owner hosts a lightweight TCP relay/file server on port 8991.
 *  - Sending as GO stages the file locally and advertises a `FILE:<json>` offer over 8988;
 *    recipients fetch raw bytes via `GET <transferId>`.
 *  - Sending as Client uploads raw bytes to GO via `PUT <transferId> <sizeBytes>` first,
 *    then sends `FILE:<json>` over 8988; GO receives directly and relays `GET` for other peers.
 *
 * All socket headers are read byte-by-byte up to `\n` before reading binary streams so no
 * payload bytes are ever consumed into a text buffer.
 */
object FileTransferManager {

    private const val TAG = "FileTransferManager"
    private const val BUFFER_SIZE = 32 * 1024
    private const val SOCKET_TIMEOUT_MS = 20_000
    private const val CONNECT_TIMEOUT_MS = 5_000

    data class FileOffer(
        val transferId: String,
        val fileName: String,
        val mimeType: String,
        val fileSize: Long,
        val senderHost: String? = null
    ) {
        fun toPayload(): String {
            val json = JSONObject().apply {
                put("id", transferId)
                put("name", fileName)
                put("mime", mimeType)
                put("size", fileSize)
                if (!senderHost.isNullOrBlank()) {
                    put("host", senderHost)
                }
            }
            return Constants.MSG_TYPE_FILE + json.toString()
        }

        companion object {
            fun fromPayload(payload: String): FileOffer? {
                if (!payload.startsWith(Constants.MSG_TYPE_FILE)) return null
                val raw = payload.removePrefix(Constants.MSG_TYPE_FILE).trim()
                return runCatching {
                    val obj = JSONObject(raw)
                    val id = obj.optString("id").trim()
                    val name = obj.optString("name").trim()
                    val mime = obj.optString("mime").trim().ifBlank { "application/octet-stream" }
                    val size = obj.optLong("size", 0L)
                    val host = obj.optString("host").trim().takeIf { it.isNotBlank() }
                    if (id.isEmpty() || name.isEmpty() || size <= 0L) return null
                    FileOffer(id, sanitizeFileName(name), mime, size, host)
                }.getOrNull()
            }
        }
    }

    data class StagedFile(
        val offer: FileOffer,
        val localFile: File
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stagedFiles = ConcurrentHashMap<String, File>()

    @Volatile
    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null

    @Volatile
    private var goHostAddress: String? = null

    @Volatile
    private var isGroupOwnerRole: Boolean = false

    /**
     * Starts the local peer file server on port [Constants.FILE_PORT] for EVERY device
     * (P2P Group Owner, P2P Client, and LAN/NSD mesh nodes) so files stream directly
     * peer-to-peer without routing every byte through one central host.
     */
    fun ensureServerRunning(context: Context) {
        startServer(context.applicationContext)
    }

    fun configureSession(context: Context, isGroupOwner: Boolean, hostAddress: String?) {
        isGroupOwnerRole = isGroupOwner
        if (!hostAddress.isNullOrBlank()) {
            goHostAddress = hostAddress
        }
        startServer(context.applicationContext)
    }

    fun stopSession() {
        isGroupOwnerRole = false
        goHostAddress = null
        stopServer()
    }

    private fun startServer(appContext: Context) {
        if (serverJob?.isActive == true && serverSocket != null && serverSocket?.isClosed == false) {
            return
        }
        stopServer()
        serverJob = scope.launch {
            var boundServer: ServerSocket? = null
            repeat(8) {
                try {
                    boundServer = ServerSocket().apply {
                        reuseAddress = true
                        bind(InetSocketAddress(Constants.FILE_PORT))
                    }
                    return@repeat
                } catch (e: Exception) {
                    Log.w(TAG, "File server bind retry: ${e.message}")
                    delay(800)
                }
            }
            val server = boundServer ?: run {
                Log.e(TAG, "Unable to bind file server port ${Constants.FILE_PORT}")
                return@launch
            }
            serverSocket = server
            Log.d(TAG, "Peer file server listening on port ${Constants.FILE_PORT}")

            while (isActive && !server.isClosed) {
                val client = try {
                    server.accept()
                } catch (_: Exception) {
                    break
                }
                launch {
                    handleServerClient(appContext, client)
                }
            }
        }
    }

    private fun stopServer() {
        serverJob?.cancel()
        serverJob = null
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
    }

    private fun handleServerClient(appContext: Context, socket: Socket) {
        socket.use { s ->
            try {
                s.soTimeout = SOCKET_TIMEOUT_MS
                s.tcpNoDelay = true
                val input = s.getInputStream()
                val output = s.getOutputStream()
                val header = readHeaderLine(input) ?: return
                val remoteIp = s.inetAddress?.hostAddress
                val parts = header.trim().split(' ')
                when (parts.firstOrNull()) {
                    "GET" -> {
                        val transferId = parts.getOrNull(1).orEmpty()
                        val file = stagedFiles[transferId]
                        if (file != null && file.exists() && file.isFile) {
                            val len = file.length()
                            writeHeaderLine(output, "OK $len")
                            FileTransferTracker.updateOutgoingPeerStream(transferId, remoteIp, 0L, len)
                            FileInputStream(file).use { fis ->
                                copyFixedOrAll(fis, output, len) { sent ->
                                    FileTransferTracker.updateOutgoingPeerStream(transferId, remoteIp, sent, len)
                                }
                            }
                            output.flush()
                        } else {
                            writeHeaderLine(output, "ERR not_found")
                        }
                    }
                    "RELAY_GET" -> {
                        // On-demand proxy fallback if an OEM AP isolates two Wi-Fi Direct clients.
                        val targetIp = parts.getOrNull(1).orEmpty()
                        val transferId = parts.getOrNull(2).orEmpty()
                        if (targetIp.isBlank() || transferId.isBlank()) {
                            writeHeaderLine(output, "ERR invalid_relay")
                            return
                        }
                        try {
                            Socket().use { upstream ->
                                upstream.soTimeout = SOCKET_TIMEOUT_MS
                                upstream.tcpNoDelay = true
                                upstream.connect(InetSocketAddress(targetIp, Constants.FILE_PORT), CONNECT_TIMEOUT_MS)
                                val upOut = upstream.getOutputStream()
                                val upIn = upstream.getInputStream()
                                writeHeaderLine(upOut, "GET $transferId")
                                val upHeader = readHeaderLine(upIn)?.trim().orEmpty()
                                if (!upHeader.startsWith("OK ")) {
                                    writeHeaderLine(output, upHeader.ifBlank { "ERR upstream_failed" })
                                    return
                                }
                                val len = upHeader.removePrefix("OK ").trim().toLongOrNull() ?: -1L
                                if (len <= 0L || len > Constants.MAX_FILE_TRANSFER_BYTES) {
                                    writeHeaderLine(output, "ERR invalid_size")
                                    return
                                }
                                writeHeaderLine(output, "OK $len")
                                copyExactBytes(upIn, output, len)
                                output.flush()
                            }
                        } catch (e: Exception) {
                            writeHeaderLine(output, "ERR relay_error")
                        }
                    }
                    "PUT" -> {
                        val transferId = parts.getOrNull(1).orEmpty()
                        val expectedSize = parts.getOrNull(2)?.toLongOrNull() ?: -1L
                        if (transferId.isBlank() || expectedSize <= 0L || expectedSize > Constants.MAX_FILE_TRANSFER_BYTES) {
                            writeHeaderLine(output, "ERR invalid_put")
                            return
                        }
                        val relayDir = File(appContext.cacheDir, "file_relay").apply { mkdirs() }
                        val tempFile = File(relayDir, "${sanitizeFileName(transferId)}.tmp")
                        val destFile = File(relayDir, "${sanitizeFileName(transferId)}.bin")
                        val received = FileOutputStream(tempFile).use { fos ->
                            copyExactBytes(input, fos, expectedSize)
                        }
                        if (received == expectedSize) {
                            if (destFile.exists()) destFile.delete()
                            tempFile.renameTo(destFile)
                            stagedFiles[transferId] = destFile
                            writeHeaderLine(output, "OK")
                        } else {
                            tempFile.delete()
                            writeHeaderLine(output, "ERR truncated")
                        }
                    }
                    else -> writeHeaderLine(output, "ERR unknown_cmd")
                }
            } catch (e: Exception) {
                Log.w(TAG, "File server client error: ${e.message}")
            }
        }
    }

    /**
     * Copies a user-picked SAF document URI into app-private storage so the sender
     * keeps a durable copy for the chat bubble and can stream it cleanly without holding
     * SAF permissions across reboots.
     */
    suspend fun stageFromUri(context: Context, uri: Uri): Result<StagedFile> = withContext(Dispatchers.IO) {
        runCatching {
            val resolver = context.contentResolver
            var displayName = "attachment_${System.currentTimeMillis()}"
            var reportedSize = -1L

            resolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIdx >= 0) {
                        cursor.getString(nameIdx)?.takeIf { it.isNotBlank() }?.let { displayName = it }
                    }
                    val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) {
                        reportedSize = cursor.getLong(sizeIdx)
                    }
                }
            }

            if (reportedSize > Constants.MAX_FILE_TRANSFER_BYTES) {
                error("File exceeds 250 MB limit")
            }

            val safeName = sanitizeFileName(displayName)
            val mimeType = resolver.getType(uri)?.takeIf { it.isNotBlank() }
                ?: guessMimeType(safeName)
            val transferId = UUID.randomUUID().toString()
            val dir = attachmentsDir(context)
            val destFile = File(dir, "${transferId}_$safeName")

            var totalBytes = 0L
            resolver.openInputStream(uri)?.use { input ->
                FileOutputStream(destFile).use { output ->
                    val buf = ByteArray(BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buf)
                        if (read <= 0) break
                        totalBytes += read
                        if (totalBytes > Constants.MAX_FILE_TRANSFER_BYTES) {
                            destFile.delete()
                            error("File exceeds 250 MB limit")
                        }
                        output.write(buf, 0, read)
                    }
                    output.flush()
                }
            } ?: error("Could not read selected file")

            if (totalBytes <= 0L) {
                destFile.delete()
                error("Selected file is empty")
            }

            val senderHost = ChatServerService.localIpForPeer(null) ?: DeviceIdentity.resolveLocalIp()
            stagedFiles[transferId] = destFile
            StagedFile(
                offer = FileOffer(
                    transferId = transferId,
                    fileName = safeName,
                    mimeType = mimeType,
                    fileSize = totalBytes,
                    senderHost = senderHost
                ),
                localFile = destFile
            )
        }
    }

    /**
     * Stages a locally generated file (such as an AAC voice note) into app-private
     * attachments storage and returns a ready [StagedFile] for P2P transfer.
     */
    suspend fun stageLocalFile(
        context: Context,
        sourceFile: File,
        displayName: String,
        mimeType: String
    ): Result<StagedFile> = withContext(Dispatchers.IO) {
        runCatching {
            if (!sourceFile.exists() || sourceFile.length() <= 0L) {
                error("Recorded file is empty")
            }
            val safeName = sanitizeFileName(displayName)
            val transferId = UUID.randomUUID().toString()
            val dir = attachmentsDir(context)
            val destFile = File(dir, "${transferId}_$safeName")
            FileInputStream(sourceFile).use { input ->
                FileOutputStream(destFile).use { output ->
                    copyFixedOrAll(input, output, sourceFile.length())
                    output.flush()
                }
            }
            val totalBytes = destFile.length()
            if (totalBytes <= 0L) {
                destFile.delete()
                error("Recorded file is empty")
            }
            val senderHost = ChatServerService.localIpForPeer(null) ?: DeviceIdentity.resolveLocalIp()
            stagedFiles[transferId] = destFile
            StagedFile(
                offer = FileOffer(
                    transferId = transferId,
                    fileName = safeName,
                    mimeType = mimeType,
                    fileSize = totalBytes,
                    senderHost = senderHost
                ),
                localFile = destFile
            )
        }
    }

    /**
     * Stages the file locally for direct serving on port [Constants.FILE_PORT] so
     * clients do not have to upload files through a central host first.
     */
    suspend fun pushStagedFileToGroup(staged: StagedFile): Boolean = withContext(Dispatchers.IO) {
        stagedFiles[staged.offer.transferId] = staged.localFile
        true
    }

    /**
     * Fetches an offered file directly from the sender's peer IP on port [Constants.FILE_PORT],
     * falling back to GO relay (`RELAY_GET` or cached `GET`) only if direct access fails.
     */
    suspend fun downloadOffer(
        context: Context,
        offer: FileOffer,
        senderId: String? = null,
        senderName: String? = null
    ): File? = withContext(Dispatchers.IO) {
        val dir = attachmentsDir(context)
        val destFile = File(dir, "${sanitizeFileName(offer.transferId)}_${sanitizeFileName(offer.fileName)}")
        if (destFile.exists() && destFile.length() == offer.fileSize) {
            FileTransferTracker.markIncomingCompleted(offer.transferId, offer.fileSize, senderId, senderName)
            return@withContext destFile
        }

        FileTransferTracker.updateIncomingDownload(offer.transferId, 0L, offer.fileSize, senderId, senderName)

        val localStaged = stagedFiles[offer.transferId]
        if (localStaged != null && localStaged.exists() && localStaged.length() > 0L) {
            val tmp = File(dir, "${destFile.name}.tmp")
            return@withContext try {
                val totalLen = localStaged.length()
                FileInputStream(localStaged).use { fis ->
                    FileOutputStream(tmp).use { fos ->
                        copyFixedOrAll(fis, fos, totalLen) { done ->
                            FileTransferTracker.updateIncomingDownload(offer.transferId, done, totalLen, senderId, senderName)
                        }
                    }
                }
                if (destFile.exists()) destFile.delete()
                tmp.renameTo(destFile)
                stagedFiles[offer.transferId] = destFile
                FileTransferTracker.markIncomingCompleted(offer.transferId, destFile.length(), senderId, senderName)
                destFile
            } catch (e: Exception) {
                tmp.delete()
                Log.w(TAG, "Local copy failed: ${e.message}")
                FileTransferTracker.markIncomingFailed(offer.transferId, senderId, senderName)
                null
            }
        }

        val directHost = offer.senderHost?.takeIf { it.isNotBlank() }
            ?: PeerRegistry.ipFor(senderId)?.takeIf { it.isNotBlank() }
        val fallbackHost = goHostAddress?.takeIf { it.isNotBlank() }
            ?: LastSession.groupOwnerAddress(context)?.takeIf { it.isNotBlank() }

        // 1. Direct peer-to-peer GET from senderHost (no central host!)
        if (!directHost.isNullOrBlank()) {
            fetchViaTcp(
                connectHost = directHost,
                command = "GET ${offer.transferId}",
                destFile = destFile,
                offer = offer,
                dir = dir,
                attempts = 3,
                senderId = senderId,
                senderName = senderName
            )?.let {
                FileTransferTracker.markIncomingCompleted(offer.transferId, it.length(), senderId, senderName)
                return@withContext it
            }
        }

        // 2. Fallback: ask relay host to stream from senderHost or serve cached transferId
        if (!fallbackHost.isNullOrBlank() && fallbackHost != directHost) {
            if (!directHost.isNullOrBlank()) {
                fetchViaTcp(
                    connectHost = fallbackHost,
                    command = "RELAY_GET $directHost ${offer.transferId}",
                    destFile = destFile,
                    offer = offer,
                    dir = dir,
                    attempts = 2,
                    senderId = senderId,
                    senderName = senderName
                )?.let {
                    FileTransferTracker.markIncomingCompleted(offer.transferId, it.length(), senderId, senderName)
                    return@withContext it
                }
            }
            fetchViaTcp(
                connectHost = fallbackHost,
                command = "GET ${offer.transferId}",
                destFile = destFile,
                offer = offer,
                dir = dir,
                attempts = 2,
                senderId = senderId,
                senderName = senderName
            )?.let {
                FileTransferTracker.markIncomingCompleted(offer.transferId, it.length(), senderId, senderName)
                return@withContext it
            }
        }
        FileTransferTracker.markIncomingFailed(offer.transferId, senderId, senderName)
        null
    }

    private suspend fun fetchViaTcp(
        connectHost: String,
        command: String,
        destFile: File,
        offer: FileOffer,
        dir: File,
        attempts: Int,
        senderId: String? = null,
        senderName: String? = null
    ): File? {
        repeat(attempts) { attempt ->
            val tmp = File(dir, "${destFile.name}.tmp")
            try {
                Socket().use { socket ->
                    socket.soTimeout = SOCKET_TIMEOUT_MS
                    socket.tcpNoDelay = true
                    socket.connect(InetSocketAddress(connectHost, Constants.FILE_PORT), CONNECT_TIMEOUT_MS)
                    val output = socket.getOutputStream()
                    val input = socket.getInputStream()
                    writeHeaderLine(output, command)
                    val header = readHeaderLine(input)?.trim().orEmpty()
                    if (!header.startsWith("OK ")) {
                        Log.w(TAG, "$command on $connectHost attempt ${attempt + 1} header=$header")
                        return@use
                    }
                    val expectedLen = header.removePrefix("OK ").trim().toLongOrNull() ?: offer.fileSize
                    if (expectedLen <= 0L || expectedLen > Constants.MAX_FILE_TRANSFER_BYTES) {
                        return null
                    }
                    FileTransferTracker.updateIncomingDownload(offer.transferId, 0L, expectedLen, senderId, senderName)
                    val bytesRead = FileOutputStream(tmp).use { fos ->
                        copyExactBytes(input, fos, expectedLen) { bytesDone ->
                            FileTransferTracker.updateIncomingDownload(offer.transferId, bytesDone, expectedLen, senderId, senderName)
                        }
                    }
                    if (bytesRead == expectedLen) {
                        if (destFile.exists()) destFile.delete()
                        tmp.renameTo(destFile)
                        stagedFiles[offer.transferId] = destFile
                        return destFile
                    }
                    tmp.delete()
                }
            } catch (e: Exception) {
                tmp.delete()
                Log.w(TAG, "$command on $connectHost attempt ${attempt + 1} failed: ${e.message}")
            }
            delay(450)
        }
        return null
    }

    /**
     * Opens a saved attachment via [FileProvider] with read-URI grant, falling back to a share sheet
     * if no installed viewer handles the MIME type.
     */
    fun openAttachment(context: Context, message: ChatMessage): String? {
        val path = message.attachmentPath ?: return "File is still transferring or unavailable"
        val file = File(path)
        if (!file.exists()) return "File no longer exists on this device"

        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val mime = message.attachmentMime?.takeIf { it.isNotBlank() } ?: guessMimeType(file.name)
        val viewIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (context !is android.app.Activity) {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
        return try {
            context.startActivity(viewIntent)
            null
        } catch (_: ActivityNotFoundException) {
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            try {
                context.startActivity(Intent.createChooser(shareIntent, message.attachmentName ?: "Open file"))
                null
            } catch (_: Exception) {
                "No app installed to open ${message.attachmentName ?: "this file"}"
            }
        } catch (e: Exception) {
            "Could not open file: ${e.message}"
        }
    }

    fun formatSummaryText(fileName: String, sizeBytes: Long, mimeType: String? = null): String {
        val temp = ChatMessage(
            text = "",
            isSentByMe = true,
            attachmentName = fileName,
            attachmentMime = mimeType,
            attachmentSize = sizeBytes
        )
        return if (mimeType?.startsWith("audio/") == true) {
            "🎤 Voice note (${temp.formattedFileSize()})"
        } else {
            "📎 $fileName (${temp.formattedFileSize()})"
        }
    }

    private fun attachmentsDir(context: Context): File =
        File(context.filesDir, "attachments").apply { mkdirs() }

    private fun sanitizeFileName(raw: String): String {
        val base = File(raw).name.ifBlank { "file" }
        return base.replace(Regex("[\\\\/:*?\"<>|\\r\\n\\t\\x00]"), "_").take(120)
    }

    private fun guessMimeType(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return if (ext.isNotEmpty()) {
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        } else {
            "application/octet-stream"
        }
    }

    private fun writeHeaderLine(output: OutputStream, line: String) {
        output.write((line + "\n").toByteArray(Charsets.UTF_8))
        output.flush()
    }

    private fun readHeaderLine(input: InputStream): String? {
        val bytes = ByteArrayOutputStream(128)
        while (bytes.size() < 4096) {
            val b = input.read()
            if (b == -1) {
                return if (bytes.size() == 0) null else bytes.toString(Charsets.UTF_8.name())
            }
            if (b == '\n'.code) {
                return bytes.toString(Charsets.UTF_8.name()).trimEnd('\r')
            }
            bytes.write(b)
        }
        return null
    }

    private fun copyFixedOrAll(
        input: InputStream,
        output: OutputStream,
        expectedMax: Long,
        onProgress: ((Long) -> Unit)? = null
    ): Long {
        val buf = ByteArray(BUFFER_SIZE)
        var total = 0L
        var lastEmitMs = 0L
        while (total < expectedMax) {
            val toRead = minOf(buf.size.toLong(), expectedMax - total).toInt()
            val read = input.read(buf, 0, toRead)
            if (read <= 0) break
            output.write(buf, 0, read)
            total += read
            if (onProgress != null) {
                val now = System.currentTimeMillis()
                if (total >= expectedMax || now - lastEmitMs >= 90L) {
                    lastEmitMs = now
                    onProgress(total)
                }
            }
        }
        return total
    }

    private fun copyExactBytes(
        input: InputStream,
        output: OutputStream,
        expectedSize: Long,
        onProgress: ((Long) -> Unit)? = null
    ): Long {
        val buf = ByteArray(BUFFER_SIZE)
        var total = 0L
        var lastEmitMs = 0L
        while (total < expectedSize) {
            val toRead = minOf(buf.size.toLong(), expectedSize - total).toInt()
            val read = input.read(buf, 0, toRead)
            if (read <= 0) break
            output.write(buf, 0, read)
            total += read
            if (onProgress != null) {
                val now = System.currentTimeMillis()
                if (total >= expectedSize || now - lastEmitMs >= 90L) {
                    lastEmitMs = now
                    onProgress(total)
                }
            }
        }
        output.flush()
        return total
    }
}
