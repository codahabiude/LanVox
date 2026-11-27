package com.example.wifip2pchat

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Central cryptographic engine for LanVox 100% Encryption:
 *  - Hardware-backed AndroidKeyStore AES-256-GCM Master Key wrapping local vault keys & identity keypair
 *  - Persistent ECDH P-256 (`secp256r1`) identity keypair + HKDF-SHA256 (RFC 5869) domain-separated session keys
 *  - End-to-End Encrypted 1:1 (`E2E1:`) and Group Broadcast (`E2EM:`) envelopes with AAD binding
 *  - Ephemeral ECDH P-256 + AES-256-GCM framing for Video (port 8990) & Audio Signaling (port 8989)
 *  - Streaming chunked 64 KB AES-256-GCM (`LVXE1`) for attachments & voice notes in transit and at rest
 *  - Hardware-backed AES-256-GCM (`ENC1:` / `LVXB1`) for Room DB rows, avatars, and sensitive prefs
 *  - 60-digit Safety Numbers with TOFU key-change detection & manual verification state
 */
object CryptoManager {

    private const val TAG = "CryptoManager"
    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val MASTER_KEY_ALIAS = "lanvox_master_keystore_v1"
    private const val PREFS_CRYPTO = "lanvox_crypto_prefs"

    private const val KEY_WRAPPED_STORAGE = "wrapped_local_storage_key_v1"
    private const val KEY_ENC_ID_PRIV = "enc_identity_priv_p256_v1"
    private const val KEY_ID_PUB = "identity_pub_p256_v1"
    private const val PREFIX_PEER_PUB = "peer_pub_"
    private const val PREFIX_PEER_VERIFIED = "peer_verified_"
    private const val PREFIX_PEER_KEY_CHANGED = "peer_key_changed_"
    private const val PREFIX_ATT_KEY = "att_key_"

    const val PREFIX_LOCAL_ENC = "ENC1:"
    private val MAGIC_BYTES_V1 = byteArrayOf('L'.code.toByte(), 'V'.code.toByte(), 'X'.code.toByte(), 'B'.code.toByte(), '1'.code.toByte())
    private val MAGIC_FILE_V1 = byteArrayOf('L'.code.toByte(), 'V'.code.toByte(), 'X'.code.toByte(), 'E'.code.toByte(), '1'.code.toByte())
    private const val FILE_CHUNK_SIZE = 64 * 1024
    private const val GCM_IV_LEN = 12
    private const val GCM_TAG_BITS = 128
    private const val GCM_TAG_BYTES = 16

    private const val INFO_CHAT = "lanvox-e2ee-v1-chat"
    private const val INFO_VIDEO = "lanvox-e2ee-v1-video"
    private const val INFO_AUDIO_SIG = "lanvox-e2ee-v1-audio-sig"

    enum class KeyRegistrationResult {
        ADDED,
        UNCHANGED,
        CHANGED,
        INVALID
    }

    private val secureRandom = SecureRandom()
    private val initLock = Any()

    @Volatile private var initialized = false
    @Volatile private var localStorageKey: SecretKey? = null
    @Volatile private var identityKeyPair: KeyPair? = null
    @Volatile private var identityPubB64: String? = null

    private val peerPublicKeys = ConcurrentHashMap<String, PublicKey>()
    private val peerPubB64Map = ConcurrentHashMap<String, String>()
    private val peerChatKeys = ConcurrentHashMap<String, SecretKey>()
    private val attachmentKeys = ConcurrentHashMap<String, SecretKey>()

    // ======================== Initialization & KeyStore Vault ========================

    fun ensureInitialized(context: Context) {
        if (initialized && localStorageKey != null && identityKeyPair != null) return
        synchronized(initLock) {
            if (initialized && localStorageKey != null && identityKeyPair != null) return
            val appCtx = context.applicationContext
            val prefs = appCtx.getSharedPreferences(PREFS_CRYPTO, Context.MODE_PRIVATE)
            val masterKey = getOrCreateMasterKey()

            val storageKey = loadOrCreateLocalStorageKey(prefs, masterKey)
            localStorageKey = storageKey

            val kp = loadOrCreateIdentityKeyPair(prefs, storageKey)
            identityKeyPair = kp
            identityPubB64 = b64UrlEncode(kp.public.encoded)
            initialized = true
        }
    }

    @Volatile private var testMasterKey: SecretKey? = null

    fun injectTestMasterKey(key: SecretKey?) {
        synchronized(initLock) {
            testMasterKey = key
            initialized = false
            localStorageKey = null
            identityKeyPair = null
            identityPubB64 = null
            peerPublicKeys.clear()
            peerPubB64Map.clear()
            peerChatKeys.clear()
        }
    }

