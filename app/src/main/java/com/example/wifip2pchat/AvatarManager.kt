package com.example.wifip2pchat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.Base64
import android.util.LruCache
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale

/**
 * Manages local and peer profile avatars:
 *  - Imports user photos from the zero-permission Android Photo Picker URI
 *  - Center-crops & compresses to a compact 128x128 JPEG (<= ~15 KB) safe for single-line TCP envelopes
 *  - Persists self & peer avatars under `filesDir/avatars/` with an in-memory LruCache
 *  - Emits [avatarVersion] updates so every screen refreshes reactively
 */
object AvatarManager {

    private const val DIR_AVATARS = "avatars"
    private const val SELF_KEY = "__self__"
    private const val AVATAR_DIM = 128
    private const val JPEG_QUALITY = 72
    private const val MAX_AVATAR_BYTES = 24 * 1024

    private val cache = object : LruCache<String, Bitmap>(32) {}
    private val _avatarVersion = MutableStateFlow(0)
    val avatarVersion: StateFlow<Int> = _avatarVersion.asStateFlow()

    private fun avatarDir(context: Context): File =
        File(context.applicationContext.filesDir, DIR_AVATARS).apply {
            if (!exists()) mkdirs()
        }

    private fun sanitizeKey(id: String): String {
        if (id == SELF_KEY) return "self"
        return id.replace(Regex("[^a-zA-Z0-9._-]"), "_")
    }

    private fun fileForKey(context: Context, key: String): File =
        File(avatarDir(context), "${sanitizeKey(key)}.jpg")

    fun saveSelfAvatarFromUri(context: Context, uri: Uri): Boolean {
        val appCtx = context.applicationContext
        val bytes = appCtx.contentResolver.openInputStream(uri)?.use { stream ->
            val raw = BitmapFactory.decodeStream(stream) ?: return false
            encodeCompactSquareJpeg(raw)
        } ?: return false

        val outFile = fileForKey(appCtx, SELF_KEY)
        outFile.writeBytes(CryptoManager.encryptLocalBytes(appCtx, bytes))
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { bmp ->
            cache.put(SELF_KEY, bmp)
        }
        bumpVersion()
        return true
    }

    fun clearSelfAvatar(context: Context) {
        val appCtx = context.applicationContext
        cache.remove(SELF_KEY)
        fileForKey(appCtx, SELF_KEY).delete()
        bumpVersion()
    }

    fun getSelfAvatarBase64(context: Context): String? {
        val appCtx = context.applicationContext
        val file = fileForKey(appCtx, SELF_KEY)
        if (!file.exists()) return null
        val stored = runCatching { file.readBytes() }.getOrNull() ?: return null
        val bytes = CryptoManager.decryptLocalBytes(appCtx, stored) ?: return null
        if (bytes.isEmpty() || bytes.size > MAX_AVATAR_BYTES) return null
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    fun buildSelfAvatarWirePayload(context: Context): String {
        val b64 = getSelfAvatarBase64(context).orEmpty()
        return Constants.MSG_TYPE_AVATAR + b64
    }

    fun applyRemoteAvatarPayload(context: Context, peerId: String, payload: String): Boolean {
        if (peerId.isBlank() || peerId == PeerRegistry.selfId) return false
        val appCtx = context.applicationContext
        val b64 = payload.removePrefix(Constants.MSG_TYPE_AVATAR).trim()
        if (b64.isEmpty()) {
            cache.remove(peerId)
            fileForKey(appCtx, peerId).delete()
            bumpVersion()
            return true
        }
        val bytes = runCatching { Base64.decode(b64, Base64.DEFAULT) }.getOrNull() ?: return false
        if (bytes.isEmpty() || bytes.size > MAX_AVATAR_BYTES) return false
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return false
        fileForKey(appCtx, peerId).writeBytes(CryptoManager.encryptLocalBytes(appCtx, bytes))
        cache.put(peerId, bmp)
        bumpVersion()
        return true
    }

    fun getAvatarBitmap(context: Context, peerId: String?): Bitmap? {
        if (peerId.isNullOrBlank() || peerId == Constants.GROUP_PEER_ID) return null
        val key = if (peerId == PeerRegistry.selfId) SELF_KEY else peerId
        cache.get(key)?.let { return it }
        val appCtx = context.applicationContext
        val file = fileForKey(appCtx, key)
        if (!file.exists()) return null
        val stored = runCatching { file.readBytes() }.getOrNull() ?: return null
        val plain = CryptoManager.decryptLocalBytes(appCtx, stored) ?: return null
        val bmp = runCatching { BitmapFactory.decodeByteArray(plain, 0, plain.size) }.getOrNull() ?: return null
        cache.put(key, bmp)
        return bmp
    }

    fun getSelfAvatarBitmap(context: Context): Bitmap? = getAvatarBitmap(context, SELF_KEY)

    fun bindAvatar(
        context: Context,
        imageView: ImageView?,
        initialView: TextView?,
        peerId: String?,
        displayName: String,
        fallbackSymbol: String? = null
    ) {
        val initial = fallbackSymbol ?: displayName.trim().take(1).uppercase(Locale.getDefault()).ifBlank { "L" }
        initialView?.text = initial
        val bmp = getAvatarBitmap(context, peerId)
        if (bmp != null && imageView != null) {
            val rounded = RoundedBitmapDrawableFactory.create(context.resources, bmp).apply {
                isCircular = true
                setAntiAlias(true)
            }
            imageView.setImageDrawable(rounded)
            imageView.visibility = View.VISIBLE
            initialView?.visibility = View.INVISIBLE
        } else {
            imageView?.setImageDrawable(null)
            imageView?.visibility = View.GONE
            initialView?.visibility = View.VISIBLE
        }
    }

    fun applyPresenceDot(view: View?, status: String?, isOnline: Boolean = true) {
        if (view == null) return
        val colorRes = when {
            !isOnline -> R.color.text_tertiary
            status == DeviceIdentity.STATUS_BUSY -> R.color.status_offline
            status == DeviceIdentity.STATUS_AWAY -> R.color.status_discovering
            else -> R.color.status_online
        }
        val color = androidx.core.content.ContextCompat.getColor(view.context, colorRes)
        (view.background?.mutate() as? android.graphics.drawable.GradientDrawable)?.setColor(color)
    }

    private fun encodeCompactSquareJpeg(source: Bitmap): ByteArray {
        val side = minOf(source.width, source.height).coerceAtLeast(1)
        val xOff = (source.width - side) / 2
        val yOff = (source.height - side) / 2
        val scale = AVATAR_DIM.toFloat() / side.toFloat()
        val matrix = Matrix().apply { postScale(scale, scale) }
        val cropped = Bitmap.createBitmap(source, xOff, yOff, side, side, matrix, true)

        var quality = JPEG_QUALITY
        var outBytes: ByteArray
        do {
            val baos = ByteArrayOutputStream()
            cropped.compress(Bitmap.CompressFormat.JPEG, quality, baos)
            outBytes = baos.toByteArray()
            quality -= 12
        } while (outBytes.size > MAX_AVATAR_BYTES && quality >= 30)

        if (cropped !== source) {
            cropped.recycle()
        }
        return outBytes
    }

    private fun bumpVersion() {
        _avatarVersion.value = _avatarVersion.value + 1
    }
}
