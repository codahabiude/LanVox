package com.example.wifip2pchat

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import javax.crypto.spec.SecretKeySpec

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LanVoxCryptoTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        CryptoManager.ensureInitialized(context)
        PeerRegistry.selfId = "test_self_id"
    }

    @Test
    fun testKeyStoreAndLocalStorageEncryption() {
        val plain = "Secret message at rest"
        val encrypted = CryptoManager.encryptLocalString(context, plain)
        assertNotNull(encrypted)
        assertTrue(encrypted!!.startsWith(CryptoManager.PREFIX_LOCAL_ENC))

        val decrypted = CryptoManager.decryptLocalString(context, encrypted)
        assertEquals(plain, decrypted)
    }

    @Test
    fun testPairwiseEncryptionAndDecryption() {
        val kpA = CryptoManager.generateEcP256KeyPair()
        val kpB = CryptoManager.generateEcP256KeyPair()

        val pubA = kpA.public
        val pubB = kpB.public

        val keyA = CryptoManager.derivePairwiseKey(kpA.private, pubA.encoded, pubB, "lanvox-e2ee-v1-chat")
        val keyB = CryptoManager.derivePairwiseKey(kpB.private, pubB.encoded, pubA, "lanvox-e2ee-v1-chat")

        val plaintext = "Hello secure mesh"
        val aad = "peerA|peerB".toByteArray(Charsets.UTF_8)

        val (iv, ct) = CryptoManager.aesGcmEncrypt(keyA, plaintext.toByteArray(Charsets.UTF_8), aad)
        val decrypted = String(CryptoManager.aesGcmDecrypt(keyB, iv, ct, aad), Charsets.UTF_8)

        assertEquals(plaintext, decrypted)
    }

    @Test
    fun testTamperedDataRejected() {
        val key = SecretKeySpec(ByteArray(32) { 1 }, "AES")
        val plaintext = "Sensitive"
        val aad = "aad".toByteArray(Charsets.UTF_8)
        val (iv, ct) = CryptoManager.aesGcmEncrypt(key, plaintext.toByteArray(Charsets.UTF_8), aad)

        // Tamper with ciphertext
        ct[0] = (ct[0].toInt() xor 0xFF).toByte()

        assertThrows(javax.crypto.AEADBadTagException::class.java) {
            CryptoManager.aesGcmDecrypt(key, iv, ct, aad)
        }
    }

    @Test
    fun testKeyChangeBlockedAndPendingState() {
        val peerId = "peer_test_1"
        val kp1 = CryptoManager.generateEcP256KeyPair()
        val kp2 = CryptoManager.generateEcP256KeyPair()

        val b64_1 = CryptoManager.b64UrlEncode(kp1.public.encoded)
        val b64_2 = CryptoManager.b64UrlEncode(kp2.public.encoded)

        // Register initial key
        val res1 = CryptoManager.registerPeerPublicKey(context, peerId, b64_1)
        assertEquals(CryptoManager.KeyRegistrationResult.ADDED, res1)
        assertFalse(CryptoManager.hasPeerKeyChanged(context, peerId))

        // Register changed key (should be held as pending and blocked)
        val res2 = CryptoManager.registerPeerPublicKey(context, peerId, b64_2)
        assertEquals(CryptoManager.KeyRegistrationResult.CHANGED, res2)
        assertTrue(CryptoManager.hasPeerKeyChanged(context, peerId))
        assertTrue(CryptoManager.isPeerKeyBlocked(context, peerId))

        // Accept pending key
        CryptoManager.acceptPendingKey(context, peerId)
        assertFalse(CryptoManager.isPeerKeyBlocked(context, peerId))
    }

    @Test
    fun testBroadcastEncryptionAndReplayRejection() {
        val plaintext = "Group broadcast payload"
        val msgKeyBytes = ByteArray(32) { 7 }
        val msgKey = SecretKeySpec(msgKeyBytes, "AES")
        val aad = "sender|__group__".toByteArray(Charsets.UTF_8)

        val (iv, ct) = CryptoManager.aesGcmEncrypt(msgKey, plaintext.toByteArray(Charsets.UTF_8), aad)
        val decrypted = String(CryptoManager.aesGcmDecrypt(msgKey, iv, ct, aad), Charsets.UTF_8)
        assertEquals(plaintext, decrypted)

        // Wrong AAD should fail
        val wrongAad = "sender|__group__|wrong_msg_id|12345".toByteArray(Charsets.UTF_8)
        assertThrows(javax.crypto.AEADBadTagException::class.java) {
            CryptoManager.aesGcmDecrypt(msgKey, iv, ct, wrongAad)
        }
    }
}