    private fun getOrCreateMasterKey(): SecretKey {
        testMasterKey?.let { return it }
        val ks = KeyStore.getInstance(KEYSTORE_PROVIDER)
        ks.load(null)
        val existing = (ks.getEntry(MASTER_KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
        if (existing != null) return existing

        val spec = KeyGenParameterSpec.Builder(
            MASTER_KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        kg.init(spec)
        return kg.generateKey()
    }

    private fun loadOrCreateLocalStorageKey(prefs: SharedPreferences, masterKey: SecretKey): SecretKey {
        val wrappedB64 = prefs.getString(KEY_WRAPPED_STORAGE, null)
        if (!wrappedB64.isNullOrBlank()) {
            val unwrapped = runCatching {
                val blob = Base64.decode(wrappedB64, Base64.NO_WRAP)
                if (blob.size <= GCM_IV_LEN + GCM_TAG_BYTES) error("Corrupt wrapped key")
                val iv = blob.copyOfRange(0, GCM_IV_LEN)
                val ct = blob.copyOfRange(GCM_IV_LEN, blob.size)
                val raw = aesGcmDecrypt(masterKey, iv, ct, KEY_WRAPPED_STORAGE.toByteArray())
                SecretKeySpec(raw, "AES")
            }.getOrNull()
            if (unwrapped != null) return unwrapped
        }

        val raw = ByteArray(32).also { secureRandom.nextBytes(it) }
        val (iv, ct) = aesGcmEncrypt(masterKey, raw, KEY_WRAPPED_STORAGE.toByteArray())
        val blob = iv + ct
        prefs.edit().putString(KEY_WRAPPED_STORAGE, Base64.encodeToString(blob, Base64.NO_WRAP)).apply()
        return SecretKeySpec(raw, "AES")
    }

    private fun loadOrCreateIdentityKeyPair(prefs: SharedPreferences, storageKey: SecretKey): KeyPair {
        val encPrivB64 = prefs.getString(KEY_ENC_ID_PRIV, null)
        val pubB64 = prefs.getString(KEY_ID_PUB, null)
        if (!encPrivB64.isNullOrBlank() && !pubB64.isNullOrBlank()) {
            val loaded = runCatching {
                val blob = Base64.decode(encPrivB64, Base64.NO_WRAP)
                val iv = blob.copyOfRange(0, GCM_IV_LEN)
                val ct = blob.copyOfRange(GCM_IV_LEN, blob.size)
                val privBytes = aesGcmDecrypt(storageKey, iv, ct, KEY_ENC_ID_PRIV.toByteArray())
                val pubBytes = b64UrlDecode(pubB64)
                val kf = KeyFactory.getInstance("EC")
                val priv = kf.generatePrivate(PKCS8EncodedKeySpec(privBytes))
                val pub = kf.generatePublic(X509EncodedKeySpec(pubBytes))
                KeyPair(pub, priv)
            }.getOrNull()
            if (loaded != null) return loaded
        }

        val kp = generateEcP256KeyPair()
        val (iv, ct) = aesGcmEncrypt(storageKey, kp.private.encoded, KEY_ENC_ID_PRIV.toByteArray())
        prefs.edit()
            .putString(KEY_ENC_ID_PRIV, Base64.encodeToString(iv + ct, Base64.NO_WRAP))
            .putString(KEY_ID_PUB, b64UrlEncode(kp.public.encoded))
            .apply()
        return kp
    }

    fun getSelfPublicKeyB64(context: Context): String {
        ensureInitialized(context)
        return identityPubB64 ?: b64UrlEncode(identityKeyPair!!.public.encoded)
    }

    fun getSelfPublicKeyBytes(context: Context): ByteArray {
        ensureInitialized(context)
        return identityKeyPair!!.public.encoded
    }

    fun getSelfFingerprintShort(context: Context): String {
        val digest = sha256(getSelfPublicKeyBytes(context))
        return digest.take(6).joinToString(":") { "%02X".format(it) }
    }

    fun isHelloPayload(payload: String): Boolean =
        payload == Constants.PAYLOAD_HELLO ||
            payload.startsWith("${Constants.PAYLOAD_HELLO}:") ||
            payload.startsWith("${Constants.PAYLOAD_HELLO}|KEY:")

    fun signIdentity(privateKey: PrivateKey, peerId: String, pubKeyB64: String): ByteArray {
        val data = "$peerId|$pubKeyB64".toByteArray(Charsets.UTF_8)
        val sig = java.security.Signature.getInstance("SHA256withECDSA")
        sig.initSign(privateKey)
        sig.update(data)
        return sig.sign()
    }

    fun verifyIdentitySignature(publicKey: PublicKey, peerId: String, pubKeyB64: String, signatureBytes: ByteArray): Boolean {
        return runCatching {
            val data = "$peerId|$pubKeyB64".toByteArray(Charsets.UTF_8)
            val sig = java.security.Signature.getInstance("SHA256withECDSA")
            sig.initVerify(publicKey)
            sig.update(data)
            sig.verify(signatureBytes)
        }.getOrDefault(false)
    }

    fun signChallenge(privateKey: PrivateKey, nonce: String, peerId: String, pubKeyB64: String): ByteArray {
        val data = "$nonce|$peerId|$pubKeyB64".toByteArray(Charsets.UTF_8)
        val sig = java.security.Signature.getInstance("SHA256withECDSA")
        sig.initSign(privateKey)
        sig.update(data)
        return sig.sign()
    }

    fun verifyChallengeSignature(publicKey: PublicKey, nonce: String, peerId: String, pubKeyB64: String, signatureBytes: ByteArray): Boolean {
        return runCatching {
            val data = "$nonce|$peerId|$pubKeyB64".toByteArray(Charsets.UTF_8)
            val sig = java.security.Signature.getInstance("SHA256withECDSA")
            sig.initVerify(publicKey)
            sig.update(data)
            sig.verify(signatureBytes)
        }.getOrDefault(false)
    }

    fun buildSignedHelloPayload(context: Context, localIp: String?, pubKeyB64: String, nonce: String = "nonce_${System.currentTimeMillis()}" ): String {
        ensureInitialized(context)
        val priv = identityKeyPair?.private
        val selfId = PeerRegistry.selfId
        val sig = if (priv != null) signChallenge(priv, nonce, selfId, pubKeyB64) else ByteArray(0)
        val sigB64 = if (sig.isNotEmpty()) b64UrlEncode(sig) else ""
        val base = if (!localIp.isNullOrBlank()) "${Constants.PAYLOAD_HELLO}:$localIp" else Constants.PAYLOAD_HELLO
        return if (sigB64.isNotBlank()) "$base|KEY:$pubKeyB64|NONCE:$nonce|SIG:$sigB64" else "$base|KEY:$pubKeyB64|NONCE:$nonce"
    }

    fun parseSignedHelloPayload(payload: String): Triple<String?, String?, ByteArray?> {
        val sigIdx = payload.indexOf("|SIG:")
        val sigB64 = if (sigIdx >= 0) payload.substring(sigIdx + 5).trim().takeIf { it.isNotBlank() } else null
        val remainder = if (sigIdx >= 0) payload.substring(0, sigIdx) else payload

        val nonceIdx = remainder.indexOf("|NONCE:")
        val rem2 = if (nonceIdx >= 0) remainder.substring(0, nonceIdx) else remainder

        val keyIdx = rem2.indexOf("|KEY:")
        val pubB64 = if (keyIdx >= 0) rem2.substring(keyIdx + 5).trim().takeIf { it.isNotBlank() } else null
        val head = if (keyIdx >= 0) rem2.substring(0, keyIdx) else rem2
        val ip = if (head.startsWith("${Constants.PAYLOAD_HELLO}:")) {
            head.substringAfter(':', "").trim().takeIf { it.isNotBlank() }
        } else {
            null
        }
        val sigBytes = sigB64?.let { b64UrlDecode(it) }
        return Triple(ip, pubB64, sigBytes)
    }

    fun buildSignedKeyxPayload(context: Context, nonce: String = "nonce_${System.currentTimeMillis()}"): String {
        ensureInitialized(context)
        val selfPubB64 = getSelfPublicKeyB64(context)
        val priv = identityKeyPair?.private
        val selfId = PeerRegistry.selfId
        val sig = if (priv != null) signChallenge(priv, nonce, selfId, selfPubB64) else ByteArray(0)
        val sigB64 = if (sig.isNotEmpty()) b64UrlEncode(sig) else ""
        return if (sigB64.isNotBlank()) "${Constants.MSG_TYPE_KEYX}$selfPubB64|NONCE:$nonce|SIG:$sigB64" else "${Constants.MSG_TYPE_KEYX}$selfPubB64|NONCE:$nonce"
    }

    fun parseSignedKeyxPayload(payload: String): Pair<String, ByteArray?> {
        val body = payload.removePrefix(Constants.MSG_TYPE_KEYX)
        val parts = body.split("|SIG:", limit = 2)
        val left = parts[0]
        val sigB64 = if (parts.size == 2) parts[1].trim() else ""
        val sigBytes = if (sigB64.isNotBlank()) b64UrlDecode(sigB64) else null

        val nonceIdx = left.indexOf("|NONCE:")
        val pubB64 = if (nonceIdx >= 0) left.substring(0, nonceIdx).trim() else left.trim()
        return pubB64 to sigBytes
    }

    // ======================== Peer Key Registry & Safety Numbers ========================

    private const val PREFIX_PENDING_PUB = "pending_pub_"

    fun registerPeerPublicKey(
        context: Context,
        peerId: String?,
        pubKeyB64: String?,
        isFromThirdPartyBroadcast: Boolean = false
    ): KeyRegistrationResult {
        if (peerId.isNullOrBlank() || pubKeyB64.isNullOrBlank() || peerId == PeerRegistry.selfId) {
            return KeyRegistrationResult.INVALID
        }
        ensureInitialized(context)
        val cleanB64 = pubKeyB64.trim()
        val pubKey = runCatching {
            val raw = b64UrlDecode(cleanB64)
            decodeEcPublicKey(raw)
        }.getOrNull() ?: return KeyRegistrationResult.INVALID

        val prefs = context.applicationContext.getSharedPreferences(PREFS_CRYPTO, Context.MODE_PRIVATE)
        val previousB64 = prefs.getString(PREFIX_PEER_PUB + peerId, null)
        val canonicalB64 = b64UrlEncode(pubKey.encoded)

        if (previousB64 == null) {
            if (isFromThirdPartyBroadcast) {
                prefs.edit()
                    .putString(PREFIX_PENDING_PUB + peerId, canonicalB64)
                    .putBoolean(PREFIX_PEER_KEY_CHANGED + peerId, true)
                    .apply()
                Log.w(TAG, "First-time key for $peerId from third-party PEERS broadcast stored as pending/unverified")
                return KeyRegistrationResult.CHANGED
            }
            prefs.edit()
                .putString(PREFIX_PEER_PUB + peerId, canonicalB64)
                .remove(PREFIX_PENDING_PUB + peerId)
                .putBoolean(PREFIX_PEER_KEY_CHANGED + peerId, false)
                .apply()
            peerPublicKeys[peerId] = pubKey
            peerPubB64Map[peerId] = canonicalB64
            peerChatKeys.remove(peerId)
            return KeyRegistrationResult.ADDED
        }

        if (previousB64 == canonicalB64) {
            peerPublicKeys[peerId] = pubKey
            peerPubB64Map[peerId] = canonicalB64
            return KeyRegistrationResult.UNCHANGED
        }

        // Key has changed! Never silently replace.
        if (isFromThirdPartyBroadcast) {
            Log.w(TAG, "Ignored conflicting key from third-party broadcast for $peerId")
            return KeyRegistrationResult.UNCHANGED
        }

        prefs.edit()
            .putString(PREFIX_PENDING_PUB + peerId, canonicalB64)
            .putBoolean(PREFIX_PEER_KEY_CHANGED + peerId, true)
            .apply()
        Log.w(TAG, "Peer identity key changed for $peerId — stored as pending, sending/calls blocked")
        return KeyRegistrationResult.CHANGED
    }

    fun getPendingPeerPublicKeyB64(context: Context, peerId: String?): String? {
        if (peerId.isNullOrBlank()) return null
        return context.applicationContext.getSharedPreferences(PREFS_CRYPTO, Context.MODE_PRIVATE)
            .getString(PREFIX_PENDING_PUB + peerId, null)
    }

    fun acceptPendingKey(context: Context, peerId: String?) {
        if (peerId.isNullOrBlank()) return
        val prefs = context.applicationContext.getSharedPreferences(PREFS_CRYPTO, Context.MODE_PRIVATE)
        val pending = prefs.getString(PREFIX_PENDING_PUB + peerId, null) ?: return
        prefs.edit()
            .putString(PREFIX_PEER_PUB + peerId, pending)
            .remove(PREFIX_PENDING_PUB + peerId)
            .putBoolean(PREFIX_PEER_KEY_CHANGED + peerId, false)
            .putBoolean(PREFIX_PEER_VERIFIED + peerId, false)
            .apply()
        peerPublicKeys.remove(peerId)
        peerPubB64Map.remove(peerId)
        peerChatKeys.remove(peerId)
    }

    fun rejectPendingKey(context: Context, peerId: String?) {
        if (peerId.isNullOrBlank()) return
        context.applicationContext.getSharedPreferences(PREFS_CRYPTO, Context.MODE_PRIVATE)
            .edit()
            .remove(PREFIX_PENDING_PUB + peerId)
            .putBoolean(PREFIX_PEER_KEY_CHANGED + peerId, false)
            .apply()
    }

    fun isPeerKeyBlocked(context: Context, peerId: String?): Boolean {
        if (peerId.isNullOrBlank() || peerId == Constants.GROUP_PEER_ID) return false
        return hasPeerKeyChanged(context, peerId) && getPendingPeerPublicKeyB64(context, peerId) != null
    }

    fun getPeerPublicKey(context: Context, peerId: String?): PublicKey? {
        if (peerId.isNullOrBlank()) return null
        ensureInitialized(context)
        peerPublicKeys[peerId]?.let { return it }
        val prefs = context.applicationContext.getSharedPreferences(PREFS_CRYPTO, Context.MODE_PRIVATE)
        val stored = prefs.getString(PREFIX_PEER_PUB + peerId, null) ?: return null
        val pub = runCatching { decodeEcPublicKey(b64UrlDecode(stored)) }.getOrNull() ?: return null
        peerPublicKeys[peerId] = pub
        peerPubB64Map[peerId] = stored
        return pub
    }

    fun getPeerPublicKeyB64(context: Context, peerId: String?): String? {
        if (peerId.isNullOrBlank()) return null
        ensureInitialized(context)
        peerPubB64Map[peerId]?.let { return it }
        getPeerPublicKey(context, peerId) ?: return null
        return peerPubB64Map[peerId]
    }

    fun hasPeerKey(context: Context, peerId: String?): Boolean =
        getPeerPublicKey(context, peerId) != null

    fun hasPeerKeyChanged(context: Context, peerId: String?): Boolean {
        if (peerId.isNullOrBlank()) return false
        return context.applicationContext.getSharedPreferences(PREFS_CRYPTO, Context.MODE_PRIVATE)
            .getBoolean(PREFIX_PEER_KEY_CHANGED + peerId, false)
    }

    fun acknowledgeKeyChange(context: Context, peerId: String?) {
        if (peerId.isNullOrBlank()) return
        context.applicationContext.getSharedPreferences(PREFS_CRYPTO, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(PREFIX_PEER_KEY_CHANGED + peerId, false)
            .apply()
    }

    fun isPeerVerified(context: Context, peerId: String?): Boolean {
        if (peerId.isNullOrBlank()) return false
        return context.applicationContext.getSharedPreferences(PREFS_CRYPTO, Context.MODE_PRIVATE)
            .getBoolean(PREFIX_PEER_VERIFIED + peerId, false)
    }

    fun setPeerVerified(context: Context, peerId: String?, verified: Boolean) {
        if (peerId.isNullOrBlank()) return
        context.applicationContext.getSharedPreferences(PREFS_CRYPTO, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(PREFIX_PEER_VERIFIED + peerId, verified)
            .apply {
                if (verified) putBoolean(PREFIX_PEER_KEY_CHANGED + peerId, false)
            }
            .apply()
    }

    /**
     * Computes a deterministic 60-digit (12 x 5-digit blocks) Signal-style Safety Number
     * from both devices' sorted ECDH identity public keys + node IDs.
     */
    fun getSafetyNumber(context: Context, peerId: String?): String? {
        if (peerId.isNullOrBlank() || peerId == Constants.GROUP_PEER_ID) return null
        ensureInitialized(context)
        val selfPub = getSelfPublicKeyBytes(context)
        val peerPub = getPeerPublicKey(context, peerId)?.encoded ?: return null
        val selfId = PeerRegistry.selfId
        return computeSafetyNumber(selfId, selfPub, peerId, peerPub)
    }

    fun getAllKnownPeerIds(context: Context): Set<String> {
        ensureInitialized(context)
        val prefs = context.applicationContext.getSharedPreferences(PREFS_CRYPTO, Context.MODE_PRIVATE)
        val fromPrefs = prefs.all.keys
            .filter { it.startsWith(PREFIX_PEER_PUB) }
            .map { it.removePrefix(PREFIX_PEER_PUB) }
        return (peerPublicKeys.keys + fromPrefs).toSet()
    }

    private fun getOrDeriveChatKey(context: Context, peerId: String): SecretKey? {
        peerChatKeys[peerId]?.let { return it }
        ensureInitialized(context)
        val peerPub = getPeerPublicKey(context, peerId) ?: return null
        val selfKp = identityKeyPair ?: return null
        val key = derivePairwiseKey(
            selfPrivateKey = selfKp.private,
            selfPublicBytes = selfKp.public.encoded,
            peerPublicKey = peerPub,
            info = INFO_CHAT
        )
        peerChatKeys[peerId] = key
        return key
    }

    // ======================== Wire Payload E2EE (Chat Port 8988) ========================

    fun encryptForPeer(
        context: Context,
        peerId: String,
        senderId: String,
        recipientId: String,
        plaintext: String
    ): String? {
        if (isPeerKeyBlocked(context, peerId)) {
            Log.w(TAG, "encryptForPeer: blocked sending to $peerId due to pending key change")
            return null
        }
        val key = getOrDeriveChatKey(context, peerId) ?: return null
        val aad = "$senderId|$recipientId".toByteArray(Charsets.UTF_8)
        val (iv, ct) = aesGcmEncrypt(key, plaintext.toByteArray(Charsets.UTF_8), aad)
        return "${Constants.MSG_TYPE_E2E_DIRECT}${b64UrlEncode(iv)}:${b64UrlEncode(ct)}"
    }

    fun decryptFromPeer(
        context: Context,
        peerId: String,
        senderId: String,
        recipientId: String,
        cipherPayload: String
    ): String? {
        if (!cipherPayload.startsWith(Constants.MSG_TYPE_E2E_DIRECT)) return null
        val key = getOrDeriveChatKey(context, peerId) ?: return null
        val body = cipherPayload.removePrefix(Constants.MSG_TYPE_E2E_DIRECT)
        val parts = body.split(':', limit = 2)
        if (parts.size != 2) return null
        return runCatching {
            val iv = b64UrlDecode(parts[0])
            val ct = b64UrlDecode(parts[1])
            val aad = "$senderId|$recipientId".toByteArray(Charsets.UTF_8)
            String(aesGcmDecrypt(key, iv, ct, aad), Charsets.UTF_8)
        }.onFailure {
            Log.w(TAG, "Failed to decrypt 1:1 payload from $senderId: ${it.message}")
        }.getOrNull()
    }

    /**
     * Encrypts a group/broadcast payload once with a fresh random 256-bit message key `K_msg`,
     * then wraps `K_msg` separately for every known peer public key via their pairwise E2EE chatKey.
     */
    fun encryptForBroadcast(
        context: Context,
        senderId: String,
        targetPeerIds: Collection<String>,
        plaintext: String
    ): String? {
        ensureInitialized(context)
        val uniqueTargets = targetPeerIds.filter { it.isNotBlank() && it != senderId && !isPeerKeyBlocked(context, it) }.distinct()
        if (uniqueTargets.isEmpty()) return null

        val msgKeyBytes = ByteArray(32).also { secureRandom.nextBytes(it) }
        val msgKey = SecretKeySpec(msgKeyBytes, "AES")
        val aadMsg = "$senderId|${Constants.RECIPIENT_BROADCAST}".toByteArray(Charsets.UTF_8)
        val (msgIv, msgCt) = aesGcmEncrypt(msgKey, plaintext.toByteArray(Charsets.UTF_8), aadMsg)

        val msgId = java.util.UUID.randomUUID().toString()
        val msgCtB64 = b64UrlEncode(msgCt)
        val priv = identityKeyPair?.private
        val signData = "$senderId|${Constants.RECIPIENT_BROADCAST}|$msgId|$msgCtB64".toByteArray(Charsets.UTF_8)
        val sig = if (priv != null) {
            val s = java.security.Signature.getInstance("SHA256withECDSA")
            s.initSign(priv)
            s.update(signData)
            s.sign()
        } else ByteArray(0)
        val sigB64 = b64UrlEncode(sig)

        val wrappedEntries = mutableListOf<String>()
        for (pid in uniqueTargets) {
            val peerChatKey = getOrDeriveChatKey(context, pid) ?: continue
            val wrapAad = "$senderId|$pid|wrap".toByteArray(Charsets.UTF_8)
            val (wIv, wCt) = aesGcmEncrypt(peerChatKey, msgKeyBytes, wrapAad)
            val encodedPid = b64UrlEncode(pid.toByteArray(Charsets.UTF_8))
            wrappedEntries += "$encodedPid=${b64UrlEncode(wIv)}.${b64UrlEncode(wCt)}"
        }
        if (wrappedEntries.isEmpty()) return null

        return buildString {
            append(Constants.MSG_TYPE_E2E_MULTI)
            append(b64UrlEncode(msgIv))
            append(':')
            append(msgCtB64)
            append(':')
            append(msgId)
            append(':')
            append(sigB64)
            append(':')
            append(wrappedEntries.joinToString(";"))
        }
    }

    fun decryptBroadcast(
        context: Context,
        senderId: String,
        selfId: String,
        cipherPayload: String
    ): String? {
        if (!cipherPayload.startsWith(Constants.MSG_TYPE_E2E_MULTI)) return null
        val peerPubKey = getPeerPublicKey(context, senderId) ?: return null
        val peerChatKey = getOrDeriveChatKey(context, senderId) ?: return null

        val body = cipherPayload.removePrefix(Constants.MSG_TYPE_E2E_MULTI)
        val parts = body.split(':', limit = 5)
        if (parts.size < 5) return null

        val msgIvB64 = parts[0]
        val msgCtB64 = parts[1]
        val msgId = parts[2]
        val sigB64 = parts[3]
        val entriesStr = parts[4]

        // Verify signature over senderId | broadcast | msgId | msgCt
        val sigBytes = b64UrlDecode(sigB64)
        val signData = "$senderId|${Constants.RECIPIENT_BROADCAST}|$msgId|$msgCtB64".toByteArray(Charsets.UTF_8)
        val validSig = runCatching {
            val sig = java.security.Signature.getInstance("SHA256withECDSA")
            sig.initVerify(peerPubKey)
            sig.update(signData)
            sig.verify(sigBytes)
        }.getOrDefault(false)

        if (!validSig) {
            Log.w(TAG, "Group message signature verification failed for sender $senderId")
            return null
        }

        val encodedSelf = b64UrlEncode(selfId.toByteArray(Charsets.UTF_8))
        val entry = entriesStr.split(';').firstOrNull { it.startsWith("$encodedSelf=") } ?: return null
        val wrapPair = entry.substringAfter('=').split('.', limit = 2)
        if (wrapPair.size != 2) return null

        return runCatching {
            val wIv = b64UrlDecode(wrapPair[0])
            val wCt = b64UrlDecode(wrapPair[1])
            val wrapAad = "$senderId|$selfId|wrap".toByteArray(Charsets.UTF_8)
            val msgKeyBytes = aesGcmDecrypt(peerChatKey, wIv, wCt, wrapAad)
            val msgKey = SecretKeySpec(msgKeyBytes, "AES")

            val msgIv = b64UrlDecode(msgIvB64)
            val msgCt = b64UrlDecode(msgCtB64)
            val aadMsg = "$senderId|${Constants.RECIPIENT_BROADCAST}".toByteArray(Charsets.UTF_8)
            String(aesGcmDecrypt(msgKey, msgIv, msgCt, aadMsg), Charsets.UTF_8)
        }.onFailure {
            Log.w(TAG, "Failed to decrypt group payload from $senderId: ${it.message}")
        }.getOrNull()
    }

    // ======================== Call Channel Crypto (Ports 8989 & 8990) ========================

    fun generateEcP256KeyPair(): KeyPair {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"), secureRandom)
        return kpg.generateKeyPair()
    }

    fun decodeEcPublicKey(x509Bytes: ByteArray): PublicKey =
        KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(x509Bytes))

    fun computeEcdhSecret(privateKey: PrivateKey, peerPublicKey: PublicKey): ByteArray {
        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(privateKey)
        ka.doPhase(peerPublicKey, true)
        return ka.generateSecret()
    }

    fun deriveAudioSignalingSessionKey(
        context: Context,
        peerId: String?,
        ephSecret: ByteArray,
        ephPubA: ByteArray,
        ephPubB: ByteArray
    ): SecretKey = deriveCallSessionKey(context, peerId, ephSecret, ephPubA, ephPubB, INFO_AUDIO_SIG)

    fun deriveVideoStreamSessionKey(
        context: Context,
        peerId: String?,
        ephSecret: ByteArray,
        ephPubA: ByteArray,
        ephPubB: ByteArray
    ): SecretKey = deriveCallSessionKey(context, peerId, ephSecret, ephPubA, ephPubB, INFO_VIDEO)

    private fun deriveCallSessionKey(
        context: Context,
        peerId: String?,
        ephSecret: ByteArray,
        ephPubA: ByteArray,
        ephPubB: ByteArray,
        info: String
    ): SecretKey {
        ensureInitialized(context)
        val selfKp = identityKeyPair
        val peerPub = if (!peerId.isNullOrBlank()) getPeerPublicKey(context, peerId) else null
        val ikm = if (selfKp != null && peerPub != null) {
            val idSecret = computeEcdhSecret(selfKp.private, peerPub)
            ephSecret + idSecret
        } else {
            ephSecret
        }
        val salt = sha256(sortedConcat(ephPubA, ephPubB))
        val okm = hkdfSha256(salt, ikm, info.toByteArray(Charsets.UTF_8), 32)
        return SecretKeySpec(okm, "AES")
    }

    fun encryptSignalingLine(key: SecretKey, seq: Long, plaintextJson: String): String {
        val aad = ByteBuffer.allocate(8).putLong(seq).array()
        val (iv, ct) = aesGcmEncrypt(key, plaintextJson.toByteArray(Charsets.UTF_8), aad)
        return "${Constants.MSG_TYPE_E2E_SIG}$seq:${b64UrlEncode(iv)}:${b64UrlEncode(ct)}"
    }

    fun decryptSignalingLine(key: SecretKey, line: String): Pair<Long, String>? {
        if (!line.startsWith(Constants.MSG_TYPE_E2E_SIG)) return null
        val parts = line.removePrefix(Constants.MSG_TYPE_E2E_SIG).split(':', limit = 3)
        if (parts.size != 3) return null
        val seq = parts[0].toLongOrNull() ?: return null
        return runCatching {
            val iv = b64UrlDecode(parts[1])
            val ct = b64UrlDecode(parts[2])
            val aad = ByteBuffer.allocate(8).putLong(seq).array()
            val plain = String(aesGcmDecrypt(key, iv, ct, aad), Charsets.UTF_8)
            seq to plain
        }.getOrNull()
    }

    fun encryptVideoFrame(key: SecretKey, frameSeq: Long, jpegBytes: ByteArray): ByteArray {
        val aad = ByteBuffer.allocate(8).putLong(frameSeq).array()
        val (iv, ct) = aesGcmEncrypt(key, jpegBytes, aad)
        return iv + ct
    }

    fun decryptVideoFrame(key: SecretKey, frameSeq: Long, packet: ByteArray): ByteArray? {
        if (packet.size <= GCM_IV_LEN + GCM_TAG_BYTES) return null
        return runCatching {
            val iv = packet.copyOfRange(0, GCM_IV_LEN)
            val ct = packet.copyOfRange(GCM_IV_LEN, packet.size)
            val aad = ByteBuffer.allocate(8).putLong(frameSeq).array()
            aesGcmDecrypt(key, iv, ct, aad)
        }.getOrNull()
    }

    // ======================== Chunked File & Attachment AEAD (`LVXE1`) ========================

    fun generateRandomFileKeyB64(): String {
        val raw = ByteArray(32).also { secureRandom.nextBytes(it) }
        return b64UrlEncode(raw)
    }

    fun saveAttachmentKey(context: Context, transferId: String, keyB64: String) {
        if (transferId.isBlank() || keyB64.isBlank()) return
        ensureInitialized(context)
        val storageKey = localStorageKey ?: return
        val rawKey = runCatching { b64UrlDecode(keyB64) }.getOrNull() ?: return
        if (rawKey.size != 32) return
        attachmentKeys[transferId] = SecretKeySpec(rawKey, "AES")

        val aad = (PREFIX_ATT_KEY + transferId).toByteArray(Charsets.UTF_8)
        val (iv, ct) = aesGcmEncrypt(storageKey, rawKey, aad)
        context.applicationContext.getSharedPreferences(PREFS_CRYPTO, Context.MODE_PRIVATE)
            .edit()
            .putString(PREFIX_ATT_KEY + transferId, Base64.encodeToString(iv + ct, Base64.NO_WRAP))
            .apply()
    }

    fun getAttachmentKey(context: Context, transferId: String?): SecretKey? {
        if (transferId.isNullOrBlank()) return null
        attachmentKeys[transferId]?.let { return it }
        ensureInitialized(context)
        val storageKey = localStorageKey ?: return null
        val prefs = context.applicationContext.getSharedPreferences(PREFS_CRYPTO, Context.MODE_PRIVATE)
        val wrapped = prefs.getString(PREFIX_ATT_KEY + transferId, null) ?: return null
        val key = runCatching {
            val blob = Base64.decode(wrapped, Base64.NO_WRAP)
            val iv = blob.copyOfRange(0, GCM_IV_LEN)
            val ct = blob.copyOfRange(GCM_IV_LEN, blob.size)
            val aad = (PREFIX_ATT_KEY + transferId).toByteArray(Charsets.UTF_8)
            val raw = aesGcmDecrypt(storageKey, iv, ct, aad)
            SecretKeySpec(raw, "AES")
        }.getOrNull() ?: return null
        attachmentKeys[transferId] = key
        return key
    }

    fun isEncryptedAttachmentFile(file: File): Boolean {
        if (!file.exists() || file.length() < MAGIC_FILE_V1.size) return false
        return runCatching {
            FileInputStream(file).use { fis ->
                val head = ByteArray(MAGIC_FILE_V1.size)
                val read = fis.read(head)
                read == MAGIC_FILE_V1.size && head.contentEquals(MAGIC_FILE_V1)
            }
        }.getOrDefault(false)
    }

    /**
     * Streams plaintext from [input] into [destCipherFile] encrypted in 64 KB AES-256-GCM chunks
     * (`LVXE1` format). Memory stays O(128 KB) even for 250 MB attachments.
     *
     * @return Pair(plaintextBytesCount, cipherFileLengthBytes)
     */
    fun encryptStreamToFile(
        input: InputStream,
        destCipherFile: File,
        fileKeyB64: String,
        maxPlainBytes: Long
    ): Pair<Long, Long> {
        val key = SecretKeySpec(b64UrlDecode(fileKeyB64), "AES")
        val baseNonce = ByteArray(GCM_IV_LEN).also { secureRandom.nextBytes(it) }

        var totalPlain = 0L
        BufferedOutputStream(FileOutputStream(destCipherFile), FILE_CHUNK_SIZE).use { bos ->
            val dos = DataOutputStream(bos)
            dos.write(MAGIC_FILE_V1)
            dos.write(baseNonce)
            dos.writeInt(FILE_CHUNK_SIZE)

            var curBuf = ByteArray(FILE_CHUNK_SIZE)
            var nextBuf = ByteArray(FILE_CHUNK_SIZE)
            var curLen = readUpTo(input, curBuf)
            if (curLen <= 0) {
                error("Selected file is empty")
            }

            var chunkIdx = 0L
            while (curLen > 0) {
                totalPlain += curLen
                if (totalPlain > maxPlainBytes) {
                    error("File exceeds limit")
                }
                val nextLen = readUpTo(input, nextBuf)
                val isFinal = nextLen <= 0

                val chunkNonce = deriveChunkNonce(baseNonce, chunkIdx)
                val aad = chunkAad(chunkIdx, isFinal)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, chunkNonce))
                cipher.updateAAD(aad)
                val encryptedChunk = cipher.doFinal(curBuf, 0, curLen)

                dos.writeInt(encryptedChunk.size)
                dos.write(encryptedChunk)

                curLen = nextLen
                val tmp = curBuf
                curBuf = nextBuf
                nextBuf = tmp
                chunkIdx++
            }
            dos.flush()
        }
        return totalPlain to destCipherFile.length()
    }

    /**
     * Reads an `LVXE1` ciphertext stream of [expectedCipherLen] bytes from [input] into [destCipherFile]
     * while verifying every 64 KB AES-GCM chunk on-the-fly using [fileKeyB64].
     * Guarantees no corrupted or tampered bytes are ever accepted, while staying encrypted at rest.
     */
    fun copyAndVerifyEncryptedStream(
        input: InputStream,
        destCipherFile: File,
        expectedCipherLen: Long,
        fileKeyB64: String,
        onProgress: ((Long) -> Unit)? = null
    ): Long {
        val headerLen = MAGIC_FILE_V1.size + GCM_IV_LEN + 4
        if (expectedCipherLen <= headerLen) error("Truncated encrypted stream")
        val key = SecretKeySpec(b64UrlDecode(fileKeyB64), "AES")
        val dis = DataInputStream(BufferedInputStream(input, 32 * 1024))

        val magic = ByteArray(MAGIC_FILE_V1.size)
        dis.readFully(magic)
        if (!magic.contentEquals(MAGIC_FILE_V1)) error("Invalid encrypted attachment header")
        val baseNonce = ByteArray(GCM_IV_LEN)
        dis.readFully(baseNonce)
        val chunkSize = dis.readInt()
        if (chunkSize <= 0 || chunkSize > 1024 * 1024) error("Invalid encrypted chunk size: $chunkSize")

        var bytesConsumed = headerLen.toLong()
        var lastEmitMs = 0L
        BufferedOutputStream(FileOutputStream(destCipherFile), FILE_CHUNK_SIZE).use { bos ->
            val dos = DataOutputStream(bos)
            dos.write(magic)
            dos.write(baseNonce)
            dos.writeInt(chunkSize)

            var chunkIdx = 0L
            while (bytesConsumed < expectedCipherLen) {
                val encLen = dis.readInt()
                bytesConsumed += 4L
                if (encLen <= GCM_TAG_BYTES || encLen > chunkSize + GCM_TAG_BYTES) {
                    error("Corrupt encrypted chunk length: $encLen")
                }
                if (bytesConsumed + encLen > expectedCipherLen) {
                    error("Encrypted chunk overruns stream")
                }
                val encBytes = ByteArray(encLen)
                dis.readFully(encBytes)
                bytesConsumed += encLen

                val isFinal = (bytesConsumed == expectedCipherLen)
                val chunkNonce = deriveChunkNonce(baseNonce, chunkIdx)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, chunkNonce))
                cipher.updateAAD(chunkAad(chunkIdx, isFinal))
                cipher.doFinal(encBytes) // Throws AEADBadTagException on tamper/truncation

                dos.writeInt(encLen)
                dos.write(encBytes)
                chunkIdx++

                if (onProgress != null) {
                    val now = System.currentTimeMillis()
                    if (bytesConsumed >= expectedCipherLen || now - lastEmitMs >= 90L) {
                        lastEmitMs = now
                        onProgress(bytesConsumed)
                    }
                }
            }
            if (chunkIdx == 0L || bytesConsumed != expectedCipherLen) {
                error("Incomplete encrypted transfer")
            }
            dos.flush()
        }
        return bytesConsumed
    }

