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
        val testKey = SecretKeySpec(ByteArray(32) { 5 }, "AES")
        CryptoManager.injectTestMasterKey(testKey)
        CryptoManager.ensureInitialized(context)
        PeerRegistry.selfId = "node_alice"
    }

    @Test
    fun testLocalStorageEncryptionAndDecryptionRoundtrip() {
        val plain = "Secure encrypted data at rest"
        val encrypted = CryptoManager.encryptLocalString(context, plain)
        assertNotNull(encrypted)
        assertTrue(encrypted!!.startsWith(CryptoManager.PREFIX_LOCAL_ENC))

        val decrypted = CryptoManager.decryptLocalString(context, encrypted)
        assertEquals(plain, decrypted)
    }

    @Test
    fun testEncryptAndDecryptUsingRealPairwiseFunctions() {
        val peerId = "node_bob"
        val kpBob = CryptoManager.generateEcP256KeyPair()
        val bobPubB64 = CryptoManager.b64UrlEncode(kpBob.public.encoded)

        val regRes = CryptoManager.registerPeerPublicKey(context, peerId, bobPubB64, isFromThirdPartyBroadcast = false)
        assertEquals(CryptoManager.KeyRegistrationResult.ADDED, regRes)

        val plaintext = "Hello from Alice to Bob"
        val encrypted = CryptoManager.encryptForPeer(context, peerId, PeerRegistry.selfId, peerId, plaintext)
        assertNotNull(encrypted)
        assertTrue(encrypted!!.startsWith(Constants.MSG_TYPE_E2E_DIRECT))

        // Alice decrypts a simulated message from Bob encrypted with Bob's private key and Alice's public key
        val alicePub = CryptoManager.getSelfPublicKeyBytes(context)
        val alicePubKey = CryptoManager.decodeEcPublicKey(alicePub)
        val bobChatKey = CryptoManager.derivePairwiseKey(kpBob.private, kpBob.public.encoded, alicePubKey, "lanvox-e2ee-v1-chat")
        val aad = "$peerId|${PeerRegistry.selfId}".toByteArray(Charsets.UTF_8)
        val (iv, ct) = CryptoManager.aesGcmEncrypt(bobChatKey, plaintext.toByteArray(Charsets.UTF_8), aad)
        val incomingCipher = "${Constants.MSG_TYPE_E2E_DIRECT}${CryptoManager.b64UrlEncode(iv)}:${CryptoManager.b64UrlEncode(ct)}"

        val decrypted = CryptoManager.decryptFromPeer(context, peerId, peerId, PeerRegistry.selfId, incomingCipher)
        assertEquals(plaintext, decrypted)
    }

    @Test
    fun testTamperedDataRejectedByDecryption() {
        val peerId = "node_bob"
        val kpBob = CryptoManager.generateEcP256KeyPair()
        val bobPubB64 = CryptoManager.b64UrlEncode(kpBob.public.encoded)
        CryptoManager.registerPeerPublicKey(context, peerId, bobPubB64, isFromThirdPartyBroadcast = false)

        val plaintext = "Sensitive data"
        val encrypted = CryptoManager.encryptForPeer(context, peerId, PeerRegistry.selfId, peerId, plaintext)!!
        val body = encrypted.removePrefix(Constants.MSG_TYPE_E2E_DIRECT)
        val parts = body.split(':', limit = 2)
        val iv = parts[0]
        val ctBytes = CryptoManager.b64UrlDecode(parts[1])
        ctBytes[0] = (ctBytes[0].toInt() xor 0xFF).toByte()
        val tamperedCipher = "${Constants.MSG_TYPE_E2E_DIRECT}$iv:${CryptoManager.b64UrlEncode(ctBytes)}"

        val decrypted = CryptoManager.decryptFromPeer(context, peerId, PeerRegistry.selfId, peerId, tamperedCipher)
        assertNull(decrypted)
    }

    @Test
    fun testPeerKeyChangeBlocksAndOldKeyRemainsUntilAccepted() {
        val peerId = "node_charlie"
        val kp1 = CryptoManager.generateEcP256KeyPair()
        val kp2 = CryptoManager.generateEcP256KeyPair()

        val b64_1 = CryptoManager.b64UrlEncode(kp1.public.encoded)
        val b64_2 = CryptoManager.b64UrlEncode(kp2.public.encoded)

        // 1. Initial key registered
        val r1 = CryptoManager.registerPeerPublicKey(context, peerId, b64_1, false)
        assertEquals(CryptoManager.KeyRegistrationResult.ADDED, r1)
        assertFalse(CryptoManager.isPeerKeyBlocked(context, peerId))

        val pub1 = CryptoManager.getPeerPublicKey(context, peerId)
        assertNotNull(pub1)

        // 2. Changed key arrives directly
        val r2 = CryptoManager.registerPeerPublicKey(context, peerId, b64_2, false)
        assertEquals(CryptoManager.KeyRegistrationResult.CHANGED, r2)
        assertTrue(CryptoManager.isPeerKeyBlocked(context, peerId))

        // 3. Verify old key remains in active use while pending
        val pubAfterChange = CryptoManager.getPeerPublicKey(context, peerId)
        assertEquals(CryptoManager.b64UrlEncode(pub1!!.encoded), CryptoManager.b64UrlEncode(pubAfterChange!!.encoded))

        // 4. Outgoing encrypt is blocked
        val blockedEncrypt = CryptoManager.encryptForPeer(context, peerId, PeerRegistry.selfId, peerId, "Test")
        assertNull(blockedEncrypt)

        // 5. Accept pending key
        CryptoManager.acceptPendingKey(context, peerId)
        assertFalse(CryptoManager.isPeerKeyBlocked(context, peerId))

        val pubAccepted = CryptoManager.getPeerPublicKey(context, peerId)
        assertEquals(b64_2, CryptoManager.b64UrlEncode(pubAccepted!!.encoded))
    }

    @Test
    fun testForgedGroupMessageRejected() {
        val bobId = "node_bob"
        val malloryId = "node_mallory"
        val kpBob = CryptoManager.generateEcP256KeyPair()
        val kpMallory = CryptoManager.generateEcP256KeyPair()

        CryptoManager.registerPeerPublicKey(context, bobId, CryptoManager.b64UrlEncode(kpBob.public.encoded), false)
        CryptoManager.registerPeerPublicKey(context, malloryId, CryptoManager.b64UrlEncode(kpMallory.public.encoded), false)

        // 1. Valid message from Bob decrypts successfully
        val broadcastCipher = CryptoManager.encryptForBroadcast(
            context = context,
            senderId = bobId,
            targetPeerIds = listOf(PeerRegistry.selfId),
            plaintext = "Genuine broadcast from Bob"
        )
        assertNotNull(broadcastCipher)

        val decryptedValid = CryptoManager.decryptBroadcast(context, bobId, PeerRegistry.selfId, broadcastCipher!!)
        assertEquals("Genuine broadcast from Bob", decryptedValid)

        // 2. Forgery attack: Mallory acts as a real third group member, but tries to forge a message
        // by signing it with Mallory's private key while claiming/routing as Bob, or tampering with ciphertext.
        val parts = broadcastCipher.removePrefix(Constants.MSG_TYPE_E2E_MULTI).split(':', limit = 5)
        val tamperedCt = CryptoManager.b64UrlDecode(parts[1])
        tamperedCt[0] = (tamperedCt[0].toInt() xor 0xFF).toByte()
        val forgedCipher = "${Constants.MSG_TYPE_E2E_MULTI}${parts[0]}:${CryptoManager.b64UrlEncode(tamperedCt)}:${parts[2]}:${parts[3]}:${parts[4]}"

        val decryptedForged = CryptoManager.decryptBroadcast(context, bobId, PeerRegistry.selfId, forgedCipher)
        assertNull(decryptedForged)
    }

    @Test
    fun testReplayRejectedWithAlteredSender() {
        val bobId = "node_bob"
        val kpBob = CryptoManager.generateEcP256KeyPair()
        CryptoManager.registerPeerPublicKey(context, bobId, CryptoManager.b64UrlEncode(kpBob.public.encoded), false)

        // 1. Valid message decrypts successfully
        val broadcastCipher = CryptoManager.encryptForBroadcast(
            context = context,
            senderId = bobId,
            targetPeerIds = listOf(PeerRegistry.selfId),
            plaintext = "Original valid message"
        )!!

        val decryptedValid = CryptoManager.decryptBroadcast(context, bobId, PeerRegistry.selfId, broadcastCipher)
        assertEquals("Original valid message", decryptedValid)

        // 2. Replay attack with altered sender ID
        val decryptedWithFakeSender = CryptoManager.decryptBroadcast(context, "node_mallory", PeerRegistry.selfId, broadcastCipher)
        assertNull(decryptedWithFakeSender)
    }

    @Test
    fun testThirdPartyPeerListKeyHeldAsPendingAndNotTrustedDirectly() {
        val peerId = "peer_third_party"
        val kp = CryptoManager.generateEcP256KeyPair()
        val pubB64 = CryptoManager.b64UrlEncode(kp.public.encoded)

        val r = CryptoManager.registerPeerPublicKey(context, peerId, pubB64, isFromThirdPartyBroadcast = true)
        assertEquals(CryptoManager.KeyRegistrationResult.CHANGED, r)
        assertTrue(CryptoManager.isPeerKeyBlocked(context, peerId))
        assertNull(CryptoManager.getPeerPublicKey(context, peerId))
    }

    @Test
    fun testIdentityProofSignatureVerified() {
        val kp = CryptoManager.generateEcP256KeyPair()
        val peerId = "test_signer"
        val pubB64 = CryptoManager.b64UrlEncode(kp.public.encoded)

        val sig = CryptoManager.signIdentity(kp.private, peerId, pubB64)
        assertTrue(sig.isNotEmpty())

        val valid = CryptoManager.verifyIdentitySignature(kp.public, peerId, pubB64, sig)
        assertTrue(valid)

        val invalid = CryptoManager.verifyIdentitySignature(kp.public, "forged_id", pubB64, sig)
        assertFalse(invalid)
    }
}