    /**
     * Decrypts a local attachment file into an in-memory [ByteArray] for image thumbnails or
     * in-memory `MediaDataSource` voice playback. Falls back cleanly for pre-encryption plaintext files.
     */
    fun decryptAttachmentToBytes(
        context: Context,
        transferId: String?,
        file: File
    ): ByteArray? {
        if (!file.exists() || !file.isFile) return null
        if (!isEncryptedAttachmentFile(file)) {
            return runCatching { file.readBytes() }.getOrNull()
        }
        val key = getAttachmentKey(context, transferId) ?: return null
        return runCatching {
            val out = ByteArrayOutputStream(file.length().toInt().coerceAtLeast(1024))
            decryptLvxFileStream(file, key, out)
            out.toByteArray()
        }.onFailure {
            Log.w(TAG, "Failed to decrypt attachment bytes (${file.name}): ${it.message}")
        }.getOrNull()
    }

    /**
     * Streams decrypted attachment bytes into [destPlainFile] (used on explicit user "Open" via FileProvider).
     */
    fun decryptAttachmentToFile(
        context: Context,
        transferId: String?,
        cipherFile: File,
        destPlainFile: File
    ): Boolean {
        if (!cipherFile.exists() || !cipherFile.isFile) return false
        if (!isEncryptedAttachmentFile(cipherFile)) {
            return runCatching {
                FileInputStream(cipherFile).use { fis ->
                    FileOutputStream(destPlainFile).use { fos ->
                        fis.copyTo(fos)
                    }
                }
                true
            }.getOrDefault(false)
        }
        val key = getAttachmentKey(context, transferId) ?: return false
        return runCatching {
            BufferedOutputStream(FileOutputStream(destPlainFile), FILE_CHUNK_SIZE).use { bos ->
                decryptLvxFileStream(cipherFile, key, bos)
                bos.flush()
            }
            true
        }.onFailure {
            destPlainFile.delete()
            Log.w(TAG, "Failed to decrypt attachment file (${cipherFile.name}): ${it.message}")
        }.getOrDefault(false)
    }

    private fun decryptLvxFileStream(cipherFile: File, key: SecretKey, output: OutputStream) {
        val totalLen = cipherFile.length()
        val headerLen = MAGIC_FILE_V1.size + GCM_IV_LEN + 4
        if (totalLen <= headerLen) error("Truncated encrypted file")

        DataInputStream(BufferedInputStream(FileInputStream(cipherFile), FILE_CHUNK_SIZE)).use { dis ->
            val magic = ByteArray(MAGIC_FILE_V1.size)
            dis.readFully(magic)
            if (!magic.contentEquals(MAGIC_FILE_V1)) error("Invalid magic header")

            val baseNonce = ByteArray(GCM_IV_LEN)
            dis.readFully(baseNonce)
            val chunkSize = dis.readInt()
            if (chunkSize <= 0 || chunkSize > 1024 * 1024) error("Bad chunk size")

            var offset = headerLen.toLong()
            var chunkIdx = 0L
            while (offset < totalLen) {
                val encLen = dis.readInt()
                offset += 4L
                if (encLen <= GCM_TAG_BYTES || encLen > chunkSize + GCM_TAG_BYTES) {
                    error("Corrupt encrypted chunk")
                }
                val encBytes = ByteArray(encLen)
                dis.readFully(encBytes)
                offset += encLen

                val isFinal = (offset == totalLen)
                val chunkNonce = deriveChunkNonce(baseNonce, chunkIdx)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, chunkNonce))
                cipher.updateAAD(chunkAad(chunkIdx, isFinal))
                val plain = cipher.doFinal(encBytes)
                output.write(plain)
                chunkIdx++
            }
            if (chunkIdx == 0L || offset != totalLen) error("Incomplete file")
        }
    }

    fun purgeDecryptedTempCache(context: Context) {
        val dir = File(context.applicationContext.cacheDir, "secure_open")
        if (dir.exists()) {
            dir.listFiles()?.forEach { runCatching { it.delete() } }
        }
    }

    // ======================== Local Vault At-Rest Crypto (Room & Files) ========================

    fun encryptLocalString(context: Context, plaintext: String?): String? {
        if (plaintext == null) return null
        if (plaintext.startsWith(PREFIX_LOCAL_ENC)) return plaintext
        ensureInitialized(context)
        val key = localStorageKey ?: return plaintext
        val (iv, ct) = aesGcmEncrypt(key, plaintext.toByteArray(Charsets.UTF_8))
        return PREFIX_LOCAL_ENC +
            Base64.encodeToString(iv, Base64.NO_WRAP) +
            ":" +
            Base64.encodeToString(ct, Base64.NO_WRAP)
    }

    fun decryptLocalString(context: Context, stored: String?): String? {
        if (stored == null) return null
        if (!stored.startsWith(PREFIX_LOCAL_ENC)) return stored
        ensureInitialized(context)
        val key = localStorageKey ?: return stored
        val parts = stored.removePrefix(PREFIX_LOCAL_ENC).split(':', limit = 2)
        if (parts.size != 2) return stored
        return runCatching {
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val ct = Base64.decode(parts[1], Base64.NO_WRAP)
            String(aesGcmDecrypt(key, iv, ct), Charsets.UTF_8)
        }.onFailure {
            Log.w(TAG, "Local string decryption failed: ${it.message}")
        }.getOrNull() ?: ""
    }

    fun encryptLocalBytes(context: Context, plain: ByteArray): ByteArray {
        ensureInitialized(context)
        val key = localStorageKey ?: return plain
        val (iv, ct) = aesGcmEncrypt(key, plain, MAGIC_BYTES_V1)
        return MAGIC_BYTES_V1 + iv + ct
    }

    fun decryptLocalBytes(context: Context, data: ByteArray): ByteArray? {
        if (data.size < MAGIC_BYTES_V1.size) return data
        val hasMagic = (0 until MAGIC_BYTES_V1.size).all { data[it] == MAGIC_BYTES_V1[it] }
        if (!hasMagic) return data
        ensureInitialized(context)
        val key = localStorageKey ?: return null
        if (data.size <= MAGIC_BYTES_V1.size + GCM_IV_LEN + GCM_TAG_BYTES) return null
        return runCatching {
            val ivStart = MAGIC_BYTES_V1.size
            val ctStart = ivStart + GCM_IV_LEN
            val iv = data.copyOfRange(ivStart, ctStart)
            val ct = data.copyOfRange(ctStart, data.size)
            aesGcmDecrypt(key, iv, ct, MAGIC_BYTES_V1)
        }.getOrNull()
    }

    // ======================== Low-Level Cryptographic Primitives ========================

    internal fun derivePairwiseKey(
        selfPrivateKey: PrivateKey,
        selfPublicBytes: ByteArray,
        peerPublicKey: PublicKey,
        info: String
    ): SecretKey {
        val sharedSecret = computeEcdhSecret(selfPrivateKey, peerPublicKey)
        val salt = sha256(sortedConcat(selfPublicBytes, peerPublicKey.encoded))
        val okm = hkdfSha256(salt, sharedSecret, info.toByteArray(Charsets.UTF_8), 32)
        return SecretKeySpec(okm, "AES")
    }

    internal fun computeSafetyNumber(
        idA: String,
        pubA: ByteArray,
        idB: String,
        pubB: ByteArray
    ): String {
        val pairA = idA.toByteArray(Charsets.UTF_8) + pubA
        val pairB = idB.toByteArray(Charsets.UTF_8) + pubB
        var acc = sha256("LanVoxSafetyV1".toByteArray(Charsets.UTF_8) + sortedConcat(pairA, pairB))
        repeat(64) {
            acc = sha256(acc)
        }
        val sb = StringBuilder(72)
        for (block in 0 until 12) {
            val off = (block * 2) % (acc.size - 3)
            val v = ((acc[off].toInt() and 0xFF) shl 16) or
                ((acc[off + 1].toInt() and 0xFF) shl 8) or
                (acc[off + 2].toInt() and 0xFF)
            val digits = (v % 100_000).toString().padStart(5, '0')
            if (block > 0) sb.append(if (block % 4 == 0) '\n' else ' ')
            sb.append(digits)
        }
        return sb.toString()
    }

    internal fun hkdfSha256(salt: ByteArray, ikm: ByteArray, info: ByteArray, length: Int): ByteArray {
        val prk = hmacSha256(salt, ikm)
        val okm = ByteArray(length)
        var t = ByteArray(0)
        var offset = 0
        var counter = 1
        while (offset < length) {
            val macInput = ByteArray(t.size + info.size + 1)
            System.arraycopy(t, 0, macInput, 0, t.size)
            System.arraycopy(info, 0, macInput, t.size, info.size)
            macInput[t.size + info.size] = counter.toByte()
            t = hmacSha256(prk, macInput)
            val toCopy = minOf(t.size, length - offset)
            System.arraycopy(t, 0, okm, offset, toCopy)
            offset += toCopy
            counter++
        }
        return okm
    }

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    private fun sha256(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(data)

    internal fun aesGcmEncrypt(
        key: SecretKey,
        plaintext: ByteArray,
        aad: ByteArray? = null
    ): Pair<ByteArray, ByteArray> {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        if (aad != null) cipher.updateAAD(aad)
        val iv = cipher.iv
        val ct = cipher.doFinal(plaintext)
        return iv to ct
    }

    internal fun aesGcmDecrypt(
        key: SecretKey,
        iv: ByteArray,
        ciphertext: ByteArray,
        aad: ByteArray? = null
    ): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        if (aad != null) cipher.updateAAD(aad)
        return cipher.doFinal(ciphertext)
    }

    private fun deriveChunkNonce(baseNonce: ByteArray, chunkIdx: Long): ByteArray {
        val nonce = baseNonce.copyOf()
        val idxBytes = ByteBuffer.allocate(8).putLong(chunkIdx).array()
        for (i in 0 until 8) {
            nonce[4 + i] = (nonce[4 + i].toInt() xor idxBytes[i].toInt()).toByte()
        }
        return nonce
    }

    private fun chunkAad(chunkIdx: Long, isFinal: Boolean): ByteArray =
        ByteBuffer.allocate(9).putLong(chunkIdx).put(if (isFinal) 1.toByte() else 0.toByte()).array()

    private fun sortedConcat(a: ByteArray, b: ByteArray): ByteArray =
        if (compareUnsignedBytes(a, b) <= 0) a + b else b + a

    private fun compareUnsignedBytes(a: ByteArray, b: ByteArray): Int {
        val min = minOf(a.size, b.size)
        for (i in 0 until min) {
            val av = a[i].toInt() and 0xFF
            val bv = b[i].toInt() and 0xFF
            if (av != bv) return av - bv
        }
        return a.size - b.size
    }

    private fun readUpTo(input: InputStream, buf: ByteArray): Int {
        var total = 0
        while (total < buf.size) {
            val r = input.read(buf, total, buf.size - total)
            if (r <= 0) break
            total += r
        }
        return total
    }

    fun b64UrlEncode(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    fun b64UrlDecode(s: String): ByteArray =
        Base64.decode(s.trim(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
}
